(ns alma-spei.api.imports.preview
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.donors.repository :as donors]
            [alma-spei.api.donors.service :as donor-service]
            [alma-spei.api.imports.decision-repository :as repository]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.imports.review :as review]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.contracts.imports :as import-contract]
            [next.jdbc :as jdbc])
  (:import [java.util UUID HexFormat]
           [java.security MessageDigest]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(def rule-version "review-preview-v2")
(defn ordered [value]
  (cond (map? value) ["map" (mapv (fn [[k v]] [(name k) (ordered v)]) (sort-by (comp name key) value))]
        (sequential? value) ["sequence" (mapv ordered value)]
        :else value))
(defn digest [context items]
  ;; Length-prefixed canonical records avoid unordered-map printing and delimiter ambiguity.
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [record (cons context items)]
      (let [bytes (.getBytes ^String (imports/json (ordered record)) StandardCharsets/UTF_8)]
        (.update md (.array (.putLong (ByteBuffer/allocate 8) (alength bytes))))
        (.update md bytes)))
    (.formatHex (HexFormat/of) (.digest md))))
(defn target-maps [tx batch rows lock?]
  (let [ids (fn [field] (sort-by str (set (keep field rows))))]
    {:donors (into {} (map (fn [id] [id (donors/find-donor tx id lock?)]) (ids :donor-id)))
     :proposals (into {} (map (fn [id] [id (repository/proposal tx batch id lock?)]) (ids :proposal-id)))
     :payments (into {} (map (fn [id] [id (repository/payment tx id lock?)]) (ids :donation-id)))}))
(defn prepare [tx batch lock?]
  (let [sources (repository/source-rows tx (:id batch)) decisions (repository/all-decisions tx (:id batch))
        by-row (into {} (map (fn [d] [[(:worksheet-index d) (:row-number d)] d]) decisions))
        targets (target-maps tx (:id batch) decisions lock?)
        catalog-version (catalog/version tx)
        checked (mapv (fn [row]
                        (let [decision (get by-row [(:worksheet-index row) (:row-number row)])
                              input (decisions/public-decision decision)
                              payload (:payload (imports/public-row row))
                              target (if (= "existing-donor" (:kind decision)) (get-in targets [:donors (:donor-id decision)])
                                         (get-in targets [:proposals (:proposal-id decision)]))
                              code (cond
                                     (not= "valid" (:validation-state row)) "invalid-row"
                                     (not (:complete decision)) "missing-decision"
                                     (not= catalog-version (:accepted-catalog-version decision)) "stale-catalog"
                                     (not (pos? (bigdec (:amount payload)))) "unsupported-amount"
                                     (or (nil? target) (not= (:target-version decision) (:version target))) "stale-target"
                                     (and (= "existing-donor" (:kind decision)) (not (:is-active target))) "inactive-donor"
                                     (and (:donation-id decision)
                                          (not (decisions/payment-matches? (get-in targets [:payments (:donation-id decision)])
                                                                           (:donor-id decision) payload (:financial input) (:donation-version decision)))) "duplicate-mismatch")]
                          {:row row :decision decision :input input :payload payload :code code})) sources)]
    {:checked checked :targets targets :catalog-version catalog-version
     :blockers (into [] (keep (fn [{:keys [row code]}] (when code {:worksheet-index (:worksheet-index row) :row-number (:row-number row) :code code}))) checked)}))
(defn item [kind target row payload]
  {:kind kind :target-id (some-> target str) :worksheet-index (:worksheet-index row) :row-number (:row-number row) :payload payload})
(defn effects [batch {:keys [checked targets catalog-version]}]
  (let [selected (filterv #(not= "unresolved" (:kind %)) (mapv :decision checked))
        donor-items (mapv (fn [id] (let [donor (get-in targets [:donors id])]
                                     (item "existing-donor-reference" id nil {:donor (donor-service/public-donor donor) :changes {}})))
                          (sort-by str (set (keep :donor-id selected))))
        proposal-items (mapv (fn [id] (let [proposal (get-in targets [:proposals id])]
                                        (item "new-donor" id nil {:proposal-id (str id) :proposal-version (:version proposal)
                                                                  :profile (imports/decode-json (:profile proposal))})))
                             (sort-by str (set (keep :proposal-id selected))))
        payment-items (mapv (fn [{:keys [row decision input payload]}]
                              (let [duplicate? (some? (:donation-id decision))]
                                (item (if duplicate? "duplicate-reference" "new-payment") nil row
                                      {:decision-version (:version decision) :target-kind (:kind decision)
                                       :target-id (str (or (:donor-id decision) (:proposal-id decision))) :target-version (:target-version decision)
                                       :target-label (if (:donor-id decision) (get-in targets [:donors (:donor-id decision) :name])
                                                         (:name (imports/decode-json (get-in targets [:proposals (:proposal-id decision) :profile]))))
                                       :amount (:amount payload) :received-on (:date payload) :currency (get-in input [:financial :currency])
                                       :evidence (get-in input [:financial :evidence]) :overlap-checked (get-in input [:financial :overlap-checked])
                                       :donation-id (some-> (:donation-id decision) str) :donation-version (:donation-version decision)
                                       :source-rule-version (:rule-version row)}))) checked)
        items (mapv (fn [i value] (assoc value :ordinal (inc i))) (range) (concat donor-items proposal-items payment-items))
        new-payments (filter #(= "new-payment" (:kind %)) payment-items)
        totals (reduce (fn [totals payment] (update totals (get-in payment [:payload :currency]) (fnil + 0M) (bigdec (get-in payment [:payload :amount])))) {} new-payments)]
    {:items items :summary {:new-donors (count proposal-items) :new-payments (count new-payments)
                            :duplicate-references (- (count payment-items) (count new-payments))
                            :totals (mapv (fn [[currency amount]] {:currency currency :amount (.toPlainString ^java.math.BigDecimal amount)}) (sort-by key totals))}
     :context [rule-version catalog-version (str (:id batch)) (:version batch) (str (:file-id batch)) (:parse-version batch) (:mapping-version batch)]}))
(defn public-preview [row]
  {:id (str (:id row)) :batch-id (str (:batch-id row)) :batch-version (:batch-version row)
   :catalog-version (:catalog-version row) :created-by (str (:created-by row)) :created-at (decisions/timestamp (:created-at row)) :rule-version (:rule-version row)
   :effect-digest (:effect-digest row) :item-count (:item-count row) :summary (imports/decode-json (:summary row))})
(defn preview! [tx batch id]
  (or (repository/preview tx batch (decisions/uuid! id)) (imports/fail! 404 "not-found" "Nie znaleziono podglądu importu.")))
(defn public-item [row]
  {:ordinal (:ordinal row) :kind (:kind row) :target-id (some-> (:target-id row) str)
   :worksheet-index (:worksheet-index row) :row-number (:row-number row) :payload (imports/decode-json (:payload row))})
(defn create! [ds token csrf id input]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf))) (imports/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
      (imports/validate! contract/preview-input input)
      (catalog/lock! tx)
      (let [batch (imports/locked-batch! tx id (:batch-version input))]
        (decisions/review-state! batch)
        (let [prepared (prepare tx batch true)]
          (when (seq (:blockers prepared))
            (throw (ex-info "Preview blocked" {:status 409 :code "review-blocked" :public-message "Uzupełnij lub odśwież decyzje przed przygotowaniem podglądu."
                                               :blockers (vec (take 25 (:blockers prepared))) :total (count (:blockers prepared))})))
          (let [{:keys [items summary context]} (effects batch prepared)
                id (UUID/randomUUID) row (repository/insert-preview! tx {:id id :batch-id (:id batch) :batch-version (:version batch)
                                                                         :catalog-version (:catalog-version prepared) :created-by (:id actor) :effect-digest (digest context items) :summary summary :item-count (count items)})]
            (doseq [item items] (repository/insert-item! tx id item))
            (decisions/event! tx (:id actor) (:id batch) "preview-created" {:preview-id id} nil
                              {:version (:version batch) :preview-id (str id) :effect-digest (:effect-digest row)})
            {:catalog-version (catalog/version tx) :batch (imports/public-batch batch) :preview (public-preview row) :stale false}))))))
(defn read! [ds token action id subject input]
  (auth/authorize! ds token false false)
  (jdbc/with-transaction [tx ds {:isolation :repeatable-read}]
    (let [batch (review/batch! tx id) batch-id (:id batch) limit (get input :limit 25) offset (get input :offset 0)]
      (imports/validate! (if (= action :preview) [:map {:closed true}] import-contract/page-query) input)
      (case action
        :blockers (let [blockers (:blockers (prepare tx batch false))]
                    {:catalog-version (catalog/version tx) :batch (imports/public-batch batch) :blockers (vec (take limit (drop offset blockers))) :total (count blockers) :limit limit :offset offset})
        :preview (let [preview (preview! tx batch-id subject)
                       prepared (prepare tx batch false)
                       current (when (and (= "review" (:state batch)) (empty? (:blockers prepared))) (effects batch prepared))]
                   {:catalog-version (catalog/version tx) :batch (imports/public-batch batch) :preview (public-preview preview)
                    :stale (not (and (= rule-version (:rule-version preview)) current (= (:version batch) (:batch-version preview))
                                     (= (:effect-digest preview) (digest (:context current) (:items current)))))})
        :items (let [preview (preview! tx batch-id subject)]
                 {:preview (public-preview preview) :items (mapv public-item (repository/items tx (:id preview) limit offset))
                  :total (:item-count preview) :limit limit :offset offset})))))
