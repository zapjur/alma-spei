(ns alma-spei.ui.donors.detail-events
  (:require [alma-spei.common.contracts.donors :as contract]
            [alma-spei.ui.donors.events :as list-events]
            [re-frame.core :as rf]))

(def editable-fields [:entity-type :name :first-name :street :postal-code :city :province])
(def optional-fields #{:first-name :street :postal-code :province})
(def page {:status :loading :rows [] :total 0 :offset 0 :limit 25})

(defn payload [draft]
  (into {:version (:version draft)}
        (map (fn [field] [field (let [value (get draft field)]
                                  (if (and (optional-fields field) (= "" value)) nil value))]) editable-fields)))

(defn dirty? [card]
  (and (:editing? card)
       (not= (select-keys (payload (:draft card)) editable-fields)
             (select-keys (:profile card) editable-fields))))

(defn queue-read [db kind offset]
  (if-let [id (get-in db [:donors :detail :id])]
    (let [request-id (inc (get db :donors-sequence 0))
          channel (if (= kind :detail) :payments :history)
          state (-> db (assoc :donors-sequence request-id)
                    (assoc-in [:donors :detail channel] (assoc page :offset offset :request-id request-id)))]
      {:db state :donors/card-request {:kind kind :id id :request-id request-id :offset offset :limit 25}})
    {:db db}))

(defn read-card [db id notice]
  (let [db (assoc-in db [:donors :detail]
                     {:id id :status :loading :profile nil :payments page :history page
                      :editing? false :draft nil :field-errors [] :save-status :idle :notice notice})
        detail (queue-read db :detail 0)
        history (queue-read (:db detail) :history 0)]
    {:db (:db history) :fx [[:donors/card-request (:donors/card-request detail)]
                            [:donors/card-request (:donors/card-request history)]]}))

(defn select-donor [db [_ id]]
  (if (:donors db)
    (read-card (assoc-in db [:donors :request-id] nil) id nil)
    {:db db}))

(defn back [db _]
  (if (= :saving (get-in db [:donors :detail :save-status]))
    {:db db}
    (list-events/load-page (update db :donors dissoc :detail))))

(defn reload-card [db _]
  (if-let [card (get-in db [:donors :detail])]
    (if (= :saving (:save-status card)) {:db db} (read-card db (:id card) nil))
    {:db db}))

(defn read-result [db [_ kind request-id {:keys [data error]}]]
  (let [channel (if (= kind :detail) :payments :history)
        card (get-in db [:donors :detail])
        path [:donors :detail channel]]
    (cond
      (not= request-id (get-in card [channel :request-id])) {:db db}
      (#{:unauthenticated :forbidden} error) {:db (list-events/session-failure db error)}
      error {:db (cond-> (assoc-in db path (assoc (get card channel) :status :error :error error :rows [] :total 0))
                   (and (= kind :detail) (nil? (:profile card))) (assoc-in [:donors :detail :status] :error))}
      :else
      (let [total (if (= kind :detail) (:donations-total data) (:total data))
            offset (get-in card [channel :offset])]
        (if (and (pos? offset) (>= offset total))
          (queue-read db kind 0)
          {:db (cond-> (assoc-in db path (assoc (get card channel) :status :ready :error nil :total total
                                                :rows (if (= kind :detail) (:donations data) (:events data))))
                 (= kind :detail) (assoc-in [:donors :detail :status] :ready)
                 (and (= kind :detail) (not (:editing? card))) (assoc-in [:donors :detail :profile] (:donor data)))})))))

(defn page-changed [db [_ channel direction]]
  (let [card (get-in db [:donors :detail])
        {:keys [offset limit status total]} (get card channel)]
    (if (and (#{:payments :history} channel) (not= :saving (:save-status card)) (= status :ready)
             (or (and (= direction :previous) (pos? offset))
                 (and (= direction :next) (< (+ offset limit) total))))
      (queue-read db (if (= channel :payments) :detail :history)
                  (if (= direction :next) (+ offset limit) (max 0 (- offset limit))))
      {:db db})))

(defn retry-read [db [_ channel]]
  (if (and (#{:payments :history} channel) (not= :saving (get-in db [:donors :detail :save-status])))
    (queue-read db (if (= channel :payments) :detail :history) (get-in db [:donors :detail channel :offset] 0))
    {:db db}))

(defn start-edit [db _]
  (let [card (get-in db [:donors :detail])
        profile (:profile card)]
    (if (and profile (not= :saving (:save-status card)))
      (let [draft (into {:version (:version profile)} (map #(vector % (or (get profile %) "")) editable-fields))]
        (update-in db [:donors :detail] assoc :editing? true :draft draft :save-status :idle :save-error nil :notice nil
                   :field-errors (contract/errors contract/update-donor (payload draft))))
      db)))

(defn field-changed [db [_ field value]]
  (let [card (get-in db [:donors :detail])]
    (if (and (:editing? card) (not= :saving (:save-status card)) (some #{field} editable-fields))
      (let [draft (assoc (:draft card) field value)]
        (update-in db [:donors :detail] assoc :draft draft :field-errors (contract/errors contract/update-donor (payload draft))))
      db)))

(defn cancel-edit [db _]
  (if (or (nil? (get-in db [:donors :detail])) (= :saving (get-in db [:donors :detail :save-status]))) db
      (update-in db [:donors :detail] assoc :editing? false :draft nil :field-errors [] :save-status :idle :save-error nil)))

(defn save [db [_ csrf]]
  (let [card (get-in db [:donors :detail])
        input (payload (:draft card))
        errors (contract/errors contract/update-donor input)]
    (cond
      (or (not (:editing? card)) (#{:saving :conflict :uncertain} (:save-status card))) {:db db}
      (seq errors) {:db (assoc-in db [:donors :detail :field-errors] errors)}
      :else (let [id (inc (get db :donors-sequence 0))]
              {:db (-> db (assoc :donors-sequence id)
                       (update-in [:donors :detail] assoc :save-id id :save-status :saving :save-error nil :field-errors [] :notice nil))
               :donors/card-save {:id (:id card) :request-id id :input input :csrf csrf}}))))

(defn saved [db [_ request-id {:keys [data error errors]}]]
  (let [card (get-in db [:donors :detail])]
    (cond
      (not= request-id (:save-id card)) {:db db}
      (#{:unauthenticated :forbidden} error) {:db (list-events/session-failure db error)}
      error {:db (update-in db [:donors :detail] assoc :save-status (case error :conflict :conflict :uncertain :uncertain :idle)
                            :save-error error :field-errors (or errors []))}
      :else (let [refreshed (read-card db (get-in data [:donor :id]) :saved)
                  listed (list-events/load-page (:db refreshed))]
              {:db (:db listed) :fx (conj (:fx refreshed) [:donors/request (:donors/request listed)])}))))

(doseq [[id handler] [[:donors/select select-donor] [:donors/back back] [:donors/card-reload reload-card]
                      [:donors/card-loaded read-result] [:donors/card-page page-changed] [:donors/card-retry retry-read]
                      [:donors/card-save save] [:donors/card-saved saved]]]
  (rf/reg-event-fx id (list-events/with-db handler)))
(rf/reg-event-db :donors/edit-start start-edit)
(rf/reg-event-db :donors/edit-field field-changed)
(rf/reg-event-db :donors/edit-cancel cancel-edit)
