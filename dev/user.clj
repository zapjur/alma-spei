(ns user)

(defonce running (atom nil))
(defn stop []
  (when @running
    ((requiring-resolve 'alma-spei.api.system/stop!) @running)
    (reset! running nil)))
(defn start []
  (when-not @running
    (let [env (System/getenv)
          env (if (or (get env "ALMA_DB_PASSWORD") (get env "ALMA_DB_PASSWORD_FILE"))
                env (assoc (into {} env) "ALMA_DB_PASSWORD_FILE" ".secrets/app-password"))]
      (reset! running
              ((requiring-resolve 'alma-spei.api.system/start!)
               ((requiring-resolve 'alma-spei.api.config/load-config) env)))))
  :started)
(defn restart [] (stop) (start))
(defn reload [] ((requiring-resolve 'alma-spei.dev.reload/reload!)))
