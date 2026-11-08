(ns alma-spei.dev.test-runner
  (:require [clojure.test :as test]))

(def suites
  {"unit" '[alma-spei.api.config-test alma-spei.api.http-test alma-spei.common.health-test
            alma-spei.api.auth.security-test alma-spei.common.auth-test
            alma-spei.common.donors-test alma-spei.api.donors-test alma-spei.dev.demo-test
            alma-spei.common.imports-test alma-spei.api.fingerprint-test alma-spei.api.bank-test]
   "integration" '[alma-spei.integration.database-test alma-spei.integration.auth-test
                   alma-spei.integration.donors-test alma-spei.integration.donor-api-test
                   alma-spei.integration.demo-test alma-spei.integration.imports-test alma-spei.integration.bank-upload-test alma-spei.integration.review-test]})

(defn -main [& [suite]]
  (let [namespaces (get suites suite)]
    (when-not (seq namespaces) (throw (ex-info "Unknown test suite" {})))
    (doseq [n namespaces] (require n))
    (let [{:keys [test fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))))
