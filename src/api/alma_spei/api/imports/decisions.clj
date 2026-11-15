(ns alma-spei.api.imports.decisions
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.common.contracts.donors :as donor-contract]
            [alma-spei.api.donors.service :as donors]
            [alma-spei.api.donors.repository :as donor-repository]
            [alma-spei.api.imports.review :as review]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.api.imports.decision-repository :as repository]
            [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.contracts.imports :as import-contract]
            [alma-spei.common.contracts.review :as review-contract]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(defn uuid! [id]
  (when-not (validation/valid? import-contract/identifier id) (imports/fail! 404 "not-found" "Nie znaleziono danych weryfikacji."))
  (UUID/fromString id))
(defn timestamp [value] (str (.toInstant ^java.sql.Timestamp value)))
(defn public-proposal [row]
  (-> (select-keys row [:version])
      (assoc :id (str (:id row)) :batch-id (str (:batch-id row)) :profile (imports/decode-json (:profile row))
             :created-by (str (:created-by row)) :updated-by (str (:updated-by row))
             :created-at (timestamp (:created-at row)) :updated-at (timestamp (:updated-at row)))))
(defn public-decision [row]
  (when row
    (-> (select-keys row [:version :kind :target-version :donation-version :complete])
        (assoc :donor-id (some-> (:donor-id row) str) :proposal-id (some-> (:proposal-id row) str)
               :financial (some-> (:financial row) imports/decode-json) :donation-id (some-> (:donation-id row) str)
               :reviewer-id (str (:reviewer-id row)) :reviewer-name (:reviewer-name row) :target-label (:target-label row) :reviewed-at (timestamp (:reviewed-at row))))))
(defn row! [tx batch locator]
  (imports/validate! review-contract/locator locator)
  (or (batches/staged-row tx batch (:worksheet locator) (:row locator))
      (imports/fail! 404 "not-found" "Nie znaleziono wiersza importu.")))
(defn review-state! [batch]
  (when-not (and (= "bank" (:source-type batch)) (= "review" (:state batch)))
    (imports/fail! 409 "review-unavailable" "Ten import nie oczekuje na weryfikację.")))
(defn stale! [] (imports/fail! 409 "stale-review" "Dane weryfikacji zmieniły się. Zachowaj potrzebne poprawki i odśwież widok."))
(defn proposal! [tx batch id lock?]
  (or (repository/proposal tx batch (uuid! id) lock?) (imports/fail! 404 "not-found" "Nie znaleziono propozycji darczyńcy.")))
(defn event! [tx actor batch kind subject before after]
  (repository/event! tx (merge {:id (UUID/randomUUID) :batch-id batch :actor-id actor :event-type kind
                                :before-version (or (:version before) 0) :after-version (:version after)
                                :before before :after after} subject)))
(defn normalize-profile [profile]
  (into {} (map (fn [field] [field (let [value (get profile field)] (if (string? value) (not-empty (str/trim value)) value))]) donor-repository/profile-fields)))
(defn payment-matches? [payment donor payload financial version]
  (boolean (and payment (= (:donor-id payment) donor) (= (:version payment) version)
                (= (:currency payment) (:currency financial)) (= (str (:received-on payment)) (:date payload))
                (:amount payload) (= (bigdec (:amount payment)) (bigdec (:amount payload))))))
(defn check-target! [tx batch input]
  (case (:kind input)
    "unresolved" nil
    "existing-donor" (let [donor (donors/donor! tx (:donor-id input) true)]
                       (when-not (:is-active donor) (imports/fail! 409 "inactive-donor" "Wybierz aktywny profil darczyńcy."))
                       (when-not (= (:target-version input) (:version donor)) (stale!)))
    "new-proposal" (when-not (= (:target-version input) (:version (proposal! tx batch (:proposal-id input) true))) (stale!))))
(defn decision-response [tx batch locator]
  {:batch (imports/public-batch (batches/batch-row tx batch)) :row (imports/public-row (row! tx batch locator))
   :decision (public-decision (repository/decision tx batch (:worksheet locator) (:row locator)))})
(defn write! [ds token csrf action id subject locator input]
  (jdbc/with-transaction [tx ds]
    (let [principal (auth/authorize! tx token false false)
          _ (when-not (and csrf (crypto/equal? (:csrf-digest principal) (crypto/digest csrf)))
              (imports/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
          actor (:id principal)
          schema (case action :proposal-create contract/proposal-create :proposal-update contract/proposal-update :decision contract/decision-input)]
      (imports/validate! schema input)
      (let [batch (imports/locked-batch! tx id (:batch-version input)) batch-id (:id batch)]
        (review-state! batch)
        (case action
          (:proposal-create :proposal-update)
          (let [profile (normalize-profile (:profile input))
                _ (imports/validate! donor-contract/create-donor profile)
                before (when (= action :proposal-update) (public-proposal (proposal! tx batch-id subject true)))
                _ (when (and before (not= (:version input) (:version before))) (stale!))
                changed? (not= profile (:profile before))
                after (if-not changed? before
                              (public-proposal (if before (repository/update-proposal! tx (uuid! subject) batch-id actor profile)
                                                   (repository/insert-proposal! tx (UUID/randomUUID) batch-id actor profile))))]
            (when changed?
              (repository/advance-batch! tx batch-id)
              (event! tx actor batch-id (if before "proposal-updated" "proposal-created") {:proposal-id (uuid! (:id after))} before after))
            {:batch (imports/public-batch (batches/batch-row tx batch-id)) :proposal after})
          :decision
          (let [row (row! tx batch-id locator)
                before (public-decision (repository/decision tx batch-id (:worksheet locator) (:row locator)))
                _ (when-not (= (:version input) (or (:version before) 0)) (stale!))
                _ (when (and (not= "unresolved" (:kind input)) (not= "valid" (:validation-state row)))
                    (imports/fail! 409 "invalid-row" "Popraw wiersz w pliku źródłowym przed podjęciem decyzji."))
                _ (check-target! tx batch-id input)
                _ (when (= "existing" (get-in input [:financial :disposition]))
                    (when-not (payment-matches? (repository/payment tx (uuid! (:donation-id input))) (uuid! (:donor-id input))
                                                (:payload (imports/public-row row)) (:financial input) (:donation-version input))
                      (imports/fail! 409 "duplicate-mismatch" "Wskazana wpłata nie odpowiada danym wiersza.")))
                fields (mapv first contract/decision-fields)]
            (when-not (= (select-keys before fields) (select-keys input fields))
              (let [_ (repository/save-decision! tx batch-id (:worksheet locator) (:row locator) actor
                                                 (-> input (update :donor-id #(some-> % uuid!)) (update :proposal-id #(some-> % uuid!))
                                                     (update :donation-id #(some-> % uuid!))) (contract/complete? input))
                    after (public-decision (repository/decision tx batch-id (:worksheet locator) (:row locator)))]
                (repository/advance-batch! tx batch-id)
                (event! tx actor batch-id (if (= "unresolved" (:kind input)) "decision-cleared" "decision-saved")
                        {:worksheet-index (:worksheet locator) :row-number (:row locator)} before after)
                (batches/record-event! tx (batches/batch-row tx batch-id) actor "reviewed" "review" nil)))
            (decision-response tx batch-id locator)))))))
(defn read! [ds token action id _subject locator input]
  (auth/authorize! ds token false false)
  (jdbc/with-transaction [tx ds {:isolation :repeatable-read}]
    (let [batch (review/batch! tx id) batch-id (:id batch)]
      (case action
        :decision (do (imports/validate! [:map {:closed true}] input) (decision-response tx batch-id locator))
        :proposals (do (imports/validate! import-contract/page-query input)
                       (let [limit (get input :limit 25) offset (get input :offset 0) result (repository/proposals tx batch-id limit offset)]
                         {:batch (imports/public-batch batch) :proposals (mapv public-proposal (:proposals result))
                          :total (:total result) :limit limit :offset offset}))
        :payments (do (imports/validate! contract/payments-query input)
                      (let [donor (donors/donor! tx (:donor-id input) false) limit (get input :limit 25) offset (get input :offset 0)
                            result (repository/payments tx (:id donor) limit offset)]
                        {:batch (imports/public-batch batch) :donor-id (:donor-id input) :total (:total result) :limit limit :offset offset
                         :payments (mapv (fn [payment] {:id (str (:id payment)) :version (:version payment)
                                                        :amount (.toPlainString ^java.math.BigDecimal (:amount payment)) :currency (:currency payment)
                                                        :received-on (str (:received-on payment))}) (:payments result))}))
        :events (do (imports/validate! import-contract/page-query input)
                    (let [limit (get input :limit 25) offset (get input :offset 0) result (repository/events tx batch-id limit offset)]
                      {:batch (imports/public-batch batch) :total (:total result) :limit limit :offset offset
                       :events (mapv (fn [event] (-> (select-keys event [:actor-name :event-type :worksheet-index :row-number :before-version :after-version])
                                                     (assoc :id (str (:id event)) :actor-id (str (:actor-id event)) :occurred-at (timestamp (:occurred-at event))
                                                            :proposal-id (some-> (:proposal-id event) str) :preview-id (some-> (:preview-id event) str)
                                                            :before (some-> (:before-value event) imports/decode-json) :after (imports/decode-json (:after-value event))))) (:events result))}))
        (imports/fail! 404 "not-found" "Nie znaleziono danych weryfikacji.")))))
