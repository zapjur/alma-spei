(ns alma-spei.dev.demo-data
  (:import [java.time LocalDate]
           [java.util Locale UUID]))

(def expected {:donors 20 :payments 30 :active 18 :archived 2
               :entity-types {"individual" 12 "business" 4 "organization" 4}
               :total-pln 2650.0000M})

(defn stable-id [kind n]
  (UUID/nameUUIDFromBytes (.getBytes (str "alma-spei:synthetic-demo:v1:" (name kind) ":" n) "UTF-8")))

(def people
  [["Łukasz" "Próbowy"] ["Łukasz" "Próbowy"] ["Żaneta" "Testowa"]
   ["Łucja" "Wzorcowa"] ["Michał" "Próbny"] ["Paweł" "Wymyślony"]
   ["Zofia" "Demonstracyjna"] ["Jerzy" "Fikcyjny"] ["Anna" "Przykładowa"]
   ["Grzegorz" "Ćwiczebny"] ["Emilia" "Wzorowa"] ["Czesław" "Pokazowy"]])
(def businesses ["Firma Żuraw" "Firma Łąka" "Firma Ćma" "Firma Świerk"])
(def organizations ["Fundacja Żółwia" "Stowarzyszenie Łodzi" "Koło Ćwiczeń" "Organizacja Wzorów"])

(defn donor [n]
  (let [[first-name surname] (when (<= n 12) (nth people (dec n)))
        entity-type (cond (<= n 12) "individual" (<= n 16) "business" :else "organization")
        label (cond first-name (str first-name " " surname)
                    (<= n 16) (nth businesses (- n 13))
                    :else (nth organizations (- n 17)))
        shared-address? (#{1 3} n)]
    {:id (stable-id :donor n) :entity-type entity-type
     :name (str "DANE SYNTETYCZNE — " label) :first-name first-name
     :street (str "Próbna " (if shared-address? 1 n))
     :postal-code (String/format Locale/ROOT "00-%03d" (object-array [(if shared-address? 1 n)]))
     :city (if shared-address? "Testowo" (nth ["Przykładowo" "Wzorowo" "Testowo"] (mod n 3)))
     :province "Testowe" :is-active (not (#{10 20} n)) :version 1}))

(defn payment [n]
  (let [donor-number (inc (mod (dec n) 20))]
    {:id (stable-id :payment n) :donor-id (stable-id :donor donor-number)
     :amount (.setScale (* 10M donor-number) 4) :currency "PLN" :version 1
     :received-on (LocalDate/of 2026 (inc (mod (dec donor-number) 3)) donor-number)}))

(defn dataset []
  {:donors (mapv donor (range 1 21)) :payments (mapv payment (range 1 31))})
