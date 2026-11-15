(ns alma-spei.ui.imports.decision-events
  (:require [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.contracts.donors :as donors]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(def empty-financial {:currency "" :evidence "" :disposition "unresolved" :overlap-checked false})
(def empty-draft {:kind "unresolved" :donor-id nil :proposal-id nil :target-version nil :financial empty-financial :donation-id nil :donation-version nil})
(defn draft-from [decision]
  (if decision (-> (select-keys decision (keys empty-draft))
                   (update :financial #(merge empty-financial %)) (update-in [:financial :currency] #(or % ""))) empty-draft))
(defn next-id [db] (inc (get db :decision-sequence 0)))
(defn request [db slot op args]
  (let [id (next-id db)]
    {:db (-> db (assoc :decision-sequence id) (update-in (into [:review] slot) assoc :request-id id :status :loading :error nil :op op))
     :decision/request (merge args {:request-id id :op op :slot slot :batch-id (get-in db [:review :work :batch-id])})}))
(defn dirty? [state]
  (let [editor (:editor state) form (:proposal-form editor)]
    (or (and (:draft editor) (not= (:draft editor) (:initial editor)))
        (and form (or (not= (:profile form) (:initial form)) (:checked-existing form) (:checked-proposals form))))))
(defn busy? [state] (contains? #{:saving :loading} (get-in state [:editor :status])))
(defn editable? [editor] (and (= :ready (:status editor)) (not (:requires-reload editor))))
(defn load-editor [db]
  (let [locator (get-in db [:review :selected]) batch (get-in db [:review :work :batch])]
    (if (and locator batch (= "review" (:state batch)))
      (request (-> db (assoc-in [:review :editor] {:batch-version (:version batch)})
                   (update :review dissoc :aux :preview)) [:editor] :decision-load locator)
      {:db db})))
(defn reload [db _] (load-editor db))
(defn field [db [_ path value]]
  (if (and (editable? (get-in db [:review :editor]))
           (contains? #{[:financial :currency] [:financial :evidence] [:financial :disposition] [:financial :overlap-checked]} path))
    (let [db (assoc-in db (into [:review :editor :draft] path) value)]
      (if (= path [:financial :disposition])
        (-> db (assoc-in [:review :editor :draft :donation-id] nil)
            (assoc-in [:review :editor :draft :donation-version] nil)
            (assoc-in [:review :editor :draft :financial :overlap-checked] false)) db)) db))
(defn choose-target [db [_ kind id version]]
  (if (and (editable? (get-in db [:review :editor])) (contains? #{"existing-donor" "new-proposal" "unresolved"} kind))
    (-> db (update-in [:review :editor :draft] assoc :kind kind :donor-id (when (= kind "existing-donor") id)
                      :proposal-id (when (= kind "new-proposal") id) :target-version (when-not (= kind "unresolved") version)
                      :donation-id nil :donation-version nil)
        (assoc-in [:review :editor :draft :financial :disposition] "unresolved")
        (assoc-in [:review :editor :draft :financial :overlap-checked] false)
        (update-in [:review :aux] dissoc :payments)) db))
(defn build-input [state]
  (let [editor (:editor state) draft (:draft editor)]
    (-> draft (assoc :catalog-version (:catalog-version editor) :batch-version (:batch-version editor) :version (or (get-in editor [:current :version]) 0))
        (assoc :financial (when-not (= "unresolved" (:kind draft))
                            (update (:financial draft) :currency #(not-empty (str/trim (or % "")))))))))
(defn saveable? [state]
  (let [input (build-input state)]
    (and (editable? (:editor state)) (validation/valid? contract/decision-input input)
         (or (not= "existing" (get-in input [:financial :disposition])) (contract/complete? input)))))
(defn save [db [_ csrf clear?]]
  (let [state (:review db)
        db (if clear? (assoc-in db [:review :editor :draft] empty-draft) db)
        state (if clear? (:review db) state)]
    (if (and csrf (saveable? state))
      (let [result (request db [:editor] :decision-save (merge (:selected state) {:csrf csrf :input (build-input state)}))]
        (assoc-in result [:db :review :editor :status] :saving)) {:db db})))
(defn load-aux [db [_ op offset]]
  (let [state (:review db) donor-id (get-in state [:editor :draft :donor-id])]
    (if (and (get-in state [:work :batch-id]) (contains? #{:proposals :payments :events :blockers :items} op)
             (or (not= op :payments) donor-id) (or (not= op :items) (get-in state [:preview :data :preview :id])))
      (request (assoc-in db [:review :aux op] {}) [:aux op] op
               {:limit 25 :offset offset :donor-id donor-id :preview-id (get-in state [:preview :data :preview :id])}) {:db db})))
(def empty-profile {:entity-type "" :name "" :city "" :first-name "" :street "" :postal-code "" :province ""})
(defn proposal-form [db [_ proposal]]
  (if (editable? (get-in db [:review :editor]))
    (let [profile (if proposal (merge empty-profile (into {} (map (fn [[k v]] [k (or v "")]) (:profile proposal)))) empty-profile)]
      (assoc-in db [:review :editor :proposal-form] {:id (:id proposal) :version (:version proposal) :profile profile :initial profile
                                                     :checked-existing false :checked-proposals false})) db))
(defn proposal-field [db [_ field value]]
  (if (and (editable? (get-in db [:review :editor])) (contains? (set (keys empty-profile)) field))
    (assoc-in db [:review :editor :proposal-form :profile field] value) db))
(defn proposal-check [db [_ field value]]
  (if (and (editable? (get-in db [:review :editor])) (contains? #{:checked-existing :checked-proposals} field))
    (assoc-in db [:review :editor :proposal-form field] value) db))
(defn profile-input [form]
  (into {} (map (fn [[field value]] [field (if (contains? #{:entity-type :name :city} field) value (not-empty (str/trim (or value ""))))]) (:profile form))))
(defn proposal-saveable? [state]
  (let [form (get-in state [:editor :proposal-form])]
    (and (editable? (:editor state)) form (validation/valid? donors/create-donor (profile-input form))
         (or (:id form) (and (:checked-existing form) (:checked-proposals form))))))
(defn save-proposal [db [_ csrf]]
  (let [state (:review db) form (get-in state [:editor :proposal-form])
        update? (:id form)
        input (cond-> {:batch-version (get-in state [:editor :batch-version]) :profile (profile-input form)}
                update? (assoc :version (:version form))
                (not update?) (assoc :checked-existing true :checked-proposals true :catalog-version (get-in state [:editor :catalog-version])))]
    (if (and csrf (proposal-saveable? state))
      (let [result (request db [:editor] (if update? :proposal-update :proposal-create) {:csrf csrf :input input :proposal-id (:id form)})]
        (assoc-in result [:db :review :editor :status] :saving)) {:db db})))
(defn choose-payment [db [_ id]]
  (if-let [payment (some #(when (= id (:id %)) %) (get-in db [:review :aux :payments :data :payments]))]
    (if (editable? (get-in db [:review :editor]))
      (-> db (assoc-in [:review :editor :draft :donation-id] id)
          (assoc-in [:review :editor :draft :donation-version] (:version payment))
          (assoc-in [:review :editor :draft :financial :disposition] "existing")
          (assoc-in [:review :editor :draft :financial :overlap-checked] false)) db) db))
(defn create-preview [db [_ csrf]]
  (if (and csrf (get-in db [:review :work :batch]) (not (dirty? (:review db))) (not (busy? (:review db)))
           (not= :loading (get-in db [:review :preview :status])))
    (request (-> db (assoc-in [:review :preview] {}) (update-in [:review :aux] dissoc :items :blockers)) [:preview] :preview-create
             {:csrf csrf :input {:batch-version (get-in db [:review :work :batch :version])}}) {:db db}))
(defn load-preview [db [_ id]]
  (if (get-in db [:review :work :batch-id])
    (request (assoc-in db [:review :preview] {}) [:preview] :preview-load {:preview-id id}) {:db db}))
(defn loaded [db [_ slot id op {:keys [data error]}]]
  (if (not= id (get-in db (into [:review] (conj slot :request-id)))) {:db db}
      (cond
        (contains? #{:unauthenticated :forbidden} error)
        {:db (assoc db :review {:mode :session-error :error error}) :decision/abort nil :review/abort nil}
        error (let [mutation? (contains? #{:decision-save :proposal-create :proposal-update} op)
                    result {:db (update-in db (into [:review] slot) assoc :status (if mutation? :ready :error) :error error
                                           :requires-reload (and mutation? (contains? #{:conflict :uncertain} error)))}]
                (if (= error :blocked) (assoc result :dispatch [:decision/aux :blockers 0]) result))
        (= op :decision-load)
        {:db (-> db (assoc-in [:review :editor] {:status :ready :catalog-version (:catalog-version data) :batch-version (get-in data [:batch :version]) :current (:decision data)
                                                 :draft (draft-from (:decision data)) :initial (draft-from (:decision data))})
                 (assoc-in [:review :work :batch] (:batch data)))}
        (= op :decision-save)
        {:db (-> db (update-in [:review :editor] assoc :status :ready :error nil :requires-reload false
                               :catalog-version (:catalog-version data) :batch-version (get-in data [:batch :version]) :current (:decision data)
                               :draft (draft-from (:decision data)) :initial (draft-from (:decision data)) :notice :decision-saved)
                 (assoc-in [:review :work :batch] (:batch data)) (update :review dissoc :preview :aux))}
        (contains? #{:proposal-create :proposal-update} op)
        {:db (-> db (update-in [:review :editor] assoc :status :ready :error nil :requires-reload false :notice :proposal-saved
                               :batch-version (get-in data [:batch :version]) :saved-proposal (:proposal data) :proposal-form nil)
                 (assoc-in [:review :work :batch] (:batch data)) (update :review dissoc :preview :aux))}
        (contains? #{:preview-create :preview-load} op)
        {:db (assoc-in db [:review :preview] {:status :ready :data data}) :dispatch [:decision/aux :items 0]}
        :else {:db (assoc-in db (into [:review] slot) {:status :ready :data data})})))
(defn with-db [handler] (fn [{:keys [db]} event] (handler db event)))
(rf/reg-event-fx :decision/reload (with-db reload))
(rf/reg-event-db :decision/field field)
(rf/reg-event-db :decision/target choose-target)
(rf/reg-event-fx :decision/save (with-db save))
(rf/reg-event-fx :decision/aux (with-db load-aux))
(rf/reg-event-db :decision/proposal-form proposal-form)
(rf/reg-event-db :decision/proposal-field proposal-field)
(rf/reg-event-db :decision/proposal-check proposal-check)
(rf/reg-event-fx :decision/proposal-save (with-db save-proposal))
(rf/reg-event-db :decision/payment choose-payment)
(rf/reg-event-fx :decision/preview (with-db create-preview))
(rf/reg-event-fx :decision/open-preview (with-db load-preview))
(rf/reg-event-fx :decision/loaded (with-db loaded))
