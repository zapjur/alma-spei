(ns alma-spei.api.imports.review
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.donors.service :as donors]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.contracts.review :as review]
            [alma-spei.common.validation :as validation]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(defn batch! [tx id]
  (when-not (validation/valid? contract/identifier id) (imports/fail! 404 "not-found" "Nie znaleziono importu."))
  (let [batch (repository/batch-row tx (UUID/fromString id))]
    (when-not (and batch (= "bank" (:source-type batch))) (imports/fail! 404 "not-found" "Nie znaleziono importu."))
    batch))

(defn execute! [ds token action id locator input]
  ;; Metadata, row and donor versions in each response share one database snapshot.
  ;; Session activity is touched outside the snapshot to avoid write conflicts
  ;; between concurrent reads made by the same staff session.
  (auth/authorize! ds token false false)
  (jdbc/with-transaction [tx ds {:isolation :repeatable-read}]
    (case action
      :queue (do
               (imports/validate! contract/page-query input)
               (let [limit (get input :limit 25) offset (get input :offset 0)
                     result (repository/review-batches tx limit offset)]
                 {:batches (mapv imports/public-batch (:batches result)) :total (:total result) :limit limit :offset offset}))
      :batch (do (imports/validate! [:map {:closed true}] input)
                 {:batch (imports/public-batch (batch! tx id))})
      :comparison
      (let [batch (batch! tx id)]
        (imports/validate! review/locator locator)
        (imports/validate! review/comparison-query input)
        (when-not (= "review" (:state batch))
          (imports/fail! 409 "review-unavailable" "Ten import nie oczekuje na weryfikację. Odśwież kolejkę."))
        (let [row (or (repository/staged-row tx (:id batch) (:worksheet locator) (:row locator))
                      (imports/fail! 404 "not-found" "Nie znaleziono wiersza importu."))
              donor (donors/donor! tx (:donor-id input) false)]
          {:batch (imports/public-batch batch) :row (imports/public-row row) :donor (donors/public-donor donor)})))))
