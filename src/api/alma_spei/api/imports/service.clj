(ns alma-spei.api.imports.service
  (:require [alma-spei.api.auth.service :as auth]
            [alma-spei.api.auth.crypto :as crypto]
            [muuntaja.core :as muuntaja]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.validation :as validation]
            [next.jdbc :as jdbc])
  (:import [java.util UUID]))

(defn fail! [status code message] (throw (ex-info message {:status status :code code :public-message message})))
(defn validate! [schema input]
  (let [errors (contract/errors schema input)]
    (when (seq errors)
      (throw (ex-info "Import validation failed" {:status 400 :code "validation" :public-message "Sprawdź dane importu." :errors errors})))))

(defn public-batch [row]
  (-> (select-keys row [:attempt-number :source-type :original-filename :byte-count :version :state
                        :failure-code :parse-version :mapping-version :total-rows :valid-rows :invalid-rows :reviewed-rows])
      (assoc :id (str (:id row)) :file-id (str (:file-id row)) :created-by (str (:created-by row))
             :retry-of (some-> (:retry-of row) str)
             :created-at (str (.toInstant ^java.sql.Timestamp (:created-at row)))
             :updated-at (str (.toInstant ^java.sql.Timestamp (:updated-at row))))))

(defn locked-batch! [tx id expected-version]
  (when-not (validation/valid? contract/identifier id) (fail! 404 "not-found" "Nie znaleziono importu."))
  (let [row (or (repository/batch-row tx (UUID/fromString id)) (fail! 404 "not-found" "Nie znaleziono importu."))]
    ;; Always lock the file first; retries and failures use the same lock order.
    (repository/lock-file! tx (:file-id row))
    (let [current (repository/lock-batch! tx (:id row))]
      (when-not (= expected-version (:version current))
        (fail! 409 "stale-import" "Dane importu zostały zmienione. Odśwież widok."))
      current)))

(defn reserve! [ds token metadata measured]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (validate! contract/upload-metadata metadata)
      (validate! contract/fingerprint measured)
      (when (zero? (:byte-count measured)) (fail! 400 "empty-file" "Plik jest pusty."))
      ;; The future HTTP upload handler must compute this descriptor on the server.
      (let [file (or (repository/create-file! tx measured)
                     (fail! 409 "duplicate-file" "Ten plik został już przesłany. Otwórz historię importów."))
            batch (repository/create-batch! tx (:id file) (:id actor) metadata 1 nil)]
        (repository/record-event! tx batch (:id actor) "reserved" nil nil)
        {:batch (public-batch batch)}))))

(defn fail-batch! [ds token id input]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (validate! contract/failure input)
      (let [batch (locked-batch! tx id (:version input))]
        (when-not (#{"reserved" "staging"} (:state batch))
          (fail! 409 "ineligible-import" "Nie można zakończyć tego importu w wybrany sposób."))
        (let [failed (repository/mark-failed! tx (:id batch) (:failure-code input))]
          (repository/record-event! tx failed (:id actor) "failed" (:state batch) (:failure-code input))
          {:batch (public-batch failed)})))))

(defn retry!
  ([ds token id input] (retry! ds token id input nil))
  ([ds token id input measured]
   (jdbc/with-transaction [tx ds]
     (let [actor (auth/authorize! tx token false false)]
       (validate! contract/retry-request input)
       (let [batch (locked-batch! tx id (:version input))
             latest (repository/latest tx (:file-id batch))]
         (when measured
           (validate! contract/fingerprint measured)
           (let [file (repository/file-row tx (:file-id batch))]
             (when-not (and (= (:file-hash-sha256 measured) (:fingerprint file))
                            (= (:byte-count measured) (:byte-count file)) (= "bank" (:source-type batch)))
               (fail! 409 "retry-file-mismatch" "Ponowienie wymaga tego samego pliku źródłowego."))))
        ;; Reimport of undone batches is enabled only with the later undo service.
         (when-not (and (= "failed" (:state batch)) (= (:id batch) (:id latest)))
           (fail! 409 "ineligible-retry" "Można ponowić tylko ostatni nieudany import tego pliku."))
         (repository/record-event! tx batch (:id actor) "retry-requested" (:state batch) nil)
         (let [next-batch (repository/create-batch! tx (:file-id batch) (:id actor) batch (inc (:attempt-number batch)) (:id batch))]
           (repository/record-event! tx next-batch (:id actor) "reserved" nil nil)
           {:batch (public-batch next-batch)}))))))

(defn authorize-upload! [ds token csrf]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)]
      (when-not (and csrf (crypto/equal? (:csrf-digest actor) (crypto/digest csrf)))
        (fail! 403 "csrf" "Sesja formularza wygasła. Odśwież stronę."))
      actor)))

(defn json [value] (slurp (muuntaja/encode "application/json" value)))
(defn decode-json [value] (muuntaja/decode "application/json" (.getBytes (.getValue ^org.postgresql.util.PGobject value) "UTF-8")))
(defn public-row [row]
  {:worksheet-index (:worksheet-index row) :row-number (:row-number row)
   :payload (decode-json (:normalized-payload row)) :validation-state (:validation-state row)
   :errors (mapv (fn [code] {:code code :message (get contract/row-messages code "Nieprawidłowe dane wiersza.")})
                 (decode-json (:validation-codes row)))})

(defn rows! [ds token id input]
  (jdbc/with-transaction [tx ds]
    (auth/authorize! tx token false false)
    (validate! contract/page-query input)
    (when-not (validation/valid? contract/identifier id) (fail! 404 "not-found" "Nie znaleziono importu."))
    (let [batch (or (repository/batch-row tx (UUID/fromString id)) (fail! 404 "not-found" "Nie znaleziono importu."))
          limit (get input :limit 25) offset (get input :offset 0)]
      {:batch (public-batch batch) :rows (mapv public-row (repository/staged-page tx (:id batch) limit offset))
       :total (or (:total-rows batch) 0) :limit limit :offset offset})))

(defn stage! [ds token batch result parse-version mapping-version]
  (jdbc/with-transaction [tx ds]
    (let [actor (auth/authorize! tx token false false)
          current (locked-batch! tx (:id batch) (:version batch))
          rows (:rows result)]
      (when-not (= "reserved" (:state current)) (fail! 409 "ineligible-import" "Nie można przetworzyć tego importu."))
      (doseq [row rows]
        (repository/stage-row! tx (:id current) row (json (:payload row)) (json (:validation-codes row))))
      (let [staged (repository/mark-staged! tx (:id current) (count rows) (count (filter #(= "valid" (:validation-state %)) rows))
                                            parse-version mapping-version)]
        (repository/record-event! tx staged (:id actor) "staged" "reserved" nil)
        {:batch (public-batch staged)}))))

(defn fail-processing! [ds batch reason]
  ;; Internal failure bookkeeping must survive session expiry during parsing.
  ;; The actor is the authenticated attempt creator, never a client-supplied ID.
  (jdbc/with-transaction [tx ds]
    (let [current (locked-batch! tx (:id batch) (:version batch))]
      (when (#{"reserved" "staging"} (:state current))
        (let [failed (repository/mark-failed! tx (:id current) reason)]
          (repository/record-event! tx failed (:created-by current) "failed" (:state current) reason)
          {:batch (public-batch failed)})))))

(defn recover-interrupted! [ds]
  ;; Startup-only, before this single application process accepts uploads.
  (jdbc/with-transaction [tx ds]
    (when (:present (repository/one tx ["SELECT to_regclass('public.import_batches') IS NOT NULL AS present"]))
      (doseq [batch (repository/query tx ["SELECT id,version FROM import_batches WHERE state IN ('reserved','staging') ORDER BY file_id,id"])]
        (let [current (locked-batch! tx (str (:id batch)) (:version batch))
              failed (repository/mark-failed! tx (:id current) "processing-interrupted")]
          (repository/record-event! tx failed (:created-by current) "failed" (:state current) "processing-interrupted"))))))
