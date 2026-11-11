(ns alma-spei.api.donors-test
  (:require [alma-spei.api.audit.donors :as audit]
            [alma-spei.api.http.donors :as http]
            [clojure.test :refer [deftest is]]))

(deftest query-decoding-does-not-accept-ambiguous-or-unsafe-numbers
  (is (= {:limit 25 :offset 0 :search "Żółć"}
         (http/query-input {"limit" "25" "offset" "0" "search" "Żółć"})))
  (doseq [value ["-1" "1.5" "1e2" "999999999999999999999" ["1" "2"]]]
    (is (= value (:limit (http/query-input {"limit" value})))))
  (is (= {:unknown "x"} (http/query-input {"not-allowed" "x"}))))

(deftest field-audit-distinguishes-null-and-false-and-ignores-internals
  (is (= {:street {:before "Synthetic Street" :after nil}
          :is-active {:before true :after false}}
         (audit/changes {:street "Synthetic Street" :is-active true :version 1}
                        {:street nil :is-active false :version 2})))
  (is (= {} (audit/changes {:name "Synthetic Name" :version 1}
                           {:name "Synthetic Name" :version 2}))))
