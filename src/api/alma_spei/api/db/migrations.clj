(ns alma-spei.api.db.migrations
  (:require [clojure.string :as str]
            [alma-spei.api.config :as config]
            [alma-spei.api.db.core :as db]
            [migratus.core :as migratus]))

(defn migration-config [ds]
  {:store :database :migration-dir "migrations" :db {:datasource ds}})

(defn -main [& [action]]
  (try
    (let [env (System/getenv)
          user (get env "ALMA_MIGRATION_USER")
          secret-file (get env "ALMA_MIGRATION_PASSWORD_FILE")]
      (when-not (and user secret-file)
        (throw (ex-info "Migration credentials are required" {})))
      (let [base (config/load-config)
            settings (assoc (:database base) :user user :password (str/trim-newline (slurp secret-file)))
            cfg (migration-config (db/datasource settings))]
        (case action
          "up" (migratus/migrate cfg)
          "down" (migratus/rollback cfg)
          (throw (ex-info "Expected migration action: up or down" {})))))
    (catch Exception _
      (binding [*out* *err*] (println "Migration failed; check configuration and database state."))
      (System/exit 1))))
