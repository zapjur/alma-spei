(ns alma-spei.integration.database-test
  (:require [clojure.string :as str]
            [alma-spei.api.config :as config]
            [alma-spei.api.db.core :as db]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.system :as system]
            [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.util UUID]))

(def ^:dynamic *database* nil)

(defn isolated-database [run-tests]
  (when-not (= "test" (System/getenv "ALMA_ENV"))
    (throw (ex-info "Integration tests require ALMA_ENV=test" {})))
  (let [settings (:database (config/load-config))
        admin-file (System/getenv "ALMA_TEST_ADMIN_PASSWORD_FILE")
        migration-file (System/getenv "ALMA_MIGRATION_PASSWORD_FILE")]
    (when-not (and admin-file migration-file)
      (throw (ex-info "Explicit integration administrator and migration secret files required" {})))
    (let [admin (db/datasource (assoc settings :name "postgres" :user "alma_spei_admin"
                                      :password (str/trim-newline (slurp admin-file))))
          database-name (str "alma_spei_test_" (.replace (str (UUID/randomUUID)) "-" ""))
          migration-settings (assoc settings :name database-name :user "alma_spei_migrator"
                                    :password (str/trim-newline (slurp migration-file)))
          migrator (db/datasource migration-settings)
          runtime (db/datasource (assoc settings :name database-name))]
      ;; Generated identifier contains only a fixed prefix and UUID hex digits.
      (jdbc/execute! admin [(str "CREATE DATABASE " database-name)])
      (try
        (let [owner (db/datasource (assoc settings :name database-name :user "alma_spei_admin"
                                          :password (str/trim-newline (slurp admin-file))))]
          (jdbc/execute! owner ["REVOKE CREATE ON SCHEMA public FROM PUBLIC"])
          (jdbc/execute! owner ["GRANT USAGE, CREATE ON SCHEMA public TO alma_spei_migrator"])
          (jdbc/execute! owner ["GRANT USAGE ON SCHEMA public TO alma_spei_app"]))
        (binding [*database* {:admin admin :migrator migrator :runtime runtime
                              :settings (assoc settings :name database-name)}]
          (run-tests))
        (finally (jdbc/execute! admin [(str "DROP DATABASE " database-name " WITH (FORCE)")]))))))

(use-fixtures :each isolated-database)

(deftest migrations-and-privileges
  (let [{:keys [migrator runtime]} *database*
        cfg (migrations/migration-config migrator)]
    (is (false? (db/ready? runtime 1)))
    (migratus/migrate cfg)
    (is (db/ready? runtime 1))
    (let [history (jdbc/execute! migrator ["SELECT * FROM schema_migrations"])]
      (is (= 1 (count history)))
      (migratus/migrate cfg)
      (is (= history (jdbc/execute! migrator ["SELECT * FROM schema_migrations"]))))
    (is (= 1 (:application_metadata/foundation_version
              (first (jdbc/execute! runtime (sql/format {:select [:foundation_version]
                                                         :from [:application_metadata]
                                                         :where [:= :singleton true]}))))))
    (is (thrown? java.sql.SQLException (jdbc/execute! runtime ["CREATE TABLE forbidden (id integer)"])))
    (is (thrown? java.sql.SQLException (jdbc/execute! runtime ["UPDATE application_metadata SET foundation_version = 2"])))
    (jdbc/execute! migrator ["CREATE TABLE transaction_probe (id integer PRIMARY KEY)"])
    (jdbc/with-transaction [tx migrator]
      (jdbc/execute! tx ["INSERT INTO transaction_probe VALUES (?)" 1]))
    (is (thrown? Exception
                 (jdbc/with-transaction [tx migrator]
                   (jdbc/execute! tx ["INSERT INTO transaction_probe VALUES (?)" 2])
                   (throw (ex-info "Synthetic failure" {})))))
    (is (= [1] (map :transaction_probe/id (jdbc/execute! migrator ["SELECT id FROM transaction_probe"]))))
    (migratus/rollback cfg)
    (is (false? (db/ready? runtime 1)))
    (migratus/migrate cfg)
    (is (db/ready? runtime 1))))

(deftest failed-migration-is-atomic
  (let [ds (:migrator *database*)
        cfg (assoc (migrations/migration-config ds) :migration-dir "failed-migrations")]
    (is (thrown? Exception (migratus/migrate cfg)))
    (is (nil? (:probe (first (jdbc/execute! ds ["SELECT to_regclass('public.failed_probe') AS probe"])))))))

(defn free-port [] (with-open [socket (ServerSocket. 0)] (.getLocalPort socket)))
(defn status [port path]
  (.statusCode (.send (HttpClient/newHttpClient)
                      (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path))) .GET .build)
                      (HttpResponse$BodyHandlers/ofString))))

(deftest lifecycle-and-real-http
  (let [port (free-port)
        settings {:environment :test :database (:settings *database*)
                  :http {:host "127.0.0.1" :port port}}]
    (dotimes [_ 2]
      (let [running (system/start! settings)]
        (try
          (is (= 200 (status port "/api/health/live")))
          (is (= 503 (status port "/api/health/ready")))
          (migratus/migrate (migrations/migration-config (:migrator *database*)))
          (is (= 200 (status port "/api/health/ready")))
          (migratus/rollback (migrations/migration-config (:migrator *database*)))
          (finally (system/stop! running)))))))
