(ns alma-spei.api.fingerprint-test
  (:require [alma-spei.api.imports.fingerprint :as fingerprint]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream InputStream IOException]))

(deftest known-byte-vectors-and-multi-buffer-input
  (doseq [[bytes expected] [[(.getBytes "abc" "UTF-8") "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"]
                            [(.getBytes "DANE SYNTETYCZNE — Żółć" "UTF-8") "e7c23de2220a0e4105bb6308dfbe72577b58babe75d407e22629293ba2c5e646"]
                            [(byte-array 20000) "28b4f41a7f3ee6d8cc87272db6e09c6d3566551fd4d18702b041a21658272a85"]
                            [(byte-array 0) "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]]]
    (with-open [stream (ByteArrayInputStream. bytes)]
      (is (= {:file-hash-sha256 expected :byte-count (alength bytes)} (fingerprint/measure stream (max 1 (alength bytes))))))))

(deftest reads-at-most-limit-plus-one-and-keeps-stream-ownership-with-caller
  (let [base (ByteArrayInputStream. (byte-array 100))
        consumed (atom 0)
        closed (atom false)
        stream (proxy [InputStream] []
                 (read
                   ([] (.read base))
                   ([buffer offset length] (let [n (.read base buffer offset length)] (swap! consumed + (max 0 n)) n)))
                 (close [] (reset! closed true)))]
    (is (= 413 (try (fingerprint/measure stream 10) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))))
    (is (= 11 @consumed))
    (is (false? @closed))
    (.close stream)
    (is @closed)))

(deftest zero-length-read-does-not-loop-and-read-failures-are-safe
  (let [values (atom [65 -1])
        stream (proxy [InputStream] []
                 (read
                   ([] (let [n (first @values)] (swap! values subvec 1) n))
                   ([_buffer _offset _length] 0)))]
    (is (= {:file-hash-sha256 "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd" :byte-count 1}
           (fingerprint/measure stream 1))))
  (let [stream (proxy [InputStream] [] (read ([_buffer _offset _length] (throw (IOException. "Synthetic private path")))))
        error (try (fingerprint/measure stream 10) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= "file-read-failed" (:code (ex-data error))))
    (is (nil? (.getCause error)))
    (is (not (.contains (.getMessage error) "Synthetic private path"))))
  (doseq [limit [nil 0 -1 1.5 9007199254740992]]
    (is (thrown? IllegalArgumentException (fingerprint/measure (ByteArrayInputStream. (byte-array 0)) limit)))))
