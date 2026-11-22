(ns alma-spei.ui.imports.execution-views
  (:require [alma-spei.common.contracts.execution :as contract]
            [alma-spei.ui.imports.execution-events :as events]
            [alma-spei.ui.imports.execution-effects]
            [alma-spei.ui.imports.execution-location :as location]
            [alma-spei.ui.imports.navigation :as navigation]
            [alma-spei.ui.imports.decision-views :as decisions]
            [alma-spei.ui.imports.views :as uploads]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def errors {:conflict "Dane zmieniły się lub potwierdzenie wygasło. Sprawdź aktualny stan i w razie potrzeby wróć do weryfikacji."
             :validation "Nieprawidłowe dane czynności. Odśwież stan importu."
             :uncertain "Nie potwierdzono wyniku czynności. Żądanie mogło zostać wykonane. Nie ponawiamy go automatycznie. Sprawdź wynik."
             :invalid-response "Nie udało się odczytać danych. Odśwież ten widok."
             :unavailable "Nie udało się pobrać danych. Sprawdź połączenie i spróbuj ponownie."
             :not-found "Nie znaleziono wskazanego importu lub podglądu."})
(def sources {"bank" "Przelewy bankowe" "master" "Baza główna" "tax" "Odpis podatkowy"})
(defn date-text [value] (if value (.toLocaleString (js/Date. value) "pl-PL") "—"))
(defn error-panel [slot retry]
  (when (:error slot) [:div {:role "alert"} [:p (get errors (:error slot) "Nie można kontynuować tej czynności.")]
                       (when retry [:button {:type "button" :on-click retry} "Odśwież dane"])]))
(defn loading [slot] (when (= :loading (:status slot)) [:p {:role "status"} "Pobieranie danych…"]))
(defn link [route text] [:a {:href (location/fragment route) :class "review-open"} text])
(defn page-controls [state slot route-mode]
  (when-let [{:keys [limit offset total]} (get-in state [slot :data])]
    (let [route (:route state) disabled? (or (events/busy? state) (= :loading (get-in state [slot :status])))
          page (fn [value] (if route-mode (set! (.-hash js/location) (location/fragment (assoc route :mode route-mode :offset value)))
                               (rf/dispatch [:execution/refresh slot value])))]
      [:nav {:class "import-pagination" :aria-label (get {:history "Strony historii" :preview-items "Strony planowanych zmian" :applied "Strony zapisanych zmian" :eligibility "Strony powodów wstrzymania"} slot)}
       [:button {:type "button" :disabled (or disabled? (zero? offset)) :on-click #(page (max 0 (- offset limit)))} "Poprzednia strona"]
       [:span (str "Pozycje: " (if (zero? total) 0 (inc offset)) "–" (min total (+ offset limit)) " z " total)]
       [:button {:type "button" :disabled (or disabled? (>= (+ offset limit) total) (> (+ offset limit) 99999)) :on-click #(page (+ offset limit))} "Następna strona"]])))
(defn batch-context [batch uploader]
  [:dl {:class "import-summary"}
   (for [[label value] [["Plik" (:original-filename batch)] ["Źródło" (get sources (:source-type batch))] ["Próba" (:attempt-number batch)]
                        ["Przesłał(a)" uploader] ["Data przesłania" (date-text (:created-at batch))] ["Stan" (get uploads/states (:state batch))]
                        ["Wiersze z danymi" (or (:total-rows batch) "Nie ustalono")] ["Poprawne technicznie" (or (:valid-rows batch) "Nie ustalono")]
                        ["Wiersze z błędami" (or (:invalid-rows batch) "Nie ustalono")] ["Zapisane kompletne decyzje" (or (:reviewed-rows batch) "Nie ustalono")]]]
     ^{:key label} [:div [:dt label] [:dd value]])])
(defn summary [data]
  [:section {:class "execution-summary"}
   [:h3 "Zakres importu"]
   [:p (str "Nowe profile: " (:new-donors data) ", nowe wpłaty: " (:new-payments data) ", wpłaty już ujęte: " (:duplicate-references data) ".")]
   (for [total (:totals data)] ^{:key (:currency total)} [:p (str "Nowe wpłaty: " (uploads/money (:amount total)) " " (:currency total))])
   (when (empty? (:totals data)) [:p "Brak nowych wpłat do dodania."])
   [:p "Istniejące profile pozostają bez zmian pól. Kwoty różnych walut nie są sumowane."]])
(defn action-status [state csrf]
  (let [action (:action state)]
    [:div {:class "execution-action-status" :aria-live "polite"}
     (when (events/busy? state) [:p {:role "status"} "Trwa sprawdzanie lub zapisywanie. Poczekaj na wynik. Zamknięcie przeglądarki nie cofa rozpoczętej czynności."])
     [error-panel action nil]
     (when (events/unresolved? state)
       [:<>
        [:button {:type "button" :disabled (= :loading (get-in state [:outcome :status])) :on-click #(rf/dispatch [:execution/check])} "Sprawdź wynik"]
        (when (:recovery-checked state)
          [:div [:p "Odczytano bieżący stan. Poprzednie żądanie może nadal trwać. Możesz odświeżyć wynik albo jawnie ponowić dokładnie to samo żądanie."]
           [:button {:type "button" :on-click #(rf/dispatch [:execution/retry-exact csrf])} "Ponów dokładnie to żądanie"]])])]))
(defn history-panel [state]
  (let [history (:history state) data (:data history) uploaders (into {} (map (juxt :id :display-name) (:uploaders data)))]
    [:section [:header {:class "page-heading"}
               [:p {:class "eyebrow"} "IMPORTY"]
               [:h2 {:tab-index -1 :ref uploads/focus-heading} "Historia importów"]
               [:p "Sprawdź, na jakim etapie jest plik. Niedokończony import możesz otworzyć i kontynuować."]]
     [:button {:type "button" :disabled (= :loading (:status history)) :on-click #(rf/dispatch [:execution/refresh :history (get-in state [:route :offset])])} "Odśwież historię"]
     [error-panel history #(rf/dispatch [:execution/refresh :history (get-in state [:route :offset])])] [loading history]
     (when (and data (empty? (:batches data))) [:p "Brak importów w historii."])
     (for [batch (:batches data)]
       ^{:key (:id batch)}
       [:article {:class "execution-history-entry"}
        [:div {:class "history-entry-heading"} [:h3 (:original-filename batch)] [:span {:class "state-badge"} (get uploads/states (:state batch))]]
        [:p (str (date-text (:created-at batch)) " · " (get uploaders (:created-by batch)))]
        (if (= "review" (:state batch))
          [:a {:class "button-primary" :href (str "#review/" (:id batch) "/0")} "Kontynuuj sprawdzanie przelewów"]
          [link {:mode :batch :batch-id (:id batch) :offset 0} "Zobacz wynik importu"])
        [:details {:class "review-secondary"} [:summary "Szczegóły pliku i przebiegu"] [batch-context batch (get uploaders (:created-by batch))]]])
     [page-controls state :history :history]]))
(defn confirmation-panel [state csrf]
  (let [preview (:preview state) data (:data preview) metadata (:preview data) outcome (get-in state [:outcome :data])
        enabled? (get-in state [:capabilities :data :commit-enabled]) backup (get-in state [:backup :data :backup]) disabled? (or (events/busy? state) (events/unresolved? state))]
    [:section [:header {:class "page-heading"}
               [:p {:class "eyebrow"} "OSTATNI KROK"]
               [:h2 {:tab-index -1 :ref uploads/focus-heading} "Zatwierdź sprawdzone przelewy"]
               [:p "Sprawdź podsumowanie. Dopiero przycisk zatwierdzenia zapisze darczyńców i wpłaty w bazie."]]
     [uploads/import-steps 4]
     [error-panel (:outcome state) #(rf/dispatch [:execution/check])] [loading (:outcome state)]
     [error-panel preview #(rf/dispatch [:execution/refresh :preview 0])] [loading preview]
     [error-panel (:capabilities state) #(rf/dispatch [:execution/refresh :capabilities 0])]
     (when outcome [:details {:class "review-secondary"} [:summary "Szczegóły importowanego pliku"] [batch-context (:batch outcome) (:uploaded-by-name outcome)]])
     (when metadata
       [:<>
        [summary (:summary metadata)]
        (when-not (events/ready-preview? state)
          [:p {:role "alert"} "Ten podgląd nie jest gotowy do zatwierdzenia. Może być nieaktualny albo import zakończył się. Wróć do wyniku lub weryfikacji."])
        [link {:mode :batch :batch-id (get-in state [:route :batch-id]) :offset 0} "Sprawdź wynik importu"]
        [:a {:href (str "#review/" (get-in state [:route :batch-id]) "/0") :class "review-open"} "Wróć do weryfikacji"]
        [:h3 "Wszystkie planowane zmiany"]
        [error-panel (:preview-items state) #(rf/dispatch [:execution/refresh :preview-items (get-in state [:route :offset])])]
        [loading (:preview-items state)]
        (for [item (get-in state [:preview-items :data :items])] ^{:key (:ordinal item)} [decisions/preview-item item])
        [page-controls state :preview-items :confirm]
        (cond
          (false? enabled?) [:div {:role "status" :class "notice notice-error"}
                             [:h3 "Zapis do bazy jest wyłączony w tej instalacji"]
                             [:p "Możesz sprawdzać przelewy i przygotować podsumowanie, ale nie możesz ich teraz zatwierdzić. Brak zatwierdzonej usługi kopii zapasowej."]
                             [:p "W środowisku testowym konfigurację trybu syntetycznego ustawia osoba uruchamiająca aplikację. Ponowne klikanie nie odblokuje zapisu."]]
          (true? enabled?) [:p {:class "import-development-note"} "Tryb testowy — nie powstaje rzeczywista kopia zapasowa. Używaj wyłącznie danych syntetycznych."]
          :else [:p {:role "status"} "Sprawdzanie dostępności zatwierdzania…"])
        (when (true? enabled?)
          [:div {:class "confirmation-instructions"}
           [:h3 "Co zrobić teraz?"]
           [:ol [:li "Sprawdź wszystkie planowane zmiany powyżej."]
            [:li "Kliknij „Przygotuj zatwierdzenie testowe”. To nie tworzy rzeczywistej kopii zapasowej."]
            [:li "Zaznacz potwierdzenie i kliknij „Zatwierdź import testowy”."]]])
        [:button {:type "button" :class (when-not (events/valid-backup? state) "button-primary") :disabled (not (events/can-backup? state)) :on-click #(rf/dispatch [:execution/backup csrf])} "Przygotuj zatwierdzenie testowe"]
        (when backup
          [:div {:role "status"}
           [:p (str "Potwierdzenie testowe: " (date-text (:completed-at backup)) ". Ważne do: " (date-text (:expires-at backup)) ".")]
           (when-not (events/valid-backup? state) [:p "Potwierdzenie wygasło lub nie odpowiada temu podglądowi. Poproś jawnie o nowe."])])
        [:label {:class "execution-consent"}
         [:input {:id "execution-consent" :type "checkbox" :checked (true? (:consent state))
                  :disabled (or disabled? (not (events/can-backup? state)) (not (events/valid-backup? state)))
                  :on-change #(rf/dispatch [:execution/consent (.. % -target -checked)])}]
         " Zatwierdzam cały wyświetlony podgląd. Potwierdzam użycie wyłącznie danych syntetycznych i rozumiem, że nie powstała rzeczywista kopia zapasowa."]
        [:button {:type "button" :class "button-primary" :disabled (not (events/can-commit? state)) :on-click #(rf/dispatch [:execution/commit csrf])} "Zatwierdź import testowy"]])
     [action-status state csrf]]))
(defn undo-dialog [state csrf]
  (r/with-let [element (volatile! nil) return-focus (or (.getElementById js/document "execution-open-undo") (.-activeElement js/document))]
    (let [busy? (events/busy? state) data (get-in state [:outcome :data])]
      [:dialog {:class "execution-dialog" :aria-labelledby "undo-heading" :aria-describedby "undo-description"
                :ref (fn [node] (when (and node (not (.-open node))) (vreset! element node) (.showModal node)
                                      (when-let [button (.querySelector node "button")] (.focus button))))
                :on-cancel (fn [e] (.preventDefault e) (when-not busy? (rf/dispatch [:execution/cancel-undo])))}
       [:h3 {:id "undo-heading"} "Cofnąć ostatni import?"]
       [:p (get-in data [:batch :original-filename])]
       [:p (str "Zatwierdzono: " (get-in data [:commit :actor-name]) " · " (date-text (get-in data [:commit :occurred-at])))]
       [summary (get-in data [:commit :summary])]
       [:p {:id "undo-description"} "Nowe wpłaty znikną z aktywnej historii. Niezmienione profile utworzone przez ten import zostaną zarchiwizowane. Oryginalne dane, ślad zmian i historia importu pozostaną zachowane."]
       [:button {:type "button" :disabled busy? :on-click #(rf/dispatch [:execution/cancel-undo])} "Anuluj cofnięcie"]
       [:button {:type "button" :disabled (or busy? (events/unresolved? state)) :on-click #(rf/dispatch [:execution/undo csrf])} "Potwierdzam cofnięcie importu"]
       [action-status state csrf]])
    (finally (when-let [node @element] (when (.-open node) (.close node)))
             (when (and return-focus (.-isConnected return-focus)) (.focus return-focus)))))
(defn applied-item [item undone?]
  (let [snapshot (:snapshot item) profile (:donor snapshot) payment (:payment snapshot)
        payload (if payment (assoc payment :target-label (:name profile) :target-id (:id profile)) {:profile profile})]
    [:div
     [decisions/preview-item {:ordinal (:ordinal item) :kind (:kind item) :worksheet-index (:worksheet-index item) :row-number (:row-number item) :payload payload}]
     (when (:reversed item) [:p "Wpłata wycofana z aktywnej historii; oryginalny zapis zachowano."])
     (when (and undone? (= "new-donor" (:kind item))) [:p "Profil utworzony przez ten import został zarchiwizowany przy cofnięciu."])]))
(defn outcome-panel [state csrf]
  (let [outcome (:outcome state) data (:data outcome) batch (:batch data) commit (:commit data) undo (:undo data) eligibility (:eligibility state)]
    [:section [:h2 {:tab-index -1 :ref uploads/focus-heading} "Wynik importu"]
     [error-panel outcome #(rf/dispatch [:execution/check])] [loading outcome]
     (when data
       [:<>
        [:div {:class "outcome-banner"}
         [:h3 (cond undo "Import cofnięty" commit "Przelewy zostały zapisane" (= "review" (:state batch)) "Ten import czeka na sprawdzenie" :else (get uploads/states (:state batch)))]
         [:p (:original-filename batch)]]
        [:details {:class "review-secondary"} [:summary "Szczegóły pliku i przebiegu"] [batch-context batch (:uploaded-by-name data)]]
        [:button {:type "button" :disabled (events/busy? state) :on-click #(rf/dispatch [:execution/check])} "Odśwież wynik"]
        (when (= "review" (:state batch)) [:a {:href (str "#review/" (:id batch) "/0") :class "button-primary"} "Kontynuuj sprawdzanie przelewów"])
        (when commit
          [:section [:h3 "Zapisane zatwierdzenie"]
           [:p (str "Zatwierdził(a): " (:actor-name commit) " · " (date-text (:occurred-at commit)))]
           [summary (:summary commit)]
           [:p "Potwierdzenie kopii było testowe; nie utworzono rzeczywistej kopii zapasowej."]
           [:p (str "Potwierdzenie testowe: " (date-text (get-in commit [:backup :completed-at])))]
           [link {:mode :confirm :batch-id (:id batch) :preview-id (:preview-id commit) :offset 0} "Otwórz oryginalny podgląd"]])
        (when undo
          [:section {:role "status"} [:h3 "Import został cofnięty"]
           [:p (str "Cofnął/cofnęła: " (:actor-name undo) " · " (date-text (:occurred-at undo)))]
           [:p (str "Wycofane wpłaty: " (:reversed-payments undo) ", zarchiwizowane profile: " (:archived-donors undo) ". Historia pozostaje zachowana.")]])
        (when (= "undone" (:state batch)) [link {:mode :reimport :batch-id (:id batch)} "Wczytaj ten sam plik ponownie"])
        (when commit
          [:section [:h3 "Wszystkie zapisane efekty"]
           [error-panel (:applied state) #(rf/dispatch [:execution/refresh :applied (get-in state [:route :offset])])]
           [loading (:applied state)]
           (for [item (get-in state [:applied :data :items])] ^{:key (:ordinal item)} [applied-item item (some? undo)])
           [page-controls state :applied :batch]])
        [:section [:h3 "Możliwość cofnięcia"]
         [:p "Można cofnąć tylko najnowszy zatwierdzony import. Cofnięcie nie udostępnia starszych importów do dalszego cofania."]
         [:button {:type "button" :disabled (events/busy? state) :on-click #(rf/dispatch [:execution/refresh :eligibility 0])} "Sprawdź możliwość cofnięcia"]
         [error-panel eligibility #(rf/dispatch [:execution/refresh :eligibility 0])] [loading eligibility]
         (for [blocker (get-in eligibility [:data :blockers])]
           ^{:key (str (:code blocker) (:entity-id blocker))} [:p {:role "status"} (get contract/conflict-messages (:code blocker) "Sprawdź powiązania importu.")
                                                               (when (:entity-id blocker) [:span (str " Identyfikator rekordu: " (:entity-id blocker))])])
         [page-controls state :eligibility nil]
         [:button {:id "execution-open-undo" :type "button" :disabled (or (not (true? (get-in eligibility [:data :eligible]))) (events/busy? state) (events/unresolved? state))
                   :on-click #(rf/dispatch [:execution/open-undo])} "Cofnij ostatni import"]]])
     [action-status state csrf]
     (when (:modal state) [undo-dialog state csrf])]))
(defn reimport-panel [state refresh-session csrf]
  (let [slot (:outcome state) data (:data slot) batch (:batch data)]
    [:section [:h2 {:tab-index -1 :ref uploads/focus-heading} "Nowa próba po cofnięciu importu"]
     [error-panel slot #(rf/dispatch [:execution/check])] [loading slot]
     (when data
       (if (= "undone" (:state batch))
         [:<> [:p (str "Poprzedni plik: " (:original-filename batch) ", próba " (:attempt-number batch) ". Wybierz ponownie ten sam plik. Wymagana jest nowa weryfikacja wszystkich wierszy.")]
          [uploads/panel refresh-session csrf batch]]
         [:p {:role "alert"} "Ten import nie jest cofnięty. Sprawdź aktualną historię i wybierz właściwą próbę."]))]))
(defn panel [refresh-session csrf route]
  (r/with-let [last-route (volatile! route) _ (rf/dispatch-sync [:execution/open route (.now js/Date)])
               timer (js/setInterval #(rf/dispatch [:execution/tick (.now js/Date)]) 1000)
               before-unload (fn [event] (when (navigation/operation?) (.preventDefault event) (set! (.-returnValue event) "")))
               _ (.addEventListener js/window "beforeunload" before-unload)]
    (when (not= route @last-route) (vreset! last-route route) (rf/dispatch-sync [:execution/open route (.now js/Date)]))
    (let [state @(rf/subscribe [:execution/state])]
      [:section {:class "execution-screen" :aria-busy (events/busy? state)}
       [link {:mode :history :offset 0} "Historia importów"]
       (if (:session-error state)
         [:div [:p {:role "alert"} "Sesja wymaga odświeżenia. Zaloguj się ponownie, jeśli wygasła."]
          [:button {:type "button" :on-click #(do (refresh-session) (rf/dispatch [:execution/open route (.now js/Date)]))} "Odśwież sesję"]]
         (case (get-in state [:route :mode]) :history [history-panel state] :confirm [confirmation-panel state csrf]
               :batch [outcome-panel state csrf] :reimport [reimport-panel state refresh-session csrf] nil))])
    (finally (js/clearInterval timer) (.removeEventListener js/window "beforeunload" before-unload) (rf/dispatch-sync [:execution/close]))))
