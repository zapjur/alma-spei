(ns alma-spei.api.system
  (:require [alma-spei.api.db.core :as db]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.api.http.server :as server]
            [integrant.core :as ig]))

(defmethod ig/init-key ::database [_ settings] (db/datasource settings))
(defmethod ig/halt-key! ::database [_ _ds] nil)
(defmethod ig/init-key ::handler [_ {:keys [database timeout]}] (routes/handler database timeout))
(defmethod ig/init-key ::server [_ {:keys [handler settings]}] (server/start! handler settings))
(defmethod ig/halt-key! ::server [_ server] (server/stop! server))

(defn definition [config]
  {::database (:database config)
   ::handler {:database (ig/ref ::database) :timeout (get-in config [:database :query-timeout])}
   ::server {:handler (ig/ref ::handler) :settings (:http config)}})

(defn start! [config]
  (let [definition (definition config)]
    (try (ig/init definition)
         (catch clojure.lang.ExceptionInfo error
           (when-let [partial (:system (ex-data error))] (ig/halt! partial))
           (throw (ex-info "Application startup failed" {}))))))

(defn stop! [system] (ig/halt! system))
