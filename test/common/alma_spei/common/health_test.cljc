(ns alma-spei.common.health-test
  (:require [alma-spei.common.contracts.health :as health]
            [alma-spei.common.validation :as validation]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(deftest health-contract
  (doseq [value [{:status "ok"} {:status "unavailable"}]]
    (is (validation/valid? health/response value)))
  (doseq [value [nil {} {:status :ok} {:status "unknown"} {:status "ok" :secret "hidden"}]]
    (is (not (validation/valid? health/response value)))))

(deftest arbitrary-status-is-rejected
  (let [result (tc/quick-check 100
                               (prop/for-all [status gen/string-alphanumeric]
                                             (= (contains? #{"ok" "unavailable"} status)
                                                (validation/valid? health/response {:status status})))
                               :seed 20261025)]
    (is (:pass? result) (pr-str result))))
