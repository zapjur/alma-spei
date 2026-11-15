(ns alma-spei.api.imports.execution
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as auth]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.api.imports.execution-repository :as repository]
            [alma-spei.api.imports.decision-repository :as review-repository]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.preview :as preview]
            [alma-spei.api.imports.service :as imports]))

(defn authorize! [tx token csrf]
  (let [actor (auth/authorize! tx token false false)]
    (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf)))
      (imports/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
    actor))
(defn validate-preview! [tx batch preview-id catalog-version]
  (decisions/review-state! batch)
  (catalog/check! tx catalog-version)
  (let [stored (preview/preview! tx (:id batch) preview-id)
        prepared (preview/prepare tx batch true)]
    (when-not (and (= preview/rule-version (:rule-version stored)) (= (:version batch) (:batch-version stored))
                   (= catalog-version (:catalog-version stored)) (empty? (:blockers prepared))
                   (= (:total-rows batch) (count (:checked prepared))) (pos? (:total-rows batch)))
      (imports/fail! 409 "stale-preview" "Podgląd wymaga ponownej weryfikacji decyzji i przygotowania aktualnej wersji."))
    (let [plan (preview/effects batch prepared)]
      (when-not (and (= (:effect-digest stored) (preview/digest (:context plan) (:items plan)))
                     (= (:item-count stored) (count (:items plan)))
                     (= (:items plan) (mapv preview/public-item (review-repository/items tx (:id stored) (:item-count stored) 0))))
        (imports/fail! 409 "stale-preview" "Zawartość podglądu zmieniła się. Przygotuj nowy podgląd."))
      {:stored stored :plan plan})))
(defn public-backup [row]
  {:id (str (:id row)) :adapter (:adapter row) :synthetic (:synthetic row)
   :completed-at (decisions/timestamp (:completed-at row)) :expires-at (decisions/timestamp (:expires-at row))})
(defn public-commit [tx row]
  (when row
    (-> (select-keys row [:ordinal :actor-name :batch-version :effect-digest])
        (assoc :id (str (:id row)) :actor-id (str (:actor-id row)) :preview-id (str (:preview-id row))
               :occurred-at (decisions/timestamp (:occurred-at row)) :summary (imports/decode-json (:summary row))
               :backup (public-backup (repository/backup tx (:backup-id row)))))))
(defn public-undo [row]
  (when row
    (-> (select-keys row [:actor-name :batch-version :reversed-payments :archived-donors])
        (assoc :id (str (:id row)) :actor-id (str (:actor-id row)) :occurred-at (decisions/timestamp (:occurred-at row))))))
(defn outcome [tx batch-id]
  (let [commit (repository/commit-row tx batch-id)]
    {:batch (imports/public-batch (batches/batch-row tx batch-id))
     :commit (public-commit tx commit) :undo (public-undo (when commit (repository/undo-row tx (:id commit))))}))
(defn replay! [row input]
  (when-not (= input (imports/decode-json (:request-context row)))
    (imports/fail! 409 "different-request" "Operację już wykonano z innymi danymi. Otwórz aktualną historię importu.")))
