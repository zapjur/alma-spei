(ns alma-spei.ui.donors.events
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def initial-state
  {:query {:search "" :status "active" :sort-by "name" :sort-direction "asc" :limit 25 :offset 0}
   :draft-search "" :status :loading :donors [] :total 0})

(defn session-failure [db error]
  (-> db (assoc :donors (assoc initial-state :status :error :error error))
      (update :donors-sequence (fnil inc 0))))

(defn load-page [db]
  (if-let [state (:donors db)]
    (let [id (inc (get db :donors-sequence 0))]
      {:db (-> db (assoc :donors-sequence id)
               (assoc :donors (assoc state :request-id id :status :loading :error nil :donors [] :total 0)))
       :donors/request {:request-id id :query (:query state)}})
    {:db db}))

(defn open [db _] (load-page (assoc db :donors initial-state)))
(defn close [db _]
  (-> db (dissoc :donors) (update :donors-sequence (fnil inc 0))))
(defn reload [db _] (load-page db))
(defn search-draft [db [_ value]]
  (if (:donors db) (assoc-in db [:donors :draft-search] value) db))
(defn search [db _]
  (if (:donors db)
    (load-page (-> db (assoc-in [:donors :query :search] (str/trim (get-in db [:donors :draft-search])))
                   (assoc-in [:donors :query :offset] 0)))
    {:db db}))

(defn filter-changed [db [_ field value]]
  (if (and (:donors db)
           (or (and (= field :entity-type) (#{"" "individual" "business" "organization"} value))
               (and (= field :status) (#{"active" "archived" "all"} value))))
    (load-page (-> db
                   (update-in [:donors :query] #(if (empty? value) (dissoc % field) (assoc % field value)))
                   (assoc-in [:donors :query :offset] 0)))
    {:db db}))

(defn sort-changed [db [_ field]]
  (if (and (:donors db) (#{"name" "city" "entity-type"} field))
    (let [query (get-in db [:donors :query])
          direction (if (and (= field (:sort-by query)) (= "asc" (:sort-direction query))) "desc" "asc")]
      (load-page (update-in db [:donors :query] assoc :sort-by field :sort-direction direction :offset 0)))
    {:db db}))

(defn page-changed [db [_ direction]]
  (let [{:keys [query status total]} (:donors db)
        {:keys [offset limit]} query]
    (if (and (= status :ready)
             (or (and (= direction :next) (< (+ offset limit) total))
                 (and (= direction :previous) (pos? offset))))
      (load-page (assoc-in db [:donors :query :offset]
                           (if (= direction :next) (+ offset limit) (max 0 (- offset limit)))))
      {:db db})))

(defn clear-filters [db _]
  (if (:donors db) (load-page (assoc db :donors initial-state)) {:db db}))

(defn loaded [db [_ id {:keys [data error]}]]
  (if (not= id (get-in db [:donors :request-id]))
    {:db db}
    (if (#{:unauthenticated :forbidden} error)
      {:db (session-failure db error)}
      (if error
        {:db (update db :donors assoc :status :error :error error :donors [] :total 0)}
        (if (and (pos? (get-in db [:donors :query :offset]))
                 (>= (get-in db [:donors :query :offset]) (:total data)))
        ;; Concurrent deletions can empty a later page. Retry from the beginning.
          (load-page (assoc-in db [:donors :query :offset] 0))
          {:db (update db :donors assoc :status :ready :error nil :donors (:donors data) :total (:total data))})))))

(defn with-db [handler]
  (fn [{:keys [db]} event] (handler db event)))

(rf/reg-event-fx :donors/open (with-db open))
(rf/reg-event-db :donors/close close)
(rf/reg-event-fx :donors/reload (with-db reload))
(rf/reg-event-db :donors/search-draft search-draft)
(rf/reg-event-fx :donors/search (with-db search))
(rf/reg-event-fx :donors/filter-changed (with-db filter-changed))
(rf/reg-event-fx :donors/sort-changed (with-db sort-changed))
(rf/reg-event-fx :donors/page-changed (with-db page-changed))
(rf/reg-event-fx :donors/clear-filters (with-db clear-filters))
(rf/reg-event-fx :donors/loaded (with-db loaded))
