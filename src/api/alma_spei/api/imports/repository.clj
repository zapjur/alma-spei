(ns alma-spei.api.imports.repository
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.util UUID]))

(defn query [tx statement] (jdbc/execute! tx statement {:builder-fn rs/as-unqualified-kebab-maps}))
(defn one [tx statement] (first (query tx statement)))

(defn create-file! [tx fingerprint]
  (one tx ["INSERT INTO import_files(id,file_hash_sha256,byte_count) VALUES (?,?,?) ON CONFLICT (file_hash_sha256) DO NOTHING RETURNING *"
           (UUID/randomUUID) (:file-hash-sha256 fingerprint) (:byte-count fingerprint)]))
(defn batch-row [tx id]
  (one tx ["SELECT b.*,f.byte_count FROM import_batches b JOIN import_files f ON f.id=b.file_id WHERE b.id=?" id]))
(defn lock-file! [tx id] (one tx ["SELECT id FROM import_files WHERE id=? FOR UPDATE" id]))
(defn lock-batch! [tx id]
  (one tx ["SELECT b.*,f.byte_count FROM import_batches b JOIN import_files f ON f.id=b.file_id WHERE b.id=? FOR UPDATE OF b" id]))
(defn latest [tx file-id]
  (one tx ["SELECT * FROM import_batches WHERE file_id=? ORDER BY attempt_number DESC LIMIT 1" file-id]))

(defn create-batch! [tx file-id actor metadata attempt retry-of]
  (let [id (UUID/randomUUID)]
    (query tx ["INSERT INTO import_batches(id,file_id,created_by,source_type,original_filename,attempt_number,retry_of) VALUES (?,?,?,?,?,?,?)"
               id file-id actor (:source-type metadata) (:original-filename metadata) attempt retry-of])
    (query tx ["UPDATE import_files SET last_attempt_at=now() WHERE id=?" file-id])
    (batch-row tx id)))

(defn record-event! [tx batch actor event previous reason]
  (query tx ["INSERT INTO import_batch_events(id,batch_id,actor_id,batch_version,event_type,previous_state,new_state,reason_code) VALUES (?,?,?,?,?,?,?,?)"
             (UUID/randomUUID) (:id batch) actor (:version batch) event previous (:state batch) reason]))

(defn mark-failed! [tx id reason]
  (query tx ["UPDATE import_batches SET state='failed',failure_code=?,version=version+1,updated_at=now() WHERE id=?" reason id])
  (batch-row tx id))

(defn file-row [tx id]
  (one tx ["SELECT *,file_hash_sha256 AS fingerprint FROM import_files WHERE id=?" id]))

(defn stage-row! [tx id row payload codes]
  (query tx ["INSERT INTO staged_rows(batch_id,worksheet_index,row_number,normalized_payload,validation_state,validation_codes,rule_version) VALUES (?,?,?,?::jsonb,?,?::jsonb,'bank-four-columns-v1')"
             id (:worksheet-index row) (:row-number row) payload (:validation-state row) codes]))

(defn mark-staged! [tx id total valid parse-version mapping-version]
  (query tx ["UPDATE import_batches SET state='review',version=version+1,updated_at=now(),parse_version=?,mapping_version=?,total_rows=?,valid_rows=?,invalid_rows=?,reviewed_rows=0 WHERE id=?"
             parse-version mapping-version total valid (- total valid) id])
  (batch-row tx id))

(defn staged-page [tx id limit offset]
  (query tx ["SELECT worksheet_index,row_number,normalized_payload,validation_state,validation_codes FROM staged_rows WHERE batch_id=? ORDER BY worksheet_index,row_number LIMIT ? OFFSET ?"
             id limit offset]))
