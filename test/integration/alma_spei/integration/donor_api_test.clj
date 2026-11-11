(ns alma-spei.integration.donor-api-test
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.auth.service :as auth]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.donors.repository :as repository]
            [alma-spei.api.donors.service :as service]
            [alma-spei.api.http.auth :as auth-http]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.api.system :as system]
            [alma-spei.integration.database-test :as fixture]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc]
            [ring.util.codec :as codec])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time LocalDate]
           [java.util UUID]))

(def ^:dynamic *context* nil)
(def profile {:entity-type "individual" :name "Synthetic Żółć" :city "Synthetic City"})

(defn ready [f]
  (fixture/isolated-database
   (fn []
     (let [ds (:runtime fixture/*database*)
           actor (UUID/randomUUID)
           settings {:csrf-secret "synthetic-donor-test-signing-key" :origin "http://127.0.0.1:3000" :secure-cookie false}]
       (migratus/migrate (migrations/migration-config (:migrator fixture/*database*)))
       (repository/query ds ["INSERT INTO users(id,username,display_name) VALUES (?,'synthetic.donor','Synthetic Donor Staff')" actor])
       (let [csrf (auth-http/signed-csrf (:csrf-secret settings))
             session (jdbc/with-transaction [tx ds] (auth/issue-session! tx (auth/user-row tx actor) csrf))]
         (binding [*context* {:ds ds :actor actor :token (:token session) :csrf csrf :settings settings
                              :handler (routes/handler ds 2 settings)}]
           (f)))))))

(use-fixtures :each ready)

(defn call
  ([method path] (call method path nil {}))
  ([method path input] (call method path input {}))
  ([method path input overrides]
   (let [{:keys [handler settings csrf token]} *context*
         [uri query] (str/split path #"\?" 2)
         request (merge {:request-method method :uri uri :query-string query
                         :headers {"accept" "application/json" "content-type" "application/json"
                                   "origin" (:origin settings) "x-csrf-token" csrf
                                   "cookie" (str "alma-session=" token "; alma-csrf=" csrf)}} overrides)
         response (handler (cond-> request input (assoc :body (muuntaja/encode "application/json" input))))]
     (update response :body #(muuntaja/decode "application/json" %)))))

(defn create! [input]
  (let [response (call :post "/api/donors" input)]
    (is (= 201 (:status response)))
    (get-in response [:body :donor])))

(defn donor-path [donor] (str "/api/donors/" (:id donor)))

(deftest staff-crud-and-field-history
  (let [donor (create! (assoc profile :street "Synthetic Street" :postal-code "00-001"))
        path (donor-path donor)
        detail (call :get path)
        updated (call :put path (assoc profile :name "Synthetic Changed" :version 1))
        history (call :get (str path "/history"))
        events (get-in history [:body :events])
        changes (mapcat :changes events)]
    (is (= 200 (:status detail)))
    (is (= [] (get-in detail [:body :donations])))
    (is (= 0 (get-in detail [:body :donations-total])))
    (is (= 200 (:status updated)))
    (is (= 2 (get-in updated [:body :donor :version])))
    (is (nil? (get-in updated [:body :donor :street])))
    (is (= 200 (:status history)))
    (is (= 2 (get-in history [:body :total])))
    (is (= #{"create" "update"} (set (map :action events))))
    (is (every? #(= (str (:actor *context*)) (:actor-id %)) events))
    (is (some #(= {:field "name" :before "Synthetic Żółć" :after "Synthetic Changed"} %) changes))
    (is (some #(= {:field "street" :before "Synthetic Street" :after nil} %) changes))
    (is (not (contains? (get-in detail [:body :donor]) :created-by)))
    (is (= "no-store" (get-in history [:headers "Cache-Control"])))
    (is (= 1 (count (get-in (call :get (str path "/history?limit=1&offset=1")) [:body :events]))))))

(deftest validation-and-missing-resources
  (is (= 400 (:status (call :post "/api/donors" nil
                            {:body (java.io.ByteArrayInputStream. (.getBytes "{invalid-json" "UTF-8"))}))))
  (doseq [input [(dissoc profile :name) (assoc profile :postal-code "SYNTHETIC-REJECTED")
                 (assoc profile :created-by (str (:actor *context*))) (assoc profile :is-active false)]]
    (let [response (call :post "/api/donors" input)]
      (is (= 400 (:status response)))
      (is (= "validation" (get-in response [:body :code])))
      (is (seq (get-in response [:body :errors])))
      (is (not (str/includes? (str (:body response)) "SYNTHETIC-REJECTED")))))
  (is (= 0 (:total (repository/one (:ds *context*) ["SELECT count(*) AS total FROM donors"]))))
  (is (= 0 (:total (repository/one (:ds *context*) ["SELECT count(*) AS total FROM donor_audit_events"]))))
  (doseq [path ["/api/donors/invalid" (str "/api/donors/" (UUID/randomUUID))]]
    (is (= 404 (:status (call :get path))))
    (is (= 404 (:status (call :get (str path "/history"))))))
  (doseq [query ["limit=0" "limit=101" "limit=999999999999999999999" "offset=-1" "limit=2&limit=3"
                 "sort-by=password_hash" "sort-direction=wrong" "status=deleted" "unknown=true"]]
    (is (= 400 (:status (call :get (str "/api/donors?" query)))))))

(deftest literal-search-filter-sort-and-pagination
  (let [a (create! profile)
        b (create! (assoc profile :name "Synthetic 100%" :entity-type "business"))
        c (create! (assoc profile :name "Synthetic Organization" :entity-type "organization"))
        path "/api/donors"]
    (repository/query (:ds *context*) ["UPDATE donors SET is_active=false WHERE id=?" (UUID/fromString (:id c))])
    (is (= 2 (get-in (call :get path) [:body :total])))
    (is (= 3 (get-in (call :get (str path "?status=all")) [:body :total])))
    (is (= [(:id c)] (mapv :id (get-in (call :get (str path "?status=archived")) [:body :donors]))))
    (is (= [(:id b)] (mapv :id (get-in (call :get (str path "?entity-type=business")) [:body :donors]))))
    (is (= [(:id a)] (mapv :id (get-in (call :get (str path "?search=" (codec/url-encode "żółć"))) [:body :donors]))))
    (is (= [(:id b)] (mapv :id (get-in (call :get (str path "?search=%25")) [:body :donors]))))
    (is (= 0 (get-in (call :get (str path "?search=" (codec/url-encode "' OR true --"))) [:body :total])))
    (let [asc (get-in (call :get path) [:body :donors])
          desc (get-in (call :get (str path "?sort-direction=desc")) [:body :donors])
          page (:body (call :get (str path "?limit=1&offset=1")))]
      (is (= (reverse (map :id asc)) (map :id desc)))
      (is (= 2 (:total page)))
      (is (= [(:id (second asc))] (mapv :id (:donors page)))))
    (is (= [] (get-in (call :get (str path "?offset=100")) [:body :donors])))))

(deftest authorization-and-session-bound-csrf
  (let [headers {"accept" "application/json" "content-type" "application/json"}
        csrf (:csrf *context*)
        token (:token *context*)
        signed-other (auth-http/signed-csrf (get-in *context* [:settings :csrf-secret]))]
    (doseq [path ["/api/donors" "/api/donors/00000000-0000-0000-0000-000000000001"
                  "/api/donors/00000000-0000-0000-0000-000000000001/history"]]
      (is (= 401 (:status (call :get path nil {:headers headers})))))
    (is (= 403 (:status (call :post "/api/donors" profile {:headers headers}))))
    (is (= 401 (:status (call :post "/api/donors" profile
                              {:headers (assoc headers "origin" (get-in *context* [:settings :origin])
                                               "x-csrf-token" csrf "cookie" (str "alma-csrf=" csrf))}))))
    (is (= 403 (:status (call :post "/api/donors" profile
                              {:headers (assoc headers "origin" "http://other" "x-csrf-token" csrf
                                               "cookie" (str "alma-session=" token "; alma-csrf=" csrf))}))))
    (is (= 403 (:status (call :post "/api/donors" profile
                              {:headers (assoc headers "origin" (get-in *context* [:settings :origin])
                                               "x-csrf-token" signed-other
                                               "cookie" (str "alma-session=" token "; alma-csrf=" signed-other))}))))
    (repository/query (:ds *context*) ["INSERT INTO local_credentials(user_id,password_hash,password_change_required) VALUES (?,'synthetic-unused-hash',true)" (:actor *context*)])
    (is (= 403 (:status (call :get "/api/donors"))))
    (repository/query (:ds *context*) ["UPDATE local_credentials SET password_change_required=false"])
    (repository/query (:ds *context*) ["UPDATE sessions SET expires_at=now()-interval '1 second'"])
    (is (= 401 (:status (call :get "/api/donors"))))))

(deftest stale-edits-concurrency-and-audit-failure
  (let [donor (create! profile)
        path (donor-path donor)
        gate (promise)
        ds (:ds *context*)
        token (:token *context*)
        csrf (:csrf *context*)
        edit (fn [name]
               (future @gate
                       (try (service/execute! ds token :update (:id donor) (assoc profile :version 1 :name name) csrf)
                            200 (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
        a (edit "Synthetic Concurrent A")
        b (edit "Synthetic Concurrent B")]
    (deliver gate true)
    (is (= #{200 409} #{@a @b}))
    (is (= 409 (:status (call :put path (assoc profile :version 1)))))
    (let [current (get-in (call :get path) [:body :donor])
          input (assoc (select-keys current repository/profile-fields) :version (:version current))]
      (is (= 200 (:status (call :put path input))))
      (is (= 2 (get-in (call :get (str path "/history")) [:body :total])))
      (with-redefs [audit/record! (fn [& _] (throw (ex-info "Synthetic audit failure" {})))]
        (is (= 500 (:status (call :put path (assoc input :name "Synthetic Must Roll Back")))))
        (is (= 500 (:status (call :post "/api/donors" (assoc profile :name "Synthetic Must Not Exist"))))))
      (is (= current (get-in (call :get path) [:body :donor])))
      (is (= 1 (get-in (call :get "/api/donors") [:body :total])))
      (is (= 2 (get-in (call :get (str path "/history")) [:body :total]))))))

(deftest paginated-exact-payment-read-and-real-http
  (let [donor (create! profile)
        path (donor-path donor)
        ds (:ds *context*)
        id (UUID/fromString (:id donor))]
    (dotimes [_ 2]
      (repository/query ds ["INSERT INTO donations(id,donor_id,amount,currency,received_on,created_by) VALUES (?,?,?,'PLN',?,?)"
                            (UUID/randomUUID) id 9999999999999999.9999M (LocalDate/of 2026 1 2) (:actor *context*)]))
    (let [response (call :get (str path "?limit=1"))]
      (is (= 200 (:status response)))
      (is (= 2 (get-in response [:body :donations-total])))
      (is (= 1 (count (get-in response [:body :donations]))))
      (is (= "9999999999999999.9999" (get-in response [:body :donations 0 :amount])))
      (is (= "2026-01-02" (get-in response [:body :donations 0 :received-on]))))
    (let [port (fixture/free-port)
          running (system/start! {:environment :test :database (:settings fixture/*database*)
                                  :http {:host "127.0.0.1" :port port} :auth (:settings *context*)})]
      (try
        (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                          (.header "Cookie" (str "alma-session=" (:token *context*)))
                          (.header "Accept" "application/json") .GET .build)
              response (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]
          (is (= 200 (.statusCode response)))
          (is (= (:id donor) (get-in (muuntaja/decode "application/json" (.getBytes (.body response) "UTF-8")) [:donor :id]))))
        (finally (system/stop! running))))))
