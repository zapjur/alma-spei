(ns alma-spei.integration.imports-test
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.imports.fingerprint :as fingerprint]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.imports.service :as service]
            [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.validation :as validation]
            [alma-spei.integration.database-test :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.io ByteArrayInputStream]
           [java.sql SQLException]
           [java.util UUID]))

(def ^:dynamic *context* nil)
(def metadata {:source-type "bank" :original-filename "synthetic.xlsx"})
(defn measured [text]
  (with-open [input (ByteArrayInputStream. (.getBytes ^String text "UTF-8"))] (fingerprint/measure input 1024)))
(defn ds [] (:runtime fixture/*database*))
(defn account! [username]
  (let [id (UUID/randomUUID)]
    (repository/query (ds) ["INSERT INTO users(id,username,display_name) VALUES (?,?,'Synthetic Import Staff')" id username])
    {:id id :token (:token (jdbc/with-transaction [tx (ds)] (auth/issue-session! tx (auth/user-row tx id) "synthetic-csrf")))}))
(defn ready [f]
  (fixture/isolated-database
   #(do (migratus/migrate (migrations/migration-config (:migrator fixture/*database*)))
        (binding [*context* {:a (account! "synthetic.import.a") :b (account! "synthetic.import.b")}]
          (f)))))
(use-fixtures :each ready)
(defn token [key] (get-in *context* [key :token]))
(defn reserve [] (:batch (service/reserve! (ds) (token :a) metadata (measured "synthetic file bytes"))))
(defn fail-batch [batch]
  (:batch (service/fail-batch! (ds) (token :a) (:id batch) {:version (:version batch) :failure-code "parse-failed"})))
(defn status [f] (try (f) 200 (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))
(defn count-of [table] (:n (repository/one (ds) [(str "SELECT count(*) AS n FROM " table)])))
(defn canonical-counts [] (mapv count-of ["donors" "donations" "donor_audit_events" "donor_audit_changes"]))
(defn snapshot []
  (mapv #(repository/query (ds) [(str "SELECT * FROM " % " ORDER BY id")]) ["import_files" "import_batches" "import_batch_events"]))
(defn sql-state [f] (try (f) nil (catch SQLException e (.getSQLState e))))

(deftest upgrade-and-test-only-rollback-preserve-existing-domain-and-auth
  (let [migrator (:migrator fixture/*database*) cfg (migrations/migration-config migrator)
        donor (UUID/randomUUID) actor (get-in *context* [:a :id])]
    (migratus/rollback cfg)
    (repository/query (ds) ["INSERT INTO donors(id,entity_type,name,created_by,updated_by) VALUES (?,'individual','Synthetic Existing',?,?)" donor actor actor])
    (migratus/migrate cfg)
    (is (= 4 (count (repository/query migrator ["SELECT * FROM schema_migrations"]))))
    (is (= "Synthetic Existing" (:name (repository/one (ds) ["SELECT name FROM donors WHERE id=?" donor]))))
    (is (:id (auth/authorize! (ds) (token :a) false false)))
    (migratus/rollback cfg)
    (is (nil? (:name (repository/one migrator ["SELECT to_regclass('public.import_files') AS name"]))))
    (is (= donor (:id (repository/one (ds) ["SELECT id FROM donors"]))))
    (migratus/migrate cfg)
    (is (= 0 (count-of "import_files")))))

(deftest renamed-and-relabelled-identical-files-are-still-duplicates
  (let [batch (reserve)]
    (is (validation/valid? contract/batch-response {:batch batch}))
    (is (= "reserved" (:state batch)))
    (is (every? nil? (map batch [:total-rows :valid-rows :invalid-rows :reviewed-rows])))
    (is (= (str (get-in *context* [:a :id])) (:created-by batch)))
    (doseq [other [(assoc metadata :original-filename "renamed.xlsx") (assoc metadata :source-type "tax")]]
      (is (= 409 (status #(service/reserve! (ds) (token :b) other (measured "synthetic file bytes"))))))
    (is (= 1 (count-of "import_files")))
    (is (= 1 (count-of "import_batches")))
    (is (= 1 (count-of "import_batch_events")))
    (is (= [0 0 0 0] (canonical-counts)))
    (is (= 400 (status #(service/reserve! (ds) (token :a) metadata (measured "")))))
    (is (= 400 (status #(service/reserve! (ds) (token :a) (assoc metadata :created-by (:id batch)) (measured "different")))))))

(deftest explicit-retry-retains-history-and-original-metadata
  (let [reserved (reserve)
        failed (fail-batch reserved)
        old-row (repository/batch-row (ds) (UUID/fromString (:id failed)))
        retry (:batch (service/retry! (ds) (token :b) (:id failed) {:version 2 :confirmed true}))]
    (is (= "failed" (:state failed)))
    (is (= 2 (:version failed)))
    (is (= 2 (:attempt-number retry)))
    (is (= (:file-id failed) (:file-id retry)))
    (is (= (:id failed) (:retry-of retry)))
    (is (= (str (get-in *context* [:b :id])) (:created-by retry)))
    (is (= old-row (repository/batch-row (ds) (UUID/fromString (:id failed)))))
    (is (= 4 (count-of "import_batch_events")))
    (is (= 409 (status #(service/retry! (ds) (token :a) (:id failed) {:version 2 :confirmed true}))))
    (is (= 409 (status #(service/retry! (ds) (token :a) (:id retry) {:version 1 :confirmed true}))))
    (is (= 409 (status #(service/fail-batch! (ds) (token :a) (:id retry) {:version 99 :failure-code "parse-failed"}))))
    (is (= 400 (status #(service/retry! (ds) (token :a) (:id retry) {:version 1 :confirmed false}))))
    (is (= [0 0 0 0] (canonical-counts)))))

(deftest concurrent-reservations-and-retries-have-one-winner
  (let [runtime (ds) a (token :a) b (token :b)
        gate (promise)
        start (fn [token] (future @gate (status #(service/reserve! runtime token metadata (measured "race")))))
        first (start a) second (start b)]
    (deliver gate true)
    (is (= #{200 409} #{@first @second}))
    (is (= 1 (count-of "import_files")))
    (let [id (str (:id (repository/one runtime ["SELECT id FROM import_batches"])))
          failed (:batch (service/fail-batch! runtime a id {:version 1 :failure-code "processing-interrupted"}))
          gate (promise)
          start (fn [token] (future @gate (status #(service/retry! runtime token (:id failed) {:version 2 :confirmed true}))))
          first (start a) second (start b)]
      (deliver gate true)
      (is (= #{200 409} #{@first @second}))
      (is (= 2 (count-of "import_batches")))
      (is (= 4 (count-of "import_batch_events")))
      (is (= 1 (:n (repository/one runtime ["SELECT count(*) AS n FROM import_batches WHERE state='reserved'"])))))))

(deftest event-failures-roll-back-file-attempt-and-lifecycle-writes
  (with-redefs [repository/record-event! (fn [& _] (throw (ex-info "Synthetic audit failure" {})))]
    (is (thrown? clojure.lang.ExceptionInfo (reserve))))
  (is (= 0 (count-of "import_files")))
  (is (= 0 (count-of "import_batches")))
  (let [batch (reserve) before (snapshot)]
    (with-redefs [repository/record-event! (fn [& _] (throw (ex-info "Synthetic audit failure" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (fail-batch batch))))
    (is (= before (snapshot)))
    (let [failed (fail-batch batch) before (snapshot) original repository/record-event!]
      (with-redefs [repository/record-event! (fn [tx batch actor type previous reason]
                                               (if (= type "reserved") (throw (ex-info "Synthetic new-attempt audit failure" {}))
                                                   (original tx batch actor type previous reason)))]
        (is (thrown? clojure.lang.ExceptionInfo (service/retry! (ds) (token :b) (:id failed) {:version 2 :confirmed true}))))
      (is (= before (snapshot)))))
  (is (= [0 0 0 0] (canonical-counts))))

(deftest authorization-missing-resources-and-future-lifecycles-are-gated
  (is (= 401 (status #(service/reserve! (ds) nil metadata (measured "synthetic")))))
  (is (= 404 (status #(service/retry! (ds) (token :a) "bad-id" {:version 1 :confirmed true}))))
  (is (= 404 (status #(service/retry! (ds) (token :a) (str (UUID/randomUUID)) {:version 1 :confirmed true}))))
  (let [batch (reserve) actor (get-in *context* [:a :id])]
    (repository/query (ds) ["INSERT INTO local_credentials(user_id,password_hash,password_change_required) VALUES (?,'synthetic-unused-hash',true)" actor])
    (is (= 403 (status #(service/reserve! (ds) (token :a) metadata (measured "another")))))
    (repository/query (ds) ["UPDATE local_credentials SET password_change_required=false WHERE user_id=?" actor])
    (doseq [state ["review" "approved" "committed" "cancelled" "undone"]]
      (repository/query (ds) ["UPDATE import_batches SET state=?,version=2,total_rows=0,valid_rows=0,invalid_rows=0,reviewed_rows=0 WHERE id=?" state (UUID/fromString (:id batch))])
      (is (= 409 (status #(service/retry! (ds) (token :a) (:id batch) {:version 2 :confirmed true})))))
    (repository/query (ds) ["UPDATE users SET active=false WHERE id=?" actor])
    (is (= 401 (status #(service/reserve! (ds) (token :a) metadata (measured "another")))))
    (repository/query (ds) ["UPDATE users SET active=true WHERE id=?" actor])
    (repository/query (ds) ["UPDATE sessions SET expires_at=now()-interval '1 second'"])
    (is (= 401 (status #(service/reserve! (ds) (token :a) metadata (measured "another")))))))

(deftest constraints-and-runtime-privileges-protect-identity-events-and-staging
  (let [batch (reserve) id (UUID/fromString (:id batch))]
    (doseq [statement ["UPDATE import_files SET file_hash_sha256=repeat('a',64)" "UPDATE import_files SET byte_count=1"
                       "UPDATE import_batches SET original_filename='changed.xlsx'" "UPDATE import_batches SET source_type='tax'"
                       "UPDATE import_batch_events SET event_type='failed'" "DELETE FROM import_batch_events"
                       "DELETE FROM import_batches" "DELETE FROM import_files" "DELETE FROM staged_rows"
                       "UPDATE staged_rows SET rule_version='changed'" "TRUNCATE import_batch_events"]]
      (is (= "42501" (sql-state #(repository/query (ds) [statement])))))
    (is (= "23514" (sql-state #(repository/query (ds) ["UPDATE import_batches SET total_rows=0 WHERE id=?" id]))))
    (is (= "23514" (sql-state #(repository/query (ds) ["UPDATE import_batches SET total_rows=2,valid_rows=1,invalid_rows=0,reviewed_rows=0 WHERE id=?" id]))))
    (is (= "23514" (sql-state #(repository/query (ds) ["UPDATE import_batches SET state='approved',total_rows=2,valid_rows=1,invalid_rows=1,reviewed_rows=1 WHERE id=?" id]))))
    (repository/query (ds) ["INSERT INTO staged_rows(batch_id,worksheet_index,row_number,normalized_payload,validation_state,rule_version) VALUES (?,1,2,'{\"synthetic\":true}','valid','synthetic-v1')" id])
    (is (= 1 (count-of "staged_rows")))
    (doseq [[payload state codes row] [["[]" "valid" "[]" 3] ["{}" "invalid" "[]" 3]
                                       ["{}" "invalid" "[123]" 3] ["{}" "invalid" "[\"Synthetic private value\"]" 3]
                                       ["{}" "valid" "[]" 0]]]
      (is (= "23514" (sql-state #(repository/query (ds) ["INSERT INTO staged_rows(batch_id,worksheet_index,row_number,normalized_payload,validation_state,validation_codes,rule_version) VALUES (?,1,?,?::jsonb,?,?::jsonb,'synthetic-v1')"
                                                         id row payload state codes])))))
    (repository/query (ds) ["INSERT INTO staged_rows(batch_id,worksheet_index,row_number,normalized_payload,validation_state,validation_codes,rule_version) VALUES (?,1,3,'{}','invalid','[\"synthetic-error\"]','synthetic-v1')" id])
    (is (= 2 (count-of "staged_rows")))
    (is (= [0 0 0 0] (canonical-counts)))))

(deftest database-indexes-and-lineage-guard-against-competing-internal-writers
  (let [first-batch (reserve)
        file-id (UUID/fromString (:file-id first-batch))
        batch-id (UUID/fromString (:id first-batch))
        actor (get-in *context* [:a :id])
        statement "INSERT INTO import_batches(id,file_id,created_by,source_type,original_filename,attempt_number,retry_of) VALUES (?,?,?,'bank','synthetic.xlsx',2,?)"]
    (is (= "23505" (sql-state #(repository/query (ds) [statement (UUID/randomUUID) file-id actor batch-id]))))
    (fail-batch first-batch)
    (let [other (:batch (service/reserve! (ds) (token :a) metadata (measured "different synthetic file")))]
      (fail-batch other)
      (is (= "23503" (sql-state #(repository/query (ds) [statement (UUID/randomUUID) (UUID/fromString (:file-id other)) actor batch-id])))))
    (is (= 2 (count-of "import_batches")))
    (is (= [0 0 0 0] (canonical-counts)))))
