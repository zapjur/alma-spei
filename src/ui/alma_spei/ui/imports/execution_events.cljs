(ns alma-spei.ui.imports.execution-events
  (:require [alma-spei.ui.imports.execution-location :as location]
            [re-frame.core :as rf]))

(defn busy? [state] (contains? #{:checking :saving} (get-in state [:action :status])))
(defn unresolved? [state] (= :uncertain (get-in state [:action :status])))
(defn request [db slot op args]
  (let [id (inc (get db :execution-sequence 0)) route (get-in db [:execution :route])]
    {:db (-> db (assoc :execution-sequence id) (assoc-in [:execution slot] {:status :loading :request-id id}))
     :execution/request (merge (select-keys route [:batch-id :preview-id :offset]) args {:slot slot :op op :request-id id})}))
(defn reads [db operations]
  (reduce (fn [result [slot op args]]
            (let [next (request (:db result) slot op args)]
              {:db (:db next) :fx (conj (:fx result) [:execution/request (:execution/request next)])})) {:db db :fx []} operations))
(defn open [db [_ route now]]
  (let [db (assoc db :execution {:route route :consent false :now now})
        offset (get route :offset 0)]
    (reads db (case (:mode route)
                :history [[:history :history {:offset offset}]]
                :confirm [[:capabilities :capabilities {}] [:outcome :outcome {}] [:preview :preview {}] [:preview-items :preview-items {:offset offset}]]
                :batch [[:outcome :outcome {}] [:eligibility :eligibility {:offset 0}] [:applied :applied {:offset offset}]]
                :reimport [[:outcome :outcome {}]] []))))
(defn close [db _] {:db (dissoc db :execution) :execution/abort nil})
(defn context [state]
  (let [data (get-in state [:preview :data]) preview (:preview data)]
    {:batch-id (get-in data [:batch :id]) :batch-version (:batch-version preview) :preview-id (:id preview)
     :effect-digest (:effect-digest preview) :catalog-version (:catalog-version preview)}))
(defn ready-preview? [state]
  (let [data (get-in state [:preview :data]) current (get-in state [:outcome :data :batch])]
    (and (= :ready (get-in state [:preview :status])) (= :ready (get-in state [:outcome :status]))
         (= "review" (:state current)) (= (:version current) (get-in data [:preview :batch-version]))
         (= "review-preview-v2" (get-in data [:preview :rule-version])) (false? (:stale data))
         (= (get-in data [:preview :catalog-version]) (:catalog-version data)))))
(defn valid-backup? [state]
  (let [receipt (get-in state [:backup :data :backup])]
    (and receipt (= (:backup-context state) (context state))
         (js/Number.isFinite (js/Date.parse (:expires-at receipt))) (< (:now state) (js/Date.parse (:expires-at receipt))))))
(defn can-backup? [state] (and (ready-preview? state) (true? (get-in state [:capabilities :data :commit-enabled])) (not (busy? state)) (not (unresolved? state))))
(defn can-commit? [state] (and (can-backup? state) (valid-backup? state) (true? (:consent state))))
(defn tick [db [_ now]]
  (if (:execution db)
    (let [db (assoc-in db [:execution :now] now)]
      (if (and (not (busy? (:execution db))) (not (valid-backup? (:execution db)))) (assoc-in db [:execution :consent] false) db)) db))
(defn consent [db [_ value]]
  (if (and (can-backup? (:execution db)) (valid-backup? (:execution db))) (assoc-in db [:execution :consent] (true? value)) db))
(defn action-request [db op input csrf]
  (let [result (request db :mutation op {:input input :csrf csrf})]
    (assoc-in result [:db :execution :action] {:status :saving :pending {:op op :input input} :error nil})))
(defn start-backup [db [_ csrf]]
  (if (and csrf (can-backup? (:execution db)))
    (let [ctx (context (:execution db))]
      (action-request (-> db (assoc-in [:execution :consent] false) (assoc-in [:execution :backup-context] ctx) (update :execution dissoc :backup))
                      :backup (select-keys ctx [:batch-version :catalog-version]) csrf)) {:db db}))
(defn start-commit [db [_ csrf]]
  (if (and csrf (can-commit? (:execution db)))
    (let [state (:execution db) input (assoc (context state) :confirmed true :backup-id (get-in state [:backup :data :backup :id]))
          result (request db :preflight :preview {:csrf csrf})]
      (assoc-in result [:db :execution :action] {:status :checking :pending {:op :commit :input input}})) {:db db}))
(defn open-undo [db _]
  (let [state (:execution db) data (get-in state [:outcome :data]) eligibility (get-in state [:eligibility :data])]
    (if (and (not (busy? state)) (not (unresolved? state)) (= "committed" (get-in data [:batch :state]))
             (true? (:eligible eligibility)) (= (:commit-id eligibility) (get-in data [:commit :id]))
             (= (get-in data [:batch :version]) (get-in eligibility [:batch :version])))
      (assoc-in db [:execution :modal] {:batch-id (get-in data [:batch :id]) :batch-version (get-in data [:batch :version])
                                        :commit-id (get-in data [:commit :id]) :confirmed true}) db)))
(defn cancel-undo [db _] (if (busy? (:execution db)) db (update db :execution dissoc :modal)))
(defn start-undo [db [_ csrf]]
  (if (and csrf (get-in db [:execution :modal]) (not (busy? (:execution db))) (not (unresolved? (:execution db))))
    (action-request db :undo (get-in db [:execution :modal]) csrf) {:db db}))
(defn check-outcome [db _]
  (if (not (busy? (:execution db))) (request db :outcome :outcome {}) {:db db}))
(defn retry-exact [db [_ csrf]]
  (let [state (:execution db) {:keys [op input]} (get-in state [:action :pending])]
    (if (and csrf (unresolved? state) (:recovery-checked state) op input)
      (action-request db op input csrf) {:db db})))
(defn refresh [db [_ slot offset]]
  (if (or (busy? (:execution db)) (not (contains? #{:history :outcome :eligibility :applied :preview-items :preview :capabilities} slot))) {:db db}
      (let [db (if (#{:preview :capabilities} slot) (-> db (assoc-in [:execution :consent] false) (update :execution dissoc :backup :backup-context)) db)]
        (request db slot slot {:offset (or offset 0)}))))
(defn purge [db error]
  {:db (-> db (dissoc :review :imports) (assoc :execution {:session-error error}))
   :execution/abort nil :review/abort nil :decision/abort nil :imports/abort nil})
(defn completed [db data]
  (let [batch-id (get-in data [:batch :id]) route {:mode :batch :batch-id batch-id :offset 0}
        result (reads (-> db (dissoc :review :imports)
                          (assoc :execution {:route route :outcome {:status :ready :data data} :now (get-in db [:execution :now]) :consent false}))
                      [[:eligibility :eligibility {:offset 0}] [:applied :applied {:offset 0}]])]
    (update result :fx into [[:execution/location route] [:review/abort nil] [:decision/abort nil] [:imports/abort nil]])))
(defn loaded [db [_ slot id op {:keys [data error]} csrf]]
  (if (not= id (get-in db [:execution slot :request-id])) {:db db}
      (cond
        (#{:unauthenticated :forbidden} error) (purge db error)
        error (let [mutation? (or (= slot :preflight) (contains? #{:backup :commit :undo} op))
                    db (assoc-in db [:execution slot] {:status :error :error error})
                    db (if mutation? (-> db (assoc-in [:execution :consent] false)
                                         (assoc-in [:execution :action :status] (if (= error :uncertain) :uncertain :error))
                                         (assoc-in [:execution :action :error] error)
                                         (assoc-in [:execution :recovery-checked] false)) db)]
                (if (and mutation? (= error :conflict))
                  (reads (update db :execution dissoc :backup :backup-context :modal)
                         (cond-> [[:outcome :outcome {}] [:eligibility :eligibility {:offset 0}]]
                           (= :confirm (get-in db [:execution :route :mode])) (conj [:preview :preview {}]))) {:db db}))
        (= slot :preflight)
        (let [state (:execution db) input (get-in state [:action :pending :input])]
          (if (and (false? (:stale data)) (= "review" (get-in data [:batch :state]))
                   (= (:batch-version input) (get-in data [:batch :version])) (= (:effect-digest input) (get-in data [:preview :effect-digest]))
                   (= (:catalog-version input) (:catalog-version data)) (= (:catalog-version input) (get-in data [:preview :catalog-version]))
                   (valid-backup? state) csrf)
            (action-request db :commit input csrf)
            {:db (-> db (assoc-in [:execution :preview] {:status :ready :data data}) (assoc-in [:execution :consent] false)
                     (assoc-in [:execution :action] {:status :error :error :conflict}) (update :execution dissoc :backup :backup-context))}))
        (#{:commit :undo} op) (completed db data)
        (= op :backup) {:db (-> db (assoc-in [:execution :backup] {:status :ready :data data})
                                (assoc-in [:execution :action] {:status :ready}) (assoc-in [:execution :consent] false))}
        (= op :outcome)
        (if (and (unresolved? (:execution db))
                 (case (get-in db [:execution :action :pending :op]) :commit (:commit data) :undo (:undo data) false))
          (completed db data)
          {:db (cond-> (assoc-in db [:execution :outcome] {:status :ready :data data})
                 (not= (select-keys (get-in db [:execution :outcome :data :batch]) [:id :version :state]) (select-keys (:batch data) [:id :version :state]))
                 (update :execution #(-> % (assoc :consent false) (dissoc :backup :backup-context)))
                 (unresolved? (:execution db)) (assoc-in [:execution :recovery-checked] true))})
        :else {:db (assoc-in db [:execution slot] {:status :ready :data data})})))
(defn with-db [handler] (fn [{:keys [db]} event] (handler db event)))
(rf/reg-event-fx :execution/open (with-db open))
(rf/reg-event-fx :execution/close (with-db close))
(rf/reg-event-fx :execution/backup (with-db start-backup))
(rf/reg-event-fx :execution/commit (with-db start-commit))
(rf/reg-event-fx :execution/undo (with-db start-undo))
(rf/reg-event-fx :execution/check (with-db check-outcome))
(rf/reg-event-fx :execution/retry-exact (with-db retry-exact))
(rf/reg-event-fx :execution/refresh (with-db refresh))
(rf/reg-event-fx :execution/loaded (with-db loaded))
(rf/reg-event-db :execution/consent consent)
(rf/reg-event-db :execution/tick tick)
(rf/reg-event-db :execution/open-undo open-undo)
(rf/reg-event-db :execution/cancel-undo cancel-undo)
(rf/reg-sub :execution/state (fn [db _] (:execution db)))
(rf/reg-fx :execution/location location/write!)
