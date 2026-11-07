(ns alma-spei.api.auth.crypto
  (:require [buddy.hashers :as hashers]
            [alma-spei.common.contracts.auth :as contract])
  (:import [java.security SecureRandom MessageDigest]
           [java.util Base64]
           [java.util.concurrent Semaphore]))

(defonce random-source (SecureRandom.))
(defonce hashing-slots (Semaphore. 2 true))
(def options {:alg :argon2id :memory 65536 :iterations 2 :parallelism 1})
(defn token []
  (let [bytes (byte-array 32)]
    (.nextBytes random-source bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))
(defn digest [s]
  (.encodeToString (Base64/getUrlEncoder)
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn equal? [a b]
  (and (string? a) (string? b)
       (MessageDigest/isEqual (.getBytes ^String a "UTF-8") (.getBytes ^String b "UTF-8"))))
(defn with-hashing-slot [f]
  (when-not (.tryAcquire hashing-slots)
    (throw (ex-info "Password verifier busy" {:status 429 :code "busy"})))
  (try (f) (finally (.release hashing-slots))))
(defn hash-password [password] (with-hashing-slot #(hashers/derive password options)))
(defn verify [password encoded]
  (with-hashing-slot #(boolean (:valid (hashers/verify password encoded {:limit #{:argon2id}})))))
(defonce dummy-hash (delay (hash-password "Synthetic-Dummy9!")))
(defn temporary-password []
  ;; Rejection sampling avoids imposing a predictable prefix on generated secrets.
  (loop [] (let [s (token)] (if (contract/password-valid? s) s (recur)))))
