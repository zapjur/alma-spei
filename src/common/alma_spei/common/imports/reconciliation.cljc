(ns alma-spei.common.imports.reconciliation
  (:require [alma-spei.common.domain.donor-identity :as identity]
            [clojure.string :as str]))

(def rule-version "bank-exact-identity-v2")
(def identity-fields [:name :street :postal-code :city])
(def normalized identity/text-key)
(defn identity-key [{:keys [name street postal-code city]}]
  [(normalized name) (identity/street-key street) (identity/postal-key postal-code) (normalized city)])
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
              (and (every? seq key) (= 1 (count exact)) (:is-active (first exact))) {:kind "existing-donor" :donor (first exact)}
              (seq related) {:reason "identity-ambiguity" :candidates (vec related)}
              (nil? new-type) {:reason "donor-type"}
              :else {:kind "new-proposal" :profile (merge {:first-name nil :province nil :entity-type new-type} profile)}))))
