(ns alma-spei.dev.repl
  (:require [alma-spei.dev.reload :as reload]
            [cider.nrepl :as cider]
            [clojure.java.io :as io]
            [nrepl.server :as nrepl]
            [reply.main :as reply]
            [user]))

(defonce server (atom nil))

(defn stop! []
  (reload/stop!)
  (when-let [running @server]
    (nrepl/stop-server running)
    (reset! server nil)
    (io/delete-file ".nrepl-port" true)))

(defn start! []
  (when @server (throw (ex-info "Development REPL is already running" {})))
  (try
    (let [running (nrepl/start-server :bind "127.0.0.1" :port 0
                                      :handler (apply nrepl/default-handler
                                                      (map requiring-resolve cider/cider-middleware)))]
      (reset! server running)
      (spit ".nrepl-port" (str (:port running)))
      (reload/start! {:dirs ["src/api" "src/common"] :stop user/stop :start user/start})
      (println (str "Development nREPL listening on 127.0.0.1:" (:port running)))
      (println "Backend files reload on save. Connect your editor using .nrepl-port.")
      running)
    (catch Exception error (stop!) (throw error))))

(defn -main [& _args]
  (let [hook (Thread. ^Runnable stop!)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [running (start!)]
        (reply/launch-nrepl {:attach (str "127.0.0.1:" (:port running))}))
      (finally
        (stop!)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _ nil))
        (shutdown-agents)))))
