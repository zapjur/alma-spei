(ns alma-spei.common.domain.donors
  (:require [clojure.string :as str]))

(def entity-types #{"individual" "business" "organization"})

(defn nonblank-text? [value]
  (boolean
   (and (string? value)
        (seq (str/replace value #"[\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]" "")))))

(defn postal-code? [value]
  (boolean (and (string? value) (re-matches #"[0-9]{2}-[0-9]{3}" value))))
