(ns alma-spei.common.decisions-test
  (:require [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.validation :as validation]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])))

(def identifier "00000000-0000-0000-0000-000000000001")
(def input {:batch-version 2 :version 0 :kind "existing-donor" :donor-id identifier :proposal-id nil
            :target-version 1 :financial {:currency nil :evidence "" :disposition "unresolved" :overlap-checked false}
            :donation-id nil :donation-version nil})
(deftest incomplete-identity-can-be-saved-without-invented-financial-facts
  (is (validation/valid? contract/decision-input input))
  (is (not (contract/complete? input)))
  (doseq [currency ["" "pln" "PL" "PLNN" "123"]]
    (is (not (validation/valid? contract/decision-input (assoc-in input [:financial :currency] currency)))))
  (let [ready (assoc input :financial {:currency "EUR" :evidence "Synthetic bank evidence" :disposition "new" :overlap-checked true})]
    (is (validation/valid? contract/decision-input ready))
    (is (contract/complete? ready))
    (is (not (contract/complete? (assoc-in ready [:financial :evidence] "  "))))
    (is (not (contract/complete? (assoc-in ready [:financial :overlap-checked] false))))))
(deftest targets-and-duplicate-references-are-exclusive-and-explicit
  (is (not (validation/valid? contract/decision-input (assoc input :proposal-id identifier))))
  (is (not (validation/valid? contract/decision-input (assoc input :kind "unresolved"))))
  (is (not (validation/valid? contract/decision-input (assoc-in input [:financial :disposition] "existing"))))
  (let [duplicate (-> input (assoc :donation-id identifier :donation-version 1) (assoc-in [:financial :disposition] "existing"))]
    (is (validation/valid? contract/decision-input duplicate))
    (is (not (validation/valid? contract/decision-input (assoc duplicate :kind "new-proposal" :donor-id nil :proposal-id identifier)))))
  (doseq [extra [{:reviewer-id identifier} {:complete true} {:received-on "2026-11-15"} {:actor "synthetic"}]]
    (is (not (validation/valid? contract/decision-input (merge input extra))))))
(deftest proposal-creation-requires-manual-profile-and-both-checks
  (let [proposal {:batch-version 1 :profile {:entity-type "individual" :name "Synthetic Person" :city "Synthetic City"
                                             :first-name nil :street nil :postal-code nil :province nil}
                  :checked-existing true :checked-proposals true}]
    (is (validation/valid? contract/proposal-create proposal))
    (is (not (validation/valid? contract/proposal-create (assoc proposal :checked-existing false))))
    (is (not (validation/valid? contract/proposal-create (assoc proposal :checked-proposals false))))
    (is (not (validation/valid? contract/proposal-create (assoc-in proposal [:profile :city] ""))))))
