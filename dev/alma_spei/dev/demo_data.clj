(ns alma-spei.dev.demo-data
  (:import [java.time LocalDate]
           [java.util UUID]))

(def expected {:donors 20 :payments 30 :active 18 :archived 2
               :entity-types {"individual" 12 "business" 4 "organization" 4}
               :total-pln 2650.0000M})

(defn stable-id [kind n]
  (UUID/nameUUIDFromBytes (.getBytes (str "alma-spei:synthetic-demo:v1:" (name kind) ":" n) "UTF-8")))

(def people
  [["Jan" "Kowalski"] ["Jan" "Kowalski"] ["Jakub" "Nowak"]
   ["Anna" "Wiśniewska"] ["Michał" "Wójcik"] ["Paweł" "Kowalczyk"]
   ["Zofia" "Kamińska"] ["Piotr" "Lewandowski"] ["Maria" "Zielińska"]
   ["Tomasz" "Szymański"] ["Emilia" "Woźniak"] ["Łukasz" "Dąbrowski"]])
(def businesses ["Zielony Zakątek sp. z o.o." "Piekarnia Pod Lipą" "Pracownia Jasny Dom" "Księgarnia Papierowy Świat"])
(def organizations ["Fundacja Dobry Gest" "Stowarzyszenie Wspólna Droga" "Koło Przyjaciół Ogrodu" "Fundacja Otwarta Dłoń"])
;; All combinations are invented for the local demo; no address or identity was sourced from a person.
(def locations [["ul. Lipowa" "30-001" "Kraków" "małopolskie"]
                ["ul. Słoneczna" "32-020" "Wieliczka" "małopolskie"]
                ["ul. Ogrodowa" "32-050" "Skawina" "małopolskie"]])

(defn donor [n]
  (let [[first-name surname] (when (<= n 12) (nth people (dec n)))
        entity-type (cond (<= n 12) "individual" (<= n 16) "business" :else "organization")
        label (cond first-name (str first-name " " surname)
                    (<= n 16) (nth businesses (- n 13))
                    :else (nth organizations (- n 17)))
        shared-address? (#{1 3} n)
        address-number (if shared-address? 1 n)
        [street postal-code city province] (nth locations (mod address-number 3))]
    {:id (stable-id :donor n) :entity-type entity-type
     :name label :first-name first-name
     :street (str street " " address-number "/4") :postal-code postal-code
     :city city :province province :is-active (not (#{10 20} n)) :version 1}))

(defn payment [n]
  (let [donor-number (inc (mod (dec n) 20))]
    {:id (stable-id :payment n) :donor-id (stable-id :donor donor-number)
     :amount (.setScale (* 10M donor-number) 4) :currency "PLN" :version 1
     :received-on (LocalDate/of 2026 (inc (mod (dec donor-number) 3)) donor-number)}))

(defn dataset []
  {:donors (mapv donor (range 1 21)) :payments (mapv payment (range 1 31))})
