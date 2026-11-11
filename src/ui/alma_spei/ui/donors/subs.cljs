(ns alma-spei.ui.donors.subs
  (:require [re-frame.core :as rf]))

(defn state [db _] (:donors db))
(rf/reg-sub :donors/state state)
