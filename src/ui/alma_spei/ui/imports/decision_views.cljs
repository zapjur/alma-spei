(ns alma-spei.ui.imports.decision-views
  (:require [alma-spei.ui.imports.decision-events :as events]
            [alma-spei.ui.imports.navigation :as navigation]
            [alma-spei.ui.imports.execution-location :as execution-location]
            [alma-spei.ui.imports.decision-effects]
            [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.ui.donors.labels :as labels]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def errors {:validation "Sprawdź wymagane pola i format waluty."
             :conflict "Dane zmieniły się lub wskazane powiązanie wymaga ponownej weryfikacji. Szkic pozostaje na ekranie. Wczytaj aktualną decyzję."
             :uncertain "Nie potwierdzono wyniku zapisu. Nie ponawiamy go automatycznie. Wczytaj decyzję lub historię, aby sprawdzić stan."
             :not-found "Nie znaleziono wskazanego profilu, propozycji lub wpłaty. Wczytaj aktualne dane."
             :blocked "Podgląd wymaga uzupełnienia lub odświeżenia decyzji. Sprawdź listę braków."
             :invalid-response "Nie udało się odczytać danych weryfikacji."
             :unavailable "Nie udało się połączyć z serwerem. Spróbuj ponownie."})
(defn allow-leave! [] (navigation/allow-leave!))
(defn error-text [state]
  (when (:error state) [:p {:role "alert"} (get errors (:error state) "Nie udało się zapisać danych weryfikacji.")]))
(defn value-text [value]
  (cond (nil? value) "—" (true? value) "Tak" (false? value) "Nie" (string? value) (if (str/blank? value) "—" (get {"unresolved" "Nierozstrzygnięty" "existing-donor" "Profil w bazie" "new-proposal" "Proponowany profil" "new" "Nowa wpłata" "existing" "Wpłata już ujęta"} value value)) :else (str value)))
(defn aux-pager [op {:keys [status data]}]
  (when data
    [:nav {:class "import-pagination" :aria-label (str "Strony: " (get {:proposals "propozycje" :payments "wpłaty" :events "historia" :blockers "braki" :items "podgląd"} op))}
     [:button {:type "button" :disabled (or (= status :loading) (zero? (:offset data)))
               :on-click #(rf/dispatch [:decision/aux op (max 0 (- (:offset data) (:limit data)))])} "Poprzednia"]
     [:span (str "Łącznie: " (:total data))]
     [:button {:type "button" :disabled (or (= status :loading) (>= (+ (:offset data) (:limit data)) (:total data)))
               :on-click #(rf/dispatch [:decision/aux op (+ (:offset data) (:limit data))])} "Następna"]]))
(defn proposal-choice [state proposal]
  [:article {:class "review-proposal"}
   [:strong (get-in proposal [:profile :name])]
   [:p (str (get labels/entity-types (get-in proposal [:profile :entity-type])) ", " (get-in proposal [:profile :city]))]
   [:button {:type "button" :disabled (not (events/editable? (:editor state)))
             :on-click #(rf/dispatch [:decision/target "new-proposal" (:id proposal) (:version proposal)])} "Wybierz tego darczyńcę"]
   [:button {:type "button" :disabled (not (events/editable? (:editor state)))
             :on-click #(when (allow-leave!) (rf/dispatch [:decision/proposal-form proposal]))} "Popraw dane darczyńcy"]])
(defn proposal-form [state csrf]
  (when-let [form (get-in state [:editor :proposal-form])]
    [:form {:class "decision-proposal-form" :on-submit (fn [e] (.preventDefault e) (rf/dispatch [:decision/proposal-save csrf true]))}
     [:h5 (if (:id form) "Edytuj propozycję profilu" "Dane nowego darczyńcy")]
     [:p "Przepisz potwierdzone dane nadawcy. Profil powstanie dopiero po zatwierdzeniu całego importu."]
     [:label {:for "proposal-type"} "Typ darczyńcy"
      [:select {:id "proposal-type" :value (get-in form [:profile :entity-type]) :disabled (not (events/editable? (:editor state)))
                :on-change #(rf/dispatch [:decision/proposal-field :entity-type (.. % -target -value)])}
       [:option {:value ""} "Wybierz typ"]
       (for [[kind label] labels/entity-types] ^{:key kind} [:option {:value kind} label])]]
     [:div {:class "donor-edit-grid"}
      (for [field [:name :city :first-name :street :postal-code :province]]
        ^{:key field} [:label {:for (str "proposal-" (name field))} (get labels/fields field)
                       [:input {:id (str "proposal-" (name field)) :value (get-in form [:profile field]) :max-length 500
                                :required (contains? #{:name :city} field) :disabled (not (events/editable? (:editor state)))
                                :on-change #(rf/dispatch [:decision/proposal-field field (.. % -target -value)])}]])]
     (when-not (:id form)
       [:<>
        [:label [:input {:type "checkbox" :checked (:checked-existing form)
                         :on-change #(rf/dispatch [:decision/proposal-check :checked-existing (.. % -target -checked)])}]
         " Sprawdzono istniejące profile w bazie."]
        [:label [:input {:type "checkbox" :checked (:checked-proposals form)
                         :on-change #(rf/dispatch [:decision/proposal-check :checked-proposals (.. % -target -checked)])}]
         " Sprawdzono nowych darczyńców przygotowanych w tym imporcie; ta osoba lub organizacja nie została dodana drugi raz."]])
     [:button {:type "submit" :disabled (not (events/proposal-saveable? state))} "Zapisz i wybierz darczyńcę"]]))
(defn financial-form [state]
  (let [editor (:editor state) draft (:draft editor) financial (:financial draft) disabled? (not (events/editable? editor))
        payments (get-in state [:aux :payments])]
    [:fieldset {:disabled disabled?}
     [:legend "2. Sprawdź wpłatę"]
     [:p {:class "field-help"} "Sprawdź walutę w źródle, np. na wyciągu. Wpisz PLN, EUR lub inny potwierdzony kod. Następnie ustal, czy wpłatę trzeba dodać, czy już jest w bazie."]
     [:p "Data i kwota pozostają zgodne z plikiem."]
     [:label {:for "decision-currency"} "W jakiej walucie wpłynęły pieniądze?"
      [:input {:id "decision-currency" :value (:currency financial) :max-length 3 :auto-complete "off" :placeholder "np. PLN"
               :on-change #(rf/dispatch [:decision/field [:financial :currency] (.. % -target -value)])}]]
     [:label {:for "decision-evidence"} "Na jakiej podstawie potwierdzasz walutę i brak podwójnego ujęcia?"
      [:textarea {:id "decision-evidence" :value (:evidence financial) :max-length 500
                  :placeholder "Np. wyciąg z rachunku PLN; sprawdzono wcześniejsze importy i wpłaty darczyńcy."
                  :on-change #(rf/dispatch [:decision/field [:financial :evidence] (.. % -target -value)])}]]
     [:label {:for "decision-disposition"} "Jak ująć wpłatę?"
      [:select {:id "decision-disposition" :value (:disposition financial)
                :on-change #(rf/dispatch [:decision/field [:financial :disposition] (.. % -target -value)])}
       [:option {:value "unresolved"} "Jeszcze nie rozstrzygnięto"]
       [:option {:value "new"} "Nowa, odrębna wpłata"]
       (when (= "existing-donor" (:kind draft)) [:option {:value "existing"} "Wpłata jest już ujęta w bazie"])]]
     (when (= "existing-donor" (:kind draft))
       [:div
        [:button {:type "button" :on-click #(rf/dispatch [:decision/aux :payments 0])} "Sprawdź istniejące wpłaty"]
        [error-text payments]
        (when (= :loading (:status payments)) [:p {:role "status"} "Pobieranie wpłat…"])
        (for [payment (get-in payments [:data :payments])]
          ^{:key (:id payment)} [:p
                                 [:span (str (:received-on payment) " · " (str/replace (:amount payment) "." ",") " " (:currency payment))]
                                 [:button {:type "button" :on-click #(rf/dispatch [:decision/payment (:id payment)])} "Wskaż tę wpłatę jako już ujętą"]])
        (when (get-in payments [:data]) [aux-pager :payments payments])])
     (when (:donation-id draft) [:p (str "Wskazana istniejąca wpłata: " (:donation-id draft))])
     [:label [:input {:type "checkbox" :checked (:overlap-checked financial)
                      :on-change #(rf/dispatch [:decision/field [:financial :overlap-checked] (.. % -target -checked)])}]
      " Sprawdzono, czy ta wpłata nie została już uwzględniona z tego lub innego źródła. Identyczna data i kwota nie wystarczają do uznania jej za duplikat."]]))
(defn target-label [state]
  (let [editor (:editor state) draft (:draft editor) current (:current editor)
        donor (get-in state [:comparison :donor])
        candidate (some #(when (= (:donor-id draft) (:id %)) %) (get-in state [:resolution :candidates]))
        proposal (some #(when (= (:proposal-id draft) (:id %)) %) (conj (get-in state [:aux :proposals :data :proposals] []) (:saved-proposal editor)))]
    (or (when (= (select-keys draft [:kind :donor-id :proposal-id :target-version]) (select-keys current [:kind :donor-id :proposal-id :target-version])) (:target-label current))
        (when (= (:donor-id draft) (:id donor)) (:name donor)) (:name candidate) (get-in proposal [:profile :name])
        (:donor-id draft) (:proposal-id draft) "—")))
(defn missing-checks [state]
  (let [draft (get-in state [:editor :draft]) financial (:financial draft)
        row (get-in state [:editor :row])]
    (cond-> []
      (= "invalid" (:validation-state row)) (conj "Popraw błędy w pliku i prześlij poprawiony plik jako nowy import.")
      (str/starts-with? (or (get-in row [:payload :amount]) "") "-") (conj "Kwota jest ujemna. Ten przelew nie może zostać zatwierdzony; zasady zwrotów wymagają ustalenia.")
      (= "unresolved" (:kind draft)) (conj "Wybierz darczyńcę lub przygotuj dane nowego darczyńcy.")
      (not (re-matches #"[A-Z]{3}" (or (:currency financial) ""))) (conj "Podaj potwierdzoną walutę, np. PLN.")
      (str/blank? (:evidence financial)) (conj "Opisz, jak sprawdzono walutę i wcześniejsze ujęcie wpłaty.")
      (= "unresolved" (:disposition financial)) (conj "Wybierz, czy dodać wpłatę, czy wskazać już zapisaną.")
      (and (= "existing" (:disposition financial)) (nil? (:donation-id draft))) (conj "Wskaż konkretną wpłatę z bazy.")
      (not (:overlap-checked financial)) (conj "Potwierdź sprawdzenie, czy wpłata nie została już uwzględniona."))))
(defn editor-panel [state csrf]
  (let [editor (:editor state) current (:current editor) draft (:draft editor)
        chosen? (and draft (not= "unresolved" (:kind draft)) (not (:choosing state)))
        missing (missing-checks state)]
    [:section {:class "decision-editor" :aria-labelledby "decision-heading" :aria-busy (contains? #{:saving :loading} (:status editor))}
     [:h4 {:id "decision-heading"} "Sprawdzenie przelewu"]
     [error-text editor]
     (when (contains? #{:saving :loading} (:status editor)) [:p {:role "status"} "Wczytywanie lub zapisywanie decyzji…"])
     (when (:requires-reload editor)
       [:div {:class "notice notice-error"}
        [:p "Zapis wymaga sprawdzenia aktualnych danych. Szkic pozostaje na ekranie; odświeżenie zastąpi go aktualną zapisaną decyzją."]
        [:button {:type "button" :on-click #(when (allow-leave!) (rf/dispatch [:decision/reload]))} "Wczytaj aktualną decyzję"]])
     (when current
       [:p {:class "decision-saved" :role "status"}
        (str "Zapisano: " (or (:reviewer-name current) (:reviewer-id current)) " · " (.toLocaleString (js/Date. (:reviewed-at current)) "pl-PL")
             " · " (if (:complete current) "przelew sprawdzony" "sprawdzenie nieukończone"))])
     (when chosen?
       [:<>
        [financial-form state]
        (when (seq missing)
          [:div {:class "review-checklist" :role "status"}
           [:strong "Aby przejść dalej:"] [:ul (for [message missing] ^{:key message} [:li message])]])
        [:button {:type "button" :class "button-primary" :disabled (not (events/complete-saveable? state))
                  :on-click #(rf/dispatch [:decision/save-next csrf])} (if (:resolving state) "Zapisz wyjaśnienie i wróć do podsumowania" "Zapisz i przejdź dalej")]
        [:p {:class "field-help"} "Po zapisaniu wrócisz do podsumowania. Ten zapis nie dodaje jeszcze wpłaty do bazy."]])
     (when draft
       [:details {:class "review-secondary"}
        [:summary "Zapis roboczy i dodatkowe opcje"]
        [:p "Możesz zapisać nieukończone sprawdzenie i wrócić do niego później. Nie przejdziesz automatycznie do kolejnego przelewu."]
        [:button {:type "button" :disabled (not (events/saveable? state)) :on-click #(rf/dispatch [:decision/save csrf false])} "Zapisz roboczo"]
        [:button {:type "button" :disabled (not (events/editable? editor))
                  :on-click #(when (js/confirm "Wyczyścić zapisaną decyzję? Historia pozostanie zachowana.") (rf/dispatch [:decision/save csrf true]))} "Wyczyść przypisanie"]])]))
(def kind-labels {"new-donor" "Nowy proponowany darczyńca" "existing-donor-reference" "Istniejący profil — bez zmian pól"
                  "new-payment" "Nowa wpłata" "duplicate-reference" "Wpłata już ujęta — bez ponownego dodania"})
(defn preview-item [item]
  (let [payload (:payload item) profile (or (:profile payload) (get-in payload [:donor]))]
    [:article {:class "preview-item"}
     [:h5 (str (:ordinal item) ". " (get kind-labels (:kind item)))]
     (when (:row-number item) [:p (str "Arkusz " (:worksheet-index item) ", wiersz " (:row-number item))])
     (if profile
       [:dl (for [field [:entity-type :name :city :first-name :street :postal-code :province]]
              ^{:key field} [:div [:dt (get labels/fields field)] [:dd (if (= field :entity-type) (get labels/entity-types (get profile field)) (value-text (get profile field)))]])]
       [:dl (for [[field label] [[:target-label "Darczyńca"] [:target-id "Identyfikator wybranego profilu / propozycji"] [:received-on "Data wpłaty"] [:amount "Kwota"] [:currency "Waluta"] [:evidence "Potwierdzenie i rozstrzygnięcie"] [:donation-id "Istniejąca wpłata"]]]
              ^{:key field} [:div [:dt label] [:dd (if (= field :amount) (str/replace (or (get payload field) "") "." ",") (value-text (get payload field)))]])])]))
(defn history-panel [state]
  (let [history (get-in state [:aux :events])]
    [:section
     [:button {:type "button" :on-click #(rf/dispatch [:decision/aux :events 0])} "Pokaż historię weryfikacji"]
     [error-text history]
     (for [event (get-in history [:data :events])]
       ^{:key (:id event)}
       [:article {:class "decision-history"}
        [:p (str (get {"proposal-created" "Utworzenie propozycji" "proposal-updated" "Zmiana propozycji" "decision-saved" "Zapis decyzji" "decision-cleared" "Wyczyszczenie decyzji" "preview-created" "Przygotowanie podglądu"} (:event-type event))
                 " · " (:actor-name event) " · " (.toLocaleString (js/Date. (:occurred-at event)) "pl-PL"))]
        (when (:row-number event) [:p (str "Arkusz " (:worksheet-index event) ", wiersz " (:row-number event))])
        (for [[path label] [[[:profile :name] "Nazwa profilu"] [[:profile :city] "Miejscowość"] [[:kind] "Rodzaj decyzji"]
                            [[:target-label] "Wybrany profil"] [[:financial :currency] "Waluta"] [[:financial :evidence] "Uzasadnienie"]
                            [[:financial :disposition] "Ujęcie wpłaty"] [[:complete] "Kompletność"]]
              :let [before (get-in (:before event) path) after (get-in (:after event) path)] :when (not= before after)]
          ^{:key (str path)} [:p (str label ": " (value-text before) " → " (value-text after))])
        (when (:preview-id event) [:button {:type "button" :on-click #(rf/dispatch [:decision/open-preview (:preview-id event)])} "Otwórz ten podgląd"])])
     [aux-pager :events history]]))
(defn preview-panel [state csrf]
  (let [preview (:preview state) metadata (get-in preview [:data :preview])
        items (get-in state [:aux :items]) blockers (get-in state [:aux :blockers])]
    [:section {:class "decision-preview" :aria-labelledby "decision-preview-heading"}
     [:h4 {:id "decision-preview-heading"} "Podsumowanie importu"]
     [:p "Podgląd zapisuje plan, bez zastosowania zmian. Wszystkie wiersze wymagają pełnej i aktualnej decyzji; błędy i kwoty ujemne blokują gotowość."]
     [:button {:type "button" :disabled (or (events/dirty? state) (events/busy? state) (= :loading (:status preview)))
               :on-click #(rf/dispatch [:decision/preview csrf])} "Odśwież podsumowanie"]
     [:button {:type "button" :on-click #(rf/dispatch [:decision/aux :blockers 0])} "Sprawdź braki w decyzjach"]
     [error-text preview] [error-text blockers]
     (when (= :loading (:status preview)) [:p {:role "status"} "Przygotowywanie podglądu…"])
     (when-let [data (:data blockers)]
       [:div {:role "status"} [:p (str "Wiersze wymagające uzupełnienia: " (:total data))]
        [:ul (for [blocker (:blockers data)]
               ^{:key (str (:worksheet-index blocker) ":" (:row-number blocker))}
               [:li
                [:p (str "Wiersz " (:row-number blocker) ": " (get contract/blocker-messages (:code blocker) "Wymagana ponowna weryfikacja."))]
                [:button {:type "button" :on-click #(rf/dispatch [:review/find-row (:worksheet-index blocker) (:row-number blocker)])} "Przejdź do tego przelewu"]])]
        [aux-pager :blockers blockers]])
     (when metadata
       [:div
        (when (get-in preview [:data :stale]) [:p {:role "alert"} "Ten podgląd jest nieaktualny. Zweryfikuj zmiany i przygotuj nowy podgląd."])
        (when (and (= "review-preview-v2" (:rule-version metadata)) (not (get-in preview [:data :stale]))
                   (not (events/dirty? state)) (not (events/busy? state)))
          [:a {:href (execution-location/fragment {:mode :confirm :batch-id (:batch-id metadata) :preview-id (:id metadata) :offset 0}) :class "button-primary"} "Przejdź do zatwierdzenia"])
        [:p (str "Nowe profile: " (get-in metadata [:summary :new-donors]) ", nowe wpłaty: " (get-in metadata [:summary :new-payments])
                 ", już ujęte wpłaty: " (get-in metadata [:summary :duplicate-references]) ".")]
        (for [total (get-in metadata [:summary :totals])] ^{:key (:currency total)} [:p (str "Nowe wpłaty: " (str/replace (:amount total) "." ",") " " (:currency total))])
        [:button {:type "button" :on-click #(rf/dispatch [:decision/open-preview (:id metadata)])} "Sprawdź aktualność podglądu"]
        [error-text items]
        (for [item (get-in items [:data :items])] ^{:key (:ordinal item)} [preview-item item])
        [aux-pager :items items]])]))
(defn panel [state csrf]
  (r/with-let [before-unload (fn [event]
                               (let [current @(rf/subscribe [:review/state])]
                                 (when (or (events/dirty? current) (events/busy? current))
                                   (.preventDefault event) (set! (.-returnValue event) ""))))
               _ (.addEventListener js/window "beforeunload" before-unload)]
    [:div
     (if (= :summary (:stage state))
       [:<> [:button {:type "button" :on-click #(rf/dispatch [:review/resume])} "Wróć do sprawdzania przelewów"] [preview-panel state csrf]]
       [editor-panel state csrf])
     [:details {:class "review-secondary"} [:summary "Historia sprawdzania"] [history-panel state]]]
    (finally (.removeEventListener js/window "beforeunload" before-unload))))
