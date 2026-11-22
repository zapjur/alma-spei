(ns alma-spei.ui.imports.automatic-views
  (:require [alma-spei.ui.imports.decision-views :as decisions]
            [alma-spei.ui.imports.views :as views]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def reasons {"sender-format" "Nie można pewnie odczytać nazwy i adresu nadawcy."
              "identity-ambiguity" "Dane pasują do kilku profili lub różnią się od danych w bazie."
              "donor-type" "Nowy darczyńca wymaga określenia typu: osoba, firma lub organizacja."
              "payment-ambiguity" "Możliwa powtórzona wpłata. Sama data i kwota nie wystarczają do jej pominięcia."
              "invalid-row" "Błędy w danych źródłowych — popraw plik."
              "unsupported-amount" "Kwota ujemna wymaga wyjaśnienia zasad zwrotów."
              "stale-catalog" "Baza zmieniła się po analizie. Potrzebna jest ponowna weryfikacja."
              "manual-review" "Ta pozycja wymaga potwierdzenia człowieka."})
(defn settings-form [state csrf]
  (r/with-let [policy (r/atom {:currency "" :new-donor-type nil})]
    [:form {:on-submit (fn [event] (.preventDefault event) (rf/dispatch [:decision/automatic-run csrf @policy]))}
     [:h3 "Ustawienia źródła — raz dla całego pliku"]
     [:p "Plik nie zawiera osobnych kolumn waluty i typu darczyńcy. Potwierdź walutę; typ nowych darczyńców możesz pozostawić do wyjaśnienia."]
     [:label {:for "automatic-currency"} "Waluta przelewów"
      [:input {:id "automatic-currency" :required true :pattern "[A-Z]{3}" :max-length 3 :placeholder "np. PLN"
               :value (:currency @policy) :on-change #(swap! policy assoc :currency (.toUpperCase (.. % -target -value)))}]]
     [:label {:for "automatic-type"} "Typ nowych darczyńców, jeśli znany dla tego źródła"
      [:select {:id "automatic-type" :value (or (:new-donor-type @policy) "") :on-change #(swap! policy assoc :new-donor-type (not-empty (.. % -target -value)))}
       [:option {:value ""} "Nie wiem — pokaż wyjątki"] [:option {:value "individual"} "Osoby"]
       [:option {:value "business"} "Firmy"] [:option {:value "organization"} "Organizacje"]]]
     [:button {:type "submit" :class "button-primary" :disabled (= :loading (get-in state [:automation :status]))} "Dopasuj i pokaż podsumowanie"]]))
(defn panel [state csrf]
  (let [{:keys [status error data]} (:automation state)
        counts (:counts data) busy? (or (= status :loading) (= :loading (get-in state [:work :status])))
        preview (:preview state)]
    [:section {:class "automatic-summary" :aria-busy busy?}
     [:div {:class "review-batch-bar"} [:strong (get-in state [:work :batch :original-filename])]
      [:button {:type "button" :class "button-quiet" :disabled busy? :on-click #(rf/dispatch [:decision/automatic-report 0])} "Odśwież analizę"]]
     (when busy? [:p {:role "status"} "Dopasowywanie darczyńców i sprawdzanie wpłat…"])
     (when error [:div {:role "alert"} [:p (get decisions/errors error "Nie udało się odczytać analizy.")]
                  [:p "Jeśli wynik jest nieznany, odśwież analizę. Nie powtarzamy zapisu automatycznie."]])
     (when (and data (not (:policy data)) (not (:ready data))) [settings-form state csrf])
     (when (and data (or (:policy data) (:ready data)))
       [:<>
        [:p {:class "field-help"} "Dopasowania i nowe profile zostały przygotowane automatycznie. Baza darczyńców i wpłat nie została jeszcze zmieniona."]
        [:dl {:class "automatic-counts"}
         (for [[field label] [[:matched "Dopasowane przelewy"] [:new-donors "Nowi darczyńcy"] [:new-payments "Nowe wpłaty"]
                              [:duplicates "Potwierdzone duplikaty"] [:exceptions "Do wyjaśnienia"]]]
           ^{:key field} [:div [:dt label] [:dd (get counts field)]])]
        (for [total (:totals data)] ^{:key (:currency total)} [:p {:class "automatic-total"} "Do dodania: " [:strong (views/money (:amount total)) " " (:currency total)]])
        (if (:ready data)
          [:div {:class "next-step-card"}
           [:div [:h3 "Wszystko gotowe do zatwierdzenia"] [:p "Sprawdź pełną listę planowanych zmian. Zapis nastąpi dopiero po Twoim zatwierdzeniu."]]
           [:button {:type "button" :class "button-primary" :disabled (or busy? (= :loading (:status preview)))
                     :on-click #(rf/dispatch [:decision/automatic-preview csrf])} "Sprawdź zmiany i zatwierdź"]]
          [:section {:aria-labelledby "exceptions-heading"}
           [:button {:type "button" :disabled busy? :on-click #(rf/dispatch [:decision/automatic-run csrf (:policy data)])} "Odśwież automatyczne dopasowania"]
           [:h3 {:id "exceptions-heading"} "Tylko te pozycje potrzebują Twojej decyzji"]
           [:p "Pozostałe przelewy są już przygotowane. Nie musisz sprawdzać ich pojedynczo."]
           (for [{:keys [row reason]} (:exceptions data)]
             ^{:key (str (:worksheet-index row) ":" (:row-number row))}
             [:article {:class "exception-card"}
              [:div [:strong (get-in row [:payload :sender])]
               [:p (str (get-in row [:payload :date]) " · " (or (views/money (get-in row [:payload :amount])) (get-in row [:payload :source-amount])) " " (get-in data [:policy :currency]))]
               [:p (get reasons reason (get reasons "manual-review"))]]
              [:button {:type "button" :disabled busy? :on-click #(rf/dispatch [:review/find-row (:worksheet-index row) (:row-number row)])} "Wyjaśnij tę pozycję"]])
           [:nav {:class "import-pagination" :aria-label "Strony wyjątków"}
            [:button {:type "button" :disabled (or busy? (zero? (:offset data))) :on-click #(rf/dispatch [:decision/automatic-report (max 0 (- (:offset data) (:limit data)))])} "Poprzednie wyjątki"]
            [:span (str "Do wyjaśnienia: " (:exceptions counts))]
            [:button {:type "button" :disabled (or busy? (>= (+ (:offset data) (:limit data)) (:exceptions counts))) :on-click #(rf/dispatch [:decision/automatic-report (+ (:offset data) (:limit data))])} "Następne wyjątki"]]])
        [decisions/error-text preview]])]))
