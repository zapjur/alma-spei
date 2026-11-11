(ns alma-spei.ui.donors.detail-effects
  (:require [alma-spei.common.contracts.donors :as contract]
            [alma-spei.common.validation :as validation]
            [re-frame.core :as rf]))

(defn failure [status mutation?]
  (case status 401 :unauthenticated 403 :forbidden 404 :not-found 409 :conflict 413 :too-large
        (if mutation? :uncertain :unavailable)))

(def allowed-fields (into {} (map (fn [field] [(name field) field]) (keys contract/field-messages))))

(defn safe-field-errors [body]
  (into [] (keep (fn [error]
                   (let [path (:field error)
                         field (get allowed-fields (first path))]
                     (when (and (vector? path) (= 1 (count path)) field)
                       {:field [field] :message (get contract/field-messages field)})))) (:errors body)))

(defn request! [fetch-fn dispatch {:keys [id kind request-id offset limit input csrf] :as request}]
  (let [mutation? (= kind :save)
        schema (case kind :save contract/donor-response :history contract/history-response contract/detail-response)
        reply #(dispatch (if mutation? [:donors/card-saved request-id %] [:donors/card-loaded kind request-id %]))
        path (str "/api/donors/" (js/encodeURIComponent id)
                  (when (= kind :history) "/history")
                  (when-not mutation? (str "?limit=" limit "&offset=" offset)))
        options (cond-> {:method (if mutation? "PUT" "GET") :credentials "same-origin" :cache "no-store"
                         :headers {"Accept" "application/json"}}
                  mutation? (assoc :body (js/JSON.stringify (clj->js input))
                                   :headers {"Accept" "application/json" "Content-Type" "application/json" "X-CSRF-Token" csrf}))]
    (-> (fetch-fn path (clj->js options))
        (.then (fn [response]
                 (if-not (.-ok response)
                   (if (and mutation? (= 400 (.-status response)))
                     (-> (.json response)
                         (.then #(reply {:error :validation :errors (safe-field-errors (js->clj % :keywordize-keys true))}))
                         (.catch (fn [_] (reply {:error :validation}))))
                     (reply {:error (failure (.-status response) mutation?)}))
                   (-> (.json response)
                       (.then (fn [body]
                                (let [data (js->clj body :keywordize-keys true)]
                                  (reply (if (and (validation/valid? schema data)
                                                  (or (= kind :history) (= id (get-in data [:donor :id]))))
                                           {:data data} {:error (if mutation? :uncertain :invalid-response)})))))
                       (.catch (fn [_] (reply {:error (if mutation? :uncertain :invalid-response)})))))))
        (.catch (fn [_] (reply {:error (if (= :save (:kind request)) :uncertain :unavailable)}))))))

(rf/reg-fx :donors/card-request #(request! js/fetch rf/dispatch %))
(rf/reg-fx :donors/card-save #(request! js/fetch rf/dispatch (assoc % :kind :save)))
