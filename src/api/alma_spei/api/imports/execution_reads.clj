(ns alma-spei.api.imports.execution-reads
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.imports.execution :as execution]
            [alma-spei.api.imports.execution-repository :as repository]
            [alma-spei.api.imports.review :as review]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.api.imports.undo :as undo]
            [alma-spei.common.contracts.imports :as contract]
            [next.jdbc :as jdbc]))

(defn read! [ds token action id input]
  (auth/authorize! ds token false false)
  (imports/validate! (if (= :outcome action) [:map {:closed true}] contract/page-query) input)
  (jdbc/with-transaction [tx ds {:isolation :repeatable-read}]
    (let [limit (get input :limit 25) offset (get input :offset 0)]
      (if (= :history action)
        (let [result (repository/history tx limit offset)]
          {:batches (mapv imports/public-batch (:batches result)) :total (:total result) :limit limit :offset offset})
        (let [batch (review/batch! tx id)]
          (case action
            :outcome (execution/outcome tx (:id batch))
            :items (let [result (repository/item-page tx (:id batch) limit offset)]
                     {:batch (imports/public-batch batch) :limit limit :offset offset :total (:total result)
                      :items (mapv (fn [item] (-> (select-keys item [:ordinal :kind :worksheet-index :row-number :donor-version :donation-version :reversed])
                                                  (assoc :donor-id (str (:donor-id item)) :donation-id (some-> (:donation-id item) str)
                                                         :proposal-id (some-> (:proposal-id item) str) :snapshot (imports/decode-json (:snapshot item))))) (:items result))})
            :eligibility (let [commit (repository/commit-row tx (:id batch)) conflicts (undo/blockers tx batch commit false)]
                           {:batch (imports/public-batch batch) :commit-id (some-> (:id commit) str) :eligible (empty? conflicts)
                            :blockers (vec (take limit (drop offset conflicts))) :total (count conflicts) :limit limit :offset offset})))))))
