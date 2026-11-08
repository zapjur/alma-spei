(ns alma-spei.ui.review-test
  (:require [alma-spei.ui.imports.review-events :as events]
            [alma-spei.ui.imports.review-effects :as effects]
            [alma-spei.ui.imports.review-location :as location]
            [alma-spei.ui.imports-test :as fixture]
            [alma-spei.ui.donors-test :as donors]
            [alma-spei.ui.auth.views :as auth]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [cljs.test :refer-macros [deftest is async]]))

(def id fixture/id)
(def donor (assoc donors/donor :id fixture/other-id))
(def page (dissoc fixture/data :skipped-empty-rows))
(defn batch-open [] (events/open {} [:review/open {:batch-id id :offset 0}]))
(defn loaded-work [] (:db (events/loaded (:db (batch-open)) [:review/loaded :rows 1 {:data page}])))
(defn searched []
  (let [start (-> (loaded-work) (events/search-draft [:review/search-draft " Synthetic Żółć "]) (events/search []))]
    (:db (events/loaded (:db start) [:review/loaded :search 2 {:data {:donors [donor] :total 1}}]))))

(deftest links-contain-only-batch-and-page-and-survive-authentication
  (is (= {:offset 0} (location/parse "#review")))
  (is (= {:batch-id id :offset 25} (location/parse (str "#review/" id "/25"))))
  (doseq [hash ["#review/private" (str "#review/" id "/-1") (str "#review/" id "/25?name=private") "#other"]]
    (is (nil? (location/parse hash))))
  (let [state {:review-route {:batch-id id :offset 25}}
        user {:id "synthetic" :password-change-required false}]
    (is (= :review (:screen (auth/result-state state :login {:user user :csrf "synthetic"}))))
    (is (nil? (:review-route (auth/result-state state :logout {:user nil})))))
  (is (not= (:review-route (auth/review-route-state {} {:offset 0}))
            (:review-route (auth/review-route-state {:review-route-sequence 1} {:offset 0})))))

(deftest queue-and-rows-use-server-counts-with-no-inferred-search
  (let [open (events/open {} [:review/open {}])
        queue (:db (events/loaded (:db open) [:review/loaded :queue 1 {:data {:batches [fixture/batch] :total 1 :offset 0 :limit 25}}]))
        select (events/select-batch queue [:review/select-batch id])
        db (:db (events/loaded (:db select) [:review/loaded :rows 2 {:data page}]))]
    (is (= :queue (get-in open [:review/request :kind])))
    (is (= {:batch-id id :offset 0} (:review/location select)))
    (is (= {:worksheet 1 :row 1} (get-in db [:review :selected])))
    (is (= 0 (get-in db [:review :work :batch :reviewed-rows])))
    (is (= "" (get-in db [:review :search :draft])))
    (is (nil? (get-in db [:review :comparison])))
    (is (= (:rows page) (get-in db [:review :work :rows])))
    (is (= {:db db} (events/search db [])))))

(deftest manual-search-selection-and-page-limits
  (let [db (loaded-work)
        searched (searched)
        compare (events/compare-donor searched [:review/compare (:id donor)])
        selected (events/select-row (:db compare) [:review/select-row 1 2])
        next (events/page db [:review/page :next])]
    (is (= "Synthetic Żółć" (get-in searched [:review :search :term])))
    (is (= (:id donor) (get-in compare [:review/request :donor-id])))
    (is (= 1 (get-in compare [:review/request :row])))
    (is (nil? (get-in selected [:review :comparison])))
    (is (= [] (get-in selected [:review :search :donors])))
    (is (= 25 (get-in next [:review/request :offset])))
    (is (= {:db (:db next)} (events/page (:db next) [:review/page :next])))
    (is (= {:db db} (events/page db [:review/page :previous])))
    (is (= {:db db} (events/compare-donor db [:review/compare (:id donor)])))))

(deftest stale-search-comparison-and-closed-session-cannot-repopulate-data
  (let [start (events/compare-donor (searched) [:review/compare (:id donor)])
        changed (events/select-row (:db start) [:review/select-row 1 2])
        old (events/loaded changed [:review/loaded :comparison 3 {:data {:batch fixture/batch :row fixture/row :donor donor}}])
        closed (:db (events/close changed []))
        reopened (:db (events/open closed [:review/open {}]))]
    (is (= {:db changed} old))
    (is (= {:db closed} (events/loaded closed [:review/loaded :comparison 3 {:error :unauthenticated}])))
    (is (> (:review-sequence reopened) 3))
    (is (= {:db reopened} (events/loaded reopened [:review/loaded :comparison 3 {:data {}}]))))
  (let [db (:db (batch-open))
        failed (events/loaded db [:review/loaded :rows 1 {:error :unauthenticated}])]
    (is (= {:mode :session-error :error :unauthenticated} (get-in failed [:db :review])))
    (is (contains? failed :review/abort))))

(deftest authoritative-progress-and-batch-state-never-change-by-browsing
  (let [db (loaded-work)
        updated (events/loaded (:db (events/reload db []))
                               [:review/loaded :rows 2 {:data (assoc-in page [:batch :reviewed-rows] 1)}])
        cancelled (events/loaded (:db (events/reload db []))
                                 [:review/loaded :rows 2 {:data (assoc-in page [:batch :state] "cancelled")}])]
    (is (= 1 (get-in updated [:db :review :work :batch :reviewed-rows])))
    (is (= :closed (get-in cancelled [:db :review :work :status])))
    (is (nil? (get-in cancelled [:db :review :selected])))
    (is (= 0 (get-in (events/select-row db [:review/select-row 1 2]) [:review :work :batch :reviewed-rows])))))

(deftest request-paths-and-identity-validation-are-explicit
  (is (= "/api/donors?search=%C5%BB%C3%B3%C5%82%C4%87%20%26%20%25&status=all&limit=25&offset=0"
         (effects/path {:kind :search :term "Żółć & %" :status-filter "all" :limit 25 :offset 0})))
  (let [comparison {:batch fixture/batch :row fixture/row :donor donor}
        args {:kind :comparison :batch-id id :worksheet 1 :row 1 :donor-id (:id donor)}]
    (is (effects/valid? comparison args))
    (is (not (effects/valid? comparison (assoc args :row 2))))
    (is (not (effects/valid? comparison (assoc args :donor-id id))))
    (is (not (effects/valid? (assoc comparison :donations []) args))))
  (is (not (effects/valid? (assoc page :rows []) {:kind :rows :batch-id id :offset 0 :limit 25}))))

(deftest read-effects-sanitize-errors-and-use-same-origin
  (async done
         (let [pending (atom 3)]
           (doseq [[kind status error] [[:queue 401 :unauthenticated] [:rows 403 :forbidden] [:search 500 :unavailable]]]
             (effects/request!
              (fn [_ options]
                (is (= "GET" (.-method options)))
                (is (= "same-origin" (.-credentials options)))
                (is (= "no-store" (.-cache options)))
                (js/Promise.resolve #js {:ok false :status status :json #(js/Promise.reject (js/Error. "Synthetic private error"))}))
              (fn [event]
                (is (= [:review/loaded kind 1 {:error error}] event))
                (when (zero? (swap! pending dec)) (done)))
              {:kind kind :request-id 1 :batch-id id :offset 0 :limit 25 :term "Synthetic" :status-filter "all"})))))

(deftest registered-events-extract-db-and-clear-on-close
  (let [before @rf-db/app-db captured (atom nil)]
    (try
      (reset! rf-db/app-db {})
      (with-redefs [effects/request! (fn [_ _ input] (reset! captured input))]
        (rf/dispatch-sync [:review/open {}])
        (is (= :queue (:kind @captured)))
        (is (not (contains? @rf-db/app-db :db)))
        (rf/dispatch-sync [:review/close])
        (is (nil? (:review @rf-db/app-db))))
      (finally (reset! rf-db/app-db before)))))
