(ns alma-spei.integration.donors-test
  (:require [alma-spei.api.db.migrations :as migrations]
            [alma-spei.integration.database-test :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]
           [java.time LocalDate]
           [java.util UUID]))

(use-fixtures :each fixture/isolated-database)

(defn actor! [ds]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO users(id, username, display_name) VALUES (?, 'synthetic.schema', 'Synthetic schema actor')" id])
    id))

(defn donor! [ds actor entity-type name]
  (let [id (UUID/randomUUID)]
    (jdbc/execute! ds ["INSERT INTO donors(id, entity_type, name, created_by, updated_by) VALUES (?, ?, ?, ?, ?)"
                       id entity-type name actor actor])
    id))

(defn payment! [ds actor donor amount currency date]
  (jdbc/execute! ds ["INSERT INTO donations(id, donor_id, amount, currency, received_on, created_by) VALUES (?, ?, ?, ?, ?, ?)"
                     (UUID/randomUUID) donor amount currency date actor]))

(defn migrated! []
  (migratus/migrate (migrations/migration-config (:migrator fixture/*database*))))

(deftest upgrade-preserves-auth-and-supports-test-only-reversal
  (let [{:keys [migrator runtime]} fixture/*database*
        cfg (migrations/migration-config migrator)]
    (migratus/up cfg 202610250001)
    (migratus/up cfg 202610250002)
    (let [actor (actor! runtime)]
      (jdbc/execute! runtime ["INSERT INTO external_identities(provider_id, subject, user_id) VALUES ('synthetic', 'schema-test', ?)" actor])
      (migratus/migrate cfg)
      (is (= actor (:users/id (first (jdbc/execute! runtime ["SELECT id FROM users"])))))
      (is (= actor (:external_identities/user_id (first (jdbc/execute! runtime ["SELECT user_id FROM external_identities"])))))
      (let [donor (donor! runtime actor "individual" "Synthetic Żółć")]
        (is (= donor (:donors/id (first (jdbc/execute! runtime ["SELECT id FROM donors"])))))
        (migratus/migrate cfg)
        (is (= 1 (:n (first (jdbc/execute! runtime ["SELECT count(*) AS n FROM donors"]))))))
      (fixture/rollback-latest! cfg)
      (fixture/rollback-latest! cfg)
      (is (nil? (:table_name (first (jdbc/execute! migrator ["SELECT to_regclass('public.donors') AS table_name"])))))
      (is (= actor (:users/id (first (jdbc/execute! runtime ["SELECT id FROM users"])))))
      (migratus/migrate cfg)
      (is (empty? (jdbc/execute! runtime ["SELECT id FROM donors"])))
      (is (= actor (:users/id (first (jdbc/execute! runtime ["SELECT id FROM users"])))))
      (is (= 4 (:n (first (jdbc/execute! migrator ["SELECT count(*) AS n FROM schema_migrations"]))))))))

(deftest donor-identity-is-not-name-or-address-equality
  (migrated!)
  (let [{:keys [runtime]} fixture/*database*
        actor (actor! runtime)
        ids (mapv #(donor! runtime actor % "Synthetic Żółć") ["individual" "business" "organization"])]
    (doseq [id ids]
      (jdbc/execute! runtime ["UPDATE donors SET street='Synthetic Street 1', city='Synthetic City', postal_code='SW1A 1AA' WHERE id=?" id]))
    (is (= 3 (count (jdbc/execute! runtime ["SELECT id FROM donors WHERE name='Synthetic Żółć'"]))))
    (is (= 3 (count (set ids))))
    (is (= "synthetic żółć" (:normalized (first (jdbc/execute! runtime ["SELECT lower(name) AS normalized FROM donors LIMIT 1"])))))
    (jdbc/execute! runtime ["UPDATE donors SET is_active=false, version=version+1 WHERE id=?" (first ids)])
    (is (= {:donors/is_active false :donors/version 2}
           (first (jdbc/execute! runtime ["SELECT is_active, version FROM donors WHERE id=?" (first ids)]))))
    (is (thrown? SQLException (donor! runtime actor "household" "Synthetic Invalid")))
    (is (thrown? SQLException (donor! runtime actor "individual" "   ")))
    (is (thrown? SQLException (jdbc/execute! runtime ["UPDATE donors SET version=0 WHERE id=?" (first ids)])))
    (is (thrown? SQLException (donor! runtime (UUID/randomUUID) "individual" "Synthetic Missing Actor")))))

(deftest exact-payments-are-independent-facts
  (migrated!)
  (let [{:keys [runtime migrator]} fixture/*database*
        actor (actor! runtime)
        donor (donor! runtime actor "individual" "Synthetic Payment Profile")
        date (LocalDate/of 2026 1 2)]
    (dotimes [_ 2] (payment! runtime actor donor 123.4567M "PLN" date))
    (payment! runtime actor donor -23.4567M "PLN" date)
    (is (= 3 (:n (first (jdbc/execute! runtime ["SELECT count(*) AS n FROM donations"])))))
    (is (= 223.4567M (:total (first (jdbc/execute! runtime ["SELECT sum(amount) AS total FROM donations"])))))
    (doseq [[amount currency] [[0M "PLN"] [1M "pln"] [1M "PL"] [nil "PLN"] [1M nil]]]
      (is (thrown? SQLException (payment! runtime actor donor amount currency date))))
    (is (thrown? SQLException (payment! runtime actor donor 1M "PLN" nil)))
    (is (thrown? SQLException (payment! runtime actor (UUID/randomUUID) 1M "PLN" date)))
    (is (thrown? SQLException (jdbc/execute! migrator ["UPDATE donations SET amount='NaN'::numeric"])))
    (is (thrown? SQLException (jdbc/execute! migrator ["UPDATE donations SET amount=10000000000000000"])))
    (is (thrown? SQLException (jdbc/execute! migrator ["UPDATE donations SET received_on='infinity'::date"])))
    (is (thrown? SQLException (jdbc/execute! migrator ["DELETE FROM donors WHERE id=?" donor])))
    (is (thrown? SQLException (jdbc/execute! migrator ["DELETE FROM users WHERE id=?" actor])))
    (jdbc/execute! runtime ["UPDATE donors SET is_active=false WHERE id=?" donor])
    (is (= 3 (:n (first (jdbc/execute! runtime ["SELECT count(*) AS n FROM donations WHERE donor_id=?" donor])))))))

(deftest protected-audit-and-runtime-privileges
  (migrated!)
  (let [{:keys [runtime]} fixture/*database*
        actor (actor! runtime)
        donor (donor! runtime actor "individual" "Synthetic Audit Profile")
        event (UUID/randomUUID)]
    (jdbc/execute! runtime ["INSERT INTO donor_audit_events(id, actor_id, donor_id, action) VALUES (?, ?, ?, 'create')" event actor donor])
    (jdbc/execute! runtime ["INSERT INTO donor_audit_changes(event_id, field_name, before_value, after_value) VALUES (?, 'name', 'null'::jsonb, to_jsonb(?::text))"
                            event "Synthetic Audit Profile"])
    (is (= "null" (:before_value (first (jdbc/execute! runtime ["SELECT before_value::text AS before_value FROM donor_audit_changes"])))))
    (is (= "\"Synthetic Audit Profile\"" (:after_value (first (jdbc/execute! runtime ["SELECT after_value::text AS after_value FROM donor_audit_changes"])))))
    (doseq [statement ["UPDATE donor_audit_events SET action='update'"
                       "DELETE FROM donor_audit_events"
                       "UPDATE donor_audit_changes SET after_value='null'::jsonb"
                       "DELETE FROM donor_audit_changes"
                       "TRUNCATE donor_audit_changes"
                       "DELETE FROM donors"
                       "UPDATE donations SET amount=1"
                       "DELETE FROM donations"]]
      (is (thrown? SQLException (jdbc/execute! runtime [statement]))))
    (is (thrown? SQLException (jdbc/execute! runtime ["INSERT INTO donor_audit_changes(event_id,field_name,before_value,after_value) VALUES (?, 'name', 'null', 'null')" event])))
    (is (thrown? SQLException (jdbc/execute! runtime ["INSERT INTO donor_audit_changes(event_id,field_name,before_value,after_value) VALUES (?, 'password', 'null', 'null')" event])))
    (is (thrown? SQLException (jdbc/execute! runtime ["INSERT INTO donor_audit_changes(event_id,field_name,before_value,after_value) VALUES (?, 'city', NULL, 'null')" event])))
    (is (thrown? SQLException (jdbc/execute! runtime ["INSERT INTO donor_audit_events(id,actor_id,donor_id,action) VALUES (?, ?, ?, 'erase')" (UUID/randomUUID) actor donor])))
    (is (thrown? SQLException (jdbc/execute! runtime ["INSERT INTO donor_audit_events(id,actor_id,donor_id,action) VALUES (?, ?, ?, 'update')" (UUID/randomUUID) (UUID/randomUUID) donor])))
    (is (thrown? Exception
                 (jdbc/with-transaction [tx runtime]
                   (jdbc/execute! tx ["UPDATE donors SET name='Synthetic Rollback' WHERE id=?" donor])
                   (jdbc/execute! tx ["INSERT INTO donor_audit_events(id,actor_id,donor_id,action) VALUES (?, ?, ?, 'invalid')" (UUID/randomUUID) actor donor]))))
    (is (= "Synthetic Audit Profile" (:donors/name (first (jdbc/execute! runtime ["SELECT name FROM donors WHERE id=?" donor])))))))
