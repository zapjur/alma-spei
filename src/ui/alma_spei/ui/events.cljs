(ns alma-spei.ui.events
  (:require [re-frame.core :as rf]))

(defn initialize [_db _event]
  {:db {:health :loading} :health/request nil})
(defn health-loaded [db [_ status]] (assoc db :health status))
(rf/reg-event-fx :app/initialize initialize)
(rf/reg-event-db :health/loaded health-loaded)
