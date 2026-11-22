(ns alma-spei.api.imports.matching
  (:require [alma-spei.common.domain.donor-identity :as identity]
            [alma-spei.common.imports.reconciliation :as reconciliation]
            [clojure.string :as str]))

(defn evidence-keys [profile]
  (let [exact (reconciliation/identity-key profile)
        name (identity/folded-key (:name profile))
        address (mapv identity/folded-key (subvec exact 1))]
    (cond-> {}
      (every? seq exact) (assoc :exact exact)
      (seq name) (assoc :name name)
      (every? seq address) (assoc :address address)
      (and (not-empty name) (str/includes? name " ")
           (contains? #{nil "individual"} (:entity-type profile)))
      (assoc :last-token (last (str/split name #" "))))))

(defn suggestion-index [donors]
  (reduce (fn [index donor]
            (reduce-kv (fn [index field value] (update-in index [field value] (fnil conj []) donor))
                       index (evidence-keys donor)))
          {} (filter :is-active donors)))

(defn suggestions [index profile]
  (let [keys (evidence-keys profile)
        evidence (reduce-kv (fn [matches field value]
                              (reduce (fn [matches donor]
                                        (let [id (:id donor)]
                                          (-> matches
                                              (assoc-in [id :donor] donor)
                                              (update-in [id :fields] (fnil conj #{}) field))))
                                      matches (get-in index [field value])))
                            {} keys)]
    (->> (vals evidence)
         (map (fn [{:keys [donor fields]}]
                (let [[rank reason] (cond
                                      (fields :exact) [0 "Zgodna pełna nazwa i pełny adres."]
                                      (and (fields :name) (fields :address)) [1 "Podobna pełna nazwa i pełny adres."]
                                      (fields :name) [2 "Podobna pełna nazwa; adres różni się lub jest niepełny."]
                                      (fields :address) [3 "Podobny pełny adres; nazwa się różni."]
                                      :else [4 "Zgodny ostatni człon nazwy; sprawdź imię i adres."])]
                  {:donor donor :rank rank :reason reason})))
         (sort-by (juxt :rank #(get-in % [:donor :id])))
         (take 5)
         vec)))
