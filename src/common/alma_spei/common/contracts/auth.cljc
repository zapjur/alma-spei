(ns alma-spei.common.contracts.auth
  (:require [clojure.string :as str]
            [malli.core :as m]))

(defn codepoints [s]
  #?(:clj (iterator-seq (.iterator (.codePoints ^String s)))
     :cljs (array-seq (js/Array.from s))))

(defn password-valid? [value]
  (boolean
   (and (string? value)
        (<= 8 (count (codepoints value)) 128)
        (not (re-find #"[\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]" value))
        #?(:clj (re-find #"\p{Lu}" value) :cljs (.test (js/RegExp. "\\p{Lu}" "u") value))
        #?(:clj (re-find #"\p{Nd}" value) :cljs (.test (js/RegExp. "\\p{Nd}" "u") value))
        #?(:clj (re-find #"[\p{P}\p{S}]" value) :cljs (.test (js/RegExp. "[\\p{P}\\p{S}]" "u") value)))))

(def password [:fn {:error/message "Hasło musi mieć 8–128 znaków, wielką literę, cyfrę i symbol. Nie może zawierać spacji."} password-valid?])
(def username [:re #"^[A-Za-z0-9._-]{3,64}$"])
(def display-name [:and [:string {:min 1 :max 120}] [:fn #(= % (str/trim %))]])
(defn canonical-username [s] (str/lower-case s))
(def login [:map {:closed true} [:username username] [:password [:string {:min 1 :max 256}]]])
(def change-password [:map {:closed true} [:current-password [:string {:min 1 :max 256}]] [:password password]])
(def create-account [:map {:closed true} [:username username] [:display-name display-name] [:administrator :boolean]])
(def edit-account [:map {:closed true} [:display-name display-name] [:active :boolean] [:administrator :boolean]])
(def principal [:map {:closed true} [:id :string] [:username :string] [:display-name :string]
                [:administrator :boolean] [:active :boolean] [:password-change-required :boolean]])
(def session-response [:map {:closed true} [:user [:maybe principal]] [:csrf :string]])
(def account-response [:map {:closed true} [:user principal]])
(def temporary-response [:map {:closed true} [:user principal] [:temporary-password [:string {:min 20}]]])
(def accounts-response [:map {:closed true} [:users [:vector principal]]])
(defn valid? [schema value] (m/validate schema value))
