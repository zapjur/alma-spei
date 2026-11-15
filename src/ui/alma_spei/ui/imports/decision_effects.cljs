(ns alma-spei.ui.imports.decision-effects
  (:require [alma-spei.common.contracts.decisions :as contract]
            [alma-spei.common.validation :as validation]
            [re-frame.core :as rf]))

(defonce pending (atom {}))
(defn abort! [] (doseq [[_ controller] @pending] (.abort controller)) (reset! pending {}))
(def mutations #{:decision-save :proposal-create :proposal-update :preview-create})
(defn path [{:keys [op batch-id worksheet row proposal-id donor-id preview-id limit offset]}]
  (let [base (str "/api/imports/" batch-id)
        page (str "?limit=" limit "&offset=" offset)]
    (str base (case op
                (:decision-load :decision-save) (str "/rows/" worksheet "/" row "/decision")
                :proposal-create "/donor-proposals" :proposal-update (str "/donor-proposals/" proposal-id)
                :proposals (str "/donor-proposals" page)
                :payments (str "/payments" page "&donor-id=" donor-id)
                :events (str "/review-events" page) :blockers (str "/review-blockers" page)
                :preview-create "/preview" :preview-load (str "/previews/" preview-id)
                :items (str "/previews/" preview-id "/items" page)))))
(defn valid? [data {:keys [op batch-id worksheet row proposal-id donor-id preview-id limit offset]}]
  (and (validation/valid? (case op (:decision-load :decision-save) contract/decision-response
                                (:proposal-create :proposal-update) contract/proposal-response :proposals contract/proposals-response
                                :payments contract/payments-response :events contract/events-response :blockers contract/blockers-response
                                (:preview-create :preview-load) contract/preview-response :items contract/items-response) data)
       (= batch-id (if (= op :items) (get-in data [:preview :batch-id]) (get-in data [:batch :id])))
       (case op
         (:decision-load :decision-save) (and (= worksheet (get-in data [:row :worksheet-index])) (= row (get-in data [:row :row-number])))
         :proposal-create (= batch-id (get-in data [:proposal :batch-id]))
         :proposal-update (and (= proposal-id (get-in data [:proposal :id])) (= batch-id (get-in data [:proposal :batch-id])))
         :payments (and (= donor-id (:donor-id data)) (= limit (:limit data)) (= offset (:offset data)) (= (count (:payments data)) (min limit (max 0 (- (:total data) offset)))))
         (:proposals :events :blockers :items) (and (= limit (:limit data)) (= offset (:offset data))
                                                    (or (not= op :proposals) (every? #(= batch-id (:batch-id %)) (:proposals data)))
                                                    (or (not= op :items) (= preview-id (get-in data [:preview :id])))
                                                    (= (count (get data op)) (min limit (max 0 (- (:total data) offset)))))
         :preview-create (= batch-id (get-in data [:preview :batch-id]))
         :preview-load (and (= preview-id (get-in data [:preview :id])) (= batch-id (get-in data [:preview :batch-id]))) true)))
(defn request! [fetch-fn dispatch {:keys [op request-id slot csrf input] :as args}]
  (when-let [old (get @pending slot)] (.abort old))
  (let [controller (js/AbortController.) mutation? (contains? mutations op)
        reply (fn [value] (when (identical? controller (get @pending slot))
                            (swap! pending dissoc slot) (dispatch [:decision/loaded slot request-id op value])))
        options (cond-> {:method (case op (:proposal-create :preview-create) "POST" (:decision-save :proposal-update) "PUT" "GET")
                         :credentials "same-origin" :cache "no-store" :signal (.-signal controller) :headers {"Accept" "application/json"}}
                  mutation? (assoc :body (js/JSON.stringify (clj->js input))
                                   :headers {"Accept" "application/json" "Content-Type" "application/json" "X-CSRF-Token" csrf}))]
    (swap! pending assoc slot controller)
    (-> (fetch-fn (path args) (clj->js options))
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (.json response)
                       (.then (fn [body] (let [data (js->clj body :keywordize-keys true)]
                                           (reply (if (valid? data args) {:data data} {:error (if mutation? :uncertain :invalid-response)})))))
                       (.catch (fn [_] (reply {:error (if mutation? :uncertain :invalid-response)}))))
                   (case (.-status response)
                     401 (reply {:error :unauthenticated}) 403 (reply {:error :forbidden}) 400 (reply {:error :validation})
                     404 (reply {:error :not-found})
                     409 (-> (.json response) (.then (fn [body] (reply {:error (if (and (= op :preview-create) (= "review-blocked" (aget body "code"))) :blocked :conflict)})))
                             (.catch (fn [_] (reply {:error :conflict}))))
                     (reply {:error (if mutation? :uncertain :unavailable)})))))
        (.catch (fn [_] (reply {:error (if mutation? :uncertain :unavailable)}))))))
(rf/reg-fx :decision/request #(request! js/fetch rf/dispatch %))
(rf/reg-fx :decision/abort (fn [_] (abort!)))
