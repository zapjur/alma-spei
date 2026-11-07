(ns alma-spei.api.http.middleware)

(defn wrap-safe-errors [handler]
  (fn [request]
    (try (handler request)
         (catch Exception e
           (if (and (= :muuntaja/decode (:type (ex-data e))) (:request (ex-data e)))
             {:status 400 :headers {"Content-Type" "application/json; charset=utf-8"}
              :body "{\"code\":\"malformed-body\",\"message\":\"Nieprawidłowy format danych formularza.\"}"}
             {:status 500 :headers {"Content-Type" "application/json; charset=utf-8"}
              :body "{\"code\":\"internal-error\",\"message\":\"Wystąpił błąd serwera.\"}"})))))

(defn wrap-security-headers [handler]
  (fn [request]
    (update (handler request) :headers merge
            {"X-Content-Type-Options" "nosniff"
             "Referrer-Policy" "no-referrer"
             "Cache-Control" "no-store"})))
