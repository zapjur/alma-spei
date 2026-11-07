(ns alma-spei.api.auth.service
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.common.contracts.auth :as contract]
            [muuntaja.core :as muuntaja]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.util UUID]))

(defn query [tx sql] (jdbc/execute! tx sql {:builder-fn rs/as-unqualified-kebab-maps}))
(defn one [tx sql] (first (query tx sql)))
(defn fail! [status code message] (throw (ex-info message {:status status :code code :public-message message})))
(defn validate! [schema input]
  (when-not (contract/valid? schema input) (fail! 422 "validation" "Sprawdź poprawność danych formularza.")))

(defn lock! [tx]
  ;; One database-wide security mutation lock keeps last-admin and credential/session
  ;; invariants atomic across processes. Domain transactions never use this lock.
  (query tx ["SELECT pg_advisory_xact_lock(20971203)"]))

(defn audit! [tx actor target event changes]
  (query tx ["INSERT INTO security_audit_events(id,actor_id,target_id,event_type,changes) VALUES (?,?,?,?,?::jsonb)"
             (UUID/randomUUID) actor target event
             (slurp (muuntaja/encode "application/json" changes))]))

(defn user-row [tx id]
  (one tx ["SELECT u.*, coalesce(c.password_change_required,false) AS password_change_required, c.password_hash, c.credential_version FROM users u LEFT JOIN local_credentials c ON c.user_id=u.id WHERE u.id=?" id]))
(defn public-user [u]
  (when u (-> (select-keys u [:username :display-name :active :administrator :password-change-required])
              (assoc :id (str (:id u))))))

(defn resolve-identity [tx {:keys [provider-id subject]}]
  (when-let [identity (one tx ["SELECT user_id FROM external_identities WHERE provider_id=? AND subject=?" provider-id subject])]
    (user-row tx (:user-id identity))))

(defn local-provider [tx username password]
  (let [u (one tx ["SELECT u.id, c.password_hash FROM users u JOIN local_credentials c ON c.user_id=u.id WHERE username=?"
                   (contract/canonical-username username)])
        valid (crypto/verify password (or (:password-hash u) @crypto/dummy-hash))]
    (when (and u valid) {:provider-id "local" :subject (str (:id u))})))

(defn session! [tx token touch?]
  (when token
    (let [row (one tx ["SELECT s.token_digest,s.csrf_digest,u.*,coalesce(c.password_change_required,false) AS password_change_required,c.password_hash,c.credential_version FROM sessions s JOIN users u ON u.id=s.user_id LEFT JOIN local_credentials c ON c.user_id=u.id WHERE s.token_digest=? AND u.active AND s.security_version=u.security_version AND s.expires_at>now() AND s.last_activity>now()-interval '30 minutes'"
                       (crypto/digest token)])]
      (when (and row touch?)
        (query tx ["UPDATE sessions SET last_activity=now() WHERE token_digest=?" (crypto/digest token)]))
      row)))

(defn authorize! [tx token administrator? limited?]
  (let [u (session! tx token true)]
    (when-not u (fail! 401 "unauthenticated" "Zaloguj się ponownie."))
    (when (and (not limited?) (:password-change-required u))
      (fail! 403 "password-change-required" "Najpierw zmień hasło tymczasowe."))
    (when (and administrator? (not (:administrator u)))
      (fail! 403 "forbidden" "Brak uprawnień do zarządzania kontami."))
    u))

(defn issue-session! [tx u csrf]
  (let [token (crypto/token)]
    (query tx ["DELETE FROM sessions WHERE expires_at<=now() OR last_activity<=now()-interval '30 minutes'"])
    (query tx ["INSERT INTO sessions(token_digest,user_id,security_version,csrf_digest) VALUES (?,?,?,?)"
               (crypto/digest token) (:id u) (:security-version u) (crypto/digest csrf)])
    {:token token :user (public-user u) :csrf csrf}))

(defn throttle! [tx buckets]
  (query tx ["DELETE FROM auth_throttle_buckets WHERE expires_at<=now()"])
  ;; Count attempts before password work, including successes; login success clears
  ;; the username bucket. Return a result rather than throw so failures commit.
  (every? true?
          (mapv (fn [[bucket limit]]
                  (let [row (one tx ["INSERT INTO auth_throttle_buckets(bucket,attempts,expires_at) VALUES (?,1,now()+interval '15 minutes') ON CONFLICT(bucket) DO UPDATE SET attempts=auth_throttle_buckets.attempts+1 RETURNING attempts" bucket])]
                    (<= (:attempts row) limit))) buckets)))

(defn login! [ds input peer csrf old-token]
  (validate! contract/login input)
  (jdbc/with-transaction [tx ds]
    (lock! tx)
    (let [username-bucket (str "login:" (crypto/digest (contract/canonical-username (:username input))))]
      (if-not (throttle! tx [[username-bucket 5] [(str "peer:" (crypto/digest peer)) 30]])
        {:error 429 :code "throttled" :message "Zbyt wiele prób. Spróbuj ponownie za 15 minut."}
        (let [identity (local-provider tx (:username input) (:password input))
              u (when identity (resolve-identity tx identity))]
          (if-not (and u (:active u))
            (do (audit! tx nil nil "login-failed" {})
                {:error 401 :code "invalid-credentials" :message "Nieprawidłowa nazwa użytkownika lub hasło."})
            (do
              (query tx ["DELETE FROM auth_throttle_buckets WHERE bucket=?" username-bucket])
              (when old-token (query tx ["DELETE FROM sessions WHERE token_digest=?" (crypto/digest old-token)]))
              (audit! tx (:id u) (:id u) "login" {})
              (issue-session! tx u csrf))))))))

(defn inspect-session
  ([ds token] (inspect-session ds token nil))
  ([ds token csrf]
   (jdbc/with-transaction [tx ds]
     (lock! tx)
     (let [user (session! tx token true)]
       (when (and user csrf)
         (query tx ["UPDATE sessions SET csrf_digest=? WHERE token_digest=?"
                    (crypto/digest csrf) (crypto/digest token)]))
       (public-user user)))))

(defn logout! [ds token]
  (jdbc/with-transaction [tx ds]
    (lock! tx)
    (when-let [u (session! tx token false)]
      (audit! tx (:id u) (:id u) "logout" {}))
    (when token (query tx ["DELETE FROM sessions WHERE token_digest=?" (crypto/digest token)]))))

(defn create-user! [tx actor input bootstrap?]
  (validate! contract/create-account input)
  (let [id (UUID/randomUUID)
        temporary (crypto/temporary-password)
        username (contract/canonical-username (:username input))]
    (when (one tx ["SELECT id FROM users WHERE username=?" username])
      (fail! 409 "username-exists" "Ta nazwa użytkownika jest już zajęta."))
    (query tx ["INSERT INTO users(id,username,display_name,administrator) VALUES (?,?,?,?)"
               id username (:display-name input) (:administrator input)])
    (query tx ["INSERT INTO local_credentials(user_id,password_hash) VALUES (?,?)" id (crypto/hash-password temporary)])
    (query tx ["INSERT INTO external_identities(provider_id,subject,user_id) VALUES ('local',?,?)" (str id) id])
    (audit! tx actor id (if bootstrap? "bootstrap" "account-created")
            {:after (select-keys input [:username :display-name :administrator])})
    {:user (public-user (user-row tx id)) :temporary-password temporary}))

(defn bootstrap! [ds input]
  (jdbc/with-transaction [tx ds]
    (lock! tx)
    (when (one tx ["SELECT id FROM users LIMIT 1"])
      (fail! 409 "bootstrap-disabled" "Pierwsze konto zostało już utworzone."))
    (create-user! tx nil (assoc input :administrator true) true)))

(defn accounts! [ds token action id input]
  (jdbc/with-transaction [tx ds]
    (lock! tx)
    (let [actor (authorize! tx token true false)]
      (case action
        :list {:users (mapv public-user (query tx ["SELECT u.*,coalesce(c.password_change_required,false) AS password_change_required FROM users u LEFT JOIN local_credentials c ON c.user_id=u.id ORDER BY username"]))}
        :create (create-user! tx (:id actor) input false)
        (:edit :reset)
        (let [target (user-row tx id)]
          (when-not target (fail! 404 "not-found" "Nie znaleziono konta."))
          (if (= action :reset)
            (let [temporary (crypto/temporary-password)]
              (when-not (:password-hash target)
                (fail! 409 "no-local-credential" "To konto nie ma lokalnego hasła do zresetowania."))
              (query tx ["UPDATE local_credentials SET password_hash=?,password_change_required=true,credential_version=credential_version+1,changed_at=now() WHERE user_id=?"
                         (crypto/hash-password temporary) id])
              (query tx ["UPDATE users SET security_version=security_version+1 WHERE id=?" id])
              (query tx ["DELETE FROM sessions WHERE user_id=?" id])
              (audit! tx (:id actor) id "password-reset" {})
              {:user (public-user (user-row tx id)) :temporary-password temporary})
            (do
              (validate! contract/edit-account input)
              (when (and (:active target) (:administrator target)
                         (not (and (:active input) (:administrator input)))
                         (= 1 (:n (one tx ["SELECT count(*) AS n FROM users WHERE active AND administrator"]))))
                (fail! 409 "last-administrator" "Nie można wyłączyć ostatniego aktywnego administratora."))
              (query tx ["UPDATE users SET display_name=?,active=?,administrator=?,updated_at=now() WHERE id=?"
                         (:display-name input) (:active input) (:administrator input) id])
              (when-not (:active input) (query tx ["DELETE FROM sessions WHERE user_id=?" id]))
              (audit! tx (:id actor) id "account-updated"
                      {:before (select-keys target [:display-name :active :administrator]) :after input})
              {:user (public-user (user-row tx id))})))))))

(defn change-password! [ds token input csrf]
  (validate! contract/change-password input)
  (jdbc/with-transaction [tx ds]
    (lock! tx)
    (let [u (authorize! tx token false true)]
      (when-not (:password-hash u)
        (fail! 403 "no-local-credential" "To konto nie korzysta z lokalnego hasła."))
      (if-not (throttle! tx [[(str "password:" (:id u)) 5]])
        {:error 429 :code "throttled" :message "Zbyt wiele prób. Spróbuj ponownie za 15 minut."}
        (if-not (crypto/verify (:current-password input) (:password-hash u))
          (do (audit! tx (:id u) (:id u) "password-change-failed" {})
              {:error 422 :code "invalid-password" :message "Obecne hasło jest nieprawidłowe."})
          (if (crypto/verify (:password input) (:password-hash u))
            {:error 422 :code "password-reused" :message "Nowe hasło musi być inne od obecnego."}
            (do
              (query tx ["UPDATE local_credentials SET password_hash=?,password_change_required=false,credential_version=credential_version+1,changed_at=now() WHERE user_id=?"
                         (crypto/hash-password (:password input)) (:id u)])
              (query tx ["UPDATE users SET security_version=security_version+1 WHERE id=?" (:id u)])
              (query tx ["DELETE FROM sessions WHERE user_id=?" (:id u)])
              (query tx ["DELETE FROM auth_throttle_buckets WHERE bucket=?" (str "password:" (:id u))])
              (audit! tx (:id u) (:id u) "password-changed" {})
              (issue-session! tx (user-row tx (:id u)) csrf))))))))
