(ns alma-spei.common.donors-test
  (:require [alma-spei.common.contracts.donors :as donors]
            [alma-spei.common.domain.donors :as domain]
            [alma-spei.common.validation :as validation]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def profile {:entity-type "individual" :name "Synthetic Żółć" :city "Synthetic City"})

(deftest manual-profile-and-entity-types
  (doseq [entity-type domain/entity-types]
    (is (validation/valid? donors/create-donor (assoc profile :entity-type entity-type))))
  (is (validation/valid? donors/create-donor
                         (assoc profile :first-name nil :street nil :province nil :postal-code nil)))
  (is (validation/valid? donors/update-donor (assoc profile :version 1)))
  (doseq [field [:entity-type :name :city]]
    (is (not (validation/valid? donors/create-donor (dissoc profile field)))))
  (doseq [value [nil "" " " "\t\n" "\u00a0" "\u2007" "\ufeff" 12]]
    (is (not (validation/valid? donors/create-donor (assoc profile :name value))))
    (is (not (validation/valid? donors/create-donor (assoc profile :city value)))))
  (is (not (validation/valid? donors/create-donor (assoc profile :entity-type "household")))))

(deftest manual-postcodes
  (is (validation/valid? donors/create-donor (assoc profile :postal-code "00-001")))
  (doseq [value ["" "00001" "0-001" "000-01" "00-001 " " 00-001" "００-００１" "SW1A 1AA" 1]]
    (is (not (validation/valid? donors/create-donor (assoc profile :postal-code value))))))

(deftest closed-requests-and-stale-edit-input
  (doseq [field [:id :created-by :updated-by :is-active :donations :administrator]]
    (is (not (validation/valid? donors/create-donor (assoc profile field "untrusted")))))
  (is (not (validation/valid? donors/update-donor profile)))
  (doseq [value [nil 0 -1 1.5 "1" 9007199254740992]]
    (is (not (validation/valid? donors/update-donor (assoc profile :version value))))))

(deftest bounded-and-allowlisted-query
  (is (validation/valid? donors/list-query {}))
  (is (validation/valid? donors/list-query {:search "Żółć" :entity-type "organization"
                                            :status "archived" :sort-by "city"
                                            :sort-direction "desc" :limit 100 :offset 20}))
  (doseq [query [{:limit 0} {:limit 101} {:offset -1} {:sort-by "password_hash"}
                 {:sort-direction "descending"} {:status "deleted"} {:limit "10"}
                 {:search (apply str (repeat 201 "x"))} {:unexpected true}]]
    (is (not (validation/valid? donors/list-query query)))))

(deftest nullable-read-addresses-and-no-name-inference
  (let [record (merge profile {:id "00000000-0000-0000-0000-000000000001"
                               :first-name nil :street nil :province nil :postal-code "SW1A 1AA"
                               :city nil :is-active false :version 2})]
    (is (validation/valid? donors/donor-response {:donor record}))
    (is (validation/valid? donors/donors-response {:donors [record] :total 1}))
    (is (not (validation/valid? donors/donor-response {:donor (assoc record :created-by "internal")})))
    (is (= nil (:first-name record)))
    (is (= "Synthetic Żółć" (:name record)))))

(deftest polish-errors-do-not-expose-rejected-values
  (is (= [] (donors/errors donors/create-donor profile)))
  (is (= [{:field [:city] :code :required :message "Podaj miejscowość."}]
         (donors/errors donors/create-donor (dissoc profile :city))))
  (is (= [{:field [:postal-code] :code :invalid :message "Podaj kod pocztowy w formacie XX-XXX."}]
         (donors/errors donors/create-donor (assoc profile :postal-code "SYNTHETIC-REJECTED"))))
  (is (= [{:field [] :code :unknown-field :message "Formularz zawiera nieobsługiwane pole."}]
         (donors/errors donors/create-donor (assoc profile :untrusted "SYNTHETIC-REJECTED"))))
  (is (= [{:field [] :code :invalid :message "Nieprawidłowe dane formularza."}]
         (donors/errors donors/create-donor nil))))

(deftest financial-read-values-remain-exact-strings
  (let [payment {:id "00000000-0000-0000-0000-000000000001"
                 :amount "9999999999999999.9999" :currency "PLN" :received-on "2026-01-02"}]
    (is (validation/valid? donors/donation payment))
    (is (not (validation/valid? donors/donation (assoc payment :amount 123.45))))
    (is (not (validation/valid? donors/donation (assoc payment :amount "NaN"))))
    (is (not (validation/valid? donors/donation (assoc payment :amount "123.45678"))))))
