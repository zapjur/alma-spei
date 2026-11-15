(ns alma-spei.api.imports.adapters.bank
  (:require [alma-spei.api.imports.limits :as limits]
            [alma-spei.api.imports.xlsx :as xlsx]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStreamReader PushbackReader]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter ResolverStyle]))

(def parse-version "bank-parser-v1")
(def mapping-version "bank-four-columns-v1")
(def date-formats (mapv #(-> (DateTimeFormatter/ofPattern %) (.withResolverStyle ResolverStyle/STRICT))
                        ["uuuu-MM-dd" "dd.MM.uuuu"]))
(defn text-date [value]
  (some (fn [formatter] (try (str (LocalDate/parse value formatter)) (catch Exception _ nil))) date-formats))
(defn numeric-date [value epoch]
  (try
    (let [n (bigdec value) day (.longValueExact n)]
      (when (and (<= 0 day 2958465) (not (and (not epoch) (= 60 day))))
        ;; Integer serial arithmetic avoids binary date conversion and time zones.
        (let [date (.plusDays (LocalDate/of (if epoch 1904 1899) (if epoch 1 12) (if epoch 1 31))
                              (if (and (not epoch) (> day 60)) (dec day) day))]
          (when (<= (.getYear date) 9999) (str date)))))
    (catch Exception _ nil)))
(defn amount [value]
  (when (and (string? value) (re-matches #"[+-]?[0-9]{1,16}(?:[.,][0-9]{1,4})?" value))
    (let [n (bigdec (str/replace value "," "."))]
      (when-not (zero? n) (.toPlainString n)))))

(defn numeric-amount [value]
  (try
    (let [n (bigdec value)]
      (when (and (not (zero? n)) (<= (.scale n) 4)
                 (<= (- (.precision n) (.scale n)) 16))
        (.toPlainString n)))
    (catch Exception _ nil)))

(defn normalize-row [number cells epoch]
  (when (> number limits/rows) (limits/reject! 413 "resource-limit"))
  (when (> (count cells) 4) (limits/reject! "invalid-structure"))
  (doseq [cell cells]
    (when (str/includes? (or (:value cell) "") "\u0000") (limits/reject! "invalid-structure"))
    (when (> (count (or (:value cell) "")) limits/cell-characters) (limits/reject! 413 "resource-limit")))
  (let [values (mapv #(str/trim (or (:value %) "")) cells)]
    (when-not (every? str/blank? values)
      (let [[date sender description money] (take 4 (concat values (repeat "")))
            date-cell (first cells)
            parsed-date (if (= "n" (:type date-cell))
                          (when (:date? date-cell) (numeric-date date epoch))
                          (text-date date))
            parsed-amount (when (contains? #{nil "n" "s" "inlineStr" "str"} (:type (get cells 3))) (if (= "n" (:type (get cells 3))) (numeric-amount money) (amount money)))
            codes (cond-> []
                    (nil? parsed-date) (conj "invalid-date")
                    (str/blank? sender) (conj "missing-sender")
                    (not (contains? #{nil "s" "inlineStr" "str"} (:type (get cells 1)))) (conj "invalid-sender")
                    (not (contains? #{nil "s" "inlineStr" "str"} (:type (get cells 2)))) (conj "invalid-description")
                    (nil? parsed-amount) (conj "invalid-amount"))]
        {:worksheet-index 1 :row-number number
         :payload {:date parsed-date :sender sender :description description :amount parsed-amount :currency nil
                   :source-date date :source-amount money}
         :validation-state (if (seq codes) "invalid" "valid") :validation-codes codes}))))

(defn collect-rows [entries epoch]
  (reduce (fn [result [number cells]]
            (if-let [row (normalize-row number cells epoch)]
              (update result :rows conj row)
              (update result :skipped-empty-rows inc)))
          {:rows [] :skipped-empty-rows 0} entries))

(defn csv-rows [file separator]
  (when-not (#{"," ";"} separator) (limits/reject! "invalid-structure"))
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT) (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (with-open [input (io/input-stream file)
                reader (PushbackReader. (InputStreamReader. (limits/bounded-stream input limits/file-bytes) decoder))]
      (let [first-char (.read reader)] (when (and (not= first-char -1) (not= first-char 65279)) (.unread reader first-char)))
      (collect-rows (map-indexed (fn [i row] [(inc i) (mapv #(hash-map :value %) row)])
                                 (csv/read-csv reader :separator (first separator))) false))))

(defn parse! [file extension separator]
  (try
    (let [result (case extension
                   "csv" (csv-rows file separator)
                   "xlsx" (let [{:keys [rows epoch]} (xlsx/read-rows file)] (collect-rows rows epoch))
                   (limits/reject! 415 "unsupported-format"))]
      (when (empty? (:rows result)) (limits/reject! "invalid-structure"))
      result)
    (catch clojure.lang.ExceptionInfo e
      (if (:failure-code (ex-data e)) (throw e) (limits/reject! "parse-failed")))
    (catch Exception _ (limits/reject! "parse-failed"))
    (catch StackOverflowError _ (limits/reject! "resource-limit"))))
