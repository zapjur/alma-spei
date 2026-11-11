(ns alma-spei.ui.donors.views
  (:require [alma-spei.ui.donors.effects]
            [alma-spei.ui.donors.detail-views :as details]
            [alma-spei.ui.donors.labels :as labels]
            [alma-spei.ui.donors.events :as events]
            [alma-spei.ui.donors.subs]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def entity-labels labels/entity-types)

(defn sort-header [query field label]
  (let [selected? (= field (:sort-by query))
        ascending? (= "asc" (:sort-direction query))]
    [:th {:scope "col" :aria-sort (if selected? (if ascending? "ascending" "descending") "none")}
     [:button {:type "button" :on-click #(rf/dispatch [:donors/sort-changed field])
               :aria-label (str label " — sortuj " (if (and selected? ascending?) "malejąco" "rosnąco"))}
      label (when selected? (if ascending? " ↑" " ↓"))]]))

(defn toolbar [{:keys [query draft-search]}]
  [:div {:class "donor-toolbar"}
   [:form {:class "donor-search" :on-submit (fn [e] (.preventDefault e) (rf/dispatch [:donors/search]))}
    [:label {:for "donor-search"} "Nazwisko lub nazwa"]
    [:div {:class "donor-search-input"}
     [:input {:id "donor-search" :type "search" :max-length 200 :value draft-search
              :on-change #(rf/dispatch [:donors/search-draft (.. % -target -value)])}]
     [:button {:type "submit"} "Szukaj"]]]
   [:label {:for "donor-type"} "Typ darczyńcy"
    [:select {:id "donor-type" :value (get query :entity-type "")
              :on-change #(rf/dispatch [:donors/filter-changed :entity-type (.. % -target -value)])}
     [:option {:value ""} "Wszystkie typy"]
     [:option {:value "individual"} "Osoby"]
     [:option {:value "business"} "Firmy"]
     [:option {:value "organization"} "Organizacje"]]]
   [:label {:for "donor-status"} "Status"
    [:select {:id "donor-status" :value (:status query)
              :on-change #(rf/dispatch [:donors/filter-changed :status (.. % -target -value)])}
     [:option {:value "active"} "Aktywni"]
     [:option {:value "archived"} "Archiwalni"]
     [:option {:value "all"} "Wszyscy"]]]
   [:button {:type "button" :on-click #(rf/dispatch [:donors/clear-filters])} "Wyczyść filtry"]
   [:button {:type "button" :on-click #(rf/dispatch [:donors/reload])} "Odśwież listę"]])

(defn donor-table [{:keys [query donors status]}]
  [:div {:class "donor-table-scroll" :role "region" :aria-label "Tabela darczyńców"
         :aria-describedby "donor-scroll-help" :tab-index 0}
   [:table {:class "donor-table"}
    [:caption "Lista darczyńców"]
    [:thead
     [:tr
      [sort-header query "name" "Nazwa / imię i nazwisko"]
      [sort-header query "entity-type" "Typ"]
      [sort-header query "city" "Miejscowość"]
      [:th {:scope "col"} "Status"]]]
    [:tbody
     (for [donor donors]
       ^{:key (:id donor)}
       [:tr {:class "donor-row" :on-click #(rf/dispatch [:donors/select (:id donor)])}
        [:td [:button {:type "button" :class "donor-name" :aria-label (str "Otwórz kartę: " (:name donor))
                       :on-click (fn [event] (.stopPropagation event) (rf/dispatch [:donors/select (:id donor)]))} (:name donor)]]
        [:td (get entity-labels (:entity-type donor))]
        [:td (or (not-empty (:city donor)) "—")]
        [:td (if (:is-active donor) "Aktywny" "Archiwalny")]])
     (when (empty? donors)
       [:tr [:td {:col-span 4 :class "donor-empty"}
             (case status
               :loading "Ładowanie danych…"
               :ready "Brak darczyńców spełniających wybrane warunki."
               "Dane są niedostępne.")]])]]])

(defn pagination [{:keys [query status donors total]}]
  (let [{:keys [limit offset]} query
        ready? (= :ready status)]
    [:nav {:class "donor-pagination" :aria-label "Strony listy darczyńców"}
     [:button {:type "button" :disabled (not (and ready? (pos? offset)))
               :on-click #(rf/dispatch [:donors/page-changed :previous])} "Poprzednia"]
     [:span {:role "status" :aria-live "polite"}
      (when ready? (cond
                     (zero? total) "Wyniki: 0"
                     (empty? donors) (str "Wyniki na stronie: 0 (łącznie " total ")")
                     :else (str "Wyniki: " (inc offset) "–" (+ offset (count donors)) " z " total)))]
     [:button {:type "button" :disabled (not (and ready? (< (+ offset limit) total)))
               :on-click #(rf/dispatch [:donors/page-changed :next])} "Następna"]]))

(defn error-panel [error refresh-session]
  [:div {:role "alert"}
   [:p (case error
         :unauthenticated "Sesja wygasła. Zaloguj się ponownie."
         :forbidden "Nie można wyświetlić listy. Odśwież sesję."
         :invalid-response "Nie udało się odczytać listy darczyńców. Spróbuj ponownie."
         "Nie udało się pobrać listy darczyńców. Sprawdź połączenie i spróbuj ponownie.")]
   (if (#{:unauthenticated :forbidden} error)
     [:button {:type "button" :on-click refresh-session} "Odśwież sesję"]
     [:button {:type "button" :on-click #(rf/dispatch [:donors/reload])} "Spróbuj ponownie"])])

(defn panel [refresh-session csrf]
  (r/with-let [_ (rf/dispatch [:donors/open])]
    (let [state (or @(rf/subscribe [:donors/state]) events/initial-state)]
      (if-let [card (:detail state)]
        [details/card card refresh-session csrf]
        [:section {:class "donor-list" :aria-labelledby "donor-heading" :aria-busy (= :loading (:status state))}
         [:h2 {:id "donor-heading" :tab-index -1 :ref details/focus-heading} "Darczyńcy"]
         [toolbar state]
         (when (= :loading (:status state)) [:p {:role "status" :aria-live "polite"} "Pobieranie listy darczyńców…"])
         (when (:error state) [error-panel (:error state) refresh-session])
         [:p {:id "donor-scroll-help" :class "donor-table-hint"}
          "Na małym ekranie przewiń tabelę w bok, aby zobaczyć pozostałe kolumny."]
         [donor-table state]
         [pagination state]]))
    (finally (rf/dispatch [:donors/close]))))
