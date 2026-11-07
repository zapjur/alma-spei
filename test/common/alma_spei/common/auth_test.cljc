(ns alma-spei.common.auth-test
  (:require [alma-spei.common.contracts.auth :as auth]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest password-policy
  (doseq [p ["Abcdef1!" "Żółw2026!" "Abcdef1😀" "Abcdef١!"]]
    (is (auth/password-valid? p)))
  (doseq [p [nil "Short1!" "abcdef1!" "ABCDEFG!" "Abcdef12" "Abcdef1 !"
             "Abcdef1!\t" "Abcdef1!\u00a0" "Abcdef1!\u2007" "Abcdef1!\u0085"
             "Abcdef1!\u3000" (apply str "Abc1!" (repeat 124 "x"))]]
    (is (not (auth/password-valid? p))))
  (is (auth/password-valid? (apply str "Abc1!" (repeat 123 "x")))))

(deftest contracts
  (is (= "staff.one" (auth/canonical-username "Staff.One")))
  (is (auth/valid? auth/login {:username "Staff.One" :password "x"}))
  (is (not (auth/valid? auth/login {:username "staff" :password "x" :administrator true})))
  (is (not (auth/valid? auth/login {:username " staff " :password "x"})))
  (is (not (auth/valid? auth/create-account {:username "ab" :display-name "Test" :administrator false})))
  (is (not (auth/valid? auth/create-account {:username "staff" :display-name " Test " :administrator false}))))
