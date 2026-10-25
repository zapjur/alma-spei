(ns alma-spei.ui.subs
  (:require [re-frame.core :as rf]))

(defn health-status [db _query] (:health db))
(rf/reg-sub :health/status health-status)
