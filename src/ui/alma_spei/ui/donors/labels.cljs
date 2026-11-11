(ns alma-spei.ui.donors.labels)

(def entity-types {"individual" "Osoba" "business" "Firma" "organization" "Organizacja"})
(def fields {:entity-type "Typ darczyńcy" :name "Nazwa / imię i nazwisko" :first-name "Imię (jeśli podano osobno)"
             :street "Ulica i numer" :postal-code "Kod pocztowy" :city "Miejscowość" :province "Województwo" :is-active "Aktywny"})
(def actions {"create" "Utworzenie profilu" "update" "Edycja profilu" "archive" "Archiwizacja" "merge" "Połączenie profili"})
