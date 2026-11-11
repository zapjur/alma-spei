(ns alma-spei.dev.demo
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.config :as config]
            [alma-spei.api.db.core :as db]
            [alma-spei.api.donors.repository :as repository]
            [alma-spei.common.contracts.auth :as auth-contract]
            [alma-spei.common.contracts.donors :as donor-contract]
            [alma-spei.common.validation :as validation]
            [alma-spei.dev.demo-data :as data]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn fail! [code message]
  (throw (ex-info message {:code code :safe-message message})))

(defn check-target! [{:keys [environment database]}]
  (when-not (and (#{:development :test} environment)
                 (#{"127.0.0.1" "localhost" "::1"} (:host database))
                 (= "alma_spei_app" (:user database))
                 (string? (:name database))
                 (if (= environment :development)
                   (= "alma_spei_dev" (:name database))
                   (re-matches #"alma_spei_test_[0-9a-f]{32}" (:name database))))
    (fail! :unsafe-target "Synthetic loading requires the local development database or an isolated test database using the runtime role.")))

(defn validate-data! [{:keys [donors payments]}]
  (when-not (and (every? #(validation/valid? donor-contract/create-donor (select-keys % repository/profile-fields)) donors)
                 (every? #(validation/valid? donor-contract/donation
                                             {:id (str (:id %)) :amount (.toPlainString ^java.math.BigDecimal (:amount %))
                                              :currency (:currency %) :received-on (str (:received-on %))}) payments))
    (fail! :invalid-fixtures "Synthetic fixture definitions are invalid; no rows were changed.")))

(defn selected-rows [tx table ids]
  ;; The table name is a fixed internal argument; identifiers never come from CLI input.
  (repository/query tx (into [(str "SELECT * FROM " table " WHERE id IN (" (str/join "," (repeat (count ids) "?")) ")")]
                             ids)))

(defn comparable-payments [rows]
  (mapv #(update % :received-on str) rows))

(defn same-records? [expected actual fields]
  (= (into {} (map (fn [row] [(:id row) (select-keys row fields)]) expected))
     (into {} (map (fn [row] [(:id row) (select-keys row fields)]) actual))))

(defn existing-state [tx {:keys [donors payments]}]
  (let [donor-ids (mapv :id donors)
        existing-donors (selected-rows tx "donors" donor-ids)
        existing-payments (selected-rows tx "donations" (mapv :id payments))]
    (if (and (empty? existing-donors) (empty? existing-payments))
      :empty
      (let [created (repository/query tx (into [(str "SELECT DISTINCT donor_id FROM donor_audit_events WHERE action='create' AND donor_id IN ("
                                                     (str/join "," (repeat (count donor-ids) "?")) ")")] donor-ids))]
        (if (and (same-records? donors existing-donors (concat repository/profile-fields [:is-active :version]))
                 (same-records? (comparable-payments payments) (comparable-payments existing-payments)
                                [:donor-id :amount :currency :received-on :version])
                 (= (set donor-ids) (set (map :donor-id created))))
          :complete
          (fail! :seed-conflict "Synthetic records are incomplete or have changed. No rows were changed; use a separate disposable database for a fresh demo."))))))

(defn insert-donor! [tx actor fixture]
  (let [created (repository/create! tx (:id fixture) actor fixture)
        created (if (:is-active fixture) created
                    (repository/one tx ["UPDATE donors SET is_active=false WHERE id=? RETURNING *" (:id fixture)]))]
    (audit/record! tx actor (:id fixture) "create" (audit/changes nil created))))

(defn insert-payment! [tx actor fixture]
  (repository/query tx ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by) VALUES (?,?,?,?,?,?)"
                        (:id fixture) (:donor-id fixture) (:amount fixture) (:currency fixture) (:received-on fixture) actor]))

(defn seed! [settings username]
  (check-target! settings)
  (when-not (auth-contract/valid? auth-contract/username username)
    (fail! :invalid-actor "Supply the username of an existing active account administrator."))
  (let [fixtures (data/dataset)]
    (validate-data! fixtures)
    (jdbc/with-transaction [tx (db/datasource (:database settings))]
      ;; Serialize this developer command without using the authentication mutation lock.
      (repository/query tx ["SELECT pg_advisory_xact_lock(20971233)"])
      (let [target (repository/one tx ["SELECT current_database() AS database_name, current_user AS user_name"])]
        (when-not (and (= (get-in settings [:database :name]) (:database-name target))
                       (= "alma_spei_app" (:user-name target)))
          (fail! :unsafe-target "The connected database or role does not match the permitted synthetic target.")))
      (let [actor (:id (repository/one tx ["SELECT id FROM users WHERE username=? AND active AND administrator"
                                           (auth-contract/canonical-username username)]))]
        (when-not actor
          (fail! :invalid-actor "Supply the username of an existing active account administrator; no accounts are created by this command."))
        (let [state (existing-state tx fixtures)]
          (when (= :empty state)
            (doseq [fixture (:donors fixtures)] (insert-donor! tx actor fixture))
            (doseq [fixture (:payments fixtures)] (insert-payment! tx actor fixture)))
          {:status (if (= :empty state) :loaded :unchanged) :donors 20 :payments 30})))))

(defn -main [& args]
  (try
    (when-not (= 1 (count args)) (fail! :usage "Usage: bb seed-demo <existing-administrator-username>"))
    (prn (seed! (config/load-config) (first args)))
    (catch Exception error
      (binding [*out* *err*]
        (println (or (:safe-message (ex-data error))
                     "Synthetic loading failed. Check the local database and migrations; the transaction did not commit.")))
      (System/exit 1))))
