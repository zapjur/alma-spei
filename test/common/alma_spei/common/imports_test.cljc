(ns alma-spei.common.imports-test
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.validation :as validation]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def id "00000000-0000-0000-0000-000000000001")
(def batch {:id id :file-id id :attempt-number 1 :retry-of nil :source-type "bank" :original-filename "Próba.xlsx"
            :byte-count 3 :created-by id :created-at "2026-11-07T10:00:00Z" :updated-at "2026-11-07T10:00:00Z"
            :version 1 :state "reserved" :failure-code nil :parse-version nil :mapping-version nil
            :total-rows nil :valid-rows nil :invalid-rows nil :reviewed-rows nil})

(deftest metadata-is-closed-and-filenames-are-not-paths
  (is (validation/valid? imports/upload-metadata {:source-type "bank" :original-filename "Próba.xlsx"}))
  (doseq [filename ["" " " "." ".." "../file.xlsx" "folder/file.xlsx" "C:\\file.xlsx" "a\nfile.xlsx" "a\u0000.xlsx"
                    (apply str (repeat 256 "x"))]]
    (is (not (validation/valid? imports/upload-metadata {:source-type "bank" :original-filename filename}))))
  (is (not (validation/valid? imports/upload-metadata {:source-type "other" :original-filename "file.xlsx"})))
  (is (not (validation/valid? imports/upload-metadata {:source-type "bank" :original-filename "file.xlsx" :created-by id})))
  (is (= [] (imports/errors imports/upload-metadata {:source-type "bank" :original-filename "Próba.xlsx"}))))

(deftest byte-counts-hashes-and-confirmation-are-explicit
  (is (validation/valid? imports/fingerprint {:file-hash-sha256 (apply str (repeat 64 "a")) :byte-count 0}))
  (doseq [value [{:file-hash-sha256 "abc" :byte-count 1}
                 {:file-hash-sha256 (apply str (repeat 64 "A")) :byte-count 1}
                 {:file-hash-sha256 (apply str (repeat 64 "a")) :byte-count -1}]]
    (is (not (validation/valid? imports/fingerprint value))))
  (is (validation/valid? imports/retry-request {:version 2 :confirmed true}))
  (doseq [value [{:version 2} {:version 2 :confirmed false} {:version "2" :confirmed true} {:version 0 :confirmed true}]]
    (is (not (validation/valid? imports/retry-request value))))
  (is (not (validation/valid? imports/failure {:version 1 :failure-code "Synthetic private exception"}))))

(deftest unknown-counts-and-invalid-rows-cannot-pretend-to-be-approved
  (is (validation/valid? imports/batch-response {:batch batch}))
  (is (not (validation/valid? imports/batch (assoc batch :total-rows 0))))
  (is (not (validation/valid? imports/batch (assoc batch :state "review"))))
  (let [parsed (assoc batch :state "review" :total-rows 3 :valid-rows 2 :invalid-rows 1 :reviewed-rows 0)]
    (is (validation/valid? imports/batch parsed))
    (is (not (validation/valid? imports/batch (assoc parsed :state "approved")))))
  (is (validation/valid? imports/batch (assoc batch :state "approved" :total-rows 2 :valid-rows 2 :invalid-rows 0 :reviewed-rows 2)))
  (is (validation/valid? imports/batch (assoc batch :state "failed" :failure-code "parse-failed" :version 2)))
  (is (not (validation/valid? imports/batch (assoc batch :state "failed")))))
