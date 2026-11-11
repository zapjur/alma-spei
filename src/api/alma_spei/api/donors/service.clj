(ns alma-spei.api.donors.service
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as auth]
            [alma-spei.api.donors.repository :as repository]
            [alma-spei.common.contracts.donors :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(defn fail! [status code message]
  (throw (ex-info message {:status status :code code :public-message message})))

(defn validate! [schema input]
  (let [errors (contract/errors schema input)]
    (when (seq errors)
      (throw (ex-info "Donor validation failed"
                      {:status 400 :code "validation" :public-message "Sprawdź poprawność danych formularza."
                       :errors errors})))))

(defn public-donor [row]
  (assoc (select-keys row (concat repository/profile-fields [:is-active :version])) :id (str (:id row))))

(defn donor! [tx value lock?]
  (when-not (validation/valid? contract/identifier value)
    (fail! 404 "not-found" "Nie znaleziono darczyńcy."))
  (or (repository/find-donor tx (UUID/fromString value) lock?)
      (fail! 404 "not-found" "Nie znaleziono darczyńcy.")))

(def list-defaults {:status "active" :sort-by "name" :sort-direction "asc" :limit 25 :offset 0})
(def page-defaults {:limit 25 :offset 0})

(defn json-value [value]
  (muuntaja/decode "application/json" (.getBytes (str value) "UTF-8")))

(defn public-event [event]
  {:id (str (:id event)) :actor-id (str (:actor-id event)) :actor-name (:actor-name event)
   :occurred-at (str (.toInstant ^java.sql.Timestamp (:occurred-at event)))
   :action (:action event)
   :changes (mapv (fn [change]
                    {:field (str/replace (:field-name change) "_" "-")
                     :before (json-value (:before-value change))
                     :after (json-value (:after-value change))}) (:changes event))})

(defn execute! [ds token action id input csrf]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (when (#{:create :update} action)
        (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf)))
          (fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę.")))
      (case action
        :list
        (do (validate! contract/list-query input)
            (let [result (repository/list-donors tx (merge list-defaults input))]
              (update result :donors #(mapv public-donor %))))
        :create
        (do (validate! contract/create-donor input)
            (let [row (repository/create! tx (UUID/randomUUID) (:id actor) input)]
              (audit/record! tx (:id actor) (:id row) "create" (audit/changes nil row))
              {:donor (public-donor row)}))
        :update
        (do (validate! contract/update-donor input)
            (let [before (donor! tx id true)
                  replacement (merge (zipmap repository/profile-fields (repeat nil))
                                     (select-keys input repository/profile-fields))]
              (when-not (= (:version before) (:version input))
                (fail! 409 "stale-donor" "Dane darczyńcy zostały zmienione. Odśwież formularz i sprawdź zmiany."))
              (let [changes (audit/changes before (merge before replacement))]
                (if (empty? changes)
                  {:donor (public-donor before)}
                  (let [after (repository/update! tx (:id before) (:id actor) replacement)]
                    (audit/record! tx (:id actor) (:id before) "update" changes)
                    {:donor (public-donor after)})))))
        (:detail :history)
        (do (validate! contract/page-query input)
            (let [row (donor! tx id false)
                  {:keys [limit offset]} (merge page-defaults input)]
              (if (= action :history)
                (update (repository/history tx (:id row) limit offset) :events #(mapv public-event %))
                (let [payments (repository/payments tx (:id row) limit offset)]
                  {:donor (public-donor row)
                   :donations (mapv (fn [payment]
                                      {:id (str (:id payment)) :amount (.toPlainString ^java.math.BigDecimal (:amount payment))
                                       :currency (:currency payment) :received-on (str (:received-on payment))}) (:donations payments))
                   :donations-total (:total payments)}))))))))
