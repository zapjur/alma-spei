(ns alma-spei.api.http.server
  (:require [ring.adapter.jetty :as jetty]))

(defn start! [handler {:keys [host port]}]
  (jetty/run-jetty handler {:host host :port port :join? false}))

(defn stop! [server]
  (.stop ^org.eclipse.jetty.server.Server server))
