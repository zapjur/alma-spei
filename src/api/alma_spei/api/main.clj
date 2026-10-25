(ns alma-spei.api.main
  (:require [alma-spei.api.config :as config]
            [alma-spei.api.system :as system]))

(defn -main [& _args]
  (try
    (let [running (system/start! (config/load-config))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(system/stop! running)))
      (println "Alma Spei listening on configured loopback address."))
    (catch Exception _
      (binding [*out* *err*] (println "Application startup failed; check configuration."))
      (System/exit 1))))
