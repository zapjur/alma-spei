(ns alma-spei.ui.donors.detail-views
  (:require [alma-spei.ui.donors.detail-effects]
            [alma-spei.ui.donors.detail-events :as events]
            [alma-spei.ui.donors.labels :as labels]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(defn focus-heading [element] (when element (.focus element)))
(defn refresh! [card]
  (when (or (not (events/dirty? card)) (js/confirm "Wczytać aktualne dane i odrzucić niezapisane zmiany?"))
    (rf/dispatch [:donors/card-reload])))

(defn date-label [value]
  (str/join "." (reverse (str/split value #"-"))))
(defn time-label [value]
  (let [date (js/Date. value)]
    (if (js/isNaN (.getTime date)) "Nieznana data" (.toLocaleString date "pl-PL"))))
(defn audit-label [field value]
  (cond (nil? value) "Brak danych"
        (= "" value) "Pusty tekst"
        (= field "entity-type") (get labels/entity-types value value)
        (boolean? value) (if value "Tak" "Nie")
        :else value))

(defn read-error [error retry refresh-session]
  [:div {:role "alert"}
   [:p (case error :not-found "Nie znaleziono darczyńcy. Wróć do listy."
             :unauthenticated "Sesja wygasła. Zaloguj się ponownie."
             :forbidden "Nie można wyświetlić danych. Odśwież sesję."
             :invalid-response "Nie udało się odczytać danych darczyńcy. Spróbuj ponownie."
             "Nie udało się pobrać danych. Sprawdź połączenie i spróbuj ponownie.")]
   (if (#{:unauthenticated :forbidden} error)
     [:button {:type "button" :on-click refresh-session} "Odśwież sesję"]
     (when-not (= error :not-found) [:button {:type "button" :on-click retry} "Spróbuj ponownie"]))])

(defn page-controls [card channel title]
  (let [{:keys [offset limit rows total status]} (get card channel)
        ready? (and (= :ready status) (not= :saving (:save-status card)))]
    [:nav {:class "donor-pagination" :aria-label (str "Strony " title)}
     [:button {:type "button" :disabled (not (and ready? (pos? offset))) :aria-label (str "Poprzednia strona " title)
               :on-click #(rf/dispatch [:donors/card-page channel :previous])} "Poprzednia"]
     [:span {:role "status" :aria-live "polite"}
      (when (= :ready status) (if (empty? rows) "Pozycje na stronie: 0"
                                  (str "Pozycje: " (inc offset) "–" (+ offset (count rows)) " z " total)))]
     [:button {:type "button" :disabled (not (and ready? (< (+ offset limit) total))) :aria-label (str "Następna strona " title)
               :on-click #(rf/dispatch [:donors/card-page channel :next])} "Następna"]]))

(defn payment-history [card refresh-session]
  (let [{:keys [status rows error]} (:payments card)]
    [:section {:aria-labelledby "payments-heading" :aria-busy (= :loading status)}
     [:h3 {:id "payments-heading"} "Historia wpłat"]
     (case status
       :loading [:p {:role "status"} "Pobieranie historii wpłat…"]
       :error [read-error error #(rf/dispatch [:donors/card-retry :payments]) refresh-session]
       (if (empty? rows) [:p "Brak wpłat dla tego darczyńcy."]
           [:div {:class "donor-table-scroll" :role "region" :aria-label "Historia wpłat" :tab-index 0}
            [:table {:class "donor-payment-table"}
             [:caption "Wpłaty — tylko do odczytu"]
             [:thead [:tr [:th {:scope "col"} "Data wpływu"] [:th {:scope "col"} "Kwota"] [:th {:scope "col"} "Waluta"]]]
             [:tbody (for [payment rows]
                       ^{:key (:id payment)} [:tr [:td (date-label (:received-on payment))]
                                              [:td {:class "donor-amount"} (str/replace (:amount payment) "." ",")]
                                              [:td (:currency payment)]])]]]))
     [page-controls card :payments "historii wpłat"]]))

(defn change-history [card refresh-session]
  (let [{:keys [status rows error]} (:history card)]
    [:section {:aria-labelledby "changes-heading" :aria-busy (= :loading status)}
     [:h3 {:id "changes-heading"} "Historia zmian profilu"]
     (case status
       :loading [:p {:role "status"} "Pobieranie historii zmian…"]
       :error [read-error error #(rf/dispatch [:donors/card-retry :history]) refresh-session]
       (if (empty? rows) [:p "Brak zarejestrowanych zmian profilu."]
           [:div {:class "donor-change-history"}
            (for [event rows]
              ^{:key (:id event)}
              [:article
               [:h4 (get labels/actions (:action event))]
               [:p [:time {:date-time (:occurred-at event)} (time-label (:occurred-at event))]
                " · " (:actor-name event)]
               [:table {:class "donor-change-table"}
                [:caption "Zmienione pola"]
                [:thead [:tr [:th {:scope "col"} "Pole"] [:th {:scope "col"} "Przed zmianą"] [:th {:scope "col"} "Po zmianie"]]]
                [:tbody (for [change (:changes event)]
                          ^{:key (:field change)}
                          [:tr [:th {:scope "row"} (get labels/fields (keyword (:field change)))]
                           [:td (audit-label (:field change) (:before change))]
                           [:td (audit-label (:field change) (:after change))]])]]])]))
     [page-controls card :history "historii zmian"]]))

(defn error-for [errors field]
  (:message (first (filter #(= [field] (:field %)) errors))))

(defn edit-field [card field]
  (let [id (str "donor-edit-" (name field))
        error (error-for (:field-errors card) field)
        attrs {:id id :value (get-in card [:draft field] "") :disabled (= :saving (:save-status card))
               :aria-invalid (boolean error) :aria-describedby (when error (str id "-error"))
               :on-change #(rf/dispatch [:donors/edit-field field (.. % -target -value)])}]
    [:div {:class "donor-edit-field"}
     [:label {:for id} (get labels/fields field) (when (#{:entity-type :name :city} field) " *")]
     (if (= field :entity-type)
       [:select attrs [:option {:value "individual"} "Osoba"] [:option {:value "business"} "Firma"] [:option {:value "organization"} "Organizacja"]]
       [:input (assoc attrs :type "text" :auto-complete "off" :required (boolean (#{:name :city} field)))])
     (when error [:p {:id (str id "-error") :class "donor-field-error" :role "alert"} error])]))

(defn edit-form [card csrf]
  (let [blocked? (or (#{:saving :conflict :uncertain} (:save-status card)) (seq (:field-errors card)))]
    [:form {:class "donor-edit-form" :no-validate true
            :on-submit (fn [e] (.preventDefault e) (rf/dispatch [:donors/card-save csrf]))}
     [:h3 "Edycja danych darczyńcy"]
     [:p "Pola oznaczone * są wymagane."]
     [:div {:class "donor-edit-grid"}
      (for [field events/editable-fields] ^{:key field} [edit-field card field])]
     (when-let [error (:save-error card)]
       [:div {:role "alert"}
        [:p (case error
              :conflict "Dane darczyńcy zostały zmienione przez inną osobę. Twoje zmiany nie zostały zapisane. Zachowaj potrzebne zmiany z formularza, a następnie wczytaj aktualne dane."
              :uncertain "Nie udało się potwierdzić zapisu. Odśwież kartę, aby sprawdzić, czy zmiany zostały zapisane."
              :not-found "Nie znaleziono darczyńcy. Zmiany nie zostały zapisane."
              :too-large "Dane formularza są zbyt długie. Skróć wpisane wartości."
              "Sprawdź zaznaczone pola. Zmiany nie zostały zapisane.")]
        (when (#{:conflict :uncertain} error)
          [:button {:type "button" :on-click #(refresh! card)} "Wczytaj aktualne dane"])])
     (when (= :saving (:save-status card)) [:p {:role "status"} "Zapisywanie zmian…"])
     [:button {:type "submit" :disabled (boolean blocked?)} "Zapisz zmiany"]
     [:button {:type "button" :disabled (= :saving (:save-status card))
               :on-click #(rf/dispatch [:donors/edit-cancel])} "Anuluj edycję"]]))

(defn card [state refresh-session csrf]
  (let [profile (:profile state)
        saving? (= :saving (:save-status state))]
    [:section {:class "donor-details" :aria-labelledby "donor-detail-heading"}
     [:button {:type "button" :disabled saving?
               :on-click #(when (or (not (events/dirty? state)) (js/confirm "Porzucić niezapisane zmiany i wrócić do listy?"))
                            (rf/dispatch [:donors/back]))} "Wróć do listy"]
     [:h2 {:id "donor-detail-heading" :tab-index -1 :ref focus-heading} (or (:name profile) "Karta darczyńcy")]
     (when (= :saved (:notice state)) [:p {:role "status"} "Zapisano zmiany."])
     (cond
       (= :loading (:status state)) [:p {:role "status"} "Pobieranie danych darczyńcy…"]
       (nil? profile) [read-error (get-in state [:payments :error]) #(refresh! state) refresh-session]
       :else
       [:<>
        [:h3 "Dane darczyńcy"]
        [:dl {:class "donor-profile"}
         (for [field (concat events/editable-fields [:is-active])]
           ^{:key field} [:div [:dt (get labels/fields field)]
                          [:dd (case field :entity-type (get labels/entity-types (get profile field))
                                     :is-active (if (:is-active profile) "Aktywny" "Archiwalny")
                                     (or (not-empty (get profile field)) "—"))]])]
        (if (:editing? state) [edit-form state csrf]
            [:button {:type "button" :on-click #(rf/dispatch [:donors/edit-start])} "Edytuj dane"])
        [:button {:type "button" :disabled saving? :on-click #(refresh! state)} "Odśwież kartę"]
        [payment-history state refresh-session]
        [change-history state refresh-session]])]))
