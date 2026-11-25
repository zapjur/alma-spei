(ns alma-spei.common.contracts.reconciliation
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.decisions :as decisions]
            [alma-spei.common.contracts.donors :as donors]))
(def policy [:map {:closed true} [:currency decisions/currency] [:new-donor-type [:maybe [:enum "individual" "business" "organization"]]]])
(def input [:map {:closed true} [:batch-version imports/positive-integer] [:policy policy]])
(def exception-row [:map {:closed true} [:row imports/staged-row] [:reason :string]
                    [:new-profile-candidate :boolean]
                    [:candidates [:vector (conj donors/donor [:match-reason :string])]]])
(def queue-filter [:enum "exceptions" "exact"])
(def page-query (conj imports/page-query [:queue {:optional true} queue-filter]))
(def exact-match [:map {:closed true} [:row imports/staged-row] [:donor donors/donor]])

(def counts [:map {:closed true} [:matched imports/row-count] [:new-donors imports/row-count]
             [:new-payments imports/row-count] [:duplicates imports/row-count] [:exceptions imports/row-count]
             [:total imports/row-count]])
(def response [:map {:closed true} [:batch imports/batch] [:catalog-version imports/positive-integer]
               [:policy [:maybe policy]] [:counts counts]
               [:totals [:vector [:map {:closed true} [:currency decisions/currency] [:amount :string]]]] [:exceptions [:vector exception-row]]
               [:queue queue-filter] [:exact-match-count imports/row-count] [:exact-matches [:vector exact-match]]
               [:limit imports/positive-integer] [:offset imports/row-count] [:ready :boolean]])
(def resolution [:map {:closed true} [:batch-version imports/positive-integer] [:catalog-version imports/positive-integer]
                 [:worksheet imports/positive-integer] [:row imports/positive-integer]
                 [:kind [:enum "existing-donor" "new-proposal"]] [:target-id imports/identifier] [:target-version imports/positive-integer]])
