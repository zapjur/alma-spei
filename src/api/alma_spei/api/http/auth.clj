(ns alma-spei.api.http.auth
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as service]
            [alma-spei.common.contracts.auth :as contract]
            [clojure.string :as str])
  (:import [java.util Base64 UUID]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn sign [secret value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256"))
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.doFinal mac (.getBytes ^String value "UTF-8")))))
(defn signed-csrf [secret]
  (let [value (str (crypto/token) "." (quot (System/currentTimeMillis) 1000))]
    (str value "." (sign secret value))))
(defn valid-csrf? [secret value]
  (when (and (string? value) (< (count value) 200))
    (let [[token timestamp signature & extra] (str/split value #"\.")]
      (try
        (and (not extra) (= 43 (count token))
             (<= 0 (- (quot (System/currentTimeMillis) 1000) (Long/parseLong timestamp)) 28800)
             (crypto/equal? signature (sign secret (str token "." timestamp))))
        (catch Exception _ false)))))
(defn cookie [value secure? http-only?]
  {:value value :path "/" :http-only http-only? :secure secure? :same-site :strict})
(defn reply [result secure?]
  (if (:error result)
    {:status (:error result) :body {:code (:code result) :message (:message result)}}
    (cond-> {:status 200 :body (dissoc result :token)}
      (:token result) (assoc :cookies {"alma-session" (cookie (:token result) secure? true)}))))
(defn uuid! [value]
  (try (UUID/fromString value)
       (catch Exception _ (service/fail! 404 "not-found" "Nie znaleziono konta."))))

(defn endpoint [ds settings action]
  (fn [request]
    (try
      (let [secret (:csrf-secret settings)
            secure? (:secure-cookie settings false)
            token (get-in request [:cookies "alma-session" :value])
            csrf-cookie (get-in request [:cookies "alma-csrf" :value])
            csrf-header (get-in request [:headers "x-csrf-token"])
            mutation? (not= :get (:request-method request))
            origin (get-in request [:headers "origin"])
            csrf (if (valid-csrf? secret csrf-cookie) csrf-cookie (signed-csrf secret))]
        (when mutation?
          (when-not (and (valid-csrf? secret csrf-cookie)
                         (crypto/equal? csrf-header csrf-cookie)
                         (= origin (:origin settings)))
            (service/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
          (when (and token (not (#{:login :logout} action)))
            (let [session (service/session! ds token false)]
              (when (and session (not (crypto/equal? (:csrf-digest session) (crypto/digest csrf))))
                (service/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę.")))))
        (let [input (:body-params request)
              _ (when (#{:logout :reset} action) (service/validate! [:map {:closed true}] input))
              result
              (case action
                :session {:user (service/inspect-session ds token csrf) :csrf csrf}
                :login (service/login! ds input (or (:remote-addr request) "unknown") csrf token)
                :logout (do (service/logout! ds token) {:user nil :csrf csrf})
                :password (service/change-password! ds token input csrf)
                (:list :create :edit :reset)
                (service/accounts! ds token action
                                   (when-let [id (get-in request [:path-params :id])] (uuid! id)) input))
              response (reply result secure?)]
          (cond-> (assoc-in response [:cookies "alma-csrf"] (cookie csrf secure? true))
            (= action :logout) (assoc-in [:cookies "alma-session"]
                                         (assoc (cookie "" secure? true) :max-age 0)))))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [status code public-message]} (ex-data e)]
          (if status
            {:status status :body {:code code :message (or public-message "Spróbuj ponownie później.")}}
            (throw e)))))))

(defn routes [ds settings]
  (let [route (fn [action schema]
                {:handler (endpoint ds settings action) :responses {200 {:body schema}}})]
    [["/api/auth/session" {:get (route :session contract/session-response)}]
     ["/api/auth/login" {:post (route :login contract/session-response)}]
     ["/api/auth/logout" {:post (route :logout contract/session-response)}]
     ["/api/auth/password" {:post (route :password contract/session-response)}]
     ["/api/accounts" {:get (route :list contract/accounts-response) :post (route :create contract/temporary-response)}]
     ["/api/accounts/:id" {:patch (route :edit contract/account-response)}]
     ["/api/accounts/:id/reset-password" {:post (route :reset contract/temporary-response)}]]))

(defn wrap-body-limit [handler]
  (fn [request]
    (if (and (:body request) (not (#{:get :head} (:request-method request))))
      ;; Read at most limit+1 bytes even for chunked bodies; do not trust Content-Length.
      (let [bytes (.readNBytes ^java.io.InputStream (:body request) 16385)]
        (if (> (alength bytes) 16384)
          {:status 413 :headers {"Content-Type" "application/json"}
           :body "{\"code\":\"body-too-large\",\"message\":\"Przekroczono rozmiar formularza.\"}"}
          (handler (assoc request :body (java.io.ByteArrayInputStream. bytes)))))
      (handler request))))
