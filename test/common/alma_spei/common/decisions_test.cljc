(ns alma-spei.common.decisions-test
  (:require [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.contracts.execution :as execution]
            [alma-spei.common.validation :as validation]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])))

(def identifier "00000000-0000-0000-0000-000000000001")
(def input {:catalog-version 1 :batch-version 2 :version 0 :kind "existing-donor" :donor-id identifier :proposal-id nil
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
  (let [proposal {:catalog-version 1 :batch-version 1 :profile {:entity-type "individual" :name "Synthetic Person" :city "Synthetic City"
                                                                :first-name nil :street nil :postal-code nil :province nil}
                  :checked-existing true :checked-proposals true}]
    (is (validation/valid? contract/proposal-create proposal))
    (is (not (validation/valid? contract/proposal-create (assoc proposal :checked-existing false))))
    (is (not (validation/valid? contract/proposal-create (assoc proposal :checked-proposals false))))
    (is (not (validation/valid? contract/proposal-create (assoc-in proposal [:profile :city] ""))))))

(deftest commit-and-compensation-requests-are-closed-and-explicit
  (let [request {:batch-id identifier :batch-version 2 :preview-id identifier :effect-digest (apply str (repeat 64 "a"))
                 :catalog-version 3 :backup-id identifier :confirmed true}
        undo {:batch-id identifier :batch-version 4 :commit-id identifier :confirmed true}]
    (is (validation/valid? execution/commit-input request))
    (is (validation/valid? execution/undo-input undo))
    (doseq [changed [(dissoc request :catalog-version) (assoc request :confirmed false)
                     (assoc request :skip-backup true) (assoc request :actor-id identifier) (assoc request :payments [])]]
      (is (not (validation/valid? execution/commit-input changed))))
    (is (not (validation/valid? execution/undo-input (dissoc undo :commit-id))))
    (is (not (validation/valid? execution/undo-input (assoc undo :force true))))))
