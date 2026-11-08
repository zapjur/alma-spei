(ns alma-spei.ui.imports.review-effects
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.donors :as donors]
            [alma-spei.common.contracts.review :as review]
            [alma-spei.common.validation :as validation]
            [alma-spei.ui.imports.review-location :as location]
            [re-frame.core :as rf]))

(defonce pending (atom {}))
(defn abort! []
  (doseq [[_ controller] @pending] (.abort controller))
  (reset! pending {}))
(defn path [{:keys [kind batch-id worksheet row donor-id term status-filter limit offset]}]
  (case kind
    :queue (str "/api/imports/review?limit=" limit "&offset=" offset)
    :rows (str "/api/imports/" batch-id "/rows?limit=" limit "&offset=" offset)
    :search (str "/api/donors?search=" (js/encodeURIComponent term) "&status=" status-filter "&limit=" limit "&offset=" offset)
    :comparison (str "/api/imports/" batch-id "/rows/" worksheet "/" row "/comparison?donor-id=" donor-id)))
(defn valid? [data {:keys [kind batch-id worksheet row donor-id limit offset]}]
  (and (validation/valid? (case kind :queue review/queue-response :rows imports/rows-response
                                :search donors/donors-response :comparison review/comparison-response) data)
       (case kind
         :queue (and (= limit (:limit data)) (= offset (:offset data))
                     (= (count (:batches data)) (min limit (max 0 (- (:total data) offset))))
                     (every? #(and (= "bank" (:source-type %)) (= "review" (:state %))) (:batches data)))
         :rows (and (= batch-id (get-in data [:batch :id])) (= "bank" (get-in data [:batch :source-type]))
                    (= (:total data) (or (get-in data [:batch :total-rows]) 0))
                    (= limit (:limit data)) (= offset (:offset data))
                    (= (count (:rows data)) (min limit (max 0 (- (:total data) offset)))))
         :search (= (count (:donors data)) (min limit (max 0 (- (:total data) offset))))
         :comparison (and (= batch-id (get-in data [:batch :id])) (= "bank" (get-in data [:batch :source-type]))
                          (= "review" (get-in data [:batch :state]))
                          (= worksheet (get-in data [:row :worksheet-index])) (= row (get-in data [:row :row-number]))
                          (= donor-id (get-in data [:donor :id]))))))
(defn request! [fetch-fn dispatch {:keys [kind request-id] :as args}]
  (when-let [old (get @pending kind)] (.abort old))
  (let [controller (js/AbortController.)
        reply (fn [value]
                (when (identical? controller (get @pending kind))
                  (swap! pending dissoc kind)
                  (dispatch [:review/loaded kind request-id value])))]
    (swap! pending assoc kind controller)
    (-> (fetch-fn (path args) #js {:method "GET" :credentials "same-origin" :cache "no-store" :signal (.-signal controller)
                                   :headers #js {"Accept" "application/json"}})
        (.then (fn [response]
                 (if-not (.-ok response)
                   (reply {:error (case (.-status response) 401 :unauthenticated 403 :forbidden 404 :not-found 409 :closed :unavailable)})
                   (-> (.json response)
                       (.then (fn [body]
                                (let [data (js->clj body :keywordize-keys true)]
                                  (reply (if (valid? data args) {:data data} {:error :invalid-response})))))
                       (.catch (fn [_] (reply {:error :invalid-response})))))))
        (.catch (fn [_] (reply {:error :unavailable}))))))
(rf/reg-fx :review/request #(request! js/fetch rf/dispatch %))
(rf/reg-fx :review/abort (fn [_] (abort!)))
(rf/reg-fx :review/location location/write!)
