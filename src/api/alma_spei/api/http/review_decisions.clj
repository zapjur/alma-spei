(ns alma-spei.api.http.review-decisions
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.http.auth :as auth]
            [alma-spei.api.imports.decisions :as service]
            [alma-spei.api.imports.preview :as preview]
            [alma-spei.api.imports.reconciliation :as reconciliation]
            [alma-spei.common.contracts.reconciliation :as reconciliation-contract]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.common.contracts.decisions :as contract]
            [ring.middleware.params :refer [wrap-params]]))

(defn number-value [value]
  (if (and (string? value) (re-matches #"[0-9]{1,15}" value)) (Long/parseLong value) value))
(defn query-input [params payments?]
  (into {} (map (fn [[key value]]
                  [(get (if payments? {"limit" :limit "offset" :offset "donor-id" :donor-id} {"limit" :limit "offset" :offset}) key :unknown)
                   (if (contains? #{"limit" "offset"} key) (number-value value) value)]) params)))
(def mutations #{:proposal-create :proposal-update :decision-save :preview-create :reconcile :resolve-identity})
(defn endpoint [ds settings action]
  (wrap-params
   (fn [request]
     (try
       (let [mutation? (contains? mutations action)
             csrf (get-in request [:cookies "alma-csrf" :value])
             token (get-in request [:cookies "alma-session" :value])
             path (:path-params request)
             locator {:worksheet (number-value (:worksheet path)) :row (number-value (:row path))}
             input (if mutation? (:body-params request) (query-input (:query-params request) (= action :payments)))]
         (when mutation?
           (when-not (and (= (:origin settings) (get-in request [:headers "origin"]))
                          (auth/valid-csrf? (:csrf-secret settings) csrf) (crypto/equal? csrf (get-in request [:headers "x-csrf-token"])))
             (imports/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
           (when (seq (:query-params request)) (imports/fail! 400 "validation" "Nieprawidłowe parametry weryfikacji.")))
         {:status (if (contains? #{:proposal-create :preview-create} action) 201 200)
          :body (case action
                  :resolve-identity (reconciliation/resolve-identity! ds token csrf (:id path) input)
                  :reconcile (reconciliation/reconcile! ds token csrf (:id path) input)
                  :reconciliation (reconciliation/read! ds token (:id path) input)
                  :preview-create (preview/create! ds token csrf (:id path) input)
                  (:preview :items :blockers) (preview/read! ds token action (:id path) (:preview-id path) input)
                  (:proposal-create :proposal-update :decision-save)
                  (service/write! ds token csrf (if (= action :decision-save) :decision action) (:id path) (:proposal-id path) locator input)
                  (service/read! ds token action (:id path) (:proposal-id path) locator input))})
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [status code public-message errors blockers total]} (ex-data e)]
           (if status {:status status :body (cond-> {:code code :message public-message}
                                              errors (assoc :errors errors) blockers (assoc :blockers blockers :total total))}
               (throw e))))))))
(defn routes [ds settings]
  (let [route (fn [action schema status] {:handler (endpoint ds settings action) :responses {status {:body schema}}})]
    [["/api/imports/:id/reconciliation/identity" {:post (route :resolve-identity contract/decision-response 200)}]
     ["/api/imports/:id/reconciliation" {:get (route :reconciliation reconciliation-contract/response 200) :post (route :reconcile reconciliation-contract/response 200)}]
     ["/api/imports/:id/donor-proposals" {:get (route :proposals contract/proposals-response 200) :post (route :proposal-create contract/proposal-response 201)}]
     ["/api/imports/:id/donor-proposals/:proposal-id" {:put (route :proposal-update contract/proposal-response 200)}]
     ["/api/imports/:id/rows/:worksheet/:row/decision" {:get (route :decision contract/decision-response 200) :put (route :decision-save contract/decision-response 200)}]
     ["/api/imports/:id/payments" {:get (route :payments contract/payments-response 200)}]
     ["/api/imports/:id/review-events" {:get (route :events contract/events-response 200)}]
     ["/api/imports/:id/review-blockers" {:get (route :blockers contract/blockers-response 200)}]
     ["/api/imports/:id/preview" {:post (route :preview-create contract/preview-response 201)}]
     ["/api/imports/:id/previews/:preview-id" {:get (route :preview contract/preview-response 200)}]
     ["/api/imports/:id/previews/:preview-id/items" {:get (route :items contract/items-response 200)}]]))
