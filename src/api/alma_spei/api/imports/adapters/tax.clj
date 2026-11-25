(ns alma-spei.api.imports.adapters.tax
  (:require [alma-spei.api.imports.csv :as csv]
            [alma-spei.api.imports.limits :as limits]
            [alma-spei.api.imports.xlsx :as xlsx]
            [alma-spei.common.contracts.source-rows :as source]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]))

(def parse-version "tax-parser-v1")
(def mapping-version "tax-designation-v1")
(def width 9)
(def text-types #{nil "s" "inlineStr" "str"})
(def header-patterns
  [#"(?i)l\.?\s*p\.?"
   #"(?iu)imi.*podatnik" #"(?iu)nazw.*podatnik" #"(?iu)adres.*podatnik"
   #"(?iu)imi.*małż" #"(?iu)nazw.*małż" #"(?iu)adres.*małż"
   #"(?iu)cel.*szczeg" #"(?iu)kwot.*przekaz|przekaz.*kwot"])

(defn cell-value [cell] (or (:value cell) ""))

(defn check-limits! [number cells]
  (when (> number limits/rows) (limits/reject! 413 "resource-limit"))
  (when (> (count cells) width) (limits/reject! "invalid-structure"))
  (doseq [cell cells
          :let [value (cell-value cell)]]
    (when (> (count value) limits/cell-characters) (limits/reject! 413 "resource-limit"))
    (when (str/includes? value "\u0000") (limits/reject! "invalid-structure"))))

(defn decimal-amount [cell]
  (let [value (str/trim (cell-value cell))]
    (cond
      (contains? text-types (:type cell)) (str/replace value "," ".")
      (and (= "n" (:type cell)) (not (:date? cell)))
      (try
        (let [amount (bigdec value)
              precision (.precision amount)
              scale (long (.scale amount))
              plain-size (+ (max 1 (- precision scale)) (max 0 scale) (if (pos? scale) 1 0))]
          (when (and (not (neg? (.signum amount))) (<= plain-size limits/cell-characters))
            (.toPlainString amount)))
        (catch NumberFormatException _ nil)))))

(defn text-fields [fields values]
  (into {} (keep (fn [[field value]] (when-not (str/blank? value) [field value])))
        (map vector fields values)))

(defn normalize-row [number cells]
  (when (some (comp (complement str/blank?) cell-value) cells)
    (let [values (mapv #(str/trim (cell-value (get cells %))) (range width))
          subject-fields [:first-name :surname :address]
          taxpayer (text-fields subject-fields (subvec values 1 4))
          spouse (text-fields subject-fields (subvec values 4 7))
          amount (decimal-amount (get cells 8))
          payload (cond-> {:amount (when (validation/valid? source/decimal-text amount) amount)}
                    (seq taxpayer) (assoc :taxpayer taxpayer)
                    (seq spouse) (assoc :spouse spouse)
                    (not (str/blank? (values 7))) (assoc :purpose (values 7)))
          wrong-text? (fn [index] (and (not (str/blank? (values index)))
                                       (not (contains? text-types (:type (get cells index))))))
          codes (cond-> (mapv #(case (first (:field %))
                                 :taxpayer "invalid-taxpayer" :spouse "invalid-tax-spouse"
                                 :purpose "invalid-tax-purpose" "invalid-tax-amount")
                              (source/errors source/tax-row payload))
                  (some wrong-text? [1 2 3]) (conj "invalid-taxpayer")
                  (some wrong-text? [4 5 6]) (conj "invalid-tax-spouse")
                  (wrong-text? 7) (conj "invalid-tax-purpose"))]
      {:worksheet-index 1 :row-number number :payload payload
       :validation-state (if (seq codes) "invalid" "valid") :validation-codes (vec (distinct codes))})))

(defn collect-rows [entries]
  (let [result (reduce (fn [result [number cells]]
                         (check-limits! number cells)
                         (cond
                           (< number 3) result
                           (= number 3)
                           (if (and (= width (count cells))
                                    (every? true? (map #(boolean (re-find %1 (cell-value %2))) header-patterns cells)))
                             (assoc result :header? true)
                             (limits/reject! "invalid-structure"))
                           (not (:header? result)) (limits/reject! "invalid-structure")
                           :else (if-let [row (normalize-row number cells)]
                                   (update result :rows conj row)
                                   (update result :skipped-empty-rows inc))))
                       {:rows [] :skipped-empty-rows 0} entries)]
    (when (empty? (:rows result)) (limits/reject! "invalid-structure"))
    (dissoc result :header?)))

(defn parse! [file extension separator]
  (try
    (case extension
      "csv" (csv/with-rows file separator collect-rows)
      "xlsx" (collect-rows (:rows (xlsx/read-rows file width)))
      (limits/reject! 415 "unsupported-format"))
    (catch clojure.lang.ExceptionInfo e
      (if (:failure-code (ex-data e)) (throw e) (limits/reject! "parse-failed")))
    (catch Exception _ (limits/reject! "parse-failed"))
    (catch StackOverflowError _ (limits/reject! "resource-limit"))))
