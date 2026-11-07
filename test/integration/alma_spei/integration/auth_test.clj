(ns alma-spei.integration.auth-test
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as auth]
            [alma-spei.api.db.migrations :as migrations]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.integration.database-test :as fixture]
            [clojure.test :refer [deftest is use-fixtures]]
            [migratus.core :as migratus]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn migrated [f]
  (fixture/isolated-database
   #(do (migratus/migrate (migrations/migration-config (:migrator fixture/*database*))) (f))))
(use-fixtures :each migrated)
(defn ds [] (:runtime fixture/*database*))
(defn id [u] (UUID/fromString (:id u)))
(defn bootstrap [] (auth/bootstrap! (ds) {:username "Admin.One" :display-name "Synthetic Admin"}))
(defn login [username password]
  (auth/login! (ds) {:username username :password password} "synthetic-peer" "synthetic-csrf" nil))
(defn active-admin []
  (let [created (bootstrap)
        session (login "ADMIN.ONE" (:temporary-password created))]
    (auth/change-password! (ds) (:token session)
                           {:current-password (:temporary-password created) :password "Synthetic-New9!"} "synthetic-csrf")))
(defn status-of [f]
  (try (f) 200 (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(deftest bootstrap-first-use-and-identity
  (let [created (bootstrap)
        session (login "ADMIN.ONE" (:temporary-password created))]
    (is (:password-change-required (:user session)))
    (is (= 409 (status-of bootstrap)))
    (is (= 403 (status-of #(auth/accounts! (ds) (:token session) :list nil nil))))
    (is (= (:id (:user created)) (:id (:user session))))
    (is (= 1 (count (auth/query (ds) ["SELECT * FROM external_identities"])))))
  (is (thrown? java.sql.SQLException (auth/query (ds) ["DELETE FROM security_audit_events"]))))

(deftest permissions-and-last-administrator
  (let [admin (active-admin)
        token (:token admin)
        u (:user admin)
        staff (auth/accounts! (ds) token :create nil {:username "Staff.One" :display-name "Synthetic Staff" :administrator false})
        signed (login "staff.one" (:temporary-password staff))
        staff-session (auth/change-password! (ds) (:token signed)
                                             {:current-password (:temporary-password staff) :password "Synthetic-Staff9!"} "synthetic-csrf")]
    (is (= 401 (status-of #(auth/accounts! (ds) nil :list nil nil))))
    (is (= 403 (status-of #(auth/accounts! (ds) (:token staff-session) :list nil nil))))
    (is (= 409 (status-of #(auth/accounts! (ds) token :edit (id u)
                                           {:display-name "Synthetic Admin" :active false :administrator true}))))
    (is (= 409 (status-of #(auth/accounts! (ds) token :create nil
                                           {:username "STAFF.ONE" :display-name "Duplicate" :administrator false}))))
    (is (= 422 (status-of #(auth/accounts! (ds) token :edit (id u)
                                           {:username "changed" :display-name "Synthetic Admin" :active true :administrator true}))))
    (is (= 2 (count (:users (auth/accounts! (ds) token :list nil nil)))))))

(deftest rotation-reset-deactivation-and-expiry
  (let [admin (active-admin)
        other (login "admin.one" "Synthetic-New9!")
        changed (auth/change-password! (ds) (:token admin)
                                       {:current-password "Synthetic-New9!" :password "Synthetic-Next9!"} "synthetic-csrf")
        token (:token changed)
        staff (auth/accounts! (ds) token :create nil {:username "staff.one" :display-name "Synthetic Staff" :administrator false})
        staff-token (:token (login "staff.one" (:temporary-password staff)))]
    (is (nil? (auth/inspect-session (ds) (:token other))))
    (is (nil? (auth/inspect-session (ds) (:token admin))))
    (auth/accounts! (ds) token :reset (id (:user staff)) nil)
    (is (nil? (auth/inspect-session (ds) staff-token)))
    (auth/accounts! (ds) token :edit (id (:user staff)) {:display-name "Synthetic Staff" :active false :administrator false})
    (is (= 401 (:error (login "staff.one" (:temporary-password staff)))))
    (auth/query (ds) ["UPDATE sessions SET last_activity=now()-interval '31 minutes' WHERE token_digest=?" (crypto/digest token)])
    (is (nil? (auth/inspect-session (ds) token)))
    (let [fresh (login "admin.one" "Synthetic-Next9!")]
      (auth/query (ds) ["UPDATE sessions SET expires_at=now()-interval '1 second' WHERE token_digest=?" (crypto/digest (:token fresh))])
      (is (nil? (auth/inspect-session (ds) (:token fresh)))))))

(deftest throttling-and-no-secret-audit
  (bootstrap)
  (dotimes [_ 5] (is (= 401 (:error (login "missing" "Incorrect9!")))))
  (is (= 429 (:error (login "missing" "Incorrect9!"))))
  (let [audit (str (auth/query (ds) ["SELECT * FROM security_audit_events"]))]
    (is (not (.contains audit "Incorrect9!")))
    (is (not (.contains audit "password_hash")))))

(deftest audit-failure-rolls-back
  (let [admin (active-admin)]
    (with-redefs [auth/audit! (fn [& _] (throw (ex-info "Synthetic audit failure" {})))]
      (is (thrown? Exception (auth/accounts! (ds) (:token admin) :create nil
                                             {:username "rollback" :display-name "Synthetic Rollback" :administrator false}))))
    (is (nil? (auth/one (ds) ["SELECT id FROM users WHERE username='rollback'"])))))

(deftest concurrent-last-admin
  (let [admin (active-admin)
        second (auth/accounts! (ds) (:token admin) :create nil
                               {:username "admin.two" :display-name "Synthetic Two" :administrator true})
        signed (login "admin.two" (:temporary-password second))
        second-session (auth/change-password! (ds) (:token signed)
                                              {:current-password (:temporary-password second) :password "Synthetic-Two9!"} "synthetic-csrf")
        gate (promise)
        demote (fn [session]
                 (future @gate (status-of #(auth/accounts! (ds) (:token session) :edit (id (:user session))
                                                           {:display-name "Synthetic Admin" :active true :administrator false}))))
        a (demote admin) b (demote second-session)]
    (deliver gate true)
    (is (= #{200 409} #{@a @b}))
    (is (= 1 (:n (auth/one (ds) ["SELECT count(*) AS n FROM users WHERE active AND administrator"]))))))

(deftest real-ring-login-and-csrf
  (let [created (bootstrap)
        origin "http://127.0.0.1:3000"
        handler (routes/handler (ds) 2 {:csrf-secret (crypto/token) :origin origin :secure-cookie false})
        session (handler {:request-method :get :uri "/api/auth/session" :headers {"accept" "application/json"}})
        parsed (muuntaja/decode "application/json" (:body session))
        csrf (:csrf parsed)
        set-cookie (get-in session [:headers "Set-Cookie"])
        csrf-cookie (if (string? set-cookie) set-cookie (first set-cookie))
        cookie (first (str/split csrf-cookie #";"))
        request {:request-method :post :uri "/api/auth/login" :remote-addr "synthetic-peer"
                 :headers {"content-type" "application/json" "accept" "application/json" "origin" origin
                           "cookie" cookie "x-csrf-token" csrf}}
        body #(muuntaja/encode "application/json" {:username "admin.one" :password (:temporary-password created)})
        response (handler (assoc request :body (body)))]
    (is (= 200 (:status session)))
    (is (= 200 (:status response)))
    (is (= "no-store" (get-in response [:headers "Cache-Control"])))
    (is (= 403 (:status (handler (-> request (assoc :body (body)) (assoc-in [:headers "origin"] "http://other"))))))
    (is (= 403 (:status (handler (-> request (assoc :body (body)) (update :headers dissoc "x-csrf-token"))))))))

(deftest concurrent-bootstrap-and-login-reset
  (let [gate (promise)
        create (fn [] (future @gate (status-of bootstrap)))
        a (create) b (create)]
    (deliver gate true)
    (is (= #{200 409} #{@a @b})))
  ;; Bootstrap above deliberately does not retain its temporary password. Create
  ;; a fresh synthetic credential under the migration role for this race fixture.
  (let [user (auth/one (ds) ["SELECT id FROM users"])
        _ (auth/query (:migrator fixture/*database*)
                      ["UPDATE local_credentials SET password_hash=?,password_change_required=false WHERE user_id=?"
                       (crypto/hash-password "Synthetic-Race9!") (:id user)])
        admin (login "admin.one" "Synthetic-Race9!")
        gate (promise)
        signed (future @gate (login "admin.one" "Synthetic-Race9!"))
        reset-result (future @gate (auth/accounts! (ds) (:token admin) :reset (:id user) nil))]
    (deliver gate true)
    (let [session @signed reset @reset-result]
      (is (:temporary-password reset))
      (is (or (= 401 (:error session))
              (nil? (auth/inspect-session (ds) (:token session))))))))

(deftest future-provider-principal-needs-no-local-credential
  (let [user-id (UUID/randomUUID)]
    (auth/query (:migrator fixture/*database*)
                ["INSERT INTO users(id,username,display_name) VALUES (?,'future.synthetic','Synthetic Future Provider')" user-id])
    (auth/query (:migrator fixture/*database*)
                ["INSERT INTO external_identities(provider_id,subject,user_id) VALUES ('fake-provider','synthetic-subject',?)" user-id])
    (let [principal (auth/resolve-identity (ds) {:provider-id "fake-provider" :subject "synthetic-subject"})
          session (jdbc/with-transaction [tx (ds)] (auth/issue-session! tx principal "synthetic-csrf"))]
      (is (= (str user-id) (get-in session [:user :id])))
      (is (false? (get-in session [:user :password-change-required])))
      (is (= (str user-id) (:id (auth/inspect-session (ds) (:token session)))))
      (is (= user-id (:id (auth/authorize! (ds) (:token session) false false)))))))
