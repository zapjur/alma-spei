(ns alma-spei.common.contracts.health)

(def response
  [:map {:closed true}
   [:status [:enum "ok" "unavailable"]]])
