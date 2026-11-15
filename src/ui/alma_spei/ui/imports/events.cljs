(ns alma-spei.ui.imports.events
  (:require [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(def maximum-file-size (* 5 1024 1024))
(def initial-state {:status :idle :separator "" :file nil :rows [] :offset 0 :limit 25 :total 0})
(defn active? [state] (#{:uploading :loading} (:status state)))
(defn file-error [{:keys [name size]}]
  (cond
    (not (validation/valid? contract/filename name)) :filename
    (not (re-find #"(?i)\.(csv|xlsx)$" name)) :file-type
    (not (and (number? size) (<= 1 size maximum-file-size))) :file-size))
(defn csv? [file] (str/ends-with? (str/lower-case (or (:name file) "")) ".csv"))
(defn selectable? [state]
  (and (:file state) (not (file-error (:file state)))
       (or (not (csv? (:file state))) (contains? #{"," ";"} (:separator state)))))
(defn next-id [db] (inc (get db :imports-sequence 0)))
(defn open [db _] (-> db (assoc :imports (assoc initial-state :form-key (next-id db))) (assoc :imports-sequence (next-id db))))
(defn close [db _] {:db (-> db (dissoc :imports) (assoc :imports-sequence (next-id db))) :imports/abort nil})
(defn choose-file [db [_ file]]
  (if (and (:imports db) (not (active? (:imports db))))
    (update db :imports assoc :file (when file (select-keys file [:name :size])) :error (when file (file-error file))) db))
(defn separator [db [_ value]]
  (if (and (:imports db) (not (active? (:imports db))) (contains? #{"" "," ";"} value))
    (assoc-in db [:imports :separator] value) db))
(defn start [db [_ file csrf retry? confirmed?]]
  (let [state (:imports db) batch (:batch state)]
    (if (and (#{:idle :error :failed} (:status state)) (selectable? state)
             file csrf
             (or (and (not retry?) (nil? batch))
                 (and retry? confirmed? (= :failed (:status state)) (= "failed" (:state batch)))))
      (let [id (next-id db)]
        {:db (-> db (assoc :imports-sequence id)
                 (update :imports assoc :status :uploading :request-id id :error nil :progress nil :sent? false :rows []))
         :imports/upload (cond-> {:request-id id :file file :csrf csrf :separator (:separator state)}
                           retry? (assoc :retry {:id (:id batch) :version (:version batch)}))})
      {:db db})))
(defn progress [db [_ id percent sent?]]
  (if (and (= id (get-in db [:imports :request-id])) (= :uploading (get-in db [:imports :status])))
    (update db :imports assoc :progress percent :sent? sent?) db))
(defn purge [db error]
  (-> db (assoc :imports (assoc initial-state :status :session-error :error error))
      (assoc :imports-sequence (next-id db))))
(defn finished [db [_ id {:keys [data error batch batch-id]}]]
  (if (not= id (get-in db [:imports :request-id]))
    {:db db}
    (cond
      (#{:unauthenticated :forbidden} error) {:db (purge db error)}
      data {:db (update db :imports #(-> % (assoc :status :ready :batch (:batch data) :rows (:rows data)
                                                  :total (:total data) :offset (:offset data) :limit (:limit data)
                                                  :skipped-empty-rows (:skipped-empty-rows data) :file nil :error nil)
                                         (dissoc :request-id :batch-id :progress :sent?)))}
      :else {:db (update db :imports
                         #(cond-> (assoc % :error error :rows [] :status (cond
                                                                           batch :failed
                                                                           (or batch-id (= error :uncertain)) :uncertain
                                                                           :else :error))
                            batch (assoc :batch batch :file nil)
                            batch-id (assoc :batch-id batch-id)))})))

(defn load-page [db offset]
  (let [state (:imports db) id (next-id db) batch-id (or (get-in state [:batch :id]) (:batch-id state))]
    (if (and state batch-id (not (active? state)))
      {:db (-> db (assoc :imports-sequence id)
               (update :imports assoc :status :loading :request-id id :offset offset :rows [] :error nil))
       :imports/page {:request-id id :batch-id batch-id :offset offset :limit 25}}
      {:db db})))
(defn reload [db _] (load-page db (get-in db [:imports :offset] 0)))
(defn change-page [db [_ direction]]
  (let [{:keys [status offset limit total]} (:imports db)]
    (if (and (= status :ready)
             (or (and (= direction :next) (< (+ offset limit) total))
                 (and (= direction :previous) (pos? offset))))
      (load-page db (if (= direction :next) (+ offset limit) (max 0 (- offset limit)))) {:db db})))
(defn page-loaded [db [_ id {:keys [data error]}]]
  (if (not= id (get-in db [:imports :request-id])) {:db db}
      (cond
        (#{:unauthenticated :forbidden} error) {:db (purge db error)}
        error {:db (update db :imports assoc :status :page-error :error error :rows [])}
        :else {:db (update db :imports #(-> % (assoc :status (if (= "failed" (get-in data [:batch :state])) :failed :ready)
                                                     :batch (:batch data) :rows (:rows data) :total (:total data)
                                                     :offset (:offset data) :limit (:limit data) :error nil :file nil)
                                            (dissoc :request-id :batch-id)))})))
(defn reset-screen [db _]
  (if (active? (:imports db)) {:db db} {:db (open db [])}))
(defn with-db [handler] (fn [{:keys [db]} event] (handler db event)))
(rf/reg-event-db :imports/open open)
(rf/reg-event-fx :imports/close (with-db close))
(rf/reg-event-db :imports/choose-file choose-file)
(rf/reg-event-db :imports/separator separator)
(rf/reg-event-fx :imports/start (with-db start))
(rf/reg-event-db :imports/progress progress)
(rf/reg-event-fx :imports/finished (with-db finished))
(rf/reg-event-fx :imports/reload (with-db reload))
(rf/reg-event-fx :imports/page (with-db change-page))
(rf/reg-event-fx :imports/page-loaded (with-db page-loaded))
(rf/reg-event-fx :imports/reset (with-db reset-screen))
