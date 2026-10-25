(ns alma-spei.api.http.health
  (:require [alma-spei.api.db.core :as db]))

(defn live [_request]
  {:status 200 :body {:status "ok"}})

(defn readiness [ds timeout]
  (fn [_request]
    (if (db/ready? ds timeout)
      {:status 200 :body {:status "ok"}}
      {:status 503 :body {:status "unavailable"}})))
