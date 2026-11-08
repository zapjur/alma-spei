(ns alma-spei.ui.imports.review-events
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def initial-state {:mode :queue :queue {:status :loading :batches [] :total 0 :offset 0 :limit 25}})
(def empty-search {:status :idle :draft "" :term "" :status-filter "active" :donors [] :total 0 :offset 0 :limit 25})
(defn next-id [db] (inc (get db :review-sequence 0)))
(defn request [db slot kind args]
  (let [id (next-id db)]
    {:db (-> db (assoc :review-sequence id) (update-in [:review slot] assoc :request-id id :status :loading :error nil))
     :review/request (assoc args :request-id id :kind kind)}))
(defn queue-load [db offset]
  (request (assoc-in db [:review :queue] {:status :loading :batches [] :total 0 :offset offset :limit 25})
           :queue :queue {:offset offset :limit 25}))
(defn batch-load [db id offset]
  (let [base (-> db (assoc-in [:review :mode] :batch)
                 (assoc-in [:review :work] {:batch-id id :rows [] :total 0 :offset offset :limit 25})
                 (assoc-in [:review :search] empty-search)
                 (update :review dissoc :selected :comparison))]
    (assoc (request base :work :rows {:batch-id id :offset offset :limit 25}) :review/location {:batch-id id :offset offset})))
(defn open [db [_ route]]
  (let [db (assoc db :review initial-state)]
    (if-let [id (:batch-id route)] (batch-load db id (or (:offset route) 0)) (queue-load db 0))))
(defn close [db _] {:db (-> db (dissoc :review) (assoc :review-sequence (next-id db))) :review/abort nil})
(defn back [db _]
  (assoc (queue-load (assoc db :review initial-state) 0) :review/location {:offset 0}))
(defn select-batch [db [_ id]]
  (if (some #(= id (:id %)) (get-in db [:review :queue :batches])) (batch-load db id 0) {:db db}))
(defn reload [db _]
  (cond
    (= :queue (get-in db [:review :mode])) (queue-load db (get-in db [:review :queue :offset] 0))
    (= :batch (get-in db [:review :mode])) (batch-load db (get-in db [:review :work :batch-id]) (get-in db [:review :work :offset] 0))
    :else {:db db}))
(defn page [db [_ direction]]
  (let [slot (if (= :queue (get-in db [:review :mode])) :queue :work)
        {:keys [status offset limit total batch-id]} (get-in db [:review slot])]
    (if (and (= status :ready) (or (and (= direction :next) (< (+ offset limit) total))
                                   (and (= direction :previous) (pos? offset))))
      (let [offset (if (= direction :next) (+ offset limit) (max 0 (- offset limit)))]
        (if (= slot :queue) (queue-load db offset) (batch-load db batch-id offset))) {:db db})))
(defn select-row [db [_ worksheet row]]
  (if (some #(and (= worksheet (:worksheet-index %)) (= row (:row-number %))) (get-in db [:review :work :rows]))
    (-> db (assoc-in [:review :selected] {:worksheet worksheet :row row})
        (assoc-in [:review :search] empty-search) (update :review dissoc :comparison)) db))
(defn search-draft [db [_ value]]
  (if (and (get-in db [:review :selected]) (string? value) (<= (count value) 200))
    (assoc-in db [:review :search :draft] value) db))
(defn search-page [db offset]
  (let [search (get-in db [:review :search])]
    (request (-> db (update-in [:review :search] assoc :donors [] :offset offset :total 0)
                 (update :review dissoc :comparison)) :search :search
             {:term (:term search) :status-filter (:status-filter search) :offset offset :limit 25})))
(defn search [db _]
  (let [search (get-in db [:review :search]) term (str/trim (or (:draft search) ""))]
    (if (and (get-in db [:review :selected]) (seq term) (<= (count term) 200))
      (search-page (assoc-in db [:review :search :term] term) 0) {:db db})))
(defn search-status [db [_ value]]
  (if (and (get-in db [:review :selected]) (contains? #{"active" "archived" "all"} value))
    (-> db (assoc-in [:review :search] (assoc empty-search :status-filter value
                                              :draft (get-in db [:review :search :draft] "")))
        (update :review dissoc :comparison)) db))
(defn search-next [db [_ direction]]
  (let [{:keys [status offset limit total]} (get-in db [:review :search])]
    (if (and (= status :ready) (or (and (= direction :next) (< (+ offset limit) total))
                                   (and (= direction :previous) (pos? offset))))
      (search-page db (if (= direction :next) (+ offset limit) (max 0 (- offset limit)))) {:db db})))
(defn compare-donor [db [_ id]]
  (if (and (get-in db [:review :selected]) (some #(= id (:id %)) (get-in db [:review :search :donors])))
    (request (assoc-in db [:review :comparison] {:donor-id id}) :comparison :comparison
             (merge (get-in db [:review :selected]) {:donor-id id :batch-id (get-in db [:review :work :batch-id])})) {:db db}))
(defn loaded [db [_ kind id {:keys [data error]}]]
  (let [slot (case kind :queue :queue :rows :work :search :search :comparison :comparison)]
    (if (not= id (get-in db [:review slot :request-id])) {:db db}
        (cond
          (contains? #{:unauthenticated :forbidden} error)
          {:db (-> db (assoc :review {:mode :session-error :error error}) (assoc :review-sequence (next-id db))) :review/abort nil}
          error {:db (update-in db [:review slot] assoc :status :error :error error)}
          (= kind :queue)
          (if (and (pos? (:offset data)) (>= (:offset data) (:total data)))
            (queue-load db 0)
            {:db (assoc-in db [:review :queue] (assoc data :status :ready))})
          (= kind :rows)
          (let [current (get-in data [:batch :state])]
            (cond
              (not= current "review") {:db (-> db (assoc-in [:review :work] (assoc data :batch-id (get-in data [:batch :id]) :status :closed))
                                               (update :review dissoc :selected :comparison :search))}
              (and (pos? (:offset data)) (>= (:offset data) (:total data))) (batch-load db (get-in data [:batch :id]) 0)
              :else {:db (-> db (assoc-in [:review :work] (assoc data :batch-id (get-in data [:batch :id]) :status :ready))
                             (assoc-in [:review :selected] (when-let [row (first (:rows data))] {:worksheet (:worksheet-index row) :row (:row-number row)})))}))
          (= kind :search) {:db (update-in db [:review :search] #(-> % (merge data) (assoc :status :ready) (dissoc :request-id)))}
          (= kind :comparison) {:db (-> db (assoc-in [:review :comparison] (assoc data :status :ready))
                                        (assoc-in [:review :work :batch] (:batch data)))}
          :else {:db db}))))
(defn with-db [handler] (fn [{:keys [db]} event] (handler db event)))
(rf/reg-event-fx :review/open (with-db open))
(rf/reg-event-fx :review/close (with-db close))
(rf/reg-event-fx :review/back (with-db back))
(rf/reg-event-fx :review/select-batch (with-db select-batch))
(rf/reg-event-fx :review/reload (with-db reload))
(rf/reg-event-fx :review/page (with-db page))
(rf/reg-event-db :review/select-row select-row)
(rf/reg-event-db :review/search-draft search-draft)
(rf/reg-event-fx :review/search (with-db search))
(rf/reg-event-db :review/search-status search-status)
(rf/reg-event-fx :review/search-page (with-db search-next))
(rf/reg-event-fx :review/compare (with-db compare-donor))
(rf/reg-event-fx :review/loaded (with-db loaded))
(rf/reg-sub :review/state (fn [db _] (or (:review db) initial-state)))
