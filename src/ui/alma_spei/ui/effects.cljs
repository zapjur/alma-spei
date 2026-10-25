(ns alma-spei.ui.effects
  (:require [alma-spei.common.contracts.health :as health]
            [alma-spei.common.validation :as validation]
            [re-frame.core :as rf]))

(defn response-status [value]
  (if (validation/valid? health/response value)
    (if (= "ok" (:status value)) :ok :unavailable)
    :invalid))

(defn request-health! [fetch-fn dispatch]
  (-> (fetch-fn "/api/health/ready")
      (.then (fn [response]
               (if (or (.-ok response) (= 503 (.-status response)))
                 (.json response)
                 (throw (js/Error. "Health request failed")))))
      (.then (fn [body]
               (dispatch [:health/loaded (response-status (js->clj body :keywordize-keys true))])))
      (.catch (fn [_error] (dispatch [:health/loaded :unavailable])))))

(rf/reg-fx :health/request (fn [_] (request-health! js/fetch rf/dispatch)))
