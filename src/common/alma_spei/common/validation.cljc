(ns alma-spei.common.validation
  (:require [malli.core :as m]))

(defn valid? [schema value]
  (m/validate schema value))

(defn errors
  "Return portable field errors without rejected values or Malli internals."
  [schema value field-messages]
  (->> (:errors (m/explain schema value))
       (map (fn [{:keys [in type]}]
              (let [code (case type
                           :malli.core/missing-key :required
                           :malli.core/extra-key :unknown-field
                           :invalid)]
                {:field (if (= code :unknown-field) [] (vec in))
                 :code code
                 :message (if (= code :unknown-field)
                            "Formularz zawiera nieobsługiwane pole."
                            (get field-messages (first in) "Nieprawidłowe dane formularza."))})))
       distinct
       vec))
