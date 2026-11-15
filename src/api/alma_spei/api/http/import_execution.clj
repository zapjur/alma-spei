(ns alma-spei.api.http.import-execution
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as auth-service]
            [alma-spei.api.http.auth :as auth]
            [alma-spei.api.http.imports :as import-http]
            [alma-spei.api.imports.backup :as backup]
            [alma-spei.api.imports.commit :as commit]
            [alma-spei.api.imports.execution-reads :as reads]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.api.imports.undo :as undo]
            [alma-spei.common.contracts.execution :as contract]
            [ring.middleware.params :refer [wrap-params]]))

(defn endpoint [ds settings action]
  (wrap-params
   (fn [request]
     (try
       (let [mutation? (#{:commit :undo :backup} action)
             csrf (get-in request [:cookies "alma-csrf" :value]) token (get-in request [:cookies "alma-session" :value])
             id (get-in request [:path-params :id]) input (if mutation? (:body-params request) (import-http/page-input (:query-params request)))]
         (when mutation?
           (when-not (and (= (:origin settings) (get-in request [:headers "origin"]))
                          (auth/valid-csrf? (:csrf-secret settings) csrf) (crypto/equal? csrf (get-in request [:headers "x-csrf-token"])))
             (imports/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
           (when (seq (:query-params request)) (imports/fail! 400 "validation" "Nieprawidłowe parametry importu.")))
         {:status 200
          :body (case action
                  :capabilities (do (auth-service/authorize! ds token false false)
                                    (imports/validate! [:map {:closed true}] input)
                                    {:commit-enabled (backup/enabled? settings) :mode (if (backup/enabled? settings) "synthetic-test-v1" "disabled")})
                  :commit (commit/execute! ds settings token csrf input)
                  :undo (undo/execute! ds token csrf input)
                  :backup (backup/create! ds settings token csrf id (get-in request [:path-params :preview-id]) input)
                  (reads/read! ds token action id input))})
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [status code public-message errors blockers total]} (ex-data e)]
           (if status {:status status :body (cond-> {:code code :message public-message} errors (assoc :errors errors) blockers (assoc :blockers blockers :total total))}
               (throw e))))))))
(defn routes [ds settings]
  (let [route (fn [action schema] {:handler (endpoint ds settings action) :responses {200 {:body schema}}})]
    [["/api/imports/capabilities" {:conflicting true :get (route :capabilities contract/capabilities)}]
     ["/api/imports" {:get (route :history contract/history)}]
     ["/api/imports/commit" {:conflicting true :post (route :commit contract/outcome)}]
     ["/api/imports/undo-last" {:conflicting true :post (route :undo contract/outcome)}]
     ["/api/imports/:id/previews/:preview-id/backup" {:post (route :backup contract/backup-response)}]
     ["/api/imports/:id/outcome" {:get (route :outcome contract/outcome)}]
     ["/api/imports/:id/commit-items" {:get (route :items contract/items-response)}]
     ["/api/imports/:id/undo-eligibility" {:get (route :eligibility contract/eligibility)}]]))
