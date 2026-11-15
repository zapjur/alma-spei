(ns alma-spei.api.imports.commit
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.donors.repository :as donors]
            [alma-spei.api.donors.service :as donor-service]
            [alma-spei.api.imports.backup :as backup]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.execution :as execution]
            [alma-spei.api.imports.execution-repository :as repository]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.donors :as donor-contract]
            [alma-spei.common.contracts.execution :as contract]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]
           [java.time LocalDate]))

(defn create-donor! [tx batch actor profile]
  (imports/validate! donor-contract/create-donor profile)
  (let [id (UUID/randomUUID)
        row (donors/one tx (into ["INSERT INTO donors(id,entity_type,name,first_name,street,postal_code,city,province,created_by,updated_by,import_batch_id) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *" id]
                                 (concat (map profile donors/profile-fields) [actor actor batch])))]
    (audit/record! tx actor id "create" (audit/changes nil row))
    row))
(defn create-payment! [tx batch actor donor payload]
  (donors/one tx ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by,import_batch_id) VALUES (?,?,?,?,?,?,?) RETURNING *"
                  (UUID/randomUUID) donor (bigdec (:amount payload)) (:currency payload) (LocalDate/parse (:received-on payload)) actor batch]))
(defn apply-items! [tx batch actor commit preview items]
  (let [proposals (atom {})]
    (doseq [item items]
      (let [{:keys [kind payload]} item
            target (some-> (:target-id item) decisions/uuid!)
            donor (case kind
                    "new-donor" (let [row (create-donor! tx batch actor (:profile payload))] (swap! proposals assoc (:target-id item) row) row)
                    "existing-donor-reference" (donors/find-donor tx target false)
                    (if (= "new-proposal" (:target-kind payload)) (get @proposals (:target-id payload))
                        (donors/find-donor tx (decisions/uuid! (:target-id payload)) false)))
            payment (case kind
                      "new-payment" (create-payment! tx batch actor (:id donor) payload)
                      "duplicate-reference" (donors/one tx ["SELECT * FROM active_donations WHERE id=?" (decisions/uuid! (:donation-id payload))]) nil)
            snapshot (if payment {:donor (donor-service/public-donor donor)
                                  :payment {:id (str (:id payment)) :donor-id (str (:donor-id payment)) :version (:version payment)
                                            :amount (.toPlainString ^java.math.BigDecimal (:amount payment)) :currency (:currency payment)
                                            :received-on (str (:received-on payment)) :import-batch-id (some-> (:import-batch-id payment) str)}}
                         {:donor (donor-service/public-donor donor)})]
        (repository/insert-item! tx (merge (select-keys item [:ordinal :kind :worksheet-index :row-number])
                                           {:batch-id batch :commit-id commit :preview-id preview :donor-id (:id donor)
                                            :donation-id (:id payment) :proposal-id (when (= kind "new-donor") target)
                                            :donor-version (:version donor) :donation-version (:version payment) :snapshot snapshot}))))))
(defn execute! [ds settings token csrf input]
  (imports/validate! contract/commit-input input)
  (jdbc/with-transaction [tx ds]
    (let [actor (execution/authorize! tx token csrf) batch-id (decisions/uuid! (:batch-id input))]
      (catalog/lock! tx)
      (if-let [previous (repository/commit-row tx batch-id)]
        (do (execution/replay! previous input) (execution/outcome tx batch-id))
        (let [batch (imports/locked-batch! tx (:batch-id input) (:batch-version input))
              {:keys [stored plan]} (execution/validate-preview! tx batch (:preview-id input) (:catalog-version input))]
          (when-not (= (:effect-digest stored) (:effect-digest input))
            (imports/fail! 409 "stale-preview" "Podgląd nie odpowiada potwierdzonym zmianom."))
          (backup/check! tx settings input)
          (let [id (UUID/randomUUID)
                approved (repository/transition! tx batch-id "approved")]
            (batches/record-event! tx approved (:id actor) "approved" "review" nil)
            (repository/insert-commit! tx {:id id :batch-id batch-id :preview-id (:id stored) :effect-digest (:effect-digest stored)
                                           :catalog-version (:catalog-version input) :backup-id (decisions/uuid! (:backup-id input)) :actor-id (:id actor)
                                           :request input :batch-version (+ 2 (:version batch)) :summary (:summary plan)})
            (apply-items! tx batch-id (:id actor) id (:id stored) (:items plan))
            (let [committed (repository/transition! tx batch-id "committed")]
              (batches/record-event! tx committed (:id actor) "committed" "approved" nil))
            (execution/outcome tx batch-id)))))))
