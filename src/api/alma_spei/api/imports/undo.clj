(ns alma-spei.api.imports.undo
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.donors.repository :as donors]
            [alma-spei.api.donors.service :as donor-service]
            [alma-spei.api.imports.decision-repository :as decisions-repository]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.execution :as execution]
            [alma-spei.api.imports.execution-repository :as repository]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.execution :as contract]
            [next.jdbc :as jdbc]))

(defn conflict [code id] {:code code :entity-id (some-> id str)})
(defn blockers [tx batch commit lock?]
  (cond
    (nil? commit) [(conflict "not-committed" nil)]
    (repository/undo-row tx (:id commit)) [(conflict "already-undone" nil)]
    (not= (:id commit) (repository/head tx)) [(conflict "not-latest" nil)]
    :else
    (let [items (repository/all-items tx (:id commit))
          donor-items (filter #(contains? #{"new-donor" "existing-donor-reference"} (:kind %)) items)
          donors (into {} (map (fn [i] [(:donor-id i) (donors/find-donor tx (:donor-id i) lock?)]) (sort-by (comp str :donor-id) donor-items)))
          payment-items (filter :donation-id items)
          payments (into {} (map (fn [id] [id (decisions-repository/payment tx id lock?)]) (sort-by str (set (map :donation-id payment-items)))))]
      (vec
       (concat
        (keep (fn [item]
                (let [current (get donors (:donor-id item)) snapshot (:donor (imports/decode-json (:snapshot item)))]
                  (cond
                    (or (nil? current) (not= snapshot (donor-service/public-donor current))
                        (and (= "new-donor" (:kind item)) (not= (:id batch) (:import-batch-id current))))
                    (conflict "changed-donor" (:donor-id item))
                    (and (= "new-donor" (:kind item))
                         (repository/dependencies? tx (:id batch) (:donor-id item) (mapv :donation-id (filter #(= (:donor-id item) (:donor-id %)) payment-items))))
                    (conflict "dependent-records" (:donor-id item))))) donor-items)
        (keep (fn [item]
                (let [current (get payments (:donation-id item)) before (:payment (imports/decode-json (:snapshot item)))]
                  (cond
                    (not (and current (= (:donor-id item) (:donor-id current)) (= (:donation-version item) (:version current))
                              (= (:amount current) (bigdec (:amount before))) (= (:currency before) (:currency current))
                              (= (:received-on before) (str (:received-on current)))
                              (= (:import-batch-id before) (some-> (:import-batch-id current) str))))
                    (conflict "changed-payment" (:donation-id item))
                    (and (= "new-payment" (:kind item)) (repository/payment-dependencies? tx (:id batch) (:donation-id item)))
                    (conflict "dependent-records" (:donation-id item))))) payment-items))))))
(defn archive! [tx actor id]
  (let [before (donors/find-donor tx id false)
        after (donors/one tx ["UPDATE donors SET is_active=false,version=version+1,updated_by=?,updated_at=now() WHERE id=? RETURNING *" actor id])]
    (audit/record! tx actor id "archive" (audit/changes before after))))
(defn execute! [ds token csrf input]
  (imports/validate! contract/undo-input input)
  (jdbc/with-transaction [tx ds]
    (let [actor (execution/authorize! tx token csrf) batch-id (decisions/uuid! (:batch-id input))]
      (catalog/lock! tx)
      (let [commit (repository/commit-row tx batch-id)]
        (when-not (and commit (= (:commit-id input) (str (:id commit))))
          (imports/fail! 409 "ineligible-undo" "Wskazany import nie ma oczekiwanego zatwierdzenia."))
        (if-let [previous (repository/undo-row tx (:id commit))]
          (do (execution/replay! previous input) (execution/outcome tx batch-id))
          (let [batch (imports/locked-batch! tx (:batch-id input) (:batch-version input))
                conflicts (blockers tx batch commit true)]
            (when (or (not= "committed" (:state batch)) (seq conflicts))
              (throw (ex-info "Undo blocked" {:status 409 :code "undo-blocked" :public-message "Wycofanie wstrzymano. Sprawdź aktualną listę powiązań i zmian."
                                              :blockers (vec (take 25 conflicts)) :total (count conflicts)})))
            (let [items (repository/all-items tx (:id commit))
                  created (filter #(= "new-donor" (:kind %)) items) payments (filter #(= "new-payment" (:kind %)) items)
                  id (java.util.UUID/randomUUID)]
              (repository/insert-undo! tx {:id id :batch-id batch-id :commit-id (:id commit) :actor-id (:id actor) :request input
                                           :batch-version (inc (:version batch)) :reversed-payments (count payments) :archived-donors (count created)})
              (doseq [item payments] (repository/reverse! tx batch-id id (:id actor) (:donation-id item)))
              (doseq [item created] (archive! tx (:id actor) (:donor-id item)))
              (let [after (repository/transition! tx batch-id "undone")]
                (batches/record-event! tx after (:id actor) "undone" "committed" nil))
              (execution/outcome tx batch-id))))))))
