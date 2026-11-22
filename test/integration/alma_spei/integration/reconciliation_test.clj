(ns alma-spei.integration.reconciliation-test
  (:require [alma-spei.integration.bank-upload-test :as fixture]
            [alma-spei.integration.decisions-test :as calls]
            [alma-spei.api.donors.service :as donors]
            [alma-spei.api.imports.decision-repository :as repository]
            [alma-spei.api.imports.repository :as batches]
            [alma-spei.common.contracts.reconciliation :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.test :refer [deftest is use-fixtures]]))
(use-fixtures :each fixture/ready)
(def policy {:currency "PLN" :new-donor-type "individual"})
(defn donor! [name street]
  (:donor (donors/execute! (calls/ds) (:token fixture/*context*) :create nil
                           {:entity-type "individual" :name name :street street :postal-code "32-020" :city "Wieliczka"}
                           (:csrf fixture/*context*))))
(defn sample! [] (calls/batch (slurp "docs/testing-samples/bank-valid.csv" :encoding "UTF-8")))
(defn url [batch] (calls/path batch "/reconciliation"))
(defn analyse [batch selected-policy] (calls/call :post (url batch) {:batch-version (:version batch) :policy selected-policy}))
(defn seed! [] (donor! "Jan Kowalski" "ul. Słoneczna 1/4") (donor! "Jan Kowalski" "ul. Polna 22") (donor! "Jakub Nowak" "ul. Słoneczna 1/4"))
(defn counts [] (mapv calls/count-of ["donors" "donations" "import_review_decisions" "import_donor_proposals" "import_review_events"]))
(deftest automatic-sample-produces-complete-preview-without-canonical-writes
  (seed!)
  (let [batch (sample!) response (analyse batch policy) result (:body response) after (counts)]
    (is (= 200 (:status response)))
    (is (validation/valid? contract/response result))
    (is (= {:matched 2 :new-donors 1 :new-payments 3 :duplicates 0 :exceptions 0 :total 3} (:counts result)))
    (is (:ready result))
    (is (= [3 0 3 1 4] after))
    (is (= 200 (:status (analyse (:batch result) policy))))
    (is (= after (counts)))
    (is (= 409 (:status (analyse (:batch result) (assoc policy :currency "EUR")))))
    (is (= 409 (:status (analyse batch policy))))
    (let [preview (calls/call :post (calls/path batch "/preview") {:batch-version (get-in result [:batch :version])})]
      (is (= 201 (:status preview)))
      (is (= 3 (get-in preview [:body :preview :summary :new-payments])))
      (is (= [{:currency "PLN" :amount "250.00"}] (get-in preview [:body :preview :summary :totals]))))))
(deftest unknown-type-and-malformed-identity-are-exceptions
  (seed!)
  (let [result (:body (analyse (sample!) (assoc policy :new-donor-type nil)))]
    (is (= 2 (get-in result [:counts :matched])))
    (is (= ["donor-type"] (mapv :reason (:exceptions result))))
    (is (= 0 (calls/count-of "import_donor_proposals"))))
  (let [result (:body (analyse (calls/batch "2026-11-01;Jan Kowalski;Darowizna;20\n") policy))]
    (is (= ["sender-format"] (mapv :reason (:exceptions result)))))
  (is (= 3 (calls/count-of "donors"))))
(deftest ambiguous-identity-can-be-resolved-without-manual-payment-fields
  (let [first-donor (donor! "Jan Kowalski" "ul. Lipowa 1")
        _ (donor! "Jan Kowalski" "ul. Lipowa 1")
        batch (calls/batch "2026-11-01;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Darowizna;25\n")
        result (:body (analyse batch policy))
        response (calls/call :post (calls/path batch "/reconciliation/identity")
                             {:batch-version (get-in result [:batch :version]) :catalog-version (:catalog-version result)
                              :worksheet 1 :row 1 :kind "existing-donor" :target-id (:id first-donor) :target-version (:version first-donor)})]
    (is (= ["identity-ambiguity"] (mapv :reason (:exceptions result))))
    (is (= 200 (:status response)))
    (is (get-in response [:body :decision :complete]))
    (is (= "PLN" (get-in response [:body :decision :financial :currency])))
    (is (get-in (calls/call :get (url batch) nil) [:body :ready]))))
(deftest repeated-payments-stay-ambiguous-and-never-become-automatic-duplicates
  (let [donor (donor! "Jan Kowalski" "ul. Lipowa 1")
        line "2026-11-01;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Darowizna;25\n"
        batch (calls/batch (str line line)) result (:body (analyse batch policy))
        selected (calls/call :post (calls/path batch "/reconciliation/identity")
                             {:batch-version (get-in result [:batch :version]) :catalog-version (:catalog-version result)
                              :worksheet 1 :row 1 :kind "existing-donor" :target-id (:id donor) :target-version (:version donor)})]
    (is (= 2 (get-in result [:counts :exceptions])))
    (is (every? #(= "payment-ambiguity" (:reason %)) (:exceptions result)))
    (is (false? (get-in selected [:body :decision :complete])))
    (is (= 0 (calls/count-of "donations")))
    (is (= 0 (get-in result [:counts :duplicates])))))
(deftest analysis-is-atomic-and-preserves-human-decisions
  (let [donor (donor! "Jan Kowalski" "ul. Słoneczna 1/4") batch (sample!)
        manual (calls/save batch (calls/input batch donor))
        saved (get-in manual [:body :decision])
        result (analyse (get-in manual [:body :batch]) policy)]
    (is (= 200 (:status result)))
    (is (= saved (get-in (calls/call :get (calls/path batch "/rows/1/1/decision") nil) [:body :decision]))))
  (let [batch (calls/batch "2026-11-03;Helena Nowicka, ul. Brzozowa 18/7, 30-001 Kraków;Darowizna;50\n")
        before (counts)]
    (with-redefs [repository/save-decision! (fn [& _] (throw (ex-info "Synthetic rollback probe" {})))]
      (is (= 500 (:status (analyse batch policy)))))
    (is (= before (counts)))))
(deftest reconciliation-enforces-auth-csrf-and-paging
  (let [batch (sample!) input {:batch-version (:version batch) :policy policy}]
    (doseq [[headers status] [[(dissoc (fixture/headers) "cookie") 403]
                              [(dissoc (fixture/headers) "x-csrf-token") 403]
                              [(assoc (fixture/headers) "origin" "http://other.invalid") 403]]]
      (is (= status (:status (calls/call :post (url batch) input {:headers (assoc headers "content-type" "application/json")})))))
    (is (= 401 (:status (calls/call :get (url batch) nil {:headers {}}))))
    (is (= 400 (:status (calls/call :get (str (url batch) "?limit=101") nil))))
    (is (= 400 (:status (calls/call :post (url batch) (assoc-in input [:policy :currency] "")))))
    (let [result (:body (analyse batch (assoc policy :new-donor-type nil))) page (calls/call :get (str (url batch) "?limit=1&offset=1") nil)]
      (is (= 200 (:status page)))
      (is (= 1 (count (get-in page [:body :exceptions]))))
      (is (= (:counts result) (get-in page [:body :counts]))))))

(deftest catalog-refresh-renews-only-automatic-plans
  (seed!)
  (let [batch (sample!) initial (:body (analyse batch policy))
        _ (donor! "Anna Wiśniewska" "ul. Ogrodowa 17")
        stale (:body (calls/call :get (url batch) nil))
        refreshed (:body (analyse (:batch initial) policy))]
    (is (false? (:ready stale)))
    (is (:ready refreshed))
    (is (= (:counts initial) (:counts refreshed)))
    (is (= 1 (calls/count-of "import_donor_proposals")))
    (is (= 0 (calls/count-of "donations")))))

(deftest identical-existing-payment-is-an-exception-not-a-dropped-transfer
  (let [donor (donor! "Jan Kowalski" "ul. Lipowa 1")
        batch (calls/batch "2026-11-01;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Darowizna;25\n")]
    (batches/query (calls/ds)
                   ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by) VALUES (?,?,25,'PLN','2026-11-01',?)"
                    (java.util.UUID/randomUUID) (java.util.UUID/fromString (:id donor)) (:actor fixture/*context*)])
    (let [result (:body (analyse batch policy))]
      (is (= ["payment-ambiguity"] (mapv :reason (:exceptions result))))
      (is (= 0 (get-in result [:counts :duplicates])))
      (is (= 1 (calls/count-of "donations"))))))

(deftest conflicting-incoming-identities-are-not-created-as-two-new-donors
  (let [batch (calls/batch (str "2026-11-01;Helena Nowicka, ul. Brzozowa 18, 30-001 Kraków;Darowizna;20\n"
                                "2026-11-02;Helena Nowicka, ul. Ogrodowa 22, 30-001 Kraków;Darowizna;30\n"))
        result (:body (analyse batch policy))]
    (is (= 2 (get-in result [:counts :exceptions])))
    (is (= 0 (calls/count-of "import_donor_proposals"))))
  (let [batch (calls/batch (str "2026-11-01;Zofia Kamińska, ul. Lipowa 18, 30-001 Kraków;Darowizna;20\n"
                                "2026-11-02;Zofia Kamińska, ul. Lipowa 18, 30-001 Kraków;Darowizna;30\n"))
        result (:body (analyse batch policy))]
    (is (= 1 (get-in result [:counts :new-donors])))
    (is (= 2 (get-in result [:counts :new-payments])))
    (is (:ready result))))

(deftest invalid-and-negative-identities-cannot-bypass-source-validation
  (let [donor (donor! "Jan Kowalski" "ul. Lipowa 1")]
    (doseq [line ["2026-02-30;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Darowizna;0\n"
                  "2026-11-01;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Zwrot;-25\n"]]
      (let [batch (calls/batch line) result (:body (analyse batch policy)) before (counts)
            selected (calls/call :post (calls/path batch "/reconciliation/identity")
                                 {:batch-version (get-in result [:batch :version]) :catalog-version (:catalog-version result)
                                  :worksheet 1 :row 1 :kind "existing-donor" :target-id (:id donor) :target-version (:version donor)})]
        (is (= 409 (:status selected)))
        (is (false? (:ready result)))
        (is (= before (counts)))))
    (let [batch (calls/batch "2026-11-02;Jan Kowalski, ul. Lipowa 1, 32-020 Wieliczka;Zwrot;-30\n")
          saved (calls/save batch (calls/input batch donor))
          report (:body (calls/call :get (url batch) nil))]
      (is (= 200 (:status saved)))
      (is (false? (:ready report)))
      (is (= ["unsupported-amount"] (mapv :reason (:exceptions report)))))))
