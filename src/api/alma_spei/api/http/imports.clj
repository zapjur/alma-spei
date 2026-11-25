(ns alma-spei.api.http.imports
  (:require [alma-spei.api.auth.crypto :as crypto]
            [alma-spei.api.fs.private-files :as private]
            [alma-spei.api.http.auth :as auth-http]
            [alma-spei.api.imports.adapters.bank :as bank]
            [alma-spei.api.imports.adapters.master :as master]
            [alma-spei.api.imports.adapters.tax :as tax]
            [alma-spei.api.imports.fingerprint :as fingerprint]
            [alma-spei.api.imports.limits :as limits]
            [alma-spei.api.imports.service :as service]
            [alma-spei.api.imports.review :as review]
            [alma-spei.common.contracts.review :as review-contract]
            [alma-spei.common.contracts.imports :as contract]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.params :refer [wrap-params]])
  (:import [java.nio.file Files]
           [java.util.concurrent Semaphore]))

(defonce parser-permit (Semaphore. 1))

(defn parse-options [params]
  (when-not (and (every? #{"file" "source" "separator" "retry-batch-id" "version" "confirmed"} (keys params))
                 (contains? #{"bank" "master" "tax"} (get params "source"))
                 (every? #(and (string? %) (<= (count %) 255)) (vals (dissoc params "file"))))
    (limits/reject! "invalid-structure"))
  (let [retry-id (get params "retry-batch-id") version (get params "version") confirmed (get params "confirmed")]
    (when (and (not retry-id) (or version confirmed)) (limits/reject! "invalid-structure"))
    (cond-> {:source (get params "source") :separator (get params "separator")}
      retry-id (assoc :retry-id retry-id :retry {:version (when (and version (re-matches #"[1-9][0-9]{0,14}" version))
                                                            (Long/parseLong version))
                                                 :confirmed (= "true" confirmed)}))))

(defn process-file! [ds token file filename {:keys [source separator retry-id retry]}]
  (let [metadata {:source-type source :original-filename filename}
        [parse! parse-version mapping-version]
        (case source
          "bank" [bank/parse! bank/parse-version bank/mapping-version]
          "master" [master/parse! master/parse-version master/mapping-version]
          "tax" [tax/parse! tax/parse-version tax/mapping-version])
        extension (some-> filename (str/split #"\.") last str/lower-case)]
    (service/validate! contract/upload-metadata metadata)
    (when-not (#{"csv" "xlsx"} extension) (limits/reject! 415 "unsupported-format"))
    (when (and (= extension "csv") (not (#{"," ";"} separator))) (limits/reject! "invalid-structure"))
    (let [measured (with-open [input (io/input-stream file)] (fingerprint/measure input limits/file-bytes))
          batch (:batch (if retry-id
                          (service/retry! ds token retry-id retry measured source)
                          (service/reserve! ds token metadata measured)))]
      (try
        (let [result (parse! file extension separator)]
          (service/stage! ds token batch result parse-version mapping-version)
          ;; Read after commit; a read failure must not relabel a successfully staged batch.
          (assoc (service/rows! ds token (:id batch) {}) :skipped-empty-rows (:skipped-empty-rows result)))
        (catch Exception e
          (let [failed (try (service/fail-processing! ds batch (or (:failure-code (ex-data e)) "parse-failed"))
                            (catch Exception _ nil))]
            (throw (ex-info "Import processing failed"
                            (merge {:status 500 :code "internal-error" :public-message "Wystąpił błąd serwera."}
                                   (select-keys (ex-data e) [:status :code :public-message :errors])
                                   {:batch-id (:id batch)} failed)))))))))

(defn private-storage! [operation]
  (try (operation)
       (catch Exception _ (service/fail! 500 "private-storage-unavailable" "Nie można bezpiecznie utworzyć pliku tymczasowego. Sprawdź uprawnienia i obsługę systemu plików."))))
(defn create-upload-directory [] (private-storage! #(private/create-temp-directory! "alma-spei-upload-")))

(defn upload! [ds token request]
  (when-not (.tryAcquire parser-permit) (service/fail! 503 "import-busy" "Trwa przetwarzanie innego importu. Spróbuj ponownie."))
  (try
    (let [directory (create-upload-directory)
          file (.resolve directory "input")]
      (try
        (let [files (volatile! 0)
              body (limits/bounded-stream (:body request) limits/body-bytes)
              params (try
                       (multipart/parse-multipart-params
                        (assoc request :body body)
                        {:max-file-size limits/file-bytes :max-file-count 6 :encoding "UTF-8"
                         :store (fn [{:keys [filename stream]}]
                                  (when (> (vswap! files inc) 1) (limits/reject! "invalid-structure"))
                                  (private-storage! #(private/create-file! file))
                                  (with-open [out (io/output-stream (.toFile file))]
                                    (io/copy (limits/bounded-stream stream limits/file-bytes) out))
                                  {:filename filename})})
                       (catch clojure.lang.ExceptionInfo e (throw e))
                       (catch org.apache.commons.fileupload2.core.FileUploadSizeException _ (limits/reject! 413 "resource-limit"))
                       (catch Exception _ (limits/reject! "invalid-structure")))
              options (parse-options params)]
          ;; Include multipart epilogue bytes in the whole-request budget.
          (io/copy body (java.io.OutputStream/nullOutputStream))
          (when-not (and (= @files 1) (map? (get params "file"))
                         (<= (count (dissoc params "file")) 5)) (limits/reject! "invalid-structure"))
          {:status 201 :body (process-file! ds token (.toFile file) (get-in params ["file" :filename]) options)})
        (finally
          (Files/deleteIfExists file)
          (Files/deleteIfExists directory))))
    (finally (.release parser-permit))))

(defn page-input [params]
  (into {} (map (fn [[key value]]
                  [(get {"limit" :limit "offset" :offset} key :unknown)
                   (if (and (string? value) (re-matches #"[0-9]{1,15}" value))
                     (Long/parseLong value) value)]) params)))

(defn endpoint [ds settings upload?]
  (fn [request]
    (try
      (let [token (get-in request [:cookies "alma-session" :value])]
        (if upload?
          (let [csrf (get-in request [:cookies "alma-csrf" :value])]
            (when-not (and (= (:origin settings) (get-in request [:headers "origin"]))
                           (auth-http/valid-csrf? (:csrf-secret settings) csrf)
                           (crypto/equal? csrf (get-in request [:headers "x-csrf-token"])))
              (service/fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
            (service/authorize-upload! ds token csrf)
            (when-not (and (:body request) (str/starts-with? (get-in request [:headers "content-type"] "") "multipart/form-data;"))
              (limits/reject! 415 "unsupported-format"))
            (upload! ds token request))
          {:status 200 :body (service/rows! ds token (get-in request [:path-params :id])
                                            (page-input (:query-params request)))}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [status code public-message errors batch-id batch]} (ex-data e)]
          (if status {:status status :body (cond-> {:code code :message public-message} errors (assoc :errors errors) batch-id (assoc :batch-id batch-id) batch (assoc :batch batch))}
              (throw e)))))))

(defn positive-path [value]
  (when (and (string? value) (re-matches #"[1-9][0-9]{0,14}" value)) (Long/parseLong value)))
(defn review-endpoint [ds action]
  (wrap-params
   (fn [request]
     (try
       (let [params (:query-params request)
             input (if (= action :comparison)
                     (into {} (map (fn [[key value]] [(if (= key "donor-id") :donor-id :unknown) value]) params))
                     (page-input params))
             path (:path-params request)]
         {:status 200 :body (review/execute! ds (get-in request [:cookies "alma-session" :value]) action (:id path)
                                             {:worksheet (positive-path (:worksheet path)) :row (positive-path (:row path))} input)})
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [status code public-message errors]} (ex-data e)]
           (if status {:status status :body (cond-> {:code code :message public-message} errors (assoc :errors errors))}
               (throw e))))))))

(defn routes [ds settings]
  ;; Explicit literal-before-UUID ordering resolves these intentional overlaps.
  ;; Other router conflict checks remain enabled.
  [["/api/imports/review" {:conflicting true :get {:handler (review-endpoint ds :queue) :responses {200 {:body review-contract/queue-response}}}}]
   ["/api/imports/upload" {:conflicting true :post {:handler (endpoint ds settings true) :responses {201 {:body contract/upload-response}}}}]
   ["/api/imports/:id" {:conflicting true :get {:handler (review-endpoint ds :batch) :responses {200 {:body contract/batch-response}}}}]
   ["/api/imports/:id/rows/:worksheet/:row/comparison" {:get {:handler (review-endpoint ds :comparison) :responses {200 {:body review-contract/comparison-response}}}}]
   ["/api/imports/:id/rows" {:get {:handler (wrap-params (endpoint ds settings false)) :responses {200 {:body contract/rows-response}}}}]])
