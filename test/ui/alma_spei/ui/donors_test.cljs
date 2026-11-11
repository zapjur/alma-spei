(ns alma-spei.ui.donors-test
  (:require [alma-spei.ui.donors.effects :as effects]
            [alma-spei.ui.donors.events :as events]
            [alma-spei.ui.donors.subs :as subs]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [cljs.test :refer-macros [deftest is async]]))

(def donor {:id "00000000-0000-0000-0000-000000000001" :entity-type "individual"
            :name "Synthetic Żółć" :first-name nil :street nil :postal-code nil :city "Synthetic City"
            :province nil :is-active true :version 1})

(defn ready-db []
  (let [opened (:db (events/open {} []))]
    (:db (events/loaded opened [:donors/loaded 1 {:data {:donors [donor] :total 60}}]))))

(deftest registered-events-use-the-application-db-not-the-coeffects-envelope
  (let [before @rf-db/app-db
        request (atom nil)]
    (try
      (reset! rf-db/app-db {})
      (with-redefs [effects/request! (fn [_ _ args] (reset! request args))]
        (rf/dispatch-sync [:donors/open])
        (is (= 1 (:request-id @request)))
        (is (not (contains? @rf-db/app-db :db)))
        (rf/dispatch-sync [:donors/loaded 1 {:data {:donors [donor] :total 1}}])
        (is (= [donor] (get-in @rf-db/app-db [:donors :donors])))
        (is (= :ready (get-in @rf-db/app-db [:donors :status])))
        (rf/dispatch-sync [:donors/close])
        (is (nil? (:donors @rf-db/app-db))))
      (finally (reset! rf-db/app-db before)))))

(deftest search-filter-and-sorting-start-at-first-page
  (let [db (assoc-in (ready-db) [:donors :query :offset] 25)
        drafted (events/search-draft db [:donors/search-draft " Żółć "])
        searched (events/search drafted [])
        filtered (events/filter-changed db [:donors/filter-changed :entity-type "organization"])
        sorted (events/sort-changed db [:donors/sort-changed "name"])
        other-column (events/sort-changed (:db sorted) [:donors/sort-changed "city"])]
    (is (= "" (get-in drafted [:donors :query :search])))
    (is (= "Żółć" (get-in searched [:donors/request :query :search])))
    (is (= 0 (get-in searched [:donors/request :query :offset])))
    (is (= "organization" (get-in filtered [:donors/request :query :entity-type])))
    (is (= 0 (get-in filtered [:donors/request :query :offset])))
    (is (= "desc" (get-in sorted [:donors/request :query :sort-direction])))
    (is (= "asc" (get-in other-column [:donors/request :query :sort-direction])))
    (is (= [] (get-in sorted [:db :donors :donors])))
    (is (= :loading (get-in sorted [:db :donors :status])))
    (is (= "all" (get-in (events/filter-changed db [:donors/filter-changed :status "all"])
                         [:donors/request :query :status])))
    (is (not (contains? (get-in (events/filter-changed (:db filtered) [:donors/filter-changed :entity-type ""])
                                [:donors/request :query]) :entity-type)))
    (is (= {:db db} (events/filter-changed db [:donors/filter-changed :arbitrary "x"])))
    (is (= {:db db} (events/sort-changed db [:donors/sort-changed "password_hash"])))))

(deftest pages-are-bounded-and-not-double-submitted
  (let [db (ready-db)
        next-page (events/page-changed db [:donors/page-changed :next])
        last-page (assoc-in db [:donors :query :offset] 50)
        previous (events/page-changed last-page [:donors/page-changed :previous])]
    (is (= 25 (get-in next-page [:donors/request :query :offset])))
    (is (= {:db (:db next-page)} (events/page-changed (:db next-page) [:donors/page-changed :next])))
    (is (= {:db last-page} (events/page-changed last-page [:donors/page-changed :next])))
    (is (= {:db db} (events/page-changed db [:donors/page-changed :previous])))
    (is (= 25 (get-in previous [:donors/request :query :offset])))
    (is (= :ready (:status (subs/state db []))))))

(deftest stale-results-cannot-change-a-new-screen-or-session
  (let [before (ready-db)
        newer (:db (events/filter-changed before [:donors/filter-changed :entity-type "business"]))
        closed (events/close newer [])
        reopened (:db (events/open closed []))]
    (is (= {:db newer} (events/loaded newer [:donors/loaded 1 {:error :unauthenticated}])))
    (is (= {:db newer} (events/loaded newer [:donors/loaded 1 {:data {:donors [donor] :total 1}}])))
    (is (nil? (:donors closed)))
    (is (= {:db closed} (events/loaded closed [:donors/loaded 2 {:data {:donors [donor] :total 1}}])))
    (is (= {:db reopened} (events/loaded reopened [:donors/loaded 2 {:data {:donors [donor] :total 1}}])))
    (is (> (get-in reopened [:donors :request-id]) 2))))

(deftest errors-clear-visible-data-and-retry-keeps-filters
  (let [db (ready-db)
        failed (:db (events/loaded db [:donors/loaded 1 {:error :unauthenticated}]))
        retry (events/reload failed [])
        cleared (events/clear-filters (assoc-in db [:donors :query :search] "Żółć") [])]
    (is (= [] (get-in failed [:donors :donors])))
    (is (= 0 (get-in failed [:donors :total])))
    (is (= :unauthenticated (get-in failed [:donors :error])))
    (is (= (get-in db [:donors :query]) (get-in retry [:donors/request :query])))
    (is (= "" (get-in cleared [:donors/request :query :search])))
    (is (= 0 (get-in cleared [:donors/request :query :offset])))))

(deftest disappearing-last-page-recovers-without-looping
  (let [db (assoc-in (ready-db) [:donors :query :offset] 50)
        recovered (events/loaded db [:donors/loaded 1 {:data {:donors [] :total 0}}])
        id (get-in recovered [:db :donors :request-id])
        empty-result (events/loaded (:db recovered) [:donors/loaded id {:data {:donors [] :total 0}}])]
    (is (= 0 (get-in recovered [:donors/request :query :offset])))
    (is (= :ready (get-in empty-result [:db :donors :status])))
    (is (nil? (:donors/request empty-result)))))

(deftest successful-request-encodes-filters-and-validates-response
  (async done
         (effects/request!
          (fn [url options]
            (is (= "/api/donors?search=%C5%BB%C3%B3%C5%82%C4%87%20%26%20%25&entity-type=organization&status=all&sort-by=city&sort-direction=desc&limit=25&offset=0" url))
            (is (= "same-origin" (.-credentials options)))
            (is (= "no-store" (.-cache options)))
            (js/Promise.resolve #js {:ok true :status 200 :json #(js/Promise.resolve (clj->js {:donors [donor] :total 1}))}))
          (fn [event]
            (is (= [:donors/loaded 7 {:data {:donors [donor] :total 1}}] event))
            (done))
          {:request-id 7 :query {:search "Żółć & %" :entity-type "organization" :status "all"
                                 :sort-by "city" :sort-direction "desc" :limit 25 :offset 0}})))

(deftest failed-requests-do-not-expose-body-or-exception-values
  (async done
         (let [cases [[401 :unauthenticated] [403 :forbidden] [500 :unavailable]]
               pending (atom (count cases))]
           (doseq [[status error] cases]
             (effects/request!
              (fn [_ _] (js/Promise.resolve #js {:status status :ok false
                                                 :json #(js/Promise.reject (js/Error. "Synthetic private response"))}))
              (fn [event]
                (is (= [:donors/loaded status {:error error}] event))
                (when (zero? (swap! pending dec)) (done)))
              {:request-id status :query {:limit 25 :offset 0}})))))

(deftest network-and-malformed-success-responses
  (async done
         (let [pending (atom 3)
               run (fn [fetch-fn expected]
                     (effects/request!
                      fetch-fn
                      (fn [event]
                        (is (= [:donors/loaded 1 {:error expected}] event))
                        (when (zero? (swap! pending dec)) (done)))
                      {:request-id 1 :query {}}))]
           (run (fn [_ _] (js/Promise.reject (js/Error. "Synthetic network exception"))) :unavailable)
           (run (fn [_ _] (js/Promise.resolve #js {:ok true :status 200 :json #(js/Promise.resolve #js {:unexpected true})})) :invalid-response)
           (run (fn [_ _] (js/Promise.resolve #js {:ok true :status 200 :json #(js/Promise.reject (js/Error. "Synthetic invalid JSON"))})) :invalid-response))))
