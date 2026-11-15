(ns alma-spei.api.config-test
  (:require [alma-spei.api.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def defaults (edn/read-string (slurp (io/resource "config.edn"))))
(deftest precedence
  (let [resolved (config/resolve-config defaults {:http {:port 3001}}
                                        {"ALMA_HTTP_PORT" "3002" "ALMA_DB_PASSWORD_FILE" "example"}
                                        (constantly "synthetic-secret\n"))]
    (is (= 3002 (get-in resolved [:http :port])))
    (is (= "127.0.0.1" (get-in resolved [:http :host])))
    (is (= "synthetic-secret" (get-in resolved [:database :password])))))

(deftest invalid-and-secret-configuration
  (doseq [env [{} {"ALMA_DB_PASSWORD" "synthetic-sensitive-value" "ALMA_HTTP_PORT" "no"}
               {"ALMA_DB_PASSWORD" "synthetic-sensitive-value" "ALMA_HTTP_PORT" "65536"}
               {"ALMA_DB_PASSWORD" "synthetic-sensitive-value" "ALMA_HTTP_HOST" "0.0.0.0"}
               {"ALMA_DB_PASSWORD" "synthetic-sensitive-value" "ALMA_DB_PASSWORD_FILE" "file"}]]
    (try (config/resolve-config defaults {} env slurp)
         (is false "Expected rejection")
         (catch clojure.lang.ExceptionInfo error
           (is (empty? (ex-data error)))
           (is (not (.contains (.getMessage error) "synthetic-sensitive-value")))))))

(deftest synthetic-import-mode-is-explicit-and-typed
  (let [base {"ALMA_DB_PASSWORD" "synthetic-config-secret"}]
    (is (not (true? (:synthetic-imports (config/resolve-config defaults {} base slurp)))))
    (is (true? (:synthetic-imports (config/resolve-config defaults {} (assoc base "ALMA_SYNTHETIC_IMPORTS" "true") slurp))))
    (is (false? (:synthetic-imports (config/resolve-config defaults {} (assoc base "ALMA_SYNTHETIC_IMPORTS" "false") slurp))))
    (doseq [value ["yes" "1" "TRUE" ""]]
      (is (thrown? clojure.lang.ExceptionInfo (config/resolve-config defaults {} (assoc base "ALMA_SYNTHETIC_IMPORTS" value) slurp))))))
