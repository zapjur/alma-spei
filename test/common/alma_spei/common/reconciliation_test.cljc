(ns alma-spei.common.reconciliation-test
  (:require [alma-spei.common.imports.reconciliation :as matching]
            [alma-spei.common.domain.donor-identity :as identity]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])))
(def profile {:name "Jan Kowalski" :street "ul. Lipowa 12/4" :postal-code "30-001" :city "Kraków"})
(def donor (assoc profile :id "one" :is-active true))
(deftest explicit-format-only-and-no-inferred-first-name-or-type
  (is (= profile (matching/sender-profile "Jan Kowalski, ul. Lipowa 12/4, 30-001 Kraków")))
  (doseq [raw ["Jan Kowalski" "Jan Kowalski i Anna Kowalska" "Jan Kowalski, ul. Lipowa, Kraków" "Jan Kowalski, ul. Lipowa, 30-001 Kraków, Polska"]]
    (is (nil? (matching/sender-profile raw)))))
(deftest certain-identity-requires-unique-full-identity
  (is (= "one" (get-in (matching/donor-plan (assoc profile :name " JAN   KOWALSKI ") [donor] nil) [:donor :id])))
  (doseq [candidates [[donor (assoc donor :id "two")]
                      [(assoc donor :is-active false)]
                      [(assoc donor :street "ul. Lipowa 13/4")]
                      [(assoc donor :name "Anna Kowalska")]]]
    (is (= "identity-ambiguity" (:reason (matching/donor-plan profile candidates "individual"))))))
(deftest new-profile-needs-explicit-source-type-and-preserves-full-name
  (is (= "donor-type" (:reason (matching/donor-plan profile [] nil))))
  (let [result (matching/donor-plan profile [] "individual")]
    (is (= "new-proposal" (:kind result)))
    (is (= "Jan Kowalski" (get-in result [:profile :name])))
    (is (nil? (get-in result [:profile :first-name]))))
  (is (= "sender-format" (:reason (matching/donor-plan nil [] "individual")))))

(deftest portable-donor-normalization
  (is (= "łódź" (identity/text-key "  ŁÓDŹ ")))
  (is (= "józef" (identity/text-key "JÓZEF")))
  (is (= "lodz zazolc gesla jazn" (identity/folded-key "ŁÓDŹ Zażółć gęślą jaźń")))
  (doseq [postcode ["30-001" "30001" "30 001" "30 - 001"]]
    (is (= "30-001" (identity/postal-key postcode))))
  (is (= "30/001" (identity/postal-key "30/001")))
  (doseq [[street expected] [["Ulica Lipowa 12/4" "ul. lipowa 12/4"]
                             ["AL Jana Pawła II 12" "al. jana pawła ii 12"]
                             ["osiedle Leśne 6" "os. leśne 6"]]]
    (is (= expected (identity/street-key street))))
  (is (apply distinct? (map identity/street-key
                            ["ul. Lipowa 12/4" "ul. Lipowa 12/5" "ul. Lipowa 124" "ul. Lipowa 12"]))))

(deftest exact-matching-keeps-accents-and-apartments-distinct
  (is (= "one" (get-in (matching/donor-plan (assoc profile :street "Ulica Lipowa 12/4"
                                                   :postal-code "30001") [donor] nil) [:donor :id])))
  (is (nil? (:donor (matching/donor-plan (assoc profile :street "ul. Lipowa 12/5") [donor] nil))))
  (is (nil? (:donor (matching/donor-plan (assoc profile :city "Krakow") [donor] nil)))))
