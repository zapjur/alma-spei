(ns alma-spei.api.imports.reconciliation
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.decision-repository :as repository]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.api.imports.review :as review]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.api.donors.service :as donors]
            [alma-spei.common.imports.reconciliation :as matching]
            [alma-spei.common.contracts.reconciliation :as contract]
            [alma-spei.common.contracts.decisions :as decision-contract]
            [alma-spei.common.contracts.imports :as import-contract]
            [alma-spei.common.contracts.donors :as donor-contract]
            [alma-spei.common.validation :as validation]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(defn policy [tx id]
  (some-> (batches/one tx ["SELECT after_value->'automation'->'policy' AS policy FROM import_review_events WHERE batch_id=? AND after_value->'automation' IS NOT NULL ORDER BY occurred_at,id LIMIT 1" id])
          :policy imports/decode-json))
(defn donor-index [tx]
  (let [all (batches/query tx ["SELECT * FROM donors ORDER BY id"])]
    {:names (group-by #(matching/normalized (:name %)) all)
     :addresses (group-by #(subvec (matching/identity-key %) 1) all)}))
(defn related [index profile]
  (vals (into {} (map (juxt :id identity)
                      (concat (get-in index [:names (matching/normalized (:name profile))])
                              (get-in index [:addresses (subvec (matching/identity-key profile) 1)]))))))
(defn payment-key [donor payload currency] [donor (:date payload) (bigdec (:amount payload)) currency])
(defn context [tx batch selected-policy]
  (let [rows (mapv imports/public-row (repository/source-rows tx batch))
        parsed (mapv #(matching/sender-profile (get-in % [:payload :sender])) rows)
        keys (mapv (fn [row profile] (when (and profile (= "valid" (:validation-state row)))
                                       [(matching/identity-key profile) (get-in row [:payload :date]) (bigdec (get-in row [:payload :amount]))])) rows parsed)]
    {:catalog-version (catalog/version tx) :rows rows :parsed parsed
     :incoming-names (group-by #(matching/normalized (:name %)) (keep identity parsed)) :repeats (frequencies (keep identity keys)) :keys keys
     :index (donor-index tx)
     :payments (group-by #(vector (:donor-id %) (str (:received-on %)) (bigdec (:amount %)) (:currency %))
                         (batches/query tx ["SELECT donor_id,received_on,amount,currency FROM active_donations"]))
     :policy selected-policy}))
(defn classify [ctx row profile key]
  (let [payload (:payload row) selected-policy (:policy ctx)
        plan (matching/donor-plan profile (when profile (related (:index ctx) profile)) (:new-donor-type selected-policy))]
    (cond
      (not= "valid" (:validation-state row)) {:reason "invalid-row"}
      (not (pos? (bigdec (:amount payload)))) {:reason "unsupported-amount"}
      (> (get (:repeats ctx) key 0) 1) {:reason "payment-ambiguity"}
      (:reason plan) plan
      (and (:profile plan) (> (count (set (map matching/identity-key (get-in ctx [:incoming-names (matching/normalized (:name profile))])))) 1)) {:reason "identity-ambiguity"}
      (and (:profile plan) (not (validation/valid? donor-contract/create-donor (:profile plan)))) {:reason "sender-format"}
      (and (:donor plan) (seq (get (:payments ctx) (payment-key (get-in plan [:donor :id]) payload (:currency selected-policy)))))
      {:reason "payment-ambiguity" :candidates [(:donor plan)]}
      :else plan)))
(defn report [tx batch limit offset]
  (let [selected-policy (policy tx (:id batch))
        ctx (context tx (:id batch) selected-policy)
        saved (into {} (map (juxt #(vector (:worksheet-index %) (:row-number %)) identity) (repository/all-decisions tx (:id batch))))
        revision (catalog/version tx)
        rows (mapv (fn [row profile key]
                     (let [decision (get saved [(:worksheet-index row) (:row-number row)])
                           valid? (and (:complete decision) (= revision (:accepted-catalog-version decision))
                                       (= "valid" (:validation-state row)) (pos? (bigdec (get-in row [:payload :amount]))))
                           plan (when-not valid? (classify ctx row profile key))]
                       {:row row :decision decision :resolved valid? :reason (if (and (:complete decision) (not= revision (:accepted-catalog-version decision))) "stale-catalog" (if (and decision (not= "unresolved" (:kind decision)) (not (:complete decision))) "payment-ambiguity" (or (:reason plan) "manual-review")))
                        :candidates (vec (take 5 (:candidates plan)))})) (:rows ctx) (:parsed ctx) (:keys ctx))
        resolved (filter :resolved rows) exceptions (remove :resolved rows)
        totals (reduce (fn [totals item]
                         (let [financial (imports/decode-json (get-in item [:decision :financial]))]
                           (if (= "new" (:disposition financial))
                             (update totals (:currency financial) (fnil + 0M) (bigdec (get-in item [:row :payload :amount]))) totals))) {} resolved)
        counts {:total (count rows) :matched (count (filter #(= "existing-donor" (get-in % [:decision :kind])) resolved))
                :new-donors (count (set (keep #(get-in % [:decision :proposal-id]) resolved)))
                :new-payments (count (filter #(= "new" (:disposition (imports/decode-json (get-in % [:decision :financial])))) resolved))
                :duplicates (count (keep #(get-in % [:decision :donation-id]) resolved)) :exceptions (count exceptions)}]
    {:batch (imports/public-batch batch) :catalog-version revision :policy selected-policy :counts counts
     :totals (mapv (fn [[currency amount]] {:currency currency :amount (.toPlainString ^java.math.BigDecimal amount)}) (sort-by key totals))
     :exceptions (mapv (fn [item] {:row (:row item) :reason (:reason item) :candidates (mapv donors/public-donor (:candidates item))}) (take limit (drop offset exceptions)))
     :limit limit :offset offset :ready (boolean (and (seq rows) (empty? exceptions) (= "review" (:state batch))))}))
(defn read! [ds token id input]
  (auth/authorize! ds token false false)
  (imports/validate! import-contract/page-query input)
  (jdbc/with-transaction [tx ds {:isolation :repeatable-read}]
    (report tx (review/batch! tx id) (get input :limit 25) (get input :offset 0))))
(defn proposal-for! [tx batch actor profile proposals selected-policy]
  (let [key (matching/identity-key profile)]
    (when (empty? (get @proposals key))
      (imports/validate! donor-contract/create-donor profile)
      (let [created (repository/insert-proposal! tx (UUID/randomUUID) batch actor profile)]
        (swap! proposals assoc key [created])
        (decisions/event! tx actor batch "proposal-created" {:proposal-id (:id created)} nil (decisions/public-proposal created))))
    (let [matches (get @proposals key)]
      (when (and (= 1 (count matches)) (= (:new-donor-type selected-policy) (:entity-type (imports/decode-json (:profile (first matches))))))
        (first matches)))))
(defn reconcile! [ds token csrf id input]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf))) (imports/fail! 403 "csrf" "Sesja formularza wygasła."))
      (imports/validate! contract/input input)
      (catalog/lock! tx)
      (let [batch (imports/locked-batch! tx id (:batch-version input)) id (:id batch) existing-policy (policy tx id)
            selected-policy (:policy input)]
        (decisions/review-state! batch)
        (when (and existing-policy (not= existing-policy selected-policy))
          (imports/fail! 409 "policy-conflict" "Ten import ma już inne potwierdzone ustawienia. Nie zmieniono zapisanych decyzji."))
        (let [ctx (context tx id selected-policy)
              saved (into {} (map (juxt #(vector (:worksheet-index %) (:row-number %)) identity) (repository/all-decisions tx id)))
              automatic (set (map #(vector (:worksheet-index %) (:row-number %))
                                  (batches/query tx ["SELECT d.worksheet_index,d.row_number FROM import_review_decisions d JOIN import_review_events e ON e.batch_id=d.batch_id AND e.worksheet_index=d.worksheet_index AND e.row_number=d.row_number AND e.after_version=d.version WHERE d.batch_id=? AND e.event_type='decision-saved' AND e.after_value->'automation'->>'rule-version' IN (?,?) AND coalesce(e.after_value->'automation'->>'identity-confirmed','false')='false'" id "bank-exact-identity-v1" matching/rule-version])))
              proposals (atom (group-by #(matching/identity-key (imports/decode-json (:profile %)))
                                        (:proposals (repository/proposals tx id 50000 0))))
              changed (volatile! false)]
          (doseq [[row profile key] (map vector (:rows ctx) (:parsed ctx) (:keys ctx))
                  :let [locator {:worksheet-index (:worksheet-index row) :row-number (:row-number row)}]
                  :when (or (not (contains? saved [(:worksheet-index row) (:row-number row)])) (contains? automatic [(:worksheet-index row) (:row-number row)]))]
            (let [before (decisions/public-decision (get saved [(:worksheet-index row) (:row-number row)]))
                  plan (classify ctx row profile key)
                  proposal (when (= "new-proposal" (:kind plan)) (proposal-for! tx id (:id actor) (:profile plan) proposals selected-policy))
                  kind (cond (:donor plan) (:kind plan) proposal "new-proposal" :else "unresolved")
                  complete? (not= kind "unresolved")
                  decision {:kind kind :donor-id (when complete? (get-in plan [:donor :id])) :proposal-id (:id proposal)
                            :target-version (when complete? (or (get-in plan [:donor :version]) (:version proposal)))
                            :donation-id nil :donation-version nil :catalog-version (:catalog-version ctx)
                            :financial (when complete? {:currency (:currency selected-policy) :disposition "new" :overlap-checked true
                                                        :evidence (str "Automatyczna analiza " matching/rule-version "; waluta potwierdzona dla pliku: " (:currency selected-policy) "; brak identycznego kandydata wpłaty w bazie i powtórzenia w pliku.")})}]
              (when-not (and (= (select-keys before (mapv first decision-contract/decision-fields))
                                (select-keys (-> decision (update :donor-id #(some-> % str)) (update :proposal-id #(some-> % str)))
                                             (mapv first decision-contract/decision-fields)))
                             (= (:accepted-catalog-version before) (when complete? (:catalog-version decision))))
                (repository/save-decision! tx id (:worksheet-index row) (:row-number row) (:id actor) decision complete?)
                (decisions/event! tx (:id actor) id "decision-saved" locator before
                                  (assoc (decisions/public-decision (repository/decision tx id (:worksheet-index row) (:row-number row)))
                                         :automation {:rule-version matching/rule-version :policy selected-policy :reason (:reason plan)}))
                (vreset! changed true))))
          (when @changed (repository/advance-batch! tx id))
          (report tx (batches/batch-row tx id) 25 0))))))

(defn resolve-identity! [ds token csrf id input]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf))) (imports/fail! 403 "csrf" "Sesja formularza wygasła."))
      (imports/validate! contract/resolution input)
      (catalog/lock! tx)
      (catalog/check! tx (:catalog-version input))
      (let [batch (imports/locked-batch! tx id (:batch-version input)) id (:id batch) selected-policy (policy tx id)
            locator (select-keys input [:worksheet :row])
            row (imports/public-row (decisions/row! tx id locator))
            ctx (context tx id selected-policy)
            index (.indexOf ^java.util.List (:rows ctx) row)
            key (when (<= 0 index) (get (:keys ctx) index))
            donor-id (when (= "existing-donor" (:kind input)) (:target-id input))
            proposal-id (when (= "new-proposal" (:kind input)) (:target-id input))
            duplicate? (or (> (get (:repeats ctx) key 0) 1)
                           (and (= "valid" (:validation-state row)) donor-id (seq (get (:payments ctx) (payment-key (decisions/uuid! donor-id) (:payload row) (:currency selected-policy))))))
            before (decisions/public-decision (repository/decision tx id (:worksheet input) (:row input)))
            decision {:kind (:kind input) :donor-id donor-id :proposal-id proposal-id :target-version (:target-version input)
                      :donation-id nil :donation-version nil :catalog-version (:catalog-version input)
                      :financial {:currency (:currency selected-policy) :disposition (if duplicate? "unresolved" "new") :overlap-checked (not duplicate?)
                                  :evidence (if duplicate? "" (str "Tożsamość potwierdzona przez użytkownika; analiza " matching/rule-version "; waluta potwierdzona dla pliku; brak identycznego kandydata wpłaty w bazie i powtórzenia w pliku."))}}]
        (decisions/review-state! batch)
        (when-not selected-policy (imports/fail! 409 "missing-policy" "Najpierw potwierdź ustawienia źródła."))
        (when-not (and (= "valid" (:validation-state row)) (pos? (bigdec (get-in row [:payload :amount]))))
          (imports/fail! 409 "invalid-row" "Popraw dane źródłowe lub wyjaśnij kwotę ujemną."))
        (decisions/check-target! tx id decision)
        (repository/save-decision! tx id (:worksheet input) (:row input) (:id actor)
                                   (-> decision (update :donor-id #(some-> % decisions/uuid!)) (update :proposal-id #(some-> % decisions/uuid!))) (not duplicate?))
        (repository/advance-batch! tx id)
        (decisions/event! tx (:id actor) id "decision-saved" {:worksheet-index (:worksheet input) :row-number (:row input)} before
                          (assoc (decisions/public-decision (repository/decision tx id (:worksheet input) (:row input)))
                                 :automation {:rule-version matching/rule-version :policy selected-policy :identity-confirmed true :reason (when duplicate? "payment-ambiguity")}))
        (decisions/decision-response tx id locator)))))
