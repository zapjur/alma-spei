(ns alma-spei.common.imports-test
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.source-rows :as source]
            [alma-spei.common.contracts.review :as review]
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

(deftest staging-pages-keep-missing-currency-explicit-and-bound-pagination
  (let [payload {:date "2026-01-02" :sender "Synthetic Żółć" :description ""
                 :amount "9999999999999999.9999" :currency nil :source-date "02.01.2026" :source-amount "9999999999999999,9999"}
        row {:worksheet-index 1 :row-number 2 :payload payload :validation-state "valid" :errors []}
        page {:batch (assoc batch :state "review" :total-rows 1 :valid-rows 1 :invalid-rows 0 :reviewed-rows 0)
              :rows [row] :total 1 :limit 25 :offset 0}]
    (is (validation/valid? imports/rows-response page))
    (is (validation/valid? imports/upload-response (assoc page :skipped-empty-rows 1)))
    (is (not (validation/valid? imports/row-payload (assoc payload :currency "PLN"))))
    (is (not (validation/valid? imports/row-payload (assoc payload :raw-xml "synthetic"))))
    (is (validation/valid? imports/row-payload (assoc payload :date nil :amount nil)))
    (is (not (validation/valid? imports/row-payload (assoc payload :sender (apply str (repeat 4097 "x")))))))
  (doseq [query [{} {:limit 1 :offset 0} {:limit 100 :offset 50000}]]
    (is (validation/valid? imports/page-query query)))
  (doseq [query [{:limit 101} {:offset -1} {:limit "25"} {:sort-by "name"}]]
    (is (not (validation/valid? imports/page-query query)))))

(deftest review-contracts-are-closed-and-compare-profile-with-source-locators
  (let [parsed (assoc batch :state "review" :total-rows 1 :valid-rows 1 :invalid-rows 0 :reviewed-rows 0)
        row {:worksheet-index 1 :row-number 2 :validation-state "valid" :errors []
             :payload {:date "2026-01-02" :sender "Synthetic Sender" :description "" :amount "12.3400" :currency nil
                       :source-date "02.01.2026" :source-amount "12,3400"}}
        donor {:id id :entity-type "business" :name "Synthetic Company" :first-name nil :street nil
               :postal-code nil :city nil :province nil :is-active true :version 1}
        response {:batch parsed :row row :donor donor}]
    (is (validation/valid? review/queue-response {:batches [parsed] :total 1 :limit 25 :offset 0}))
    (is (validation/valid? review/comparison-response response))
    (is (not (validation/valid? review/comparison-response (assoc response :donations []))))
    (is (not (validation/valid? review/comparison-response (assoc-in response [:donor :password-hash] "synthetic"))))
    (is (validation/valid? review/comparison-query {:donor-id id}))
    (is (not (validation/valid? review/comparison-query {:donor-id id :score 100})))
    (is (validation/valid? review/locator {:worksheet 1 :row 2}))
    (is (not (validation/valid? review/locator {:worksheet 0 :row 2})))))

(deftest master-contract-preserves-combined-name-and-source-observations
  (let [row {:name "Żaneta Przykładowa"
             :observations [{:column 7 :year 2021 :source-type "text" :value "tak"}
                            {:column 11 :year 2025 :source-type "number" :value "10.50"}
                            {:column 13 :source-type "number" :value "5"}]}]
    (is (validation/valid? source/master-row row))
    (is (not (validation/valid? source/master-row (assoc row :first-name "Żaneta"))))
    (is (not (validation/valid? source/master-row (assoc-in row [:observations 0 :value] 1))))
    (is (= [{:field [:name] :code :required :message "Podaj pełną nazwę darczyńcy."}]
           (source/errors source/master-row (dissoc row :name))))))

(deftest tax-contract-keeps-subjects-separate-and-preserves-decimal-text
  (is (validation/valid? source/tax-row {:amount "9007199254740993.10"
                                         :taxpayer {:first-name "Jan" :surname "Przykładowy"}
                                         :spouse {:address "ul. Fikcyjna 1"}
                                         :purpose "Cel fikcyjny"}))
  (is (validation/valid? source/tax-row {:amount "0.00"}))
  (doseq [value [12.3 "12,3" "-1" "1e2" "5\n" ""]]
    (is (= [{:field [:amount] :code :invalid :message "Podaj kwotę w zapisie dziesiętnym z kropką."}]
           (source/errors source/tax-row {:amount value}))))
  (is (not (validation/valid? source/tax-row {:amount "12.30" :spouse {}})))
  (is (not (validation/valid? source/tax-row {:amount "12.30" :taxpayer {:donor-id id}}))))
