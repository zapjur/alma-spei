(ns alma-spei.ui.imports.review-views
  (:require [alma-spei.ui.imports.review-events]
            [alma-spei.ui.imports.review-effects]
            [alma-spei.ui.imports.views :as uploads]
            [alma-spei.common.contracts.imports :as imports]
            [alma-spei.ui.donors.labels :as labels]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def errors {:unauthenticated "Sesja wygasła. Zaloguj się ponownie."
             :forbidden "Nie można wyświetlić danych. Odśwież sesję."
             :not-found "Nie znaleziono importu, wiersza lub wybranego darczyńcy. Odśwież widok."
             :closed "Import nie oczekuje już na weryfikację. Odśwież widok."
             :invalid-response "Nie udało się odczytać danych weryfikacji. Odśwież widok."
             :unavailable "Nie udało się pobrać danych. Sprawdź połączenie i spróbuj ponownie."})
(defn error-panel [error retry]
  (when error [:div {:role "alert"} [:p (get errors error "Nie udało się odczytać danych.")]
               [:button {:type "button" :on-click retry} "Spróbuj ponownie"]]))
(defn pager [{:keys [status offset limit total]} event label]
  [:nav {:class "import-pagination" :aria-label label}
   [:button {:type "button" :disabled (not (and (= status :ready) (pos? offset)))
             :on-click #(rf/dispatch [event :previous])} "Poprzednia strona"]
   [:span {:role "status"} (when (= :ready status) (str "Pozycje: " (if (zero? total) 0 (inc offset)) "–" (min total (+ offset limit)) " z " total))]
   [:button {:type "button" :disabled (not (and (= status :ready) (< (+ offset limit) total)))
             :on-click #(rf/dispatch [event :next])} "Następna strona"]])
(defn progress [batch]
  (let [valid (or (:valid-rows batch) 0) reviewed (or (:reviewed-rows batch) 0)]
    [:div {:class "review-progress"}
     [:p {:role "status"} (str "Zweryfikowane: " reviewed " z " valid " poprawnych wierszy. Wiersze z błędami: " (or (:invalid-rows batch) 0) ".")]
     [:progress {:max (max 1 valid) :value reviewed :aria-label "Postęp zapisanej weryfikacji"}]]))
(defn queue-panel [{:keys [queue]}]
  [:section {:aria-busy (= :loading (:status queue))}
   [:h3 "Importy oczekujące na weryfikację"]
   [:button {:type "button" :disabled (= :loading (:status queue)) :on-click #(rf/dispatch [:review/reload])} "Odśwież kolejkę"]
   [error-panel (:error queue) #(rf/dispatch [:review/reload])]
   (when (= :loading (:status queue)) [:p {:role "status"} "Pobieranie kolejki…"])
   (when (and (= :ready (:status queue)) (empty? (:batches queue))) [:p "Brak importów oczekujących na weryfikację."])
   [:ul {:class "review-batches"}
    (for [batch (:batches queue)]
      ^{:key (:id batch)}
      [:li [:button {:type "button" :on-click #(rf/dispatch [:review/select-batch (:id batch)])} (:original-filename batch)]
       [:p (str "Próba " (:attempt-number batch) " · " (.toLocaleString (js/Date. (:created-at batch)) "pl-PL"))]
       [progress batch]])]
   [pager queue :review/page "Strony kolejki importów"]])

(defn source-card [row]
  [:section {:class "review-source" :aria-labelledby "review-source-heading"}
   [:h4 {:id "review-source-heading"} "Wiersz z pliku"]
   (if row
     [:<>
      [:p (str "Arkusz " (:worksheet-index row) ", wiersz " (:row-number row))]
      [:dl
       (for [[field label] [[:source-date "Data w pliku"] [:sender "Nadawca i adres (oryginalny tekst)"]
                            [:description "Opis"] [:source-amount "Kwota w pliku"]]]
         ^{:key field} [:div [:dt label] [:dd (uploads/display-value (get-in row [:payload field]))]])
       [:div [:dt "Waluta"] [:dd "Nieustalona"]]]
      (when (= "invalid" (:validation-state row))
        [:div {:role "status"} [:p "Wiersz zawiera błędy. Samo porównanie nie usuwa błędów ani nie zatwierdza danych."]
         [:ul (for [[index error] (map-indexed vector (:errors row))]
                ^{:key index} [:li (get imports/row-messages (:code error) "Nieprawidłowe dane wiersza.")])]])]
     [:p "Wybierz wiersz do porównania."])])
(defn donor-card [{:keys [comparison]}]
  [:section {:class "review-donor" :aria-labelledby "review-donor-heading" :aria-busy (= :loading (:status comparison))}
   [:h4 {:id "review-donor-heading"} "Podgląd darczyńcy w bazie"]
   (cond
     (= :loading (:status comparison)) [:p {:role "status"} "Pobieranie aktualnego profilu…"]
     (:error comparison) [error-panel (:error comparison) #(rf/dispatch [:review/compare (:donor-id comparison)])]
     (:donor comparison)
     (let [donor (:donor comparison)]
       [:dl
        [:div [:dt "Typ"] [:dd (get labels/entity-types (:entity-type donor))]]
        (for [field [:name :first-name :street :postal-code :city :province]]
          ^{:key field} [:div [:dt (get labels/fields field)] [:dd (uploads/display-value (get donor field))]])
        [:div [:dt "Status"] [:dd (if (:is-active donor) "Aktywny" "Archiwalny")]]])
     :else [:p "Wyszukaj darczyńcę i wybierz „Porównaj”. Żaden profil nie jest wybierany automatycznie."])])
(defn search-panel [{:keys [search selected]}]
  (when selected
    [:section {:class "review-search" :aria-busy (= :loading (:status search))}
     [:h4 "Wyszukaj darczyńcę do porównania"]
     [:form {:on-submit (fn [e] (.preventDefault e) (rf/dispatch [:review/search]))}
      [:label {:for "review-search"} "Nazwisko lub nazwa"
       [:input {:id "review-search" :type "search" :value (:draft search) :max-length 200
                :on-change #(rf/dispatch [:review/search-draft (.. % -target -value)])}]]
      [:label {:for "review-donor-status"} "Status profilu"
       [:select {:id "review-donor-status" :value (:status-filter search)
                 :on-change #(rf/dispatch [:review/search-status (.. % -target -value)])}
        [:option {:value "active"} "Aktywni"] [:option {:value "archived"} "Archiwalni"] [:option {:value "all"} "Wszyscy"]]]
      [:button {:type "submit" :disabled (= :loading (:status search))} "Szukaj darczyńcy"]]
     [error-panel (:error search) #(rf/dispatch [:review/search])]
     (when (= :loading (:status search)) [:p {:role "status"} "Wyszukiwanie…"])
     (when (and (= :ready (:status search)) (empty? (:donors search))) [:p "Brak profili spełniających podane warunki."])
     [:ul {:class "review-candidates"}
      (for [donor (:donors search)]
        ^{:key (:id donor)}
        [:li [:strong (:name donor)]
         [:p (str (get labels/entity-types (:entity-type donor)) " · " (if (:is-active donor) "Aktywny" "Archiwalny"))]
         [:p (str (uploads/display-value (:street donor)) ", " (uploads/display-value (:postal-code donor)) " " (uploads/display-value (:city donor)))]
         [:button {:type "button" :aria-label (str "Porównaj profil " (:name donor) ", " (uploads/display-value (:city donor)))
                   :on-click #(rf/dispatch [:review/compare (:id donor)])} "Porównaj"]])]
     [pager search :review/search-page "Strony wyników wyszukiwania darczyńców"]]))
(defn batch-panel [{:keys [work selected] :as state}]
  (let [row (some #(when (and (= (:worksheet selected) (:worksheet-index %)) (= (:row selected) (:row-number %))) %) (:rows work))]
    [:section {:aria-busy (= :loading (:status work))}
     [:button {:type "button" :on-click #(rf/dispatch [:review/back])} "Wróć do kolejki"]
     [:button {:type "button" :disabled (= :loading (:status work)) :on-click #(rf/dispatch [:review/reload])} "Odśwież wiersze i postęp"]
     (when-let [batch (:batch work)] [:<> [:h3 {:id "review-batch-heading" :tab-index -1 :ref uploads/focus-heading} (:original-filename batch)] [progress batch]])
     [error-panel (:error work) #(rf/dispatch [:review/reload])]
     (cond
       (= :loading (:status work)) [:p {:role "status"} "Pobieranie wierszy importu…"]
       (= :closed (:status work)) [:p {:role "status"} "Ten import nie oczekuje na weryfikację. Wróć do aktualnej kolejki."]
       (= :ready (:status work))
       [:<>
        [:label {:for "review-row"} "Wiersz do porównania"
         [:select {:id "review-row" :value (if selected (str (:worksheet selected) ":" (:row selected)) "")
                   :on-change (fn [e]
                                (let [value (.. e -target -value)
                                      row (some #(when (= value (str (:worksheet-index %) ":" (:row-number %))) %) (:rows work))]
                                  (when row (rf/dispatch [:review/select-row (:worksheet-index row) (:row-number row)]))))}
          (for [row (:rows work)]
            ^{:key (str (:worksheet-index row) ":" (:row-number row))}
            [:option {:value (str (:worksheet-index row) ":" (:row-number row))}
             (str "Arkusz " (:worksheet-index row) ", wiersz " (:row-number row) (when (= "invalid" (:validation-state row)) " — błędy"))])]]
        [pager work :review/page "Strony wierszy importu"]
        [:div {:class "review-comparison"} [source-card row] [donor-card state]]
        [search-panel state]])]))
(defn panel [refresh-session route]
  (r/with-let [last-route (volatile! route)
               _ (rf/dispatch-sync [:review/open route])]
    (when (not= route @last-route)
      (vreset! last-route route)
      (rf/dispatch-sync [:review/open route]))
    (let [state @(rf/subscribe [:review/state])]
      [:section {:class "review-screen" :aria-labelledby "review-heading"}
       [:h2 {:id "review-heading" :tab-index -1 :ref uploads/focus-heading} "Weryfikacja importów"]
       [:p "Porównaj oryginalny wiersz z wybranym profilem. Wyszukanie lub obejrzenie profilu nie zapisuje decyzji o powiązaniu."]
       [:p {:class "import-development-note"} "Na tym etapie używaj wyłącznie danych syntetycznych. Zapisywanie decyzji będzie dostępne w kolejnym kroku."]
       (case (:mode state)
         :session-error [:<> [:p {:role "alert"} (get errors (:error state))]
                         [:button {:type "button" :on-click refresh-session} "Odśwież sesję"]]
         :batch [batch-panel state]
         [queue-panel state])])
    (finally (rf/dispatch-sync [:review/close]))))
