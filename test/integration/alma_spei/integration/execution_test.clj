(ns alma-spei.integration.execution-test
  (:require [alma-spei.integration.bank-upload-test :as fixture]
            [alma-spei.integration.decisions-test :as review]
            [alma-spei.integration.review-test :as donor-fixture]
            [alma-spei.integration.database-test :as database]
            [alma-spei.api.catalog :as catalog]
            [alma-spei.api.auth.service :as auth]
            [next.jdbc :as jdbc]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.api.imports.backup :as backup]
            [alma-spei.api.imports.commit :as commit]
            [alma-spei.api.imports.undo :as undo]
            [alma-spei.api.imports.execution-repository :as execution-repository]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.donors.service :as donor-service]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.http.server :as server]
            [alma-spei.dev.demo :as demo]
            [muuntaja.core :as muuntaja]
            [migratus.core :as migratus]
            [alma-spei.common.contracts.execution :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import [java.util UUID]
           [java.sql SQLException]
           [java.time Instant]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defn ready [f]
  (fixture/ready (fn [] (let [settings (assoc (:settings fixture/*context*) :environment :test :synthetic-imports true)]
                          (binding [fixture/*context* (assoc fixture/*context* :settings settings :handler (routes/handler (review/ds) 2 settings))] (f))))))
(use-fixtures :each ready)
(defn call [method path input] (review/call method path input))
(defn successful [response]
  (when-not (#{200 201} (:status response)) (throw (ex-info "Synthetic fixture operation failed" {:status (:status response) :code (get-in response [:body :code])})))
  (:body response))
(defn plan!
  ([] (plan! nil 1))
  ([donor rows]
   (let [source-text (apply str (repeat rows (str "2026-01-02;Synthetic execution sender;Synthetic evidence " (UUID/randomUUID) ";12.3400\n")))
         batch (review/batch source-text)
         proposed (when-not donor (successful (review/propose batch {:entity-type "organization" :name "Synthetic Execution Organization" :city "Synthetic City"})))
         batch (or (:batch proposed) batch)
         batch (reduce (fn [b row]
                         (:batch (successful (call :put (review/path b (str "/rows/1/" row "/decision"))
                                                   (if donor (review/input b donor)
                                                       {:batch-version (:version b) :catalog-version (catalog/version (review/ds)) :version 0 :kind "new-proposal"
                                                        :donor-id nil :proposal-id (get-in proposed [:proposal :id]) :target-version 1 :financial review/financial
                                                        :donation-id nil :donation-version nil}))))) batch (range 1 (inc rows)))
         preview (:preview (successful (review/preview batch)))
         receipt (:backup (successful (call :post (review/path batch (str "/previews/" (:id preview) "/backup"))
                                            {:batch-version (:version batch) :catalog-version (:catalog-version preview)})))]
     {:batch batch :preview preview :source-text source-text :request {:batch-id (:id batch) :batch-version (:version batch) :catalog-version (:catalog-version preview)
                                                                       :preview-id (:id preview) :effect-digest (:effect-digest preview) :backup-id (:id receipt) :confirmed true}})))
(defn commit! [plan] (call :post "/api/imports/commit" (:request plan)))
(defn undo-input [response]
  {:batch-id (get-in response [:body :batch :id]) :batch-version (get-in response [:body :batch :version]) :commit-id (get-in response [:body :commit :id]) :confirmed true})
(defn undo! [response] (call :post "/api/imports/undo-last" (undo-input response)))
(defn count-of [table] (review/count-of table))
(defn ids [table] (mapv :id (repository/query (review/ds) [(str "SELECT id FROM " table " ORDER BY id")])))

(deftest commit-reuses-proposal-and-retains-provenance-with-exact-replay
  (let [plan (plan! nil 2) result (commit! plan) body (:body result) before (catalog/version (review/ds))]
    (is (= 200 (:status result)))
    (is (validation/valid? contract/outcome body))
    (is (= "committed" (get-in body [:batch :state])))
    (is (= (+ 2 (get-in plan [:batch :version])) (get-in body [:batch :version])))
    (is (= [1 2 3] (mapv count-of ["donors" "donations" "import_commit_items"])))
    (is (= [(:id (:batch plan))] (distinct (map #(str (:import-batch-id %)) (repository/query (review/ds) ["SELECT import_batch_id FROM donors UNION ALL SELECT import_batch_id FROM donations"])))))
    (is (= body (:body (commit! plan))))
    (is (= before (catalog/version (review/ds))))
    (is (= 409 (:status (call :post "/api/imports/commit" (assoc (:request plan) :effect-digest (apply str (repeat 64 "a")))))))
    (is (= 1 (count-of "import_commits")))
    (is (= 1 (count-of "donor_audit_events")))
    (let [items (call :get (review/path (:batch plan) "/commit-items?limit=1&offset=2") nil)]
      (is (= 200 (:status items)))
      (is (= 3 (get-in items [:body :total])))
      (is (= [3] (mapv :ordinal (get-in items [:body :items])))))
    (is (= 1 (get-in (call :get "/api/imports?limit=1" nil) [:body :total])))))

(deftest compensation-reverses-payments-and-archives-donors-without-erasure
  (let [plan (plan! nil 2) result (commit! plan) before (ids "donations") compensation (undo! result)]
    (is (= 200 (:status compensation)))
    (is (= "undone" (get-in compensation [:body :batch :state])))
    (is (= {:reversed-payments 2 :archived-donors 1} (select-keys (get-in compensation [:body :undo]) [:reversed-payments :archived-donors])))
    (is (= before (ids "donations")))
    (is (= [0 2 1 1] (mapv count-of ["active_donations" "donation_reversals" "import_batches" "import_commits"])))
    (is (false? (:is-active (first (repository/query (review/ds) ["SELECT * FROM donors"])))))
    (is (= ["create" "archive"] (mapv :action (repository/query (review/ds) ["SELECT action FROM donor_audit_events ORDER BY occurred_at"]))))
    (is (= (:body compensation) (:body (undo! result))))
    (is (= "undone" (get-in (commit! plan) [:body :batch :state])))
    (is (= 0 (get-in (donor-service/execute! (review/ds) (:token fixture/*context*) :detail (str (first (ids "donors"))) {} nil) [:donations-total])))
    (is (= 0 (get-in (call :get (review/path (:batch plan) (str "/payments?donor-id=" (first (ids "donors")))) nil) [:body :total])))
    (is (= 2 (count-of "import_review_decisions")))
    (is (= 1 (count-of "import_previews")))
    (is (= 409 (:status (call :post "/api/imports/undo-last" (assoc (undo-input result) :batch-version 999)))))))

(deftest newly-added-canonical-data-invalidates-financial-evidence-and-backup
  (let [donor (donor-fixture/create-donor "Synthetic Existing" true) plan (plan! donor 1)]
    (review/donation! donor)
    (is (= 409 (:status (commit! plan))))
    (is (= "stale-catalog" (get-in (call :get (review/path (:batch plan) "/review-blockers") nil) [:body :blockers 0 :code])))
    (is (= 0 (count-of "import_commits")))
    (let [read (successful (call :get (review/path (:batch plan) "/rows/1/1/decision") nil))
          updated (successful (call :put (review/path (:batch plan) "/rows/1/1/decision")
                                    (assoc (review/input (:batch read) donor) :version 1 :catalog-version (:catalog-version read))))]
      (is (= 2 (get-in updated [:decision :version])))
      (is (= 201 (:status (review/preview (:batch updated))))))))

(deftest backup-is-bound-expiring-fails-closed-and-rechecks-races
  (let [plan (plan!) path (review/path (:batch plan) (str "/previews/" (get-in plan [:preview :id]) "/backup"))
        input {:batch-version (get-in plan [:batch :version]) :catalog-version (get-in plan [:preview :catalog-version])}]
    (with-redefs [backup/now #(Instant/parse "2999-01-01T00:00:00Z")]
      (is (= 409 (:status (commit! plan)))))
    (is (= 409 (:status (call :post "/api/imports/commit" (assoc (:request plan) :backup-id (str (UUID/randomUUID)))))))
    (with-redefs [backup/run-synthetic! #(throw (ex-info "Synthetic backup fault" {}))]
      (is (= 409 (:status (call :post path input)))))
    (let [original backup/run-synthetic!]
      (with-redefs [backup/run-synthetic! #(do (donor-fixture/create-donor "Synthetic raced writer" true) (original))]
        (is (= 409 (:status (call :post path input))))))
    (is (= 1 (count-of "import_backup_runs")))
    (is (= 0 (count-of "import_commits")))
    (let [settings (dissoc (:settings fixture/*context*) :synthetic-imports)]
      (is (= 409 (try (commit/execute! (review/ds) settings (:token fixture/*context*) (:csrf fixture/*context*) (:request plan))
                      nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))))))

(deftest commit-failures-roll-back-all-business-state
  (let [plan (plan! nil 2) before (catalog/version (review/ds))]
    (doseq [target [#'commit/create-donor! #'commit/create-payment! #'execution-repository/insert-item! #'execution-repository/transition!]]
      (with-redefs-fn {target (fn [& _] (throw (ex-info "Synthetic write fault" {})))}
        #(is (= 500 (:status (commit! plan)))))
      (is (= before (catalog/version (review/ds))))
      (is (= [0 0 0 0 0] (mapv count-of ["donors" "donations" "import_commits" "import_commit_items" "donor_audit_events"])))
      (is (= "review" (get-in (call :get (review/path (:batch plan) "") nil) [:body :batch :state]))))
    (is (= 200 (:status (commit! plan))))))

(deftest undo-failures-and-later-edits-preserve-entire-import
  (let [plan (plan! nil 2) result (commit! plan) before (catalog/version (review/ds))]
    (doseq [target [#'execution-repository/reverse! #'undo/archive! #'execution-repository/transition!]]
      (with-redefs-fn {target (fn [& _] (throw (ex-info "Synthetic undo fault" {})))} #(is (= 500 (:status (undo! result)))))
      (is (= [2 0 0] (mapv count-of ["active_donations" "donation_reversals" "import_undos"])))
      (is (= before (catalog/version (review/ds)))))
    (let [id (first (ids "donors"))]
      (repository/query (review/ds) ["UPDATE donors SET name='Synthetic later edit',version=version+1 WHERE id=?" id])
      (is (= 409 (:status (undo! result))))
      (is (= "changed-donor" (get-in (call :get (review/path (:batch plan) "/undo-eligibility") nil) [:body :blockers 0 :code])))
      (is (= 2 (count-of "active_donations"))))))

(deftest later-dependent-payments-and-review-references-block-undo
  (let [result (commit! (plan!)) donor (first (ids "donors"))]
    (review/donation! {:id (str donor)})
    (is (= 409 (:status (undo! result))))
    (is (= "dependent-records" (get-in (undo! result) [:body :blockers 0 :code])))
    (is (= 2 (count-of "active_donations")))))

(deftest latest-ever-head-does-not-walk-backward-after-undo
  (let [a (commit! (plan!)) b (commit! (plan!))]
    (is (= 409 (:status (undo! a))))
    (is (= 200 (:status (undo! b))))
    (is (= 409 (:status (undo! a))))
    (is (= "not-latest" (get-in (undo! a) [:body :blockers 0 :code])))
    (is (= 2 (count-of "import_commits")))
    (is (= 1 (count-of "import_undos")))))

(deftest exact-concurrent-commit-and-undo-requests-apply-once
  (let [plan (plan!) gate (promise) ds (review/ds) settings (:settings fixture/*context*) token (:token fixture/*context*) csrf (:csrf fixture/*context*)
        a (future @gate (commit/execute! ds settings token csrf (:request plan))) b (future @gate (commit/execute! ds settings token csrf (:request plan)))]
    (deliver gate true)
    (is (= @a @b))
    (is (= [1 1] (mapv count-of ["import_commits" "donations"])))
    (let [input (undo-input {:body @a}) gate (promise) a (future @gate (undo/execute! ds token csrf input)) b (future @gate (undo/execute! ds token csrf input))]
      (deliver gate true)
      (is (= @a @b))
      (is (= [1 1] (mapv count-of ["import_undos" "donation_reversals"]))))))

(deftest undone-file-reimport-starts-fresh-without-losing-history
  (let [plan (plan!) result (commit! plan) undone (undo! result)
        reimport (fixture/upload "renamed-synthetic.csv" (.getBytes ^String (:source-text plan) "UTF-8")
                                 {"source" "bank" "separator" ";" "retry-batch-id" (get-in undone [:body :batch :id])
                                  "version" (str (get-in undone [:body :batch :version])) "confirmed" "true"} {})]
    (is (= 201 (:status reimport)))
    (is (= 2 (get-in reimport [:body :batch :attempt-number])))
    (is (= 0 (get-in reimport [:body :batch :reviewed-rows])))
    (is (= 2 (count-of "import_batches")))
    (is (= 1 (count-of "import_review_decisions")))
    (is (= 0 (count-of "active_donations")))
    (is (= 409 (:status (fixture/upload "same.csv" (.getBytes ^String (:source-text plan) "UTF-8") {"source" "bank" "separator" ";"} {}))))))

(deftest grants-and-used-database-downgrade-preserve-compensation
  (let [result (commit! (plan!))]
    (undo! result)
    (doseq [sql ["DELETE FROM donation_reversals" "UPDATE import_commits SET batch_version=999" "DELETE FROM import_undos"
                 "UPDATE import_backup_runs SET expires_at=now()" "UPDATE donors SET import_batch_id=NULL"]]
      (is (= "42501" (try (repository/query (review/ds) [sql]) nil (catch SQLException e (.getSQLState e))))))
    (let [cfg (migrations/migration-config (:migrator database/*database*))]
      (migratus/down cfg 202611220001)
      (is (thrown? Exception (migratus/down cfg 202611150002))))
    (is (= 1 (count-of "donation_reversals")))))

(deftest explicit-duplicate-reference-never-reverses-the-original-payment
  (let [donor (donor-fixture/create-donor "Synthetic original donor" true)
        payment (review/donation! donor) batch (review/batch)
        saved (successful (review/save batch (-> (review/input batch donor) (assoc :donation-id (:id payment) :donation-version 1)
                                                 (assoc-in [:financial :disposition] "existing"))))
        preview (:preview (successful (review/preview (:batch saved))))
        receipt (:backup (successful (call :post (review/path batch (str "/previews/" (:id preview) "/backup"))
                                           {:batch-version (get-in saved [:batch :version]) :catalog-version (:catalog-version preview)})))
        result (call :post "/api/imports/commit" {:batch-id (:id batch) :batch-version (get-in saved [:batch :version]) :preview-id (:id preview)
                                                  :catalog-version (:catalog-version preview) :effect-digest (:effect-digest preview) :backup-id (:id receipt) :confirmed true})]
    (is (= 200 (:status result)))
    (is (= 0 (get-in result [:body :commit :summary :new-payments])))
    (is (= 200 (:status (undo! result))))
    (is (= [1 0] (mapv count-of ["active_donations" "donation_reversals"])))
    (is (true? (:is-active (repository/one (review/ds) ["SELECT is_active FROM donors WHERE id=?" (UUID/fromString (:id donor))]))))))

(deftest cross-batch-live-review-reference-blocks-undo
  (let [result (commit! (plan!)) id (first (ids "donors"))
        donor (donor-service/public-donor (repository/one (review/ds) ["SELECT * FROM donors WHERE id=?" id]))
        batch (review/batch "2026-01-03;Synthetic second review;;15\n")]
    (is (= 200 (:status (review/save batch (review/input batch donor)))))
    (is (= 409 (:status (undo! result))))
    (is (= "dependent-records" (get-in (undo! result) [:body :blockers 0 :code])))
    (is (= 1 (count-of "active_donations")))))

(deftest changed-payment-or-unknown-schema-dependency-blocks-whole-undo
  (let [result (commit! (plan!)) migrator (:migrator database/*database*) id (first (ids "donations"))]
    (repository/query migrator ["UPDATE donations SET amount=amount+1,version=version+1 WHERE id=?" id])
    (is (= 409 (:status (undo! result))))
    (is (= "changed-payment" (get-in (undo! result) [:body :blockers 0 :code])))
    (is (= 0 (count-of "donation_reversals")))))

(deftest unknown-reference-tables-fail-closed-before-compensation
  (let [result (commit! (plan!)) migrator (:migrator database/*database*)]
    (repository/query migrator ["CREATE TABLE future_reference(id uuid REFERENCES donors(id))"])
    (is (= 409 (:status (undo! result))))
    (is (= "dependent-records" (get-in (undo! result) [:body :blockers 0 :code])))
    (is (= 0 (count-of "donation_reversals")))))

(deftest competing-distinct-imports-cannot-use-old-overlap-evidence
  (let [a (plan!) b (plan! (donor-fixture/create-donor "Synthetic existing target" true) 1)
    ;; Creating b's donor invalidated a, so explicitly review a against the current catalogue.
        row (successful (call :get (review/path (:batch a) "/rows/1/1/decision") nil))
        decision (get row :decision)
        saved (successful (call :put (review/path (:batch a) "/rows/1/1/decision")
                                (merge (select-keys decision [:kind :donor-id :proposal-id :target-version :financial :donation-id :donation-version :version])
                                       {:batch-version (get-in row [:batch :version]) :catalog-version (:catalog-version row)})))
        preview (:preview (successful (review/preview (:batch saved))))
        receipt (:backup (successful (call :post (review/path (:batch a) (str "/previews/" (:id preview) "/backup"))
                                           {:batch-version (get-in saved [:batch :version]) :catalog-version (:catalog-version preview)})))
        request (merge (:request a) {:batch-version (get-in saved [:batch :version]) :catalog-version (:catalog-version preview)
                                     :preview-id (:id preview) :effect-digest (:effect-digest preview) :backup-id (:id receipt)})
        gate (promise) ds (review/ds) settings (:settings fixture/*context*) token (:token fixture/*context*) csrf (:csrf fixture/*context*)
        run (fn [input] (future @gate (try (commit/execute! ds settings token csrf input) 200 (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
        x (run request) y (run (:request b))]
    (deliver gate true)
    (is (= #{200 409} #{@x @y}))
    (is (= 1 (count-of "import_commits")))
    (is (= 1 (count-of "active_donations")))))

(deftest manual-profile-edit-racing-commit-or-undo-is-preserved
  (let [donor (donor-fixture/create-donor "Synthetic concurrent donor" true) plan (plan! donor 1)
        ds (review/ds) token (:token fixture/*context*) csrf (:csrf fixture/*context*) settings (:settings fixture/*context*) gate (promise)
        other-id (UUID/randomUUID)
        _ (repository/query ds ["INSERT INTO users(id,username,display_name) VALUES (?,'synthetic.concurrent','Synthetic concurrent staff')" other-id])
        other-token (:token (jdbc/with-transaction [tx ds] (auth/issue-session! tx (auth/user-row tx other-id) csrf)))
        edited (assoc (select-keys donor [:entity-type :name :first-name :street :postal-code :city :province :version]) :name "Synthetic manual correction")
        a (future @gate (donor-service/execute! ds other-token :update (:id donor) edited csrf))
        b (future @gate (try {:result (commit/execute! ds settings token csrf (:request plan))} (catch clojure.lang.ExceptionInfo e {:status (:status (ex-data e))})))]
    (deliver gate true)
    (is (= "Synthetic manual correction" (get-in @a [:donor :name])))
    (is (= "Synthetic manual correction" (:name (repository/one ds ["SELECT name FROM donors WHERE id=?" (UUID/fromString (:id donor))]))))
    (if-let [result (:result @b)]
      (is (= 409 (:status (undo! {:body result}))))
      (is (= 409 (:status @b))))))

(deftest imported-payment-rollback-after-an-earlier-row-is-atomic
  (let [plan (plan! nil 2) original commit/create-payment! count (atom 0) revision (catalog/version (review/ds))]
    (with-redefs [commit/create-payment! (fn [& args] (if (= 2 (swap! count inc)) (throw (ex-info "Synthetic second-row fault" {})) (apply original args)))]
      (is (= 500 (:status (commit! plan)))))
    (is (= 2 @count))
    (is (= [0 0 0 0] (mapv count-of ["donors" "donations" "import_commits" "import_commit_items"])))
    (is (= revision (catalog/version (review/ds))))))

(deftest execution-endpoints-require-session-csrf-and-closed-input
  (let [plan (plan!) headers (assoc (fixture/headers) "content-type" "application/json")]
    (is (= 401 (:status (review/call :post "/api/imports/commit" (:request plan) {:headers (assoc headers "cookie" (str "alma-csrf=" (:csrf fixture/*context*)))}))))
    (is (= 403 (:status (review/call :post "/api/imports/commit" (:request plan) {:headers (dissoc headers "x-csrf-token")}))))
    (is (= 403 (:status (review/call :post "/api/imports/commit" (:request plan) {:headers (assoc headers "origin" "http://invalid.example")}))))
    (is (= 400 (:status (call :post "/api/imports/commit" (assoc (:request plan) :skip-backup true)))))
    (doseq [suffix ["/outcome" "/commit-items" "/undo-eligibility"]]
      (is (= 401 (:status (review/call :get (review/path (:batch plan) suffix) nil {:headers {"accept" "application/json"}})))))
    (is (= 400 (:status (call :get "/api/imports?limit=101" nil))))
    (is (= 0 (count-of "import_commits")))))

(deftest legacy-review-evidence-is-retained-but-requires-explicit-recheck
  (let [donor (donor-fixture/create-donor "Synthetic legacy donor" true) batch (review/batch)
        saved (successful (review/save batch (review/input batch donor)))
        cfg (migrations/migration-config (:migrator database/*database*)) id (UUID/randomUUID)]
    (migratus/down cfg 202611220001)
    (migratus/down cfg 202611150002)
    (repository/query (:migrator database/*database*)
                      ["INSERT INTO import_previews(id,batch_id,batch_version,created_by,rule_version,effect_digest,summary,item_count) VALUES (?,?,?,?,'review-preview-v1',repeat('a',64),?::jsonb,0)"
                       id (UUID/fromString (:id batch)) (get-in saved [:batch :version]) (:actor fixture/*context*)
                       "{\"new-donors\":0,\"new-payments\":0,\"duplicate-references\":0,\"totals\":[]}"])
    (migratus/migrate cfg)
    (let [decision (call :get (review/path batch "/rows/1/1/decision") nil)
          snapshot (call :get (review/path batch (str "/previews/" id)) nil)]
      (is (= 200 (:status decision)))
      (is (true? (get-in decision [:body :decision :complete])))
      (is (nil? (get-in decision [:body :decision :accepted-catalog-version])))
      (is (= 200 (:status snapshot)))
      (is (true? (get-in snapshot [:body :stale])))
      (is (= "review-preview-v1" (get-in snapshot [:body :preview :rule-version])))
      (is (= "stale-catalog" (get-in (call :get (review/path batch "/review-blockers") nil) [:body :blockers 0 :code]))))))

(deftest real-http-commit-compensation-and-outcome
  (let [plan (plan!) port (database/free-port) running (server/start! (:handler fixture/*context*) {:host "127.0.0.1" :port port})
        send (fn [path input]
               (let [builder (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))]
                 (doseq [[key value] (assoc (fixture/headers) "content-type" "application/json")] (.header builder key value))
                 (let [request (if input (.POST builder (HttpRequest$BodyPublishers/ofByteArray (let [out (muuntaja/encode "application/json" input)] (.readAllBytes out)))) (.GET builder))
                       response (.send (HttpClient/newHttpClient) (.build request) (HttpResponse$BodyHandlers/ofString))]
                   {:status (.statusCode response) :body (muuntaja/decode "application/json" (.getBytes (.body response) "UTF-8"))})))]
    (try
      (let [committed (send "/api/imports/commit" (:request plan))
            undone (send "/api/imports/undo-last" (undo-input committed))]
        (is (= 200 (:status committed)))
        (is (= 200 (:status undone)))
        (is (= "undone" (get-in (send (review/path (:batch plan) "/outcome") nil) [:body :batch :state])))
        (is (= 0 (count-of "active_donations"))))
      (finally (server/stop! running)))))

(deftest demo-loader-and-commit-share-catalogue-serialization
  (repository/query (review/ds) ["UPDATE users SET administrator=true WHERE id=?" (:actor fixture/*context*)])
  (let [plan (plan!) gate (promise) settings {:environment :test :database (:settings database/*database*)}
        ds (review/ds) token (:token fixture/*context*) csrf (:csrf fixture/*context*) auth-settings (:settings fixture/*context*)
        a (future @gate (demo/seed! settings "synthetic.bank"))
        b (future @gate (try (commit/execute! ds auth-settings token csrf (:request plan)) 200 (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))]
    (deliver gate true)
    (is (= :loaded (:status @a)))
    (is (contains? #{200 409} @b))
    (is (= (if (= 200 @b) 31 30) (count-of "active_donations")))
    (is (= (if (= 200 @b) 21 20) (count-of "donors")))))

(deftest later-duplicate-review-blocks-reversing-a-payment-on-an-existing-donor
  (let [donor (donor-fixture/create-donor "Synthetic existing payment target" true)
        result (commit! (plan! donor 1)) payment (first (ids "donations"))
        batch (review/batch "2026-01-02;Synthetic duplicate source;;12.3400\n")
        input (-> (review/input batch donor) (assoc :donation-id (str payment) :donation-version 1)
                  (assoc-in [:financial :disposition] "existing"))]
    (is (= 200 (:status (review/save batch input))))
    (is (= 409 (:status (undo! result))))
    (is (= "dependent-records" (get-in (undo! result) [:body :blockers 0 :code])))
    (is (= 1 (count-of "active_donations")))))

(deftest linked-tax-evidence-blocks-archiving-an-import-created-donor
  (let [result (commit! (plan!))
        donor (first (ids "donors"))
        tax-batch (database/staged-source! (review/ds) (:actor fixture/*context*) "tax" "c")]
    (repository/query (review/ds)
                      ["INSERT INTO tax_identification_evidence (batch_id,worksheet_index,row_number,subject_role,donor_id) VALUES (?,1,2,'taxpayer',?)"
                       tax-batch donor])
    (let [undo (undo! result)]
      (is (= 409 (:status undo)))
      (is (= [{:code "dependent-records" :entity-id (str donor)}] (get-in undo [:body :blockers])))
      (is (= 1 (count-of "active_donations"))))))
