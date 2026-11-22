(ns alma-spei.common.domain.donor-identity
  (:require [clojure.string :as str]))

(defn text-key [value]
  (let [raw (or value "")
        canonical #?(:clj (java.text.Normalizer/normalize raw java.text.Normalizer$Form/NFC)
                     :cljs (.normalize raw "NFC"))]
    (-> #?(:clj (.toLowerCase ^String canonical java.util.Locale/ROOT)
           :cljs (.toLowerCase canonical))
        (str/replace #"[\t-\r \u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+" " ")
        str/trim)))

(defn folded-key [value]
  (str/escape (text-key value)
              {\ą \a \ć \c \ę \e \ł \l \ń \n \ó \o \ś \s \ź \z \ż \z}))

(defn postal-key [value]
  (let [key (text-key value)]
    (if-let [[_ prefix suffix] (re-matches #"([0-9]{2})(?: *- *| *)([0-9]{3})" key)]
      (str prefix "-" suffix)
      key)))

(defn street-key [value]
  (-> (text-key value)
      (str/replace #"^(ul\.?|ulica) " "ul. ")
      (str/replace #"^(al\.?|aleja) " "al. ")
      (str/replace #"^(os\.?|osiedle) " "os. ")))
