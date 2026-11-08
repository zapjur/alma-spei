(ns alma-spei.ui.imports.review-location
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]))

(defn parse [hash]
  (if (= hash "#review") {:offset 0}
      (when-let [[_ id offset] (re-matches #"#review/([0-9a-fA-F-]{36})(?:/([0-9]{1,5}))?" (or hash ""))]
        (when (validation/valid? imports/identifier id)
          {:batch-id (str/lower-case id) :offset (if offset (js/parseInt offset 10) 0)}))))
(defn write! [{:keys [batch-id offset]}]
  (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location)
                                        "#review" (when batch-id (str "/" batch-id "/" (or offset 0))))))
(defn clear! []
  (when (parse (.-hash js/location))
    (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location)))))
