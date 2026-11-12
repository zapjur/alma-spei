(ns alma-spei.ui.imports.views
  (:require [alma-spei.common.contracts.imports :as contract]
            [alma-spei.ui.imports.effects]
            [alma-spei.ui.imports.events :as events]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def errors
  {:filename "Nieprawidłowa nazwa pliku. Wybierz plik bezpośrednio z dysku."
   :file-type "Wybierz plik CSV lub XLSX."
   :file-size "Plik musi być niepusty i mieć rozmiar do 5 MiB."
   :too-large "Przekroczono limit pliku lub jego zawartości. Wybierz mniejszy plik."
   :empty-file "Wybrany plik jest pusty."
   :validation "Sprawdź wybrany plik i ustawienia przesyłania."
   :invalid-file "Nie można odczytać pliku. Sprawdź układ czterech kolumn, daty i kwoty. Formuły nie są obsługiwane."
   :duplicate "Ten plik został już przesłany, także jeśli wcześniej miał inną nazwę. Kolejny import nie został utworzony."
   :retry-conflict "Nie można ponowić tego importu. Jego stan mógł się zmienić albo wybrano inny plik. Sprawdź stan importu."
   :busy "Trwa przetwarzanie innego importu. Spróbuj przesłać plik później."
   :processing-failed "Analiza zakończyła się błędem. Możesz ponowić analizę tego samego pliku. Dane darczyńców i wpłat nie zostały zmienione."
   :uncertain "Nie udało się potwierdzić wyniku przesyłania. Plik mógł zostać zapisany. Nie przesyłamy go ponownie automatycznie."
   :unauthenticated "Sesja wygasła. Odśwież sesję i zaloguj się ponownie."
   :forbidden "Nie można kontynuować. Odśwież sesję i sprawdź, czy hasło zostało zmienione."
   :not-found "Nie znaleziono importu."
   :invalid-response "Nie udało się odczytać raportu importu. Spróbuj odświeżyć raport."
   :unavailable "Nie udało się pobrać raportu. Sprawdź połączenie i spróbuj ponownie."})
(def states {"reserved" "Oczekuje na analizę" "staging" "Trwa analiza" "review" "Oczekuje na weryfikację"
             "approved" "Zatwierdzony" "committed" "Zapisany" "failed" "Analiza zakończona błędem"
             "cancelled" "Anulowany" "undone" "Cofnięty"})
(defn focus-heading [element] (when element (.focus element)))
(defn display-value [value] (if (str/blank? value) "—" value))
(defn money [value] (some-> value (str/replace "." ",")))
(defn file-description [file] (when file {:name (.-name file) :size (.-size file)}))

(defn upload-form [state csrf]
  (r/with-let [confirmed (r/atom false)]
    (let [retry? (= :failed (:status state)) busy? (= :uploading (:status state))
          file (:file state) selectable? (events/selectable? state)]
      [:form {:class "import-form" :aria-label "Przesyłanie pliku bankowego"
              :on-submit (fn [event]
                           (.preventDefault event)
                           (let [input (.namedItem (.. event -currentTarget -elements) "file")
                                 selected (aget (.-files input) 0)]
                             (rf/dispatch [:imports/start selected csrf retry? @confirmed])))}
       [:label {:for "import-file"} "Plik bankowy (CSV lub XLSX)"
        [:input {:id "import-file" :name "file" :type "file" :accept ".csv,.xlsx" :required true :disabled busy?
                 :aria-describedby "import-file-help"
                 :aria-invalid (boolean (:error state))
                 :on-change (fn [event]
                              (reset! confirmed false)
                              (rf/dispatch [:imports/choose-file (file-description (aget (.. event -target -files) 0))]))}]]
       [:p {:id "import-file-help"} "Maksymalnie 5 MiB. Cztery kolumny bez nagłówka: data, nadawca i adres, opis, kwota. Plik XLSX: jeden arkusz, bez formuł."]
       (when (events/csv? file)
         [:label {:for "import-separator"} "Separator kolumn CSV"
          [:select {:id "import-separator" :value (:separator state) :required true :disabled busy?
                    :on-change (fn [event] (reset! confirmed false) (rf/dispatch [:imports/separator (.. event -target -value)]))}
           [:option {:value ""} "Wybierz separator"]
           [:option {:value ";"} "Średnik (;)"]
           [:option {:value ","} "Przecinek (,)"]]])
       (when (events/csv? file)
         [:p "CSV: UTF-8; daty RRRR-MM-DD lub DD.MM.RRRR; kwoty bez separatorów tysięcy. Przy przecinku jako separatorze ujmij kwotę z przecinkiem w cudzysłów."])
       (when retry? [:p "Ponownie wybierz ten sam plik i potwierdź analizę. Serwer sprawdzi zgodność zawartości."])
       (when retry?
         [:label {:class "import-confirmation"}
          [:input {:type "checkbox" :checked @confirmed :disabled busy?
                   :on-change #(reset! confirmed (.. % -target -checked))}]
          " Potwierdzam ponowną analizę tego samego pliku. Poprzednia próba pozostanie w historii."])
       [:button {:type "submit" :disabled (or busy? (not selectable?) (and retry? (not @confirmed)))}
        (if retry? "Ponów analizę tego samego pliku" "Prześlij do analizy")]])))

(defn progress-panel [{:keys [progress sent?]}]
  [:div {:role "status" :aria-live "polite" :class "import-progress"}
   [:label {:for "import-progress"} (if sent? "Plik przesłany. Trwa analiza na serwerze…" "Przesyłanie pliku…")]
   [:progress (cond-> {:id "import-progress" :max 100} (number? progress) (assoc :value progress))]
   (when (and (number? progress) (not sent?)) [:span (str " " progress "%")])
   [:p "Zmiana strony nie cofa analizy rozpoczętej na serwerze."]])

(defn summary [{:keys [batch skipped-empty-rows]}]
  [:section {:aria-labelledby "import-summary-heading"}
   [:h3 {:id "import-summary-heading" :tab-index -1 :ref focus-heading} "Stan analizy"]
   [:dl {:class "import-summary"}
    [:div [:dt "Plik"] [:dd (:original-filename batch)]]
    [:div [:dt "Próba"] [:dd (:attempt-number batch)]]
    [:div [:dt "Status"] [:dd (get states (:state batch) "Stan nieznany")]]
    [:div [:dt "Wiersze z danymi"] [:dd (or (:total-rows batch) "Jeszcze nie ustalono")]]
    [:div [:dt "Poprawne technicznie"] [:dd (or (:valid-rows batch) "Jeszcze nie ustalono")]]
    [:div [:dt "Wiersze z błędami"] [:dd (or (:invalid-rows batch) "Jeszcze nie ustalono")]]
    (when (some? skipped-empty-rows) [:div [:dt "Pominięte puste wiersze"] [:dd skipped-empty-rows]])]
   [:p "Analiza pliku nie dodaje darczyńców ani wpłat. Poprawny format wiersza nie oznacza zatwierdzenia importu."]
   (when (and (:invalid-rows batch) (pos? (:invalid-rows batch)))
     [:p {:role "status"} "Wiersze z błędami pozostają w raporcie. Wymagają wyjaśnienia przed zatwierdzeniem importu."])])

(defn report-table [{:keys [rows status]}]
  [:<>
   [:p {:id "import-scroll-help" :class "import-table-hint"} "Przewiń tabelę w bok, aby zobaczyć wszystkie kolumny."]
   [:div {:class "import-table-scroll" :tab-index 0 :role "region" :aria-label "Raport wierszy importu" :aria-describedby "import-scroll-help"}
    [:table {:class "import-table"}
     [:caption "Wszystkie wiersze z danymi, także niepoprawne"]
     [:thead [:tr (for [label ["Arkusz / wiersz" "Data" "Nadawca i adres" "Opis" "Kwota" "Waluta" "Weryfikacja formatu"]]
                    ^{:key label} [:th {:scope "col"} label])]]
     [:tbody
      (for [{:keys [worksheet-index row-number payload validation-state errors]} rows]
        ^{:key (str worksheet-index ":" row-number)}
        [:tr {:class (when (= "invalid" validation-state) "import-invalid")}
         [:th {:scope "row"} (str worksheet-index " / " row-number)]
         [:td (display-value (or (:date payload) (:source-date payload)))]
         [:td (display-value (:sender payload))]
         [:td (display-value (:description payload))]
         [:td {:class "import-amount"} (display-value (if (:amount payload) (money (:amount payload)) (:source-amount payload)))]
         [:td "Nieustalona"]
         [:td (if (= "valid" validation-state) "Poprawny format"
                  [:ul (if (seq errors)
                         (for [[index error] (map-indexed vector errors)]
                           ^{:key index} [:li (get contract/row-messages (:code error) "Nieprawidłowe dane wiersza.")])
                         [:li "Nieprawidłowe dane wiersza."])])]])
      (when (empty? rows) [:tr [:td {:col-span 7} (if (= :loading status) "Pobieranie wierszy…" "Brak wierszy na tej stronie.")]])]]]])

(defn pagination [{:keys [status offset limit total rows]}]
  [:nav {:class "import-pagination" :aria-label "Strony raportu importu"}
   [:button {:type "button" :disabled (not (and (= status :ready) (pos? offset)))
             :on-click #(rf/dispatch [:imports/page :previous])} "Poprzednie wiersze"]
   [:span {:role "status" :aria-live "polite"}
    (when (= status :ready) (if (empty? rows) (str "Wiersze na stronie: 0 z " total)
                                (str "Wiersze: " (inc offset) "–" (+ offset (count rows)) " z " total)))]
   [:button {:type "button" :disabled (not (and (= status :ready) (< (+ offset limit) total)))
             :on-click #(rf/dispatch [:imports/page :next])} "Następne wiersze"]])

(defn panel [refresh-session csrf]
  (r/with-let [_ (rf/dispatch-sync [:imports/open])]
    (let [{:keys [status error batch batch-id] :as state} @(rf/subscribe [:imports/state])
          session-error? (= :session-error status)
          report? (#{:ready :loading :page-error} status)]
      [:section {:class "import-screen" :aria-labelledby "import-heading" :aria-busy (boolean (events/active? state))}
       [:h2 {:id "import-heading" :tab-index -1 :ref focus-heading} "Import przelewów bankowych"]
       [:p "Prześlij plik i sprawdź raport analizy. Dopasowanie darczyńców i zatwierdzenie zmian nastąpią w kolejnych krokach."]
       [:p {:class "import-development-note"} "Na tym etapie używaj wyłącznie danych syntetycznych."]
       (when error [:div {:role "alert"} [:p (get errors error "Nie udało się przeanalizować pliku.")]])
       (cond
         session-error? [:button {:type "button" :on-click refresh-session} "Odśwież sesję"]
         report? [:<>
                  [summary state]
                  (when (= :loading status) [:p {:role "status"} "Pobieranie raportu…"])
                  [report-table state] [pagination state]
                  [:button {:type "button" :disabled (= :loading status) :on-click #(rf/dispatch [:imports/reload])} "Odśwież raport"]]
         (= :uncertain status) [:p "Możesz sprawdzić znany import albo rozpocząć nowe przesyłanie. Ponowne przesłanie zapisanych już danych zostanie rozpoznane jako duplikat."]
         :else [:<>
                (when batch [summary state])
                (when (or (nil? batch) (= status :failed) (= status :uploading))
                  ^{:key (str (:form-key state) ":" (if (= status :failed) "retry" "upload"))} [upload-form state csrf])
                (when (= :uploading status) [progress-panel state])])
       (when (and (not session-error?) (not (events/active? state)))
         [:div {:class "import-actions"}
          (when (and (or batch batch-id) (not report?))
            [:button {:type "button" :on-click #(rf/dispatch [:imports/reload])} "Sprawdź stan importu"])
          (when (or batch report? (= :uncertain status) (= error :duplicate))
            [:button {:type "button" :on-click #(when (or (not= :uncertain status)
                                                          (js/confirm "Wynik poprzedniego przesyłania jest nieznany. Rozpocząć nowe przesyłanie?"))
                                                  (rf/dispatch [:imports/reset]))} "Wybierz inny plik"])])])
    (finally (rf/dispatch-sync [:imports/close]))))

(rf/reg-sub :imports/state (fn [db _] (or (:imports db) events/initial-state)))
