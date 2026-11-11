(ns alma-spei.ui.auth.views
  (:require [alma-spei.common.contracts.auth :as contract]
            [alma-spei.ui.donors.views :as donors]
            [reagent.core :as r]))

(defn result-state [state action result]
  (let [own-account? (= (get-in state [:user :id]) (get-in result [:user :id]))
        auth-action? (#{:session :login :password :logout} action)
        state (cond-> (assoc state :busy false :error nil :temporary-password nil)
                (contains? result :csrf) (assoc :csrf (:csrf result))
                auth-action? (assoc :user (:user result))
                (and own-account? (= action :edit)) (assoc :user (:user result))
                (and own-account? (= action :reset)) (assoc :user nil :users nil :screen :home)
                (= action :accounts) (assoc :users (:users result))
                (:temporary-password result) (assoc :temporary-password (:temporary-password result))
                (#{:login :password :logout} action) (assoc :screen :home :users nil))]
    (if (and (:users state) (#{:create :edit :reset} action))
      (update state :users
              (fn [users]
                (let [changed (:user result)]
                  (if (some #(= (:id %) (:id changed)) users)
                    (mapv #(if (= (:id %) (:id changed)) changed %) users)
                    (conj (vec users) changed)))))
      state)))

(defn request! [state action path method input]
  (swap! state assoc :busy true :error nil :temporary-password nil)
  (-> (js/fetch path
                (clj->js (cond-> {:method method :credentials "same-origin"
                                  :headers {"Content-Type" "application/json"
                                            "X-CSRF-Token" (:csrf @state)}}
                           input (assoc :body (js/JSON.stringify (clj->js input))))))
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [body]
                            (let [result (js->clj body :keywordize-keys true)]
                              (if (.-ok response)
                                (swap! state result-state action result)
                                (swap! state assoc :busy false :error (:message result)
                                       :user (if (= 401 (.-status response)) nil (:user @state))))))))))
      (.catch (fn [_] (swap! state assoc :busy false :error "Nie udało się połączyć z serwerem. Spróbuj ponownie.")))))

(defn field [label name type & [attrs]]
  [:label label [:input (merge {:name name :type type :required true} attrs)]])
(defn values [event]
  (.preventDefault event)
  (let [form (.-currentTarget event)
        data (js/FormData. form)]
    (.reset form)
    data))
(defn password-field [label name current?]
  [field label name "password" {:auto-complete (if current? "current-password" "new-password")
                                :min-length (if current? 1 8) :max-length 256}])
(defn policy [] [:p "Hasło: co najmniej 8 znaków, wielka litera, cyfra i symbol; bez spacji."])

(defn login-form [state]
  [:form {:on-submit (fn [event]
                       (let [data (values event)]
                         (request! state :login "/api/auth/login" "POST"
                                   {:username (.get data "username") :password (.get data "password")})))}
   [:h2 "Logowanie"]
   [field "Nazwa użytkownika" "username" "text" {:auto-complete "username" :max-length 64}]
   [password-field "Hasło" "password" true]
   [:button {:disabled (:busy @state)} "Zaloguj się"]])

(defn password-form [state]
  [:form {:on-submit (fn [event]
                       (let [data (values event)
                             password (.get data "password")]
                         (if-not (contract/password-valid? password)
                           (swap! state assoc :error "Nowe hasło nie spełnia wymagań.")
                           (if-not (= password (.get data "confirmation"))
                             (swap! state assoc :error "Nowe hasła muszą być takie same.")
                             (request! state :password "/api/auth/password" "POST"
                                       {:current-password (.get data "current") :password password})))))}
   [:h2 "Zmiana hasła"]
   (when (get-in @state [:user :password-change-required]) [:p "Przed rozpoczęciem pracy zmień hasło tymczasowe."])
   [policy]
   [password-field "Obecne hasło" "current" true]
   [password-field "Nowe hasło" "password" false]
   [password-field "Powtórz nowe hasło" "confirmation" false]
   [:button {:disabled (:busy @state)} "Zmień hasło"]])

(defn edit-form [state user]
  [:form {:key (str (:id user) (:display-name user) (:active user) (:administrator user))
          :on-submit (fn [event]
                       (let [data (values event)]
                         (when (js/confirm "Zapisać zmiany uprawnień i danych konta? Wyłączenie konta zakończy jego sesje.")
                           (request! state :edit (str "/api/accounts/" (:id user)) "PATCH"
                                     {:display-name (.get data "display-name")
                                      :active (= "on" (.get data "active"))
                                      :administrator (= "on" (.get data "administrator"))}))))}
   [:h3 (:username user)]
   [field "Nazwa wyświetlana" "display-name" "text" {:default-value (:display-name user) :max-length 120}]
   [:label [:input {:type "checkbox" :name "active" :default-checked (:active user)}] "Konto aktywne"]
   [:label [:input {:type "checkbox" :name "administrator" :default-checked (:administrator user)}] "Zarządzanie kontami"]
   [:button {:disabled (:busy @state)} "Zapisz"]
   [:button {:type "button" :disabled (:busy @state)
             :on-click #(when (js/confirm "Zresetować hasło? Wszystkie sesje tego użytkownika zostaną zakończone.")
                          (request! state :reset (str "/api/accounts/" (:id user) "/reset-password") "POST" {}))}
    "Resetuj hasło"]])

(defn accounts [state]
  [:section
   [:h2 "Zarządzanie kontami"]
   [:button {:disabled (:busy @state)
             :on-click #(request! state :accounts "/api/accounts" "GET" nil)} "Odśwież listę kont"]
   [:form {:on-submit (fn [event]
                        (let [data (values event)]
                          (request! state :create "/api/accounts" "POST"
                                    {:username (.get data "username") :display-name (.get data "display-name")
                                     :administrator (= "on" (.get data "administrator"))})))}
    [:h3 "Nowe konto"]
    [field "Nazwa użytkownika (niezmienna)" "username" "text" {:min-length 3 :max-length 64 :pattern "[A-Za-z0-9._-]{3,64}"}]
    [field "Nazwa wyświetlana" "display-name" "text" {:max-length 120}]
    [:label [:input {:name "administrator" :type "checkbox"}] "Zarządzanie kontami"]
    [:button {:disabled (:busy @state)} "Utwórz konto"]]
   (for [user (:users @state)] ^{:key (:id user)} [edit-form state user])])

(defn panel []
  (r/with-let [state (r/atom {:busy true :screen :home})
               _ (request! state :session "/api/auth/session" "GET" nil)]
    (let [{:keys [user csrf screen busy error temporary-password]} @state]
      [:section {:aria-busy busy}
       (when error [:p {:role "alert"} error])
       (when busy [:p {:role "status"} "Proszę czekać…"])
       (when temporary-password
         [:aside {:role "status"}
          [:p "Hasło tymczasowe — przekaż je bezpiecznie. Po zamknięciu nie będzie można go ponownie wyświetlić."]
          [:output temporary-password]
          [:button {:on-click #(swap! state assoc :temporary-password nil)} "Zamknij hasło"]])
       (cond
         (nil? csrf) [:button {:disabled busy :on-click #(request! state :session "/api/auth/session" "GET" nil)} "Ponów połączenie"]
         (nil? user) [login-form state]
         :else
         [:<>
          [:p (str "Zalogowano: " (:display-name user))]
          [:nav
           (when-not (:password-change-required user)
             [:<>
              [:button {:disabled busy :on-click #(swap! state assoc :screen :home :temporary-password nil)} "Start"]
              [:button {:disabled busy :on-click #(swap! state assoc :screen :donors :temporary-password nil)} "Darczyńcy"]
              [:button {:disabled busy :on-click #(swap! state assoc :screen :password :temporary-password nil)} "Zmień hasło"]
              (when (:administrator user)
                [:button {:disabled busy
                          :on-click #(do (swap! state assoc :screen :accounts :temporary-password nil)
                                         (request! state :accounts "/api/accounts" "GET" nil))} "Konta użytkowników"])])
           [:button {:disabled busy :on-click #(request! state :logout "/api/auth/logout" "POST" {})} "Wyloguj się"]]
          (cond
            (or (:password-change-required user) (= screen :password)) [password-form state]
            (and (= screen :accounts) (:administrator user)) [accounts state]
            (= screen :donors) [donors/panel #(request! state :session "/api/auth/session" "GET" nil)]
            :else [:section
                   [:h2 "Start"]
                   [:p "Wybierz zadanie, aby rozpocząć pracę."]
                   [:button {:type "button" :disabled busy
                             :on-click #(swap! state assoc :screen :donors :temporary-password nil)} "Przejdź do darczyńców"]])])])
    (finally (reset! state {}))))
