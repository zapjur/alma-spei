(ns alma-spei.integration.review-test
  (:require [alma-spei.integration.bank-upload-test :as upload]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.imports.service :as imports]
            [alma-spei.api.imports.review :as review]
            [alma-spei.api.donors.service :as donors]
            [alma-spei.common.contracts.review :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.test :refer [deftest is use-fixtures]]
            [muuntaja.core :as muuntaja])
  (:import [java.util UUID]))

(use-fixtures :each upload/ready)
(defn ds [] (:ds upload/*context*))
(defn call
  ([path query] (call path query (upload/headers)))
  ([path query headers]
   (let [response ((:handler upload/*context*) {:request-method :get :uri path :query-string query :headers headers})]
     (update response :body #(muuntaja/decode "application/json" %)))))
(defn create-donor [name active?]
  (let [donor (:donor (donors/execute! (ds) (:token upload/*context*) :create nil
                                       {:entity-type "individual" :name name :city "Synthetic City" :street "Synthetic Shared 1"}
                                       (:csrf upload/*context*)))]
    (when-not active? (repository/query (ds) ["UPDATE donors SET is_active=false WHERE id=?" (UUID/fromString (:id donor))]))
    donor))
(defn comparison-path [id worksheet row] (str "/api/imports/" id "/rows/" worksheet "/" row "/comparison"))
(defn batch-id [] (get-in (upload/upload) [:body :batch :id]))
(defn snapshot []
  (mapv #(repository/query (ds) [(str "SELECT * FROM " %)])
        ["donors" "donations" "donor_audit_events" "donor_audit_changes" "import_batches" "staged_rows" "import_batch_events"]))

(deftest queue-and-batch-reads-are-authorized-bounded-and-persisted
  (let [id (batch-id) first-read (call "/api/imports/review" "limit=1&offset=0")
        before (snapshot)]
    (is (= 200 (:status first-read)))
    (is (validation/valid? contract/queue-response (:body first-read)))
    (is (= [id] (mapv :id (get-in first-read [:body :batches]))))
    (is (= 0 (get-in first-read [:body :batches 0 :reviewed-rows])))
    (is (= [] (get-in (call "/api/imports/review" "offset=25") [:body :batches])))
    (is (= id (get-in (call (str "/api/imports/" id) nil) [:body :batch :id])))
    (is (= before (snapshot)))
    ;; Represents authoritative saved progress from the later decision service,
    ;; not a side effect of browsing this read-only task.
    (repository/query (ds) ["UPDATE import_batches SET reviewed_rows=1,version=version+1 WHERE id=?" (UUID/fromString id)])
    (is (= 1 (get-in (call (str "/api/imports/" id) nil) [:body :batch :reviewed-rows])))
    (is (= 3 (get-in (call "/api/imports/review" nil) [:body :batches 0 :version])))
    (doseq [query ["limit=0" "limit=101" "offset=-1" "limit=1&limit=2" "source-type=tax" "order=id"]]
      (is (= 400 (:status (call "/api/imports/review" query)))))
    (is (= 400 (:status (call (str "/api/imports/" id) "limit=1"))))
    (is (= "no-store" (get-in first-read [:headers "Cache-Control"])))
    (is (= 401 (:status (call "/api/imports/review" nil {"accept" "application/json"}))))))

(deftest manual-comparison-preserves-distinct-profiles-and-never-marks-reviewed
  (let [id (batch-id) a (create-donor "Synthetic Same Name" true) b (create-donor "Synthetic Same Name" false)
        before (snapshot)]
    (doseq [donor [a b]]
      (let [response (call (comparison-path id 1 1) (str "donor-id=" (:id donor))) body (:body response)]
        (is (= 200 (:status response)))
        (is (validation/valid? contract/comparison-response body))
        (is (= (:id donor) (get-in body [:donor :id])))
        (is (= "Synthetic Shared 1" (get-in body [:donor :street])))
        (is (= "Synthetic Sender" (get-in body [:row :payload :sender])))
        (is (nil? (get-in body [:row :payload :currency])))
        (is (= 0 (get-in body [:batch :reviewed-rows])))
        (is (not (contains? body :donations)))
        (is (not (contains? (:donor body) :created-by)))))
    (is (= before (snapshot)))
    (is (not= (:id a) (:id b)))
    (is (= 2 (get-in (call "/api/donors" "search=Synthetic%20Same&status=all") [:body :total])))))

(deftest invalid-rows-remain-comparable-and-source-locators-are-exact
  (let [response (upload/upload "invalid.csv" (.getBytes "2026-02-30;Synthetic Sender;Synthetic description;12.34.56\n" "UTF-8")
                                {"source" "bank" "separator" ";"} {})
        id (get-in response [:body :batch :id]) donor (create-donor "Synthetic Candidate" true)
        comparison (call (comparison-path id 1 1) (str "donor-id=" (:id donor)))]
    (is (= 200 (:status comparison)))
    (is (= "invalid" (get-in comparison [:body :row :validation-state])))
    (is (= "12.34.56" (get-in comparison [:body :row :payload :source-amount])))
    (is (= #{"invalid-date" "invalid-amount"} (set (map :code (get-in comparison [:body :row :errors])))))
    (is (= 404 (:status (call (comparison-path id 1 2) (str "donor-id=" (:id donor))))))
    (is (= 404 (:status (call (comparison-path id 2 1) (str "donor-id=" (:id donor))))))))

(deftest unavailable-batches-validation-and-session-denials
  (let [id (batch-id) donor (create-donor "Synthetic Candidate" true) path (comparison-path id 1 1)
        query (str "donor-id=" (:id donor))]
    (doseq [q [nil "donor-id=bad" (str query "&extra=private") (str query "&donor-id=" (:id donor))]]
      (is (= 400 (:status (call path q)))))
    (is (= 400 (:status (call (comparison-path id "bad" 1) query))))
    (is (= 404 (:status (call (comparison-path id 1 1) (str "donor-id=" (UUID/randomUUID))))))
    (doseq [missing ["bad" (str (UUID/randomUUID))]]
      (is (= 404 (:status (call (str "/api/imports/" missing) nil)))))
    (repository/query (ds) ["UPDATE import_batches SET state='cancelled' WHERE id=?" (UUID/fromString id)])
    (is (= [] (get-in (call "/api/imports/review" nil) [:body :batches])))
    (is (= "cancelled" (get-in (call (str "/api/imports/" id) nil) [:body :batch :state])))
    (is (= 409 (:status (call path query))))
    (is (= 401 (:status (call path query {"accept" "application/json"}))))
    (repository/query (ds) ["UPDATE users SET active=false WHERE id=?" (:actor upload/*context*)])
    (is (= 401 (:status (call "/api/imports/review" nil))))
    (is (= 401 (:status (call path query))))))

(deftest other-sources-and-incomplete-attempts-are-not-in-the-bank-review-queue
  (let [id (batch-id)]
    (imports/reserve! (ds) (:token upload/*context*) {:source-type "bank" :original-filename "reserved.csv"}
                      {:file-hash-sha256 (apply str (repeat 64 "a")) :byte-count 1})
    (let [other (:batch (imports/reserve! (ds) (:token upload/*context*) {:source-type "tax" :original-filename "synthetic-tax.csv"}
                                          {:file-hash-sha256 (apply str (repeat 64 "b")) :byte-count 1}))]
      (is (= 404 (:status (call (str "/api/imports/" (:id other)) nil)))))
    (is (= [id] (mapv :id (get-in (call "/api/imports/review" nil) [:body :batches]))))))

(deftest read-snapshots-do-not-serialize-concurrent-session-activity
  (let [id (batch-id) ds (ds) token (:token upload/*context*) gate (promise)
        read (fn [] (future @gate (review/execute! ds token :batch id nil {})))
        a (read) b (read)]
    (deliver gate true)
    (is (= id (get-in @a [:batch :id])))
    (is (= id (get-in @b [:batch :id])))))
