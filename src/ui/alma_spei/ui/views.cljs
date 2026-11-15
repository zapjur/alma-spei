(ns alma-spei.ui.views
  (:require [re-frame.core :as rf]
            [alma-spei.ui.auth.views :as auth]))

(def messages
  {:loading "Sprawdzanie połączenia…"
   :ok "Aplikacja jest gotowa."
   :unavailable "Połączenie z bazą danych jest niedostępne. Spróbuj ponownie."
   :invalid "Nie udało się odczytać odpowiedzi serwera."})

(defn app []
  (let [status @(rf/subscribe [:health/status])]
    [:<>
     [:a {:class "skip-link" :href "#workspace-content"
          :on-click (fn [event] (.preventDefault event) (some-> (js/document.getElementById "workspace-content") .focus))}
      "Przejdź do treści"]
     (when (#{:unavailable :invalid} status)
       [:div {:class "connection-notice" :role "status"}
        [:span (get messages status)]
        [:button {:type "button" :on-click #(rf/dispatch [:app/initialize])} "Sprawdź ponownie"]])
     [auth/panel]]))
