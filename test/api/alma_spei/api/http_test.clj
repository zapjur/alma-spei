(ns alma-spei.api.http-test
  (:require [alma-spei.api.db.core :as db]
            [alma-spei.api.http.middleware :as middleware]
            [alma-spei.api.http.routes :as routes]
            [clojure.test :refer [deftest is]]))

(deftest health-and-routing
  (let [handler (routes/handler nil 1)]
    (is (= 200 (:status (handler {:request-method :get :uri "/api/health/live"}))))
    (with-redefs [db/ready? (constantly false)]
      (is (= 503 (:status (handler {:request-method :get :uri "/api/health/ready"})))))
    (with-redefs [db/ready? (constantly true)]
      (is (= 200 (:status (handler {:request-method :get :uri "/api/health/ready"})))))
    (is (= 404 (:status (handler {:request-method :get :uri "/api/missing"}))))
    (is (= 200 (:status (handler {:request-method :get :uri "/"}))))))

(deftest safe-errors
  (let [response ((middleware/wrap-safe-errors
                   (fn [_] (throw (ex-info "sensitive payload" {:password "hidden"})))) {})]
    (is (= 500 (:status response)))
    (is (not (.contains (:body response) "hidden")))
    (is (not (.contains (:body response) "sensitive")))))
