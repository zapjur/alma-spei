(ns alma-spei.ui.imports.review-events
  (:require [clojure.string :as str]
            [alma-spei.ui.imports.decision-events :as editing]
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
                 (update :review dissoc :selected :comparison :editor :aux :preview :choice :choosing :stage))]
    (assoc (request base :work :rows {:batch-id id :offset offset :limit 25}) :review/location {:batch-id id :offset offset})))
(defn open [db [_ route]]
  (let [db (assoc db :review initial-state)]
    (if-let [id (:batch-id route)]
      (let [intent (:reconciliation-intent db) result (batch-load (dissoc db :reconciliation-intent) id (or (:offset route) 0))]
        (if (= id (:batch-id intent)) (assoc-in result [:db :review :intent] intent) result))
      (queue-load (dissoc db :reconciliation-intent) 0))))
(defn close [db _] {:db (-> db (dissoc :review :reconciliation-intent) (assoc :review-sequence (next-id db))) :review/abort nil :decision/abort nil})
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
        (assoc-in [:review :search] empty-search) (update :review dissoc :comparison :choice :choosing :stage)) db))
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
(defn loaded-base [db [_ kind id {:keys [data error]}]]
  (let [slot (case kind :queue :queue :rows :work :search :search :comparison :comparison)]
    (if (not= id (get-in db [:review slot :request-id])) {:db db}
        (cond
          (contains? #{:unauthenticated :forbidden} error)
          {:db (-> db (assoc :review {:mode :session-error :error error}) (assoc :review-sequence (next-id db))) :review/abort nil :decision/abort nil}
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
(defn loaded [db event]
  (let [[_ kind id response] event result (loaded-base db event)
        target (get-in db [:review :find-row])]
    (if (and (= kind :rows) (= id (get-in db [:review :work :request-id])) (:data response)
             (= :ready (get-in result [:db :review :work :status])))
      (let [next-db (:db result) work (get-in next-db [:review :work])
            row (some #(when (= target {:worksheet (:worksheet-index %) :row (:row-number %)}) %) (:rows work))]
        (cond
          (and target row) (editing/load-editor (-> next-db (assoc-in [:review :selected] target) (update :review dissoc :find-row)))
          (and target (< (+ (:offset work) (:limit work)) (:total work)))
          (update (batch-load next-db (:batch-id work) (+ (:offset work) (:limit work))) :db assoc-in [:review :find-row] target)
          :else (let [base (update next-db :review dissoc :find-row)
                      result (if (get-in db [:review :resolving]) (editing/load-editor base) {:db base})]
                  (cond
                    (get-in db [:review :resolving]) result
                    (get-in db [:review :intent]) (-> result (update :db update :review dissoc :intent)
                                                      (assoc :dispatch [:decision/automatic-run (get-in db [:review :intent :csrf]) (get-in db [:review :intent :policy])]))
                    :else (assoc result :dispatch [:decision/automatic-report 0]))))) result)))
(defn select-row-fx [db event]
  (let [selected (select-row db event)]
    (if (= selected db) {:db db} (editing/load-editor selected))))
(defn with-db [handler] (fn [{:keys [db]} event] (handler db event)))
(rf/reg-event-fx :review/open (with-db open))
(rf/reg-event-fx :review/close (with-db close))
(rf/reg-event-fx :review/back (with-db back))
(rf/reg-event-fx :review/select-batch (with-db select-batch))
(rf/reg-event-fx :review/reload (with-db reload))
(rf/reg-event-fx :review/page (with-db page))
(rf/reg-event-fx :review/select-row (with-db select-row-fx))
(rf/reg-event-db :review/search-draft search-draft)
(rf/reg-event-fx :review/search (with-db search))
(rf/reg-event-db :review/search-status search-status)
(rf/reg-event-fx :review/search-page (with-db search-next))
(rf/reg-event-fx :review/compare (with-db compare-donor))
(rf/reg-event-fx :review/loaded (with-db loaded))
(rf/reg-sub :review/state (fn [db _] (or (:review db) initial-state)))

(defn advance [db [_ csrf]]
  (let [{:keys [work selected]} (:review db)
        rows (:rows work)
        index (first (keep-indexed #(when (= selected {:worksheet (:worksheet-index %2) :row (:row-number %2)}) %1) rows))
        next-row (when index (get rows (inc index)))]
    (cond
      (or (editing/dirty? (:review db)) (editing/busy? (:review db))) {:db db}
      (get-in db [:review :resolving]) {:db (update db :review dissoc :resolving :stage) :dispatch [:decision/automatic-report 0]}
      next-row (select-row-fx db [:review/select-row (:worksheet-index next-row) (:row-number next-row)])
      (< (+ (:offset work) (:limit work)) (:total work)) (page db [:review/page :next])
      :else {:db (assoc-in db [:review :stage] :summary) :dispatch [:decision/preview csrf]})))
(defn summary [db [_ csrf]]
  (if (or (editing/dirty? (:review db)) (editing/busy? (:review db))) {:db db}
      {:db (assoc-in db [:review :stage] :summary) :dispatch [:decision/preview csrf]}))
(rf/reg-event-fx :review/advance (with-db advance))
(rf/reg-event-fx :review/summary (with-db summary))
(rf/reg-event-db :review/resume (fn [db _] (update db :review dissoc :stage)))

(defn find-row [db [_ worksheet row]]
  (if (and (get-in db [:review :work :batch-id]) (not (editing/dirty? (:review db))) (not (editing/busy? (:review db))))
    (let [target {:worksheet worksheet :row row}]
      (update (batch-load db (get-in db [:review :work :batch-id]) 0) :db update :review assoc :find-row target :resolving true
              :automation (get-in db [:review :automation])
              :resolution (some #(when (= target {:worksheet (get-in % [:row :worksheet-index]) :row (get-in % [:row :row-number])}) %) (get-in db [:review :automation :data :exceptions]))))
    {:db db}))
(rf/reg-event-fx :review/find-row (with-db find-row))

(rf/reg-event-fx :review/automatic
                 (with-db (fn [db _]
                            {:db (update db :review dissoc :resolving :stage) :dispatch [:decision/automatic-report 0]})))

(rf/reg-event-fx :review/intent
                 (with-db (fn [db [_ intent]] {:db (assoc db :reconciliation-intent intent) :review/start-route (:batch-id intent)})))
