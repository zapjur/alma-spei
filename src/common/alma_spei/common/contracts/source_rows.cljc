(ns alma-spei.common.contracts.source-rows
  (:require [alma-spei.common.validation :as validation]
            [clojure.string :as str]))

(defn cell-text? [value]
  (and (string? value) (<= 1 (count value) 4096)
       (not (str/blank? value)) (not (str/includes? value "\u0000"))))

(def cell-text [:fn cell-text?])
(def decimal-text [:fn (fn [value] (and (string? value) (<= (count value) 4096)
                                        (boolean (re-matches #"[0-9]+(?:\.[0-9]+)?" value))))])

(def observation
  [:map {:closed true}
   [:column [:int {:min 7 :max 24}]]
   [:year {:optional true} [:int {:min 2021 :max 2025}]]
   [:source-type [:enum "text" "number" "boolean"]]
   [:value cell-text]])

(def master-row
  [:map {:closed true}
   [:name cell-text]
   [:street {:optional true} cell-text]
   [:postal-code {:optional true} cell-text]
   [:city {:optional true} cell-text]
   [:province {:optional true} cell-text]
   [:gender {:optional true} cell-text]
   [:observations [:vector {:max 18} observation]]])

(def tax-subject
  [:and
   [:map {:closed true}
    [:first-name {:optional true} cell-text]
    [:surname {:optional true} cell-text]
    [:address {:optional true} cell-text]]
   [:fn (comp boolean seq)]])

(def tax-row
  [:map {:closed true}
   [:taxpayer {:optional true} tax-subject]
   [:spouse {:optional true} tax-subject]
   [:purpose {:optional true} cell-text]
   [:amount decimal-text]])

(def messages
  {:name "Podaj pełną nazwę darczyńcy."
   :street "Nieprawidłowa ulica."
   :postal-code "Nieprawidłowy kod pocztowy."
   :city "Nieprawidłowa miejscowość."
   :province "Nieprawidłowe województwo."
   :gender "Nieprawidłowa wartość pola płci."
   :observations "Nieprawidłowa wartość obserwacji źródłowej."
   :taxpayer "Nieprawidłowe dane podatnika."
   :spouse "Nieprawidłowe dane małżonka."
   :purpose "Nieprawidłowy cel szczegółowy."
   :amount "Podaj kwotę w zapisie dziesiętnym z kropką."})

(defn errors [schema row]
  (validation/errors schema row messages))
