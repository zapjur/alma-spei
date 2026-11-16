(ns alma-spei.common.reconciliation-test
  (:require [alma-spei.common.imports.reconciliation :as matching]
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
