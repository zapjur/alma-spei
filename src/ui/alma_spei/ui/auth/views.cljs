(ns alma-spei.ui.auth.views
  (:require [alma-spei.common.contracts.auth :as contract]
            [alma-spei.ui.donors.views :as donors]
            [alma-spei.ui.imports.views :as imports]
            [alma-spei.ui.imports.review-views :as review]
            [alma-spei.ui.imports.execution-views :as execution]
            [alma-spei.ui.imports.execution-location :as execution-location]
            [alma-spei.ui.imports.navigation :as navigation]
            [re-frame.core :as rf]
            [alma-spei.ui.imports.review-location :as review-location]
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
                (#{:login :password :logout} action) (assoc :screen :home :users nil)
                (= action :logout) (assoc :review-route nil :execution-route nil))
        state (cond-> state
                (and (#{:session :login :password} action) (:review-route state) (:user state)
                     (not (get-in state [:user :password-change-required]))) (assoc :screen :review)
                (and (#{:session :login :password} action) (:execution-route state) (:user state)
                     (not (get-in state [:user :password-change-required]))) (assoc :screen :execution))]
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

(defn navigate! [state screen]
  (when (navigation/allow-leave!)
    (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location)))
    (swap! state assoc :screen screen :temporary-password nil :review-route nil :execution-route nil)))
(defn review-route-state [state route]
  (let [revision (inc (get state :review-route-sequence 0))]
    (assoc state :screen :review :temporary-password nil :execution-route nil :review-route-sequence revision
           :review-route (assoc route :revision revision))))
(defn open-review! [state]
  (when (navigation/allow-leave!)
    (review-location/write! {:offset 0})
    (swap! state review-route-state {:offset 0})))

(defn execution-route-state [state route]
  (let [revision (inc (get state :execution-route-sequence 0))]
    (assoc state :screen :execution :temporary-password nil :review-route nil :execution-route-sequence revision :execution-route (assoc route :revision revision))))
(defn restore-route! [state]
  (let [current @(rf/subscribe [:execution/state]) review @(rf/subscribe [:review/state])]
    (cond
      (= :execution (:screen @state)) (execution-location/write! (or (:route current) (:execution-route @state)))
      (= :review (:screen @state)) (review-location/write! (if-let [work (:work review)] (select-keys work [:batch-id :offset]) {:offset 0}))
      :else (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location))))))
(defn hash-route! [state]
  (if (navigation/allow-leave!)
    (if-let [route (execution-location/parse (.-hash js/location))]
      (swap! state execution-route-state route)
      (if-let [route (review-location/parse (.-hash js/location))]
        (swap! state review-route-state route)
        (swap! state assoc :screen :home :review-route nil :execution-route nil)))
    (restore-route! state)))
(defn open-history! [_state]
  (when (navigation/allow-leave!) (set! (.-hash js/location) "#imports/history/0")))
(defn purge-imports! []
  (rf/dispatch-sync [:execution/close]) (rf/dispatch-sync [:review/close]) (rf/dispatch-sync [:imports/close]))
(defn nav-item [label active? disabled? action]
  [:button {:type "button" :class (str "sidebar-link" (when active? " is-active"))
            :aria-current (when active? "page") :disabled disabled? :on-click action} label])

(defn home [state disabled?]
  [:section {:class "home-screen" :aria-labelledby "home-heading"}
   [:header {:class "page-heading"}
    [:p {:class "eyebrow"} "TWOJE MIEJSCE PRACY"]
    [:h2 {:id "home-heading"} "Od czego zaczynamy?"]
    [:p "Znajdź darczyńcę lub przygotuj import nowych przelewów."]]
   [:div {:class "task-grid"}
    [:article {:class "task-card"}
     [:span {:class "task-symbol" :aria-hidden true} "01"]
     [:h3 "Baza darczyńców"]
     [:p "Wyszukaj osobę lub organizację. Sprawdź dane kontaktowe i historię wpłat."]
     [:button {:type "button" :class "button-primary" :disabled disabled?
               :on-click #(navigate! state :donors)} "Znajdź darczyńcę"]]
    [:article {:class "task-card"}
     [:span {:class "task-symbol" :aria-hidden true} "02"]
     [:h3 "Nowe przelewy"]
     [:p "Wybierz plik bankowy. Krok po kroku sprawdzisz dane przed zapisaniem zmian."]
     [:button {:type "button" :class "button-primary" :disabled disabled?
               :on-click #(navigate! state :imports)} "Importuj przelewy"]]]
   [:section {:class "resume-card" :aria-labelledby "resume-heading"}
    [:div [:h3 {:id "resume-heading"} "Masz już rozpoczęty import?"]
     [:p "Wróć do sprawdzania wierszy albo zobacz wynik wcześniejszego importu."]]
    [:div {:class "resume-actions"}
     [:button {:type "button" :disabled disabled? :on-click #(open-review! state)} "Wznów weryfikację"]
     [:button {:type "button" :class "button-quiet" :disabled disabled? :on-click #(open-history! state)} "Historia importów"]]]
   [:div {:class "home-guide"}
    [:h3 "Jak działa import?"]
    [:ol
     [:li [:strong "Wybierz plik"] [:span "Prześlij przelewy z banku."]]
     [:li [:strong "Sprawdź dane"] [:span "Przejrzyj błędy i przypisz darczyńców."]]
     [:li [:strong "Zatwierdź zmiany"] [:span "Najpierw zobaczysz pełne podsumowanie."]]]]])

(defn sidebar [state user screen disabled? busy]
  (r/with-let [expanded? (r/atom false)]
    [:aside {:class "app-sidebar" :aria-label "Panel aplikacji"}
     [:div {:class "brand"} [:span {:class "brand-mark" :aria-hidden true} "a"]
      [:div [:h1 "Alma Spei"] [:span "Baza darczyńców"]]]
     [:button {:type "button" :class "sidebar-toggle" :aria-expanded @expanded? :aria-controls "sidebar-navigation"
               :on-click #(swap! expanded? not)} (if @expanded? "Zamknij menu" "Menu")]
     [:div {:id "sidebar-navigation" :class (str "sidebar-menu" (when @expanded? " is-open"))}
      [:nav {:class "sidebar-nav" :aria-label "Nawigacja główna"}
       (when-not (:password-change-required user)
         [:<>
          [nav-item "Start" (= screen :home) disabled? #(navigate! state :home)]
          [nav-item "Darczyńcy" (= screen :donors) disabled? #(navigate! state :donors)]
          [:p {:class "nav-group-label"} "IMPORTY"]
          [nav-item "Nowy import" (= screen :imports) disabled? #(navigate! state :imports)]
          [nav-item "Do weryfikacji" (= screen :review) disabled? #(open-review! state)]
          [nav-item "Historia importów" (= screen :execution) disabled? #(open-history! state)]
          [:p {:class "nav-group-label"} "USTAWIENIA"]
          [nav-item "Zmień hasło" (= screen :password) disabled? #(navigate! state :password)]
          (when (:administrator user)
            [nav-item "Konta użytkowników" (= screen :accounts) disabled?
             #(when (navigate! state :accounts) (request! state :accounts "/api/accounts" "GET" nil))])])]
      [:div {:class "sidebar-account"}
       [:span {:class "account-label"} "Zalogowano jako"] [:strong (:display-name user)]
       [:button {:type "button" :class "button-quiet" :disabled busy
                 :on-click #(do (purge-imports!) (.replaceState js/history nil "" (str (.-pathname js/location) (.-search js/location)))
                                (request! state :logout "/api/auth/logout" "POST" {}))} "Wyloguj się"]]]]))

(defn panel []
  (r/with-let [route (review-location/parse (.-hash js/location))
               execution-route (execution-location/parse (.-hash js/location))
               state (r/atom {:busy true :screen (cond execution-route :execution route :review :else :home) :review-route route :execution-route execution-route})
               hash-handler (fn [_] (hash-route! state))
               _ (.addEventListener js/window "hashchange" hash-handler)
               _ (request! state :session "/api/auth/session" "GET" nil)]
    (let [{:keys [user csrf screen busy error temporary-password]} @state
          disabled? (or busy (navigation/operation?))]
      [:div {:class (if user "workspace" "signin-shell")}
       (when user [sidebar state user screen disabled? busy])
       [:main {:id "workspace-content" :class (if user "workspace-content" "signin-content") :tab-index -1 :aria-busy busy}
        (when-not user
          [:div {:class "brand signin-brand"} [:span {:class "brand-mark" :aria-hidden true} "a"]
           [:div [:h1 "Alma Spei"] [:span "Baza darczyńców"]]])
        (when error [:p {:role "alert" :class "notice notice-error"} error])
        (when busy [:p {:role "status"} "Proszę czekać…"])
        (when temporary-password
          [:aside {:role "status"}
           [:p "Hasło tymczasowe — przekaż je bezpiecznie. Po zamknięciu nie będzie można go ponownie wyświetlić."]
           [:output temporary-password]
           [:button {:on-click #(swap! state assoc :temporary-password nil)} "Zamknij hasło"]])
        (cond
          (nil? csrf) [:button {:disabled busy :on-click #(request! state :session "/api/auth/session" "GET" nil)} "Ponów połączenie"]
          (nil? user) [login-form state]
          (or (:password-change-required user) (= screen :password)) [password-form state]
          (and (= screen :accounts) (:administrator user)) [accounts state]
          (= screen :execution) [execution/panel #(request! state :session "/api/auth/session" "GET" nil) csrf (:execution-route @state)]
          (= screen :review) [review/panel #(request! state :session "/api/auth/session" "GET" nil) (:review-route @state) csrf]
          (= screen :imports) [imports/panel #(request! state :session "/api/auth/session" "GET" nil) csrf]
          (= screen :donors) [donors/panel #(request! state :session "/api/auth/session" "GET" nil) csrf]
          :else [home state disabled?])]])
    (finally (.removeEventListener js/window "hashchange" hash-handler) (reset! state {}))))
