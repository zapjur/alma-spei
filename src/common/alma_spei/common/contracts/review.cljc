(ns alma-spei.common.contracts.review
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.donors :as donors]))

(def queue-response
  [:map {:closed true} [:batches [:vector imports/batch]] [:total imports/row-count]
   [:limit [:int {:min 1 :max 100}]] [:offset imports/row-count]])
(def comparison-query [:map {:closed true} [:donor-id imports/identifier]])
(def locator [:map {:closed true} [:worksheet imports/positive-integer] [:row imports/positive-integer]])
(def comparison-response
  [:map {:closed true} [:batch imports/batch] [:row imports/staged-row] [:donor donors/donor]])
