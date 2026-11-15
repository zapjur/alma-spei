(ns alma-spei.integration.bank-upload-test
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.http.auth :as auth-http]
            [alma-spei.api.http.imports :as http]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.api.imports.adapters.bank :as bank]
            [alma-spei.api.imports.limits :as limits]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.api.imports.service :as service]
            [alma-spei.api.system :as system]
            [alma-spei.dev.bank-fixtures :as fixture]
            [alma-spei.integration.database-test :as database]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.util UUID]))

(def ^:dynamic *context* nil)
(def boundary "synthetic-boundary-35")
(defn ready [f]
  (database/isolated-database
   (fn []
     (let [ds (:runtime database/*database*) actor (UUID/randomUUID)
           settings {:csrf-secret "synthetic-bank-test-key" :origin "http://127.0.0.1:3000" :secure-cookie false}]
       (migratus/migrate (migrations/migration-config (:migrator database/*database*)))
       (repository/query ds ["INSERT INTO users(id,username,display_name) VALUES (?,'synthetic.bank','Synthetic Bank Staff')" actor])
       (let [csrf (auth-http/signed-csrf (:csrf-secret settings))
             session (jdbc/with-transaction [tx ds] (auth/issue-session! tx (auth/user-row tx actor) csrf))]
         (binding [*context* {:ds ds :actor actor :token (:token session) :csrf csrf :settings settings
                              :handler (routes/handler ds 2 settings)}]
           (f)))))))
(use-fixtures :each ready)

(defn multipart [filename bytes fields]
  (let [out (ByteArrayOutputStream.)
        write! (fn [text] (.write out (.getBytes ^String text "UTF-8")))]
    (doseq [[name value] fields]
      (write! (str "--" boundary "\r\nContent-Disposition: form-data; name=\"" name "\"\r\n\r\n" value "\r\n")))
    (write! (str "--" boundary "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\nContent-Type: application/octet-stream\r\n\r\n"))
    (.write out ^bytes bytes)
    (write! (str "\r\n--" boundary "--\r\n"))
    (.toByteArray out)))
(defn headers []
  {"accept" "application/json" "content-type" (str "multipart/form-data; boundary=" boundary)
   "origin" (get-in *context* [:settings :origin]) "x-csrf-token" (:csrf *context*)
   "cookie" (str "alma-session=" (:token *context*) "; alma-csrf=" (:csrf *context*))})
(defn upload
  ([] (upload "synthetic.csv" (.getBytes fixture/sample-csv "UTF-8") {"source" "bank" "separator" ";"} {}))
  ([filename bytes fields overrides]
   (let [request (merge {:request-method :post :uri "/api/imports/upload" :headers (headers)
                         :body (ByteArrayInputStream. (multipart filename bytes fields))} overrides)
         response ((:handler *context*) request)]
     (update response :body #(muuntaja/decode "application/json" %)))))
(defn page [id query]
  (let [response ((:handler *context*) {:request-method :get :uri (str "/api/imports/" id "/rows")
                                        :query-string query :headers (headers)})]
    (update response :body #(muuntaja/decode "application/json" %))))
(defn count-of [table] (:n (repository/one (:ds *context*) [(str "SELECT count(*) AS n FROM " table)])))
(defn canonical-counts [] (mapv count-of ["donors" "donations" "donor_audit_events" "donor_audit_changes"]))

(deftest successful-csv-and-xlsx-with-complete-paged-report
  (let [csv (upload) xlsx (upload "synthetic.xlsx" (fixture/zip-bytes (fixture/entries)) {"source" "bank"} {})
        batch (get-in csv [:body :batch]) id (:id batch)]
    (is (= 201 (:status csv)))
    (is (= 201 (:status xlsx)))
    (is (= "review" (:state batch)))
    (is (= 2 (:version batch)))
    (is (= 2 (:total-rows batch)))
    (is (= 0 (:reviewed-rows batch)))
    (is (= (str (:actor *context*)) (:created-by batch)))
    (is (= [2 3] (mapv :row-number (get-in xlsx [:body :rows]))))
    (is (= ["12.3400" "-2.50"] (mapv #(get-in % [:payload :amount]) (get-in csv [:body :rows]))))
    (is (= 1 (count (get-in (page id "limit=1&offset=1") [:body :rows]))))
    (is (= [] (get-in (page id "offset=100") [:body :rows])))
    (doseq [query ["limit=101" "offset=-1" "limit=1&limit=2" "unknown=true"]]
      (is (= 400 (:status (page id query)))))
    (is (= "no-store" (get-in csv [:headers "Cache-Control"])))
    (is (= [0 0 0 0] (canonical-counts)))))

(deftest invalid-rows-remain-visible-and-no-header-is-invented
  (let [response (upload "invalid.csv" (.getBytes "2026-01-02;Synthetic;;12.00\n2026-02-30;;;0\n\n" "UTF-8")
                         {"source" "bank" "separator" ";"} {})
        rows (get-in response [:body :rows])]
    (is (= 201 (:status response)))
    (is (= 1 (get-in response [:body :skipped-empty-rows])))
    (is (= 1 (get-in response [:body :batch :invalid-rows])))
    (is (= [1 2] (mapv :row-number rows)))
    (is (= #{"missing-sender" "invalid-date" "invalid-amount"} (set (map :code (:errors (second rows))))))
    (is (= 2 (count-of "staged_rows")))
    (is (= [0 0 0 0] (canonical-counts)))))

(deftest authentication-csrf-and-busy-deny-before-reading
  (let [reads (atom 0)
        body (proxy [InputStream] [] (read [] (swap! reads inc) -1))
        call (fn [h] (upload "synthetic.csv" (byte-array 0) {} {:headers h :body body}))]
    (is (= 403 (:status (call (dissoc (headers) "x-csrf-token")))))
    (is (= 403 (:status (call (assoc (headers) "origin" "http://other.invalid")))))
    (is (= 401 (:status (call (assoc (headers) "cookie" (str "alma-csrf=" (:csrf *context*)))))))
    (let [csrf (auth-http/signed-csrf (get-in *context* [:settings :csrf-secret]))]
      (is (= 403 (:status (call (assoc (headers) "x-csrf-token" csrf "cookie" (str "alma-session=" (:token *context*) "; alma-csrf=" csrf)))))))
    (.acquire http/parser-permit)
    (try (is (= 503 (:status (call (headers))))) (finally (.release http/parser-permit)))
    (is (= 0 @reads))
    (repository/query (:ds *context*) ["UPDATE sessions SET expires_at=now()-interval '1 second'"])
    (is (= 401 (:status (call (headers)))))
    (is (= 0 @reads))
    (is (= 0 (count-of "import_files"))))
  (is (= 401 (:status (page (str (UUID/randomUUID)) nil)))))

(deftest measured-size-and-format-limits-do-not-trust-content-length
  (with-redefs [limits/body-bytes 128]
    (doseq [length [nil "1"]]
      (is (= 413 (:status (upload "synthetic.csv" (.getBytes fixture/sample-csv "UTF-8") {"source" "bank" "separator" ";"}
                                  {:headers (cond-> (headers) length (assoc "content-length" length))}))))))
  (with-redefs [limits/file-bytes 10]
    (is (= 413 (:status (upload)))))
  (is (= 415 (:status (upload "synthetic.xls" (byte-array [1]) {"source" "bank"} {}))))
  (is (= 400 (:status (upload "synthetic.csv" (byte-array [1]) {"source" "bank" "separator" ";" "extra" "private"} {}))))
  (is (= 400 (:status (upload "synthetic.csv" (byte-array [1]) {"source" "tax"} {}))))
  (is (= 0 (count-of "import_files"))))

(deftest duplicates-are-rejected-before-parser-and-retries-require-original-bytes
  (let [bytes (.getBytes fixture/sample-csv "UTF-8")
        failed (with-redefs [bank/parse! (fn [& _] (limits/reject! "parse-failed"))] (upload))
        batch (repository/one (:ds *context*) ["SELECT * FROM import_batches"])
        fields {"source" "bank" "separator" ";" "retry-batch-id" (str (:id batch)) "version" "2" "confirmed" "true"}]
    (is (= 400 (:status failed)))
    (is (= (str (:id batch)) (get-in failed [:body :batch-id])))
    (is (= "failed" (get-in failed [:body :batch :state])))
    (is (= 2 (get-in failed [:body :batch :version])))
    (is (= "failed" (:state batch)))
    (with-redefs [bank/parse! (fn [& _] (throw (Exception. "Synthetic parser must not run")))]
      (is (= 409 (:status (upload "renamed.csv" bytes {"source" "bank" "separator" ";"} {})))))
    (is (= 409 (:status (upload "synthetic.csv" (.getBytes "different bytes" "UTF-8") fields {}))))
    (is (= 1 (count-of "import_batches")))
    (let [response (upload "renamed.csv" bytes fields {})]
      (is (= 201 (:status response)))
      (is (= "synthetic.csv" (get-in response [:body :batch :original-filename])))
      (is (= 2 (get-in response [:body :batch :attempt-number])))
      (is (= 2 (count-of "import_batches"))))
    (is (= 409 (:status (upload "synthetic.csv" bytes fields {}))))
    (is (= [0 0 0 0] (canonical-counts)))))

(deftest staging-is-atomic-and-failure-bookkeeping-survives-session-revocation
  (let [original repository/stage-row! written (atom 0)]
    (with-redefs [repository/stage-row! (fn [& args]
                                          (if (= 2 (swap! written inc)) (throw (Exception. "Synthetic insert fault")) (apply original args)))]
      (is (= 500 (:status (upload)))))
    (is (= 0 (count-of "staged_rows")))
    (is (= "failed" (:state (repository/one (:ds *context*) ["SELECT state FROM import_batches"])))))
  (let [original bank/parse!]
    (with-redefs [bank/parse! (fn [& args]
                                (repository/query (:ds *context*) ["UPDATE sessions SET expires_at=now()-interval '1 second'"])
                                (apply original args))]
      (is (= 401 (:status (upload "revoked.csv" (.getBytes (str fixture/sample-csv "\n") "UTF-8") {"source" "bank" "separator" ";"} {})))))
    (is (= 0 (count-of "staged_rows")))
    (is (= 2 (:n (repository/one (:ds *context*) ["SELECT count(*) AS n FROM import_batches WHERE state='failed'"])))))
  (is (= [0 0 0 0] (canonical-counts))))

(deftest startup-recovers-interrupted-reservations-with-audit
  (let [measured {:file-hash-sha256 (apply str (repeat 64 "a")) :byte-count 10}
        result (service/reserve! (:ds *context*) (:token *context*) {:source-type "bank" :original-filename "interrupted.csv"} measured)]
    (service/recover-interrupted! (:ds *context*))
    (service/recover-interrupted! (:ds *context*))
    (let [batch (repository/batch-row (:ds *context*) (UUID/fromString (get-in result [:batch :id])))]
      (is (= "failed" (:state batch)))
      (is (= "processing-interrupted" (:failure-code batch)))
      (is (= 2 (:version batch)))
      (is (= 2 (count-of "import_batch_events"))))))

(deftest real-http-multipart-upload
  (let [port (database/free-port)
        running (system/start! {:environment :test :database (:settings database/*database*)
                                :http {:host "127.0.0.1" :port port} :auth (:settings *context*)})]
    (try
      (let [bytes (multipart "synthetic.csv" (.getBytes fixture/sample-csv "UTF-8") {"source" "bank" "separator" ";"})
            builder (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api/imports/upload")))
            _ (doseq [[key value] (headers)] (.header builder key value))
            request (-> builder (.POST (HttpRequest$BodyPublishers/ofByteArray bytes)) .build)
            response (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]
        (is (= 201 (.statusCode response)))
        (is (= 2 (get-in (muuntaja/decode "application/json" (.getBytes (.body response) "UTF-8")) [:batch :valid-rows])))
        (is (= [0 0 0 0] (canonical-counts))))
      (finally (system/stop! running)))))

(deftest temporary-files-are-private-and-cleaned-after-success-and-failure
  (let [original http/create-upload-directory paths (atom [])]
    (with-redefs [http/create-upload-directory
                  (fn []
                    (let [path (original)]
                      (swap! paths conj path)
                      (is (= "rwx------" (java.nio.file.attribute.PosixFilePermissions/toString
                                          (java.nio.file.Files/getPosixFilePermissions path (make-array java.nio.file.LinkOption 0)))))
                      path))]
      (is (= 201 (:status (upload))))
      (is (= 409 (:status (upload))))
      (is (= 400 (:status (upload "malformed.xlsx" (.getBytes "synthetic malformed" "UTF-8") {"source" "bank"} {}))))
      (with-redefs [limits/body-bytes 100] (is (= 413 (:status (upload))))))
    (is (= 4 (count @paths)))
    (is (every? #(java.nio.file.Files/notExists % (make-array java.nio.file.LinkOption 0)) @paths))))

(deftest trailing-body-bytes-cannot-bypass-request-budget
  (let [content (multipart "synthetic.csv" (.getBytes fixture/sample-csv "UTF-8") {"source" "bank" "separator" ";"})
        bytes (byte-array (+ (alength content) 1024))]
    (System/arraycopy content 0 bytes 0 (alength content))
    (with-redefs [limits/body-bytes (inc (alength content))]
      (is (= 413 (:status (upload "ignored.csv" (byte-array 0) {} {:body (ByteArrayInputStream. bytes)})))))
    (is (= 0 (count-of "import_files")))))
