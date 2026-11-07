(ns alma-spei.common.contracts.donors
  (:require [alma-spei.common.domain.donors :as domain]
            [alma-spei.common.validation :as validation]))

(def entity-type [:enum "individual" "business" "organization"])
(def required-text [:fn domain/nonblank-text?])
(def postal-code [:fn domain/postal-code?])
(def version [:int {:min 1 :max 9007199254740991}])
(def identifier [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

;; Manual entry is stricter than nullable persisted/source address data.
(def profile-fields
  [[:entity-type entity-type]
   [:name required-text]
   [:city required-text]
   [:first-name {:optional true} [:maybe :string]]
   [:street {:optional true} [:maybe :string]]
   [:postal-code {:optional true} [:maybe postal-code]]
   [:province {:optional true} [:maybe :string]]])

(def create-donor (into [:map {:closed true}] profile-fields))
(def update-donor (into [:map {:closed true} [:version version]] profile-fields))

(def donor
  [:map {:closed true}
   [:id identifier]
   [:entity-type entity-type]
   [:name required-text]
   [:first-name [:maybe :string]]
   [:street [:maybe :string]]
   [:postal-code [:maybe :string]]
   [:city [:maybe :string]]
   [:province [:maybe :string]]
   [:is-active :boolean]
   [:version version]])

;; Query values here are typed, after HTTP decoding; raw query coercion belongs
;; in the backend. Pagination bounds limit work without exposing SQL identifiers.
(def list-query
  [:map {:closed true}
   [:search {:optional true} [:string {:max 200}]]
   [:entity-type {:optional true} entity-type]
   [:status {:optional true} [:enum "active" "archived" "all"]]
   [:sort-by {:optional true} [:enum "name" "city" "entity-type"]]
   [:sort-direction {:optional true} [:enum "asc" "desc"]]
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0 :max 9007199254740991}]]])

(def donor-response [:map {:closed true} [:donor donor]])
(def donors-response
  [:map {:closed true}
   [:donors [:vector donor]]
   [:total [:int {:min 0 :max 9007199254740991}]]])

(def page-query
  [:map {:closed true}
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0 :max 9007199254740991}]]])

(def donation
  [:map {:closed true}
   [:id identifier]
   [:amount [:re #"^-?[0-9]{1,16}\.[0-9]{4}$"]]
   [:currency [:re #"^[A-Z]{3}$"]]
   [:received-on [:re #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"]]])

(def detail-response
  [:map {:closed true} [:donor donor] [:donations [:vector donation]]
   [:donations-total [:int {:min 0 :max 9007199254740991}]]])

(def audit-value [:maybe [:or :string :boolean]])
(def audit-change
  [:map {:closed true}
   [:field [:enum "entity-type" "name" "first-name" "street" "postal-code" "city" "province" "is-active"]]
   [:before audit-value] [:after audit-value]])
(def audit-event
  [:map {:closed true} [:id identifier] [:actor-id identifier]
   [:occurred-at :string] [:action [:enum "create" "update" "archive" "merge"]]
   [:changes [:vector audit-change]]])
(def history-response
  [:map {:closed true} [:events [:vector audit-event]]
   [:total [:int {:min 0 :max 9007199254740991}]]])

(def field-messages
  {:entity-type "Wybierz osobę, firmę lub organizację."
   :name "Podaj nazwę lub imię i nazwisko darczyńcy."
   :city "Podaj miejscowość."
   :first-name "Imię musi być tekstem."
   :street "Ulica musi być tekstem."
   :postal-code "Podaj kod pocztowy w formacie XX-XXX."
   :province "Województwo musi być tekstem."
   :version "Nieprawidłowa wersja danych. Odśwież formularz."
   :id "Nieprawidłowy identyfikator darczyńcy."
   :search "Wyszukiwanie może mieć najwyżej 200 znaków."
   :status "Wybierz prawidłowy status darczyńcy."
   :sort-by "Wybierz prawidłową kolumnę sortowania."
   :sort-direction "Wybierz prawidłowy kierunek sortowania."
   :limit "Liczba wyników musi wynosić od 1 do 100."
   :offset "Nieprawidłowa pozycja na liście wyników."})

(defn errors [schema value]
  (validation/errors schema value field-messages))
