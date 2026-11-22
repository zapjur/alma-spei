(ns alma-spei.api.imports.execution-repository
  (:require [alma-spei.api.imports.repository :refer [one query]]
            [alma-spei.api.imports.service :as imports]))

(defn commit-row [tx batch]
  (one tx ["SELECT c.*,u.display_name AS actor_name FROM import_commits c JOIN users u ON u.id=c.actor_id WHERE batch_id=?" batch]))
(defn undo-row [tx commit]
  (one tx ["SELECT x.*,u.display_name AS actor_name FROM import_undos x JOIN users u ON u.id=x.actor_id WHERE commit_id=?" commit]))
(defn head [tx] (:id (one tx ["SELECT id FROM import_commits ORDER BY ordinal DESC LIMIT 1"])))
(defn backup [tx id] (one tx ["SELECT * FROM import_backup_runs WHERE id=?" id]))
(defn insert-backup! [tx receipt]
  (one tx ["INSERT INTO import_backup_runs(id,batch_id,preview_id,effect_digest,catalog_version,batch_version,requested_by,requested_at,adapter,policy_version,synthetic,artifact_ref,completed_at,expires_at) VALUES (?,?,?,?,?,?,?,?,'synthetic-test-v1','synthetic-test-v1',true,?,?,?) RETURNING *"
           (:id receipt) (:batch-id receipt) (:preview-id receipt) (:effect-digest receipt) (:catalog-version receipt) (:batch-version receipt)
           (:actor-id receipt) (:requested-at receipt) (:artifact-ref receipt) (:completed-at receipt) (:expires-at receipt)]))
(defn transition! [tx batch state]
  (one tx ["UPDATE import_batches SET state=?,version=version+1,updated_at=now() WHERE id=? RETURNING *" state batch]))
(defn insert-commit! [tx row]
  (one tx ["INSERT INTO import_commits(id,batch_id,preview_id,effect_digest,catalog_version,backup_id,actor_id,request_context,batch_version,summary) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) RETURNING *"
           (:id row) (:batch-id row) (:preview-id row) (:effect-digest row) (:catalog-version row) (:backup-id row) (:actor-id row)
           (imports/json (:request row)) (:batch-version row) (imports/json (:summary row))]))
(defn insert-item! [tx row]
  (query tx ["INSERT INTO import_commit_items(batch_id,commit_id,preview_id,ordinal,kind,donor_id,donation_id,proposal_id,worksheet_index,row_number,donor_version,donation_version,created_donor_batch_id,created_payment_batch_id,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)"
             (:batch-id row) (:commit-id row) (:preview-id row) (:ordinal row) (:kind row) (:donor-id row) (:donation-id row) (:proposal-id row)
             (:worksheet-index row) (:row-number row) (:donor-version row) (:donation-version row) (when (= "new-donor" (:kind row)) (:batch-id row)) (when (= "new-payment" (:kind row)) (:batch-id row)) (imports/json (:snapshot row))]))
(defn all-items [tx commit]
  (query tx ["SELECT * FROM import_commit_items WHERE commit_id=? ORDER BY ordinal" commit]))
(defn insert-undo! [tx row]
  (one tx ["INSERT INTO import_undos(id,batch_id,commit_id,actor_id,request_context,batch_version,reversed_payments,archived_donors) VALUES (?,?,?,?,?::jsonb,?,?,?) RETURNING *"
           (:id row) (:batch-id row) (:commit-id row) (:actor-id row) (imports/json (:request row)) (:batch-version row) (:reversed-payments row) (:archived-donors row)]))
(defn reverse! [tx batch undo actor donation]
  (query tx ["INSERT INTO donation_reversals(donation_id,import_batch_id,undo_id,actor_id,reason) VALUES (?,?,?,?,'import-undo')" donation batch undo actor]))
(defn unknown-dependencies? [tx]
  (:present (one tx ["SELECT EXISTS(SELECT 1 FROM pg_constraint WHERE contype='f' AND confrelid IN ('donors'::regclass,'donations'::regclass) AND conrelid NOT IN ('donations'::regclass,'donor_audit_events'::regclass,'import_review_decisions'::regclass,'import_commit_items'::regclass,'donation_reversals'::regclass) AND conrelid <> 'tax_identification_evidence'::regclass) AS present"])))
(defn payment-dependencies? [tx batch payment]
  (or (unknown-dependencies? tx)
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM import_commit_items WHERE donation_id=? AND batch_id<>?) AS present" payment batch]))
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM import_review_decisions d JOIN import_batches b ON b.id=d.batch_id WHERE d.batch_id<>? AND b.state IN ('review','approved') AND d.donation_id=?) AS present" batch payment]))))
(defn dependencies? [tx batch donor payments]
  (or (unknown-dependencies? tx)
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM tax_identification_evidence WHERE donor_id=?) AS present" donor]))
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM active_donations WHERE donor_id=? AND import_batch_id IS DISTINCT FROM ?) AS present" donor batch]))
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM import_commit_items WHERE donor_id=? AND batch_id<>?) AS present" donor batch]))
      (:present (one tx ["SELECT EXISTS(SELECT 1 FROM import_review_decisions d JOIN import_batches b ON b.id=d.batch_id WHERE d.batch_id<>? AND b.state IN ('review','approved') AND d.donor_id=?) AS present" batch donor]))
      (some (fn [id] (:present (one tx ["SELECT EXISTS(SELECT 1 FROM import_review_decisions d JOIN import_batches b ON b.id=d.batch_id WHERE d.batch_id<>? AND b.state IN ('review','approved') AND d.donation_id=?) AS present" batch id]))) payments)))
(defn history [tx limit offset]
  {:batches (query tx ["SELECT b.*,f.byte_count,u.display_name AS uploaded_by_name FROM import_batches b JOIN import_files f ON f.id=b.file_id JOIN users u ON u.id=b.created_by ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?" limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM import_batches"]))})
(defn item-page [tx batch limit offset]
  {:items (query tx ["SELECT i.*,EXISTS(SELECT 1 FROM donation_reversals r WHERE r.donation_id=i.donation_id) AS reversed FROM import_commit_items i WHERE batch_id=? ORDER BY ordinal LIMIT ? OFFSET ?" batch limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM import_commit_items WHERE batch_id=?" batch]))})

(defn uploader-name [tx batch]
  (:display-name (one tx ["SELECT u.display_name FROM users u JOIN import_batches b ON b.created_by=u.id WHERE b.id=?" batch])))
