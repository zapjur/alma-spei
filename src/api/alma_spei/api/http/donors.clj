(ns alma-spei.api.http.donors
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.http.auth :as auth-http]
            [alma-spei.api.donors.service :as service]
            [alma-spei.common.contracts.donors :as contract]
            [ring.middleware.params :refer [wrap-params]]))

(def query-fields
  {"search" :search "entity-type" :entity-type "status" :status
   "sort-by" :sort-by "sort-direction" :sort-direction "limit" :limit "offset" :offset})

(defn query-input [params]
  (into {} (map (fn [[field value]]
                  (let [key (get query-fields field :unknown)]
                    [key (if (and (#{:limit :offset} key) (string? value)
                                  (re-matches #"[0-9]{1,16}" value))
                           (Long/parseLong value) value)])) params)))

(defn endpoint [ds settings action]
  (fn [request]
    (try
      (let [mutation? (#{:create :update} action)
            csrf (get-in request [:cookies "alma-csrf" :value])]
        (when mutation?
          (when-not (and (= (:origin settings) (get-in request [:headers "origin"]))
                         (auth-http/valid-csrf? (:csrf-secret settings) csrf)
                         (crypto/equal? csrf (get-in request [:headers "x-csrf-token"])))
            (service/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę.")))
        (let [result (service/execute! ds (get-in request [:cookies "alma-session" :value])
                                       action (get-in request [:path-params :id])
                                       (if mutation? (:body-params request) (query-input (:query-params request)))
                                       (when mutation? csrf))]
          {:status (if (= action :create) 201 200) :body result}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [status code public-message errors]} (ex-data e)]
          (if status
            {:status status :body (cond-> {:code code :message public-message} errors (assoc :errors errors))}
            (throw e)))))))

(defn routes [ds settings]
  (let [route (fn [action schema status]
                {:handler (wrap-params (endpoint ds settings action)) :responses {status {:body schema}}})]
    [["/api/donors" {:get (route :list contract/donors-response 200)
                     :post (route :create contract/donor-response 201)}]
     ["/api/donors/:id" {:get (route :detail contract/detail-response 200)
                         :put (route :update contract/donor-response 200)}]
     ["/api/donors/:id/history" {:get (route :history contract/history-response 200)}]]))
