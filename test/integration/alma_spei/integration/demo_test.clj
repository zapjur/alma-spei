(ns alma-spei.integration.demo-test
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.db.core :as db]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.donors.repository :as repository]
            [alma-spei.dev.demo :as demo]
            [alma-spei.dev.demo-data :as data]
            [alma-spei.integration.database-test :as fixture]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus])
  (:import [java.time LocalDate]
           [java.util UUID]))

(defn ready [f]
  (fixture/isolated-database
   #(do (migratus/migrate (migrations/migration-config (:migrator fixture/*database*))) (f))))
(use-fixtures :each ready)
(defn runtime [] (:runtime fixture/*database*))
(defn settings [] {:environment :test :database (:settings fixture/*database*)})
(defn actor! []
  (let [id (UUID/randomUUID)]
    (repository/query (runtime) ["INSERT INTO users(id,username,display_name,administrator) VALUES (?,'synthetic.admin','Synthetic Demo Administrator',true)" id])
    id))
(defn count-of [table]
  (:n (repository/one (runtime) [(str "SELECT count(*) AS n FROM " table)])))
(defn snapshot []
  {:donors (repository/query (runtime) ["SELECT * FROM donors ORDER BY id"])
   :payments (repository/query (runtime) ["SELECT * FROM donations ORDER BY id"])
   :events (repository/query (runtime) ["SELECT * FROM donor_audit_events ORDER BY id"])
   :changes (mapv #(-> % (update :before-value str) (update :after-value str))
                  (repository/query (runtime) ["SELECT * FROM donor_audit_changes ORDER BY event_id,field_name"]))})
(defn error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest repeat-safe-transactional-load-preserves-unrelated-records
  (let [actor (actor!)
        unrelated-id (UUID/randomUUID)
        unrelated (repository/create! (runtime) unrelated-id actor
                                      {:entity-type "organization" :name "Synthetic Unrelated" :city "Synthetic City"})]
    (audit/record! (runtime) actor unrelated-id "create" (audit/changes nil unrelated))
    (repository/query (runtime) ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by) VALUES (?,?,7.5,'PLN',?,?)"
                                 (UUID/randomUUID) unrelated-id (LocalDate/of 2026 1 1) actor])
    (is (= {:status :loaded :donors 20 :payments 30} (demo/seed! (settings) "synthetic.admin")))
    (is (= 21 (count-of "donors")))
    (is (= 31 (count-of "donations")))
    (is (= 21 (count-of "donor_audit_events")))
    (is (= 1 (count-of "users")))
    (is (= 0 (count-of "local_credentials")))
    (is (= 2657.5000M (:total (repository/one (runtime) ["SELECT sum(amount) AS total FROM donations"]))))
    (is (= actor (:created-by (repository/one (runtime) ["SELECT created_by FROM donors WHERE id=?" (data/stable-id :donor 1)]))))
    (is (= actor (:created-by (repository/one (runtime) ["SELECT created_by FROM donations WHERE id=?" (data/stable-id :payment 1)]))))
    (is (= unrelated (repository/find-donor (runtime) unrelated-id false)))
    (let [before (snapshot)]
      (is (= {:status :unchanged :donors 20 :payments 30} (demo/seed! (settings) "SYNTHETIC.ADMIN")))
      (is (= before (snapshot))))))

(deftest later-manual-changes-stop-the-entire-repeat
  (actor!)
  (demo/seed! (settings) "synthetic.admin")
  (repository/query (runtime) ["UPDATE donors SET name='DANE SYNTETYCZNE — Manual correction',version=version+1 WHERE id=?" (data/stable-id :donor 1)])
  (let [before (snapshot)]
    (is (= :seed-conflict (error-code #(demo/seed! (settings) "synthetic.admin"))))
    (is (= before (snapshot)))))

(deftest partial-id-collision-is-not-filled-in-or-overwritten
  (let [actor (actor!)]
    (repository/create! (runtime) (data/stable-id :donor 1) actor
                        {:entity-type "business" :name "Synthetic Collision" :city "Synthetic City"})
    (let [before (snapshot)]
      (is (= :seed-conflict (error-code #(demo/seed! (settings) "synthetic.admin"))))
      (is (= before (snapshot)))
      (is (= 1 (count-of "donors")))
      (is (= 0 (count-of "donations"))))))

(deftest changed-payment-is-never-restored-by-the-seeder
  (actor!)
  (demo/seed! (settings) "synthetic.admin")
  (repository/query (:migrator fixture/*database*)
                    ["UPDATE donations SET amount=amount+1,version=version+1 WHERE id=?" (data/stable-id :payment 1)])
  (let [before (snapshot)]
    (is (= :seed-conflict (error-code #(demo/seed! (settings) "synthetic.admin"))))
    (is (= before (snapshot)))))

(deftest payment-and-audit-failures-leave-no-partial-dataset
  (actor!)
  (let [insert demo/insert-payment!
        n (atom 0)]
    (with-redefs [demo/insert-payment! (fn [tx actor fixture]
                                         (when (= 8 (swap! n inc)) (throw (ex-info "Synthetic failure" {})))
                                         (insert tx actor fixture))]
      (is (thrown? clojure.lang.ExceptionInfo (demo/seed! (settings) "synthetic.admin")))))
  (is (= 0 (count-of "donors")))
  (is (= 0 (count-of "donations")))
  (is (= 0 (count-of "donor_audit_events")))
  (is (= 0 (count-of "donor_audit_changes")))
  (with-redefs [audit/record! (fn [& _] (throw (ex-info "Synthetic audit failure" {})))]
    (is (thrown? clojure.lang.ExceptionInfo (demo/seed! (settings) "synthetic.admin"))))
  (is (= 0 (count-of "donors")))
  (is (= 1 (count-of "users"))))

(deftest concurrent-loads-create-only-one-dataset
  (actor!)
  (let [configuration (settings)
        gate (promise)
        start (fn [] (future @gate (demo/seed! configuration "synthetic.admin")))
        a (start) b (start)]
    (deliver gate true)
    (is (= #{:loaded :unchanged} #{(:status @a) (:status @b)}))
    (is (= 20 (count-of "donors")))
    (is (= 30 (count-of "donations")))
    (is (= 20 (count-of "donor_audit_events")))))

(deftest actor-and-connected-target-are-checked-before-writes
  (let [actor (actor!)]
    (is (= :invalid-actor (error-code #(demo/seed! (settings) "synthetic.missing"))))
    (repository/query (runtime) ["UPDATE users SET administrator=false WHERE id=?" actor])
    (is (= :invalid-actor (error-code #(demo/seed! (settings) "synthetic.admin"))))
    (repository/query (runtime) ["UPDATE users SET administrator=true,active=false WHERE id=?" actor])
    (is (= :invalid-actor (error-code #(demo/seed! (settings) "synthetic.admin"))))
    (with-redefs [db/datasource (fn [_] (runtime))]
      (is (= :unsafe-target (error-code #(demo/seed! (assoc-in (settings) [:database :name] "alma_spei_test_0123456789abcdef0123456789abcdef") "synthetic.admin")))))
    (with-redefs [db/datasource (fn [_] (:migrator fixture/*database*))]
      (is (= :unsafe-target (error-code #(demo/seed! (settings) "synthetic.admin")))))
    (is (= 0 (count-of "donors")))))

(deftest babashka-command-loads-and-repeats-in-an-isolated-database
  (actor!)
  (let [database (:database (settings))
        env (merge (into {} (System/getenv))
                   {"ALMA_ENV" "test" "ALMA_DB_NAME" (:name database) "ALMA_DB_USER" "alma_spei_app"
                    "ALMA_DB_HOST" (:host database) "ALMA_DB_PORT" (str (:port database))})]
    (doseq [status [:loaded :unchanged]]
      (let [result (shell/sh "bb" "seed-demo" "synthetic.admin" :env env)]
        (is (= 0 (:exit result)))
        (is (= {:status status :donors 20 :payments 30} (edn/read-string (:out result))))))
    (let [result (shell/sh "bb" "seed-demo" :env env)]
      (is (= 1 (:exit result)))
      (is (.contains ^String (:err result) "Usage: bb seed-demo")))
    (is (= 20 (count-of "donors")))
    (is (= 30 (count-of "donations")))))
