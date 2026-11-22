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

(defn rollback-latest! [cfg]
  ;; Migratus orders rollback by millisecond applied timestamps without an ID tie-break.
  ;; These disposable tests need deterministic reverse migration order.
  (when-let [id (:id (first (jdbc/execute! (get-in cfg [:db :datasource])
                                           ["SELECT max(id) AS id FROM schema_migrations WHERE id > 0"])))]
    (migratus/down cfg id)))

(deftest migrations-and-privileges
  (let [{:keys [migrator runtime]} *database*
        cfg (migrations/migration-config migrator)]
    (is (false? (db/ready? runtime 1)))
    (migratus/migrate cfg)
    (is (db/ready? runtime 1))
    (let [history (jdbc/execute! migrator ["SELECT * FROM schema_migrations"])]
      (is (= 7 (count history)))
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
    (dotimes [_ 7] (rollback-latest! cfg))
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
          (dotimes [_ 7] (rollback-latest! (migrations/migration-config (:migrator *database*))))
          (finally (system/stop! running)))))))

(defn staged-source! [runtime actor source hash-digit]
  (let [file (UUID/randomUUID)
        batch (UUID/randomUUID)]
    (jdbc/execute! runtime ["INSERT INTO import_files (id,file_hash_sha256,byte_count) VALUES (?,?,1)"
                            file (apply str (repeat 64 hash-digit))])
    (jdbc/execute! runtime ["INSERT INTO import_batches (id,file_id,attempt_number,source_type,original_filename,created_by) VALUES (?,?,1,?,'synthetic.xlsx',?)"
                            batch file source actor])
    (jdbc/execute! runtime ["INSERT INTO staged_rows (batch_id,worksheet_index,row_number,normalized_payload,validation_state,rule_version) VALUES (?,1,2,'{}','valid','synthetic-v1')" batch])
    batch))

(deftest source-evidence-upgrade-preserves-data-and-enforces-provenance
  (let [{:keys [runtime migrator]} *database*
        cfg (migrations/migration-config migrator)
        migration 202611220001
        actor (UUID/randomUUID)
        donor (UUID/randomUUID)
        observation "INSERT INTO source_observations (batch_id,worksheet_index,row_number,column_index,observed_value,source_value_type) VALUES (?,1,2,7,'synthetic marker','text')"
        tax "INSERT INTO tax_identification_evidence (batch_id,worksheet_index,row_number,subject_role,donor_id) VALUES (?,1,2,?,?)"]
    (migratus/migrate cfg)
    (migratus/down cfg migration)
    (jdbc/execute! runtime ["INSERT INTO users (id,username,display_name) VALUES (?,'synthetic.evidence','Synthetic Evidence Staff')" actor])
    (jdbc/execute! runtime ["INSERT INTO donors (id,entity_type,name,created_by,updated_by) VALUES (?,'individual','Synthetic Evidence Donor',?,?)"
                            donor actor actor])
    (let [master (staged-source! runtime actor "master" \a)
          tax-batch (staged-source! runtime actor "tax" \b)
          verify-data #(mapv (fn [query] (jdbc/execute! runtime [query]))
                             ["SELECT * FROM users" "SELECT * FROM donors" "SELECT * FROM import_batches ORDER BY id"
                              "SELECT * FROM staged_rows ORDER BY batch_id" "SELECT * FROM donations"])
          snapshots (verify-data)]
      (migratus/migrate cfg)
      (is (= snapshots (verify-data)))
      (jdbc/execute! runtime [observation master])
      (jdbc/execute! runtime [tax tax-batch "taxpayer" donor])
      (jdbc/execute! runtime [tax tax-batch "spouse" nil])
      (is (= ["synthetic marker"]
             (map :source_observations/observed_value (jdbc/execute! runtime ["SELECT * FROM source_observations"]))))
      (is (= [["spouse" nil nil] ["taxpayer" donor nil]]
             (mapv (juxt :tax_identification_evidence/subject_role :tax_identification_evidence/donor_id :tax_identification_evidence/tax_year)
                   (jdbc/execute! runtime ["SELECT * FROM tax_identification_evidence ORDER BY subject_role"]))))
      (is (= snapshots (verify-data)))
      (doseq [statement [[observation master] [observation tax-batch] [tax master "taxpayer" donor]
                         [tax tax-batch "spouse" donor] [tax tax-batch "unknown" nil]
                         ["INSERT INTO source_observations (batch_id,worksheet_index,row_number,column_index,observed_value,source_value_type) VALUES (?,1,3,7,'synthetic marker','text')" master]
                         ["UPDATE source_observations SET observed_value='changed'"]
                         ["DELETE FROM source_observations"]
                         ["UPDATE tax_identification_evidence SET donor_id=NULL"]
                         ["DELETE FROM tax_identification_evidence"]]]
        (is (thrown? java.sql.SQLException (jdbc/execute! runtime statement))))
      (is (thrown? Exception (migratus/down cfg migration)))
      (is (= 1 (count (jdbc/execute! runtime ["SELECT * FROM source_observations"]))))
      (is (= 2 (count (jdbc/execute! runtime ["SELECT * FROM tax_identification_evidence"])))))))
