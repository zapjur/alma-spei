(ns alma-spei.integration.decisions-test
  (:require [alma-spei.integration.bank-upload-test :as fixture]
            [alma-spei.integration.review-test :as review-fixture]
            [alma-spei.integration.database-test :as database]
            [alma-spei.api.auth.service :as auth]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.imports.decision-repository :as decisions-repository]
            [alma-spei.api.imports.decisions :as decisions]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [muuntaja.core :as muuntaja]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]
           [java.sql SQLException]
           [java.time LocalDate]))

(use-fixtures :each fixture/ready)
(defn ds [] (:ds fixture/*context*))
(defn call
  ([method path input] (call method path input {}))
  ([method path input overrides]
   (let [[uri query] (str/split path #"\?" 2)
         response ((:handler fixture/*context*)
                   (merge {:request-method method :uri uri :query-string query
                           :headers (assoc (fixture/headers) "content-type" "application/json")}
                          (when (some? input) {:body (muuntaja/encode "application/json" input)}) overrides))]
     (update response :body #(muuntaja/decode "application/json" %)))))
(defn batch
  ([] (batch "2026-01-02;Synthetic Sender;Synthetic evidence;12.3400\n"))
  ([text] (get-in (fixture/upload "synthetic.csv" (.getBytes ^String text "UTF-8") {"source" "bank" "separator" ";"} {}) [:body :batch])))
(defn path [batch suffix] (str "/api/imports/" (:id batch) suffix))
(def financial {:currency "PLN" :evidence "Synthetic currency and overlap evidence" :disposition "new" :overlap-checked true})
(defn input [batch donor]
  {:batch-version (:version batch) :version 0 :kind "existing-donor" :donor-id (:id donor) :proposal-id nil :target-version (:version donor)
   :financial financial :donation-id nil :donation-version nil})
(defn save [batch input] (call :put (path batch "/rows/1/1/decision") input))
(defn count-of [table] (:n (repository/one (ds) [(str "SELECT count(*) AS n FROM " table)])))
(defn canonical [] (mapv #(repository/query (ds) [(str "SELECT * FROM " %)]) ["donors" "donations" "donor_audit_events" "donor_audit_changes"]))
(defn propose [batch profile]
  (call :post (path batch "/donor-proposals") {:batch-version (:version batch) :profile profile :checked-existing true :checked-proposals true}))
(defn preview [batch] (call :post (path batch "/preview") {:batch-version (:version batch)}))
(defn updated-batch [response] (get-in response [:body :batch]))
(defn donation! [donor]
  (let [id (UUID/randomUUID)]
    (repository/query (ds) ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by) VALUES (?,?,12.3400,'PLN',?,?)"
                            id (UUID/fromString (:id donor)) (LocalDate/of 2026 1 2) (:actor fixture/*context*)])
    {:id (str id) :version 1}))

(deftest saved-decisions-are-attributed-versioned-and-reloadable-without-canonical-writes
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true) before (canonical)
        request (input batch donor) response (save batch request) current (updated-batch response)
        saved (get-in response [:body :decision])]
    (is (= 200 (:status response)))
    (is (validation/valid? contract/decision-response (:body response)))
    (is (= 3 (:version current)))
    (is (= 1 (:reviewed-rows current)))
    (is (= 1 (:version saved)))
    (is (= "Synthetic Bank Staff" (:reviewer-name saved)))
    (is (= "Synthetic Existing" (:target-label saved)))
    (is (= (str (:actor fixture/*context*)) (:reviewer-id saved)))
    (is (= saved (get-in (call :get (path batch "/rows/1/1/decision") nil) [:body :decision])))
    (is (= 409 (:status (save batch request))))
    (is (= 200 (:status (save current (assoc request :batch-version 3 :version 1)))))
    (is (= 1 (count-of "import_review_events")))
    (is (= before (canonical)))
    (is (= "review" (get-in (call :get (path batch "") nil) [:body :batch :state])))
    (let [cleared (save current {:batch-version 3 :version 1 :kind "unresolved" :donor-id nil :proposal-id nil :target-version nil
                                 :financial nil :donation-id nil :donation-version nil})]
      (is (= 200 (:status cleared)))
      (is (= 0 (get-in cleared [:body :batch :reviewed-rows])))
      (is (= 2 (get-in cleared [:body :decision :version])))
      (is (= 1 (count-of "import_review_decisions")))
      (is (= 2 (count-of "import_review_events"))))))

(deftest all-proposed-donor-types-reuse-one-profile-with-explicit-same-batch-identity
  (doseq [type ["individual" "business" "organization"]]
    (let [batch (batch (str "2026-01-02;Synthetic " type ";;12.3400\n2026-01-02;Synthetic " type ";;12.3400\n"))
          created (propose batch {:entity-type type :name (str "Synthetic " type) :city "Synthetic City"})
          proposal (get-in created [:body :proposal])
          decision {:batch-version (get-in created [:body :batch :version]) :version 0 :kind "new-proposal" :donor-id nil
                    :proposal-id (:id proposal) :target-version 1 :financial financial :donation-id nil :donation-version nil}
          first (save batch decision)
          second (call :put (path batch "/rows/1/2/decision") (assoc decision :batch-version (get-in first [:body :batch :version])))
          ready (preview (updated-batch second))]
      (is (= 201 (:status created)))
      (is (= type (get-in proposal [:profile :entity-type])))
      (is (= 200 (:status first)))
      (is (= 200 (:status second)))
      (is (= 201 (:status ready)))
      (is (= {:new-donors 1 :new-payments 2 :duplicate-references 0 :totals [{:currency "PLN" :amount "24.6800"}]}
             (get-in ready [:body :preview :summary])))))
  (is (= 0 (count-of "donors")))
  (is (= 0 (count-of "donations"))))

(deftest incomplete-invalid-negative-and-stale-facts-block-preview
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true)]
    (is (= 409 (:status (preview batch))))
    (let [saved (save batch (assoc-in (input batch donor) [:financial :currency] nil)) current (updated-batch saved)]
      (is (= 200 (:status saved)))
      (is (= false (get-in saved [:body :decision :complete])))
      (is (= 0 (:reviewed-rows current)))
      (is (= 409 (:status (preview current))))
      (is (= "missing-decision" (get-in (call :get (path batch "/review-blockers") nil) [:body :blockers 0 :code])))))
  (let [batch (batch "2026-01-02;Synthetic Negative;;-12.3400\n") donor (review-fixture/create-donor "Synthetic Negative" true)
        saved (save batch (input batch donor))]
    (is (= 409 (:status (preview (updated-batch saved)))))
    (is (= "unsupported-amount" (get-in (call :get (path batch "/review-blockers") nil) [:body :blockers 0 :code]))))
  (let [batch (batch "2026-02-30;Synthetic Invalid;;0\n") donor (review-fixture/create-donor "Synthetic Invalid" true)]
    (is (= 409 (:status (save batch (input batch donor)))))
    (is (= "invalid-row" (get-in (call :get (path batch "/review-blockers") nil) [:body :blockers 0 :code]))))
  (let [batch (batch "2026-01-02;Synthetic Stale;;12.3400\n") donor (review-fixture/create-donor "Synthetic Stale" true)
        saved (save batch (input batch donor))]
    (repository/query (ds) ["UPDATE donors SET version=version+1,name='Synthetic Changed' WHERE id=?" (UUID/fromString (:id donor))])
    (is (= 409 (:status (preview (updated-batch saved)))))
    (is (= "stale-target" (get-in (call :get (path batch "/review-blockers") nil) [:body :blockers 0 :code]))))
  (is (= 0 (count-of "import_previews"))))

(deftest ready-preview-is-complete-immutable-paged-and-stales-after-decision-changes
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true)
        saved (save batch (input batch donor)) current (updated-batch saved) before (canonical)
        first (preview current) second (preview current) meta (get-in first [:body :preview])
        url (path batch (str "/previews/" (:id meta))) items (call :get (str url "/items?limit=1&offset=1") nil)]
    (is (= 201 (:status first)))
    (is (validation/valid? contract/preview-response (:body first)))
    (is (= (:effect-digest meta) (get-in second [:body :preview :effect-digest])))
    (is (= 2 (:item-count meta)))
    (is (= 2 (get-in items [:body :total])))
    (is (= "new-payment" (get-in items [:body :items 0 :kind])))
    (is (= "12.3400" (get-in items [:body :items 0 :payload :amount])))
    (is (= "review" (get-in first [:body :batch :state])))
    (is (= 3 (get-in first [:body :batch :version])))
    (is (= false (get-in (call :get url nil) [:body :stale])))
    (is (= before (canonical)))
    (save current (assoc (input current donor) :version 1 :kind "unresolved" :donor-id nil :target-version nil :financial nil))
    (is (= true (get-in (call :get url nil) [:body :stale])))
    (is (= "12.3400" (get-in (call :get (str url "/items?offset=1") nil) [:body :items 0 :payload :amount])))))

(deftest explicit-duplicate-reference-is-separate-from-genuine-equal-payments
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true) payment (donation! donor)
        request (-> (input batch donor) (assoc :donation-id (:id payment) :donation-version 1) (assoc-in [:financial :disposition] "existing"))
        saved (save batch request) ready (preview (updated-batch saved))]
    (is (= 200 (:status saved)))
    (is (= 201 (:status ready)))
    (is (= {:new-donors 0 :new-payments 0 :duplicate-references 1 :totals []} (get-in ready [:body :preview :summary])))
    (is (= 1 (count-of "donations")))
    (is (= 409 (:status (save (updated-batch saved) (assoc request :batch-version 3 :version 1 :donation-version 2)))))
    (is (= 409 (:status (save (updated-batch saved) (-> request (assoc :batch-version 3 :version 1) (assoc-in [:financial :currency] "EUR"))))))
    ;; A human may explicitly confirm a genuinely separate equal payment.
    (let [new (save (updated-batch saved) (assoc (input (updated-batch saved) donor) :version 1))]
      (is (= 200 (:status new)))
      (is (= 1 (get-in (preview (updated-batch new)) [:body :preview :summary :new-payments]))))))

(deftest proposal-edits-and-cross-batch-references-need-explicit-re-review
  (let [a (batch) b (batch "2026-01-02;Synthetic Other;;12.3400\n")
        created (propose a {:entity-type "individual" :name "Synthetic New" :city "Synthetic City"})
        proposal (get-in created [:body :proposal]) current (updated-batch created)
        request {:batch-version (:version current) :version 0 :kind "new-proposal" :donor-id nil :proposal-id (:id proposal)
                 :target-version 1 :financial financial :donation-id nil :donation-version nil}]
    (is (= 404 (:status (save b (assoc request :batch-version (:version b))))))
    (let [saved (save current request) edited (call :put (path a (str "/donor-proposals/" (:id proposal)))
                                                    {:batch-version (get-in saved [:body :batch :version]) :version 1
                                                     :profile {:entity-type "individual" :name "Synthetic Edited" :city "Synthetic City"}})]
      (is (= 200 (:status edited)))
      (is (= 2 (get-in edited [:body :proposal :version])))
      (is (= 409 (:status (preview (updated-batch edited)))))
      (is (= "stale-target" (get-in (call :get (path a "/review-blockers") nil) [:body :blockers 0 :code]))))))

(deftest journal-and-preview-item-failures-roll-back-all-metadata
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true)]
    (with-redefs [decisions-repository/event! (fn [& _] (throw (Exception. "Synthetic journal failure")))]
      (is (= 500 (:status (save batch (input batch donor))))))
    (is (= 0 (count-of "import_review_decisions")))
    (is (= 2 (get-in (call :get (path batch "") nil) [:body :batch :version])))
    (let [saved (save batch (input batch donor)) before (count-of "import_review_events")]
      (with-redefs [decisions-repository/insert-item! (fn [& _] (throw (Exception. "Synthetic item failure")))]
        (is (= 500 (:status (preview (updated-batch saved))))))
      (is (= 0 (count-of "import_previews")))
      (is (= 0 (count-of "import_preview_items")))
      (is (= before (count-of "import_review_events"))))))

(deftest same-batch-concurrent-review-has-one-winner-and-no-lost-update
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true)
        other (UUID/randomUUID) csrf (:csrf fixture/*context*) runtime (ds)]
    (repository/query runtime ["INSERT INTO users(id,username,display_name) VALUES (?,'synthetic.reviewer.two','Synthetic Reviewer Two')" other])
    (let [token (:token (jdbc/with-transaction [tx runtime] (auth/issue-session! tx (auth/user-row tx other) csrf)))
          gate (promise) request (input batch donor)
          run (fn [token] (future @gate (try (decisions/write! runtime token csrf :decision (:id batch) nil {:worksheet 1 :row 1} request) 200
                                             (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
          a (run (:token fixture/*context*)) b (run token)]
      (deliver gate true)
      (is (= #{200 409} #{@a @b}))
      (is (= 1 (count-of "import_review_events")))
      (is (= 1 (get-in (call :get (path batch "") nil) [:body :batch :reviewed-rows]))))))

(deftest migration-grants-and-preview-journal-immutability
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Existing" true)
        saved (save batch (input batch donor)) ready (preview (updated-batch saved)) before (canonical)
        cfg (migrations/migration-config (:migrator database/*database*))]
    (doseq [sql ["DELETE FROM import_review_decisions" "DELETE FROM import_donor_proposals" "UPDATE import_review_events SET event_type='decision-cleared'"
                 "DELETE FROM import_review_events" "UPDATE import_previews SET item_count=0" "UPDATE import_preview_items SET ordinal=99" "TRUNCATE import_previews"]]
      (is (= "42501" (try (repository/query (ds) [sql]) nil (catch SQLException e (.getSQLState e))))))
    (is (= 201 (:status ready)))
    (is (= "23514" (try
                     (repository/query (ds) ["INSERT INTO import_preview_items(preview_id,ordinal,kind,payload) VALUES (?,99,'new-payment','{}'::jsonb)"
                                             (UUID/fromString (get-in ready [:body :preview :id]))])
                     nil (catch SQLException e (.getSQLState e)))))
    (database/rollback-latest! cfg)
    (is (= before (canonical)))
    (migratus/migrate cfg)
    (is (= 0 (count-of "import_review_events")))
    (is (= 5 (:n (repository/one (:migrator database/*database*) ["SELECT count(*) AS n FROM schema_migrations"]))))))

(deftest authorization-closed-inputs-and-archived-targets
  (let [batch (batch) donor (review-fixture/create-donor "Synthetic Archived" false) input (input batch donor)
        endpoint (path batch "/rows/1/1/decision") headers (assoc (fixture/headers) "content-type" "application/json")]
    (is (= 409 (:status (save batch input))))
    (is (= 403 (:status (call :put endpoint input {:headers (dissoc headers "x-csrf-token")}))))
    (is (= 401 (:status (call :put endpoint input {:headers (assoc headers "cookie" (str "alma-csrf=" (:csrf fixture/*context*)))}))))
    (is (= 400 (:status (save batch (assoc input :reviewer-id (:id donor))))))
    (is (= 400 (:status (save batch (assoc-in input [:financial :currency] "pln")))))
    (doseq [suffix ["/donor-proposals" "/review-events" "/review-blockers"]]
      (is (= 401 (:status (call :get (path batch suffix) nil {:headers {"accept" "application/json"}})))))
    (is (= 0 (count-of "import_review_decisions")))))
