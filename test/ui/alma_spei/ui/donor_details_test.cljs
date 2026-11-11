(ns alma-spei.ui.donor-details-test
  (:require [alma-spei.ui.donors.detail-events :as events]
            [alma-spei.ui.donors.detail-effects :as effects]
            [alma-spei.ui.donors.detail-views :as views]
            [alma-spei.ui.donors.events :as list-events]
            [cljs.test :refer-macros [deftest is async]]))

(def profile {:id "00000000-0000-0000-0000-000000000001" :entity-type "individual"
              :name "Synthetic Żółć" :first-name nil :street "Synthetic Street" :postal-code "00-001"
              :city "Synthetic City" :province nil :is-active true :version 3})
(def detail {:donor profile :donations [] :donations-total 0})

(defn loaded-db []
  (let [opened (events/select-donor {:donors list-events/initial-state} [:donors/select (:id profile)])]
    (:db (events/read-result (:db opened) [:donors/card-loaded :detail 1 {:data detail}]))))

(deftest edit-payload-preserves-version-and-does-not-include-finances-or-actors
  (let [editing (events/start-edit (loaded-db) [])
        changed (events/field-changed editing [:donors/edit-field :street ""])
        card (get-in changed [:donors :detail])
        result (events/save changed [:donors/card-save "synthetic-csrf"])]
    (is (= 3 (get-in card [:draft :version])))
    (is (events/dirty? card))
    (is (nil? (get-in result [:donors/card-save :input :street])))
    (is (= 3 (get-in result [:donors/card-save :input :version])))
    (is (= #{:entity-type :name :first-name :street :postal-code :city :province :version}
           (set (keys (get-in result [:donors/card-save :input])))))
    (is (not (contains? (get-in result [:db :donors :detail]) :csrf)))
    (is (= {:db (:db result)} (events/save (:db result) [:donors/card-save "synthetic-csrf"])))
    (is (= (:db result) (events/cancel-edit (:db result) [])))))

(deftest client-validation-prevents-invalid-request-and-preserves-draft
  (let [editing (events/start-edit (loaded-db) [])]
    (doseq [[field value] [[:name ""] [:city " "] [:postal-code "00001"]]]
      (let [db (events/field-changed editing [:donors/edit-field field value])
            result (events/save db [:donors/card-save "synthetic-csrf"])]
        (is (nil? (:donors/card-save result)))
        (is (some #(= [field] (:field %)) (get-in result [:db :donors :detail :field-errors])))
        (is (= value (get-in result [:db :donors :detail :draft field])))))
    (is (= editing (events/field-changed editing [:donors/edit-field :version 9])))))

(deftest history-failure-does-not-break-profile-loading
  (let [opened (:db (events/select-donor {:donors list-events/initial-state} [:donors/select (:id profile)]))
        failed (:db (events/read-result opened [:donors/card-loaded :history 2 {:error :unavailable}]))]
    (is (= :loading (get-in failed [:donors :detail :status])))
    (is (= :error (get-in failed [:donors :detail :history :status])))
    (is (= :ready (get-in (events/read-result failed [:donors/card-loaded :detail 1 {:data detail}])
                          [:db :donors :detail :status])))))

(deftest conflicts-and-unknown-outcomes-require-explicit-reload
  (let [edited (events/field-changed (events/start-edit (loaded-db) []) [:donors/edit-field :name "Synthetic Changed"])
        saving (events/save edited [:donors/card-save "synthetic-csrf"])
        id (get-in saving [:donors/card-save :request-id])]
    (doseq [error [:conflict :uncertain]]
      (let [result (events/saved (:db saving) [:donors/card-saved id {:error error}])
            card (get-in result [:db :donors :detail])]
        (is (= "Synthetic Changed" (get-in card [:draft :name])))
        (is (= 3 (get-in card [:draft :version])))
        (is (= {:db (:db result)} (events/save (:db result) [:donors/card-save "synthetic-csrf"])))
        (is (nil? (get-in (events/reload-card (:db result) []) [:db :donors :detail :draft])))))))

(deftest successful-save-refreshes-all-three-views-and-rejects-late-responses
  (let [saving (events/save (events/start-edit (loaded-db) []) [:donors/card-save "synthetic-csrf"])
        id (get-in saving [:donors/card-save :request-id])
        result (events/saved (:db saving) [:donors/card-saved id {:data {:donor (assoc profile :version 4)}}])
        effect-names (map first (:fx result))
        closed (list-events/close (:db result) [])]
    (is (= [:donors/card-request :donors/card-request :donors/request] effect-names))
    (is (= :saved (get-in result [:db :donors :detail :notice])))
    (is (nil? (get-in result [:db :donors :detail :draft])))
    (is (= {:db (:db result)} (events/read-result (:db result) [:donors/card-loaded :detail 1 {:data detail}])))
    (is (= {:db closed} (events/saved closed [:donors/card-saved id {:data {:donor profile}}])))))

(deftest session-errors-purge-profile-draft-history-and-list
  (let [edited (events/start-edit (loaded-db) [])
        failed (:db (events/read-result edited [:donors/card-loaded :history 2 {:error :unauthenticated}]))]
    (is (nil? (get-in failed [:donors :detail])))
    (is (= [] (get-in failed [:donors :donors])))
    (is (= :unauthenticated (get-in failed [:donors :error])))
    (is (= {:db failed} (events/read-result failed [:donors/card-loaded :detail 1 {:data detail}])))))

(deftest paging-is-independent-and-never-changes-the-edited-version
  (let [db (-> (loaded-db) (events/start-edit [])
               (assoc-in [:donors :detail :payments :total] 30))
        next-page (events/page-changed db [:donors/card-page :payments :next])
        request-id (get-in next-page [:donors/card-request :request-id])
        result (events/read-result (:db next-page) [:donors/card-loaded :detail request-id
                                                    {:data (assoc detail :donor (assoc profile :version 99) :donations-total 30)}])]
    (is (= 25 (get-in next-page [:donors/card-request :offset])))
    (is (= 3 (get-in result [:db :donors :detail :draft :version])))
    (is (= 3 (get-in result [:db :donors :detail :profile :version])))
    (is (= {:db (:db next-page)} (events/page-changed (:db next-page) [:donors/card-page :payments :next])))))

(deftest display-values-are-polish-without-rounding-money
  (is (= "02.01.2026" (views/date-label "2026-01-02")))
  (is (= "Nieznana data" (views/time-label "not-a-time")))
  (is (= "Nie" (views/audit-label "is-active" false)))
  (is (= "Brak danych" (views/audit-label "city" nil)))
  (is (= "Pusty tekst" (views/audit-label "city" "")))
  (is (= "Organizacja" (views/audit-label "entity-type" "organization"))))

(deftest save-request-sends-csrf-and-exact-version
  (async done
         (effects/request!
          (fn [url options]
            (is (= (str "/api/donors/" (:id profile)) url))
            (is (= "PUT" (.-method options)))
            (is (= "synthetic-csrf" (aget (.-headers options) "X-CSRF-Token")))
            (is (= 3 (aget (js/JSON.parse (.-body options)) "version")))
            (js/Promise.resolve #js {:ok true :status 200 :json #(js/Promise.resolve (clj->js {:donor profile}))}))
          (fn [event] (is (= [:donors/card-saved 7 {:data {:donor profile}}] event)) (done))
          {:kind :save :id (:id profile) :request-id 7 :input {:version 3} :csrf "synthetic-csrf"})))

(deftest save-conflict-and-network-failure-never-auto-resubmit
  (async done
         (let [remaining (atom 2)
               run (fn [fetch-fn expected]
                     (effects/request! fetch-fn
                                       (fn [event] (is (= [:donors/card-saved 8 {:error expected}] event))
                                         (when (zero? (swap! remaining dec)) (done)))
                                       {:kind :save :id (:id profile) :request-id 8 :input {} :csrf "synthetic-csrf"}))]
           (run (fn [_ _] (js/Promise.resolve #js {:ok false :status 409})) :conflict)
           (run (fn [_ _] (js/Promise.reject (js/Error. "Synthetic private error"))) :uncertain))))

(deftest read-response-cannot-display-another-donor
  (async done
         (effects/request!
          (fn [_ _] (js/Promise.resolve #js {:ok true :status 200 :json #(js/Promise.resolve (clj->js (assoc-in detail [:donor :id] "00000000-0000-0000-0000-000000000002")))}))
          (fn [event] (is (= [:donors/card-loaded :detail 3 {:error :invalid-response}] event)) (done))
          {:kind :detail :id (:id profile) :request-id 3 :offset 0 :limit 25})))

(deftest server-errors-keep-only-allowlisted-field-messages
  (is (= [{:field [:postal-code] :message "Podaj kod pocztowy w formacie XX-XXX."}]
         (effects/safe-field-errors {:errors [{:field ["postal-code"] :message "Synthetic rejected value"}
                                              {:field ["not-a-field"] :message "Synthetic private data"}]}))))

(deftest server-validation-uses-json-string-paths-without-reflecting-private-values
  (async done
         (effects/request!
          (fn [_ _] (js/Promise.resolve #js {:ok false :status 400
                                             :json #(js/Promise.resolve #js {:errors #js [#js {:field #js ["postal-code"] :message "Synthetic private message"}]})}))
          (fn [event]
            (is (= [:donors/card-saved 1 {:error :validation :errors [{:field [:postal-code] :message "Podaj kod pocztowy w formacie XX-XXX."}]}] event))
            (done))
          {:kind :save :id (:id profile) :request-id 1 :input {} :csrf "synthetic-csrf"})))
