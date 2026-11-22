(ns alma-spei.ui.imports.review-views
  (:require [alma-spei.ui.imports.review-events]
            [alma-spei.ui.imports.decision-views :as decisions]
            [alma-spei.ui.imports.decision-events :as editing]
            [alma-spei.ui.imports.automatic-views :as automatic]
            [alma-spei.ui.imports.review-effects]
            [alma-spei.ui.imports.views :as uploads]
            [alma-spei.common.contracts.imports :as imports]
            [alma-spei.ui.donors.labels :as labels]
            [clojure.string :as str]
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
             :on-click #(when (or (not= event :review/page) (decisions/allow-leave!)) (rf/dispatch [event :previous]))} "Poprzednia strona"]
   [:span {:role "status"} (when (= :ready status) (str "Pozycje: " (if (zero? total) 0 (inc offset)) "–" (min total (+ offset limit)) " z " total))]
   [:button {:type "button" :disabled (not (and (= status :ready) (< (+ offset limit) total)))
             :on-click #(when (or (not= event :review/page) (decisions/allow-leave!)) (rf/dispatch [event :next]))} "Następna strona"]])
(defn progress [batch]
  (let [valid (or (:valid-rows batch) 0) reviewed (or (:reviewed-rows batch) 0)]
    [:div {:class "review-progress"}
     [:p {:role "status"} (str "Sprawdzone przelewy: " reviewed " z " valid " poprawnych wierszy. Błędy w pliku: " (or (:invalid-rows batch) 0) ".")]
     [:progress {:max (max 1 valid) :value reviewed :aria-label "Postęp zapisanej weryfikacji"}]]))
(defn queue-panel [{:keys [queue]}]
  [:section {:aria-busy (= :loading (:status queue))}
   [:h3 "Importy oczekujące na weryfikację"]
   [:button {:type "button" :disabled (= :loading (:status queue)) :on-click #(when (decisions/allow-leave!) (rf/dispatch [:review/reload]))} "Odśwież kolejkę"]
   [error-panel (:error queue) #(when (decisions/allow-leave!) (rf/dispatch [:review/reload]))]
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
     [:h4 "Znajdź darczyńcę w bazie"]
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
                   :on-click #(rf/dispatch [:review/compare (:id donor)])} "Sprawdź tego darczyńcę"]])]
     [pager search :review/search-page "Strony wyników wyszukiwania darczyńców"]]))
(defn donor-choice [state csrf]
  (let [editor (:editor state) draft (:draft editor) donor (get-in state [:comparison :donor])
        chosen? (and draft (not= "unresolved" (:kind draft)) (not (:choosing state)))
        mode (:choice state) proposals (get-in state [:aux :proposals])
        choose (fn [mode] (when (decisions/allow-leave!)
                            (rf/dispatch [:decision/mode mode])
                            (case mode :new (rf/dispatch [:decision/proposal-form nil])
                                  :reuse (rf/dispatch [:decision/aux :proposals 0]) nil)))]
    [:section {:class "donor-choice" :aria-labelledby "donor-choice-heading"}
     [:h3 {:id "donor-choice-heading"} "1. Kto przekazał tę wpłatę?"]
     (if chosen?
       [:div {:class "chosen-donor"}
        [:div [:span "Wybrany darczyńca"] [:strong (decisions/target-label state)]
         (when (= "new-proposal" (:kind draft)) [:p "Nowy profil powstanie dopiero po zatwierdzeniu całego importu."])]
        [:button {:type "button" :disabled (not (editing/editable? editor)) :on-click #(choose :existing)} "Zmień darczyńcę"]]
       [:<>
        (when-let [candidates (seq (get-in state [:resolution :candidates]))]
          [:div {:class "review-candidates"}
           [:p "Możliwe profile — porównaj dane i wybierz właściwy:"]
           (for [candidate candidates]
             ^{:key (:id candidate)} [:article {:class "review-proposal"}
                                      [:strong (:name candidate)] [:p (:street candidate) ", " (:postal-code candidate) " " (:city candidate)]
                                      [:p (:match-reason candidate)]
                                      [:button {:type "button" :disabled (or (not (:is-active candidate)) (not (editing/editable? editor)))
                                                :on-click #(when (decisions/allow-leave!)
                                                             (rf/dispatch [:decision/target "existing-donor" (:id candidate) (:version candidate)]))} "To ten darczyńca"]])])
        (when (get-in state [:resolution :new-profile-candidate])
          [:p "Brak podobnych aktywnych profili. Sprawdź, czy dodać nowego darczyńcę."])
        [:p "Sprawdź nadawcę z pliku po lewej. Wybierz osobę lub organizację z bazy albo przygotuj nowy profil."]
        [:div {:class "donor-choice-actions"}
         [:button {:type "button" :class (when (= mode :existing) "button-primary") :disabled (not (editing/editable? editor))
                   :on-click #(choose :existing)} "Wybierz darczyńcę z bazy"]
         [:button {:type "button" :class (when (= mode :new) "button-primary") :disabled (not (editing/editable? editor))
                   :on-click #(choose :new)} "Dodaj nowego darczyńcę"]]
        (when (= mode :existing)
          [:<> [search-panel state]
           (when (or donor (= :loading (get-in state [:comparison :status])) (get-in state [:comparison :error]))
             [:div [donor-card state]
              (when donor
                [:button {:type "button" :class "button-primary" :disabled (or (not (editing/editable? editor)) (not (:is-active donor)))
                          :on-click #(when (decisions/allow-leave!)
                                       (rf/dispatch [:decision/target "existing-donor" (:id donor) (:version donor)]))} "Przypisz wpłatę temu darczyńcy"])
              (when (and donor (not (:is-active donor))) [:p "Ten profil jest archiwalny. Wybierz aktywnego darczyńcę."])])])
        (when (= mode :new) [decisions/proposal-form state csrf])
        [:button {:type "button" :class "button-quiet" :disabled (not (editing/editable? editor)) :on-click #(choose :reuse)} "Użyj nowego darczyńcy przygotowanego w tym imporcie"]
        (when (= mode :reuse)
          [:div [:p "Jeśli kilka przelewów pochodzi od tej samej nowej osoby, użyj tego samego przygotowanego profilu."]
           [decisions/error-text proposals]
           (when (= :loading (:status proposals)) [:p {:role "status"} "Pobieranie profili…"])
           (when (and (= :ready (:status proposals)) (empty? (get-in proposals [:data :proposals]))) [:p "Nie przygotowano jeszcze nowych darczyńców. Wybierz „Dodaj nowego darczyńcę”."])
           (for [proposal (get-in proposals [:data :proposals])] ^{:key (:id proposal)} [decisions/proposal-choice state proposal])
           [decisions/aux-pager :proposals proposals]])])]))

(defn exception-panel [{:keys [work selected stage] :as state} csrf]
  (let [row (some #(when (= selected {:worksheet (:worksheet-index %) :row (:row-number %)}) %) (:rows work))
        index (first (keep-indexed #(when (= row %2) %1) (:rows work)))
        batch (:batch work)]
    [:section {:aria-busy (= :loading (:status work))}
     [:div {:class "review-batch-bar"}
      [:button {:type "button" :class "button-quiet" :on-click #(when (decisions/allow-leave!) (rf/dispatch [:review/automatic]))} "Wróć do podsumowania"]
      [:span (:original-filename batch)]]
     (when batch
       [:header {:class "review-work-heading"}
        [:h3 {:key (str selected stage) :id "review-batch-heading" :tab-index -1 :ref uploads/focus-heading}
         (if (= stage :summary) "Sprawdź planowane zmiany" (if (:resolving state) "Pozycja wymagająca wyjaśnienia" (str "Przelew " (when index (+ (:offset work) index 1)) " z " (:total work))))]
        [progress batch]])
     [error-panel (:error work) #(when (decisions/allow-leave!) (rf/dispatch [:review/reload]))]
     (when (and (not= stage :summary) (pos? (or (:valid-rows batch) 0))
                (= (:valid-rows batch) (:reviewed-rows batch)) (zero? (or (:invalid-rows batch) 0)))
       [:div {:class "next-step-card"}
        [:div [:h3 "Wszystkie przelewy zostały sprawdzone"] [:p "Przejdź do podsumowania, aby sprawdzić planowane zmiany."]]
        [:button {:type "button" :class "button-primary" :disabled (or (editing/dirty? state) (editing/busy? state))
                  :on-click #(rf/dispatch [:review/automatic])} "Wróć do podsumowania"]])
     (cond
       (= :loading (:status work)) [:p {:role "status"} "Pobieranie przelewów…"]
       (= :closed (:status work)) [:p {:role "status"} "Ten import nie oczekuje na weryfikację. Wróć do aktualnej kolejki."]
       (= :ready (:status work))
       [:<>
        (when-not (= stage :summary)
          [:<>
           [:details {:class "review-secondary"}
            [:summary "Wybierz inny przelew lub stronę"]
            [:label {:for "review-row"} "Przelew"
             [:select {:id "review-row" :value (if selected (str (:worksheet selected) ":" (:row selected)) "")
                       :on-change (fn [e]
                                    (let [value (.. e -target -value)
                                          row (some #(when (= value (str (:worksheet-index %) ":" (:row-number %))) %) (:rows work))]
                                      (if (and row (decisions/allow-leave!)) (rf/dispatch [:review/select-row (:worksheet-index row) (:row-number row)])
                                          (set! (.. e -target -value) (str (:worksheet selected) ":" (:row selected))))))}
              (for [row (:rows work)]
                ^{:key (str (:worksheet-index row) ":" (:row-number row))}
                [:option {:value (str (:worksheet-index row) ":" (:row-number row))}
                 (str "Wiersz " (:row-number row) " · " (get-in row [:payload :sender]) (when (= "invalid" (:validation-state row)) " — błędy"))])]]
            [pager work :review/page "Strony przelewów"]]
           [:div {:class "guided-review"}
            [source-card row]
            [:div {:class "guided-review-editor"}
             (when (= "invalid" (:validation-state row))
               [:p {:role "alert" :class "notice notice-error"} "Ten przelew zawiera błędy. Popraw plik i prześlij poprawioną wersję jako nowy import. Zmiana darczyńcy nie naprawi błędów źródłowych."])
             (when (or (= "invalid" (:validation-state row)) (str/starts-with? (or (get-in row [:payload :amount]) "") "-"))
               [:div {:class "review-checklist"}
                (when (str/starts-with? (or (get-in row [:payload :amount]) "") "-") [:p "Kwota jest ujemna. Zatwierdzenie tego przelewu jest zablokowane do ustalenia zasad zwrotów."])
                [:button {:type "button" :on-click #(when (decisions/allow-leave!) (rf/dispatch [:review/advance csrf]))} "Sprawdź kolejny przelew bez zapisywania"]])
             [donor-choice state csrf]
             [decisions/panel state csrf]]]
           [:div {:class "review-summary-action"}
            [:button {:type "button" :disabled (or (editing/dirty? state) (editing/busy? state))
                      :on-click #(rf/dispatch [:review/automatic])} "Wróć do podsumowania"]
            [:p {:class "field-help"} "Podsumowanie sprawdzi wszystkie przelewy. Jeśli czegoś brakuje, pokaże listę do uzupełnienia."]]])
        (when (= stage :summary) [decisions/panel state csrf])])]))
(defn batch-panel [state csrf]
  (if (:resolving state) [exception-panel state csrf]
      ^{:key (get-in state [:work :batch-id])} [automatic/panel state csrf]))

(defn panel [refresh-session route csrf]
  (r/with-let [last-route (volatile! route)
               _ (rf/dispatch-sync [:review/open route])]
    (when (not= route @last-route)
      (vreset! last-route route)
      (rf/dispatch-sync [:review/open route]))
    (let [state @(rf/subscribe [:review/state])]
      [:section {:class "review-screen" :aria-labelledby "review-heading"}
       [:header {:class "page-heading"}
        [:p {:class "eyebrow"} "IMPORT PRZELEWÓW"]
        [:h2 {:id "review-heading" :tab-index -1 :ref uploads/focus-heading} (if (= :batch (:mode state)) (if (:resolving state) "Wyjaśnij niejednoznaczną pozycję" "Podsumowanie importu") "Importy do dokończenia")]
        [:p (if (= :batch (:mode state)) (if (:resolving state) "Zdecyduj tylko o tym wyjątku. Pozostałe przelewy są już przygotowane." "System przygotowuje dopasowania i nowe wpłaty. Twojej decyzji wymagają tylko niejednoznaczne pozycje.") "Wybierz plik z listy, aby wrócić do sprawdzania przelewów.")]]
       (when (= :batch (:mode state)) [uploads/import-steps (if (get-in state [:automation :data :ready]) 3 2)])
       (case (:mode state)
         :session-error [:<> [:p {:role "alert"} (get errors (:error state))]
                         [:button {:type "button" :on-click refresh-session} "Odśwież sesję"]]
         :batch [batch-panel state csrf]
         [queue-panel state])])
    (finally (rf/dispatch-sync [:review/close]))))
