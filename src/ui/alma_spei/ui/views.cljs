(ns alma-spei.ui.views
  (:require [re-frame.core :as rf]))

(def messages
  {:loading "Sprawdzanie połączenia…"
   :ok "Aplikacja jest gotowa."
   :unavailable "Połączenie z bazą danych jest niedostępne. Spróbuj ponownie."
   :invalid "Nie udało się odczytać odpowiedzi serwera."})

(defn app []
  (let [status @(rf/subscribe [:health/status])]
    [:main
     [:h1 "Alma Spei"]
     [:p "Baza darczyńców"]
     [:p {:role "status" :aria-live "polite"} (get messages status)]
     [:button {:type "button" :disabled (= :loading status)
               :on-click #(rf/dispatch [:app/initialize])} "Sprawdź ponownie"]]))
