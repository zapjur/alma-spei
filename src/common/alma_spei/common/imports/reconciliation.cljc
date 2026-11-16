(ns alma-spei.common.imports.reconciliation
  (:require [clojure.string :as str]))

(def rule-version "bank-exact-identity-v1")
(def identity-fields [:name :street :postal-code :city])
(defn normalized [value] (-> (or value "") str/trim str/lower-case (str/replace #"\s+" " ")))
(defn identity-key [profile] (mapv #(normalized (get profile %)) identity-fields))
(defn sender-profile [sender]
  ;; Only this explicit comma-separated layout is supported. Never infer first names or entity types.
  (when-let [[_ name street postcode city] (re-matches #"\s*([^,]+),\s*([^,]+),\s*([0-9]{2}-[0-9]{3})\s+([^,]+)\s*" (or sender ""))]
    (let [profile {:name (str/trim name) :street (str/trim street) :postal-code postcode :city (str/trim city)}]
      (when (every? #(seq (normalized (get profile %))) identity-fields) profile))))
(defn donor-plan [profile donors new-type]
  (if-not profile {:reason "sender-format"}
          (let [key (identity-key profile)
                exact (filter #(= key (identity-key %)) donors)
                related (filter #(or (= (first key) (normalized (:name %)))
                                     (= (subvec key 1) (subvec (identity-key %) 1))) donors)]
            (cond
              (and (= 1 (count exact)) (:is-active (first exact))) {:kind "existing-donor" :donor (first exact)}
              (seq related) {:reason "identity-ambiguity" :candidates (vec related)}
              (nil? new-type) {:reason "donor-type"}
              :else {:kind "new-proposal" :profile (merge {:first-name nil :province nil :entity-type new-type} profile)}))))
