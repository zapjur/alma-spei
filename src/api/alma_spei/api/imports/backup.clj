(ns alma-spei.api.imports.backup
  (:require [alma-spei.api.catalog :as catalog]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.execution :as execution]
            [alma-spei.api.imports.execution-repository :as repository]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.execution :as contract]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]
           [java.time Instant]
           [java.sql Timestamp]))

(defn now [] (Instant/now))
(defn enabled! [settings]
  (when-not (and (#{:development :test} (:environment settings)) (= true (:synthetic-imports settings)))
    (imports/fail! 409 "backup-unavailable" "Zatwierdzanie importów jest wyłączone. Brak zatwierdzonej usługi kopii zapasowej.")))
(defn run-synthetic! []
  ;; This is explicitly not a backup artifact or a production recovery guarantee.
  (let [completed (now)]
    {:artifact-ref (UUID/randomUUID) :completed-at (Timestamp/from completed) :expires-at (Timestamp/from (.plusSeconds completed 300))}))
(defn create! [ds settings token csrf id preview-id input]
  (imports/validate! contract/backup-input input)
  (let [binding (jdbc/with-transaction [tx ds]
                  (let [actor (execution/authorize! tx token csrf)]
                    (enabled! settings)
                    (catalog/lock! tx)
                    (let [batch (imports/locked-batch! tx id (:batch-version input))
                          {:keys [stored]} (execution/validate-preview! tx batch preview-id (:catalog-version input))]
                      {:batch-id (:id batch) :batch-version (:version batch) :actor-id (:id actor) :preview-id (:id stored)
                       :requested-at (Timestamp/from (now)) :effect-digest (:effect-digest stored) :catalog-version (:catalog-version input)})))
        result (try (run-synthetic!)
                    (catch Exception _ (imports/fail! 409 "backup-failed" "Nie udało się potwierdzić kopii zapasowej. Import nie został zastosowany.")))]
    (when-not (and (instance? UUID (:artifact-ref result)) (instance? Timestamp (:completed-at result)) (instance? Timestamp (:expires-at result))
                   (not (.isAfter (.toInstant ^Timestamp (:completed-at result)) (now)))
                   (.isBefore (.toInstant ^Timestamp (:completed-at result)) (.toInstant ^Timestamp (:expires-at result)))
                   (.isBefore (now) (.toInstant ^Timestamp (:expires-at result))))
      (imports/fail! 409 "backup-failed" "Potwierdzenie kopii zapasowej jest nieprawidłowe lub wygasło."))
    (jdbc/with-transaction [tx ds]
      (execution/authorize! tx token csrf)
      (catalog/lock! tx)
      (let [batch (imports/locked-batch! tx id (:batch-version input))]
        (execution/validate-preview! tx batch preview-id (:catalog-version input))
        {:batch (imports/public-batch batch)
         :backup (execution/public-backup (repository/insert-backup! tx (merge binding result {:id (UUID/randomUUID)})))}))))
(defn check! [tx settings input]
  (enabled! settings)
  (let [receipt (repository/backup tx (decisions/uuid! (:backup-id input)))]
    (when-not (and receipt (= "synthetic-test-v1" (:adapter receipt)) (= "synthetic-test-v1" (:policy-version receipt)) (:synthetic receipt)
                   (= (str (:batch-id receipt)) (:batch-id input)) (= (str (:preview-id receipt)) (:preview-id input))
                   (= (:effect-digest receipt) (:effect-digest input)) (= (:catalog-version receipt) (:catalog-version input))
                   (= (:batch-version receipt) (:batch-version input))
                   (not (.isAfter (.toInstant ^Timestamp (:completed-at receipt)) (now)))
                   (.isBefore (now) (.toInstant ^Timestamp (:expires-at receipt))))
      (imports/fail! 409 "backup-required" "Wymagane jest aktualne potwierdzenie kopii zapasowej dla tego podglądu."))
    receipt))
