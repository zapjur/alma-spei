(ns alma-spei.ui.core
  (:require [alma-spei.ui.effects]
            [alma-spei.ui.events]
            [alma-spei.ui.subs]
            [alma-spei.ui.views :as views]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdom]))

(defonce root (atom nil))
(defn mount! []
  (when-not @root (reset! root (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root [views/app]))
(defn init [] (rf/dispatch-sync [:app/initialize]) (mount!))
