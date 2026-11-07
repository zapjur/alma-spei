(ns alma-spei.dev.test-runner
  (:require [clojure.test :as test]))

(def suites
  {"unit" '[alma-spei.api.config-test alma-spei.api.http-test alma-spei.common.health-test
            alma-spei.api.auth.security-test alma-spei.common.auth-test]
   "integration" '[alma-spei.integration.database-test alma-spei.integration.auth-test]})

(defn -main [& [suite]]
  (let [namespaces (get suites suite)]
    (when-not (seq namespaces) (throw (ex-info "Unknown test suite" {})))
    (doseq [n namespaces] (require n))
    (let [{:keys [test fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))))
