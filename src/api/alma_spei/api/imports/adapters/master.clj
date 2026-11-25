(ns alma-spei.api.imports.adapters.master
  (:require [alma-spei.api.imports.csv :as csv]
            [alma-spei.api.imports.limits :as limits]
            [alma-spei.api.imports.xlsx :as xlsx]
            [alma-spei.common.contracts.source-rows :as source]
            [clojure.string :as str]))

(def parse-version "master-parser-v1")
(def mapping-version "master-observations-v1")
(def width 26)
(def profile-fields [:name :street :postal-code :city :province :gender])
(def text-types #{nil "s" "inlineStr" "str"})
(def header-patterns [#"(?i)imi.*nazw|nazw.*imi" #"(?i)ulic" #"(?i)kod" #"(?i)miejsc" #"(?i)woj" #"(?i)płe"])

(defn cell-value [cell] (or (:value cell) ""))

(defn check-limits! [number cells]
  (when (> number limits/rows) (limits/reject! 413 "resource-limit"))
  (when (> (count cells) width) (limits/reject! "invalid-structure"))
  (doseq [cell cells
          :let [value (cell-value cell)]]
    (when (> (count value) limits/cell-characters) (limits/reject! 413 "resource-limit"))
    (when (str/includes? value "\u0000") (limits/reject! "invalid-structure"))))

(defn check-header! [[number cells]]
  (check-limits! (or number 0) cells)
  (let [values (mapv (comp str/trim cell-value) cells)]
    (when-not (and (= 1 number) (<= 25 (count values))
                   (every? true? (map #(boolean (re-find %1 %2)) header-patterns values))
                   (every? true? (map #(boolean (re-find (re-pattern (str "\\b" %1 "\\b")) %2))
                                      (range 2021 2026) (subvec values 6 11)))
                   (re-find #"1[,.]5" (values 11))
                   (every? (complement str/blank?) (subvec values 12 24))
                   (re-find #"(?i)sum" (values 24)))
      (limits/reject! "invalid-structure"))))

(defn observation [index cell]
  (let [value (cell-value cell)]
    (when-not (str/blank? value)
      (cond-> {:column (inc index) :value value
               :source-type (cond (contains? text-types (:type cell)) "text"
                                  (= "b" (:type cell)) "boolean"
                                  (and (= "n" (:type cell)) (not (:date? cell))) "number"
                                  :else "unsupported")}
        (<= 6 index 10) (assoc :year (+ 2021 (- index 6)))))))

(defn normalize-row [number cells]
  (check-limits! number cells)
  (when (some (comp (complement str/blank?) cell-value) cells)
    (let [profile (into {} (keep-indexed (fn [index field]
                                           (let [value (str/trim (cell-value (get cells index)))]
                                             (when-not (str/blank? value) [field value])))
                                         profile-fields))
          payload (assoc profile :observations (into [] (keep #(observation % (get cells %))) (range 6 24)))
          codes (cond-> (into [] (distinct) (map #(case (first (:field %))
                                                    :name "invalid-master-name"
                                                    :observations "invalid-master-observation"
                                                    "invalid-master-profile")
                                                 (source/errors source/master-row payload)))
                  (some #(and (not (str/blank? (cell-value %))) (not (contains? text-types (:type %)))) (take 6 cells))
                  (conj "invalid-master-profile")
                  (not (str/blank? (cell-value (get cells 25))))
                  (conj "unexpected-master-column"))]
      {:worksheet-index 1 :row-number number :payload payload
       :validation-state (if (seq codes) "invalid" "valid") :validation-codes (vec (distinct codes))})))

(defn collect-rows [entries]
  (check-header! (first entries))
  (let [result (reduce (fn [result [number cells]]
                         (if-let [row (normalize-row number cells)]
                           (update result :rows conj row)
                           (update result :skipped-empty-rows inc)))
                       {:rows [] :skipped-empty-rows 0} (rest entries))]
    (when (empty? (:rows result)) (limits/reject! "invalid-structure"))
    result))

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
