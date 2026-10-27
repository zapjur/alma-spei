(ns alma-spei.ui.state-test
  (:require [alma-spei.ui.effects :as effects]
            [alma-spei.ui.events :as events]
            [alma-spei.ui.subs :as subs]
            [cljs.test :refer-macros [deftest is async]]))

(deftest state-transitions
  (is (= {:db {:health :loading} :health/request nil} (events/initialize {} [])))
  (is (= :ok (subs/health-status (events/health-loaded {} [:health/loaded :ok]) [])))
  (is (= :invalid (effects/response-status {:status "unexpected"}))))

(deftest successful-effect
  (async done
         (effects/request-health!
          (fn [_] (js/Promise.resolve #js {:ok true :json (fn [] (js/Promise.resolve #js {:status "ok"}))}))
          (fn [event] (is (= [:health/loaded :ok] event)) (done)))))

(deftest failed-effect
  (async done
         (effects/request-health!
          (fn [_] (js/Promise.reject (js/Error. "synthetic network failure")))
          (fn [event] (is (= [:health/loaded :unavailable] event)) (done)))))
