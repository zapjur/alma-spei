(ns alma-spei.api.auth.security-test
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.auth.service :as service]
            [alma-spei.api.http.auth :as http]
            [alma-spei.common.contracts.auth :as contract]
            [clojure.test :refer [deftest is]]))

(deftest password-hashing
  (let [password "Synthetic9!"
        hash-one (crypto/hash-password password)
        hash-two (crypto/hash-password password)]
    (is (not= hash-one hash-two))
    (is (not (.contains hash-one password)))
    (is (crypto/verify password hash-one))
    (is (not (crypto/verify "incorrect" hash-one))))
  (dotimes [_ 20] (is (contract/password-valid? (crypto/temporary-password)))))

(deftest csrf-and-cookies
  (let [secret (crypto/token)
        value (http/signed-csrf secret)]
    (is (http/valid-csrf? secret value))
    (is (not (http/valid-csrf? "other" value)))
    (is (not (http/valid-csrf? secret (str value "x"))))
    (is (not (http/valid-csrf? secret nil)))
    (let [old-value (str (crypto/token) ".1")]
      (is (not (http/valid-csrf? secret (str old-value "." (http/sign secret old-value))))))
    (let [response (http/reply {:token "opaque" :user nil} true)
          cookie (get-in response [:cookies "alma-session"])]
      (is (:secure cookie)) (is (:http-only cookie)) (is (= :strict (:same-site cookie)))
      (is (not (contains? cookie :max-age)))
      (is (not (contains? (:body response) :token))))))

(deftest csrf-blocks-before-services
  (let [handler (http/endpoint nil {:csrf-secret (crypto/token) :origin "http://127.0.0.1:3000"} :login)]
    (with-redefs [service/login! (fn [& _] (throw (ex-info "Must not reach service" {})))]
      (is (= 403 (:status (handler {:request-method :post :headers {} :cookies {}})))))))

(deftest body-limit
  (let [handler (http/wrap-body-limit (constantly {:status 200}))]
    (is (= 413 (:status (handler {:request-method :post :body (java.io.ByteArrayInputStream. (byte-array 16385))}))))
    (is (= 200 (:status (handler {:request-method :post :body (java.io.ByteArrayInputStream. (byte-array 16384))}))))))

(deftest provider-independent-resolution
  (with-redefs [service/one (fn [_ sql] (when (= ["future" "subject"] (vec (rest sql))) {:user-id :stable}))
                service/user-row (fn [_ id] {:id id :administrator false})]
    (is (= {:id :stable :administrator false}
           (service/resolve-identity nil {:provider-id "future" :subject "subject"})))))
