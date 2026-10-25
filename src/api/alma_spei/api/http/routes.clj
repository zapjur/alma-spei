(ns alma-spei.api.http.routes
  (:require [alma-spei.api.http.health :as health]
            [alma-spei.api.http.middleware :as middleware]
            [alma-spei.common.contracts.health :as contract]
            [muuntaja.core :as muuntaja]
            [reitit.coercion.malli :as coercion]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring-coercion]
            [reitit.ring.middleware.muuntaja :as format]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :as response]))

(defn handler [ds timeout]
  (-> (ring/ring-handler
       (ring/router
        [["/api/health/live" {:get {:handler health/live
                                    :responses {200 {:body contract/response}}}}]
         ["/api/health/ready" {:get {:handler (health/readiness ds timeout)
                                     :responses {200 {:body contract/response}
                                                 503 {:body contract/response}}}}]]
        {:data {:muuntaja muuntaja/instance
                :coercion coercion/coercion
                :middleware [format/format-middleware
                             ring-coercion/coerce-response-middleware]}})
       (fn [request]
         (if (.startsWith ^String (:uri request) "/api/")
           {:status 404 :headers {"Content-Type" "application/json; charset=utf-8"}
            :body "{\"code\":\"not-found\",\"message\":\"Nie znaleziono zasobu.\"}"}
           (or (response/resource-response (if (= "/" (:uri request)) "index.html" (subs (:uri request) 1))
                                           {:root "public"})
               {:status 404 :body "Nie znaleziono strony."}))))
      middleware/wrap-safe-errors
      wrap-content-type
      middleware/wrap-security-headers))
