(ns alma-spei.ui.donors.effects
  (:require [alma-spei.common.contracts.donors :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]
            [re-frame.core :as rf]))

(defn request-url [query]
  (str "/api/donors?"
       (str/join "&" (for [field [:search :entity-type :status :sort-by :sort-direction :limit :offset]
                           :let [value (get query field)]
                           :when (and (some? value) (not= "" value))]
                       (str (name field) "=" (js/encodeURIComponent (str value)))))))

(defn request! [fetch-fn dispatch {:keys [request-id query]}]
  (let [reply #(dispatch [:donors/loaded request-id %])]
    (-> (fetch-fn (request-url query)
                  #js {:method "GET" :credentials "same-origin" :cache "no-store"
                       :headers #js {"Accept" "application/json"}})
        (.then (fn [response]
                 (cond
                   (= 401 (.-status response)) (reply {:error :unauthenticated})
                   (= 403 (.-status response)) (reply {:error :forbidden})
                   (not (.-ok response)) (reply {:error :unavailable})
                   :else (-> (.json response)
                             (.then (fn [body]
                                      (let [data (js->clj body :keywordize-keys true)]
                                        (reply (if (validation/valid? contract/donors-response data)
                                                 {:data data} {:error :invalid-response})))))
                             (.catch (fn [_] (reply {:error :invalid-response})))))))
        (.catch (fn [_] (reply {:error :unavailable}))))))

(rf/reg-fx :donors/request #(request! js/fetch rf/dispatch %))
