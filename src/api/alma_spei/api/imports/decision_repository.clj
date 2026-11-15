(ns alma-spei.api.imports.decision-repository
  (:require [alma-spei.api.imports.repository :refer [one query]]
            [alma-spei.api.imports.service :as imports]))

(defn proposal [tx batch id lock?]
  (one tx [(str "SELECT * FROM import_donor_proposals WHERE batch_id=? AND id=?" (when lock? " FOR UPDATE")) batch id]))
(defn proposals [tx batch limit offset]
  {:proposals (query tx ["SELECT * FROM import_donor_proposals WHERE batch_id=? ORDER BY created_at,id LIMIT ? OFFSET ?" batch limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM import_donor_proposals WHERE batch_id=?" batch]))})
(defn insert-proposal! [tx id batch actor profile]
  (one tx ["INSERT INTO import_donor_proposals(id,batch_id,created_by,updated_by,profile) VALUES (?,?,?,?,?::jsonb) RETURNING *"
           id batch actor actor (imports/json profile)]))
(defn update-proposal! [tx id batch actor profile]
  (one tx ["UPDATE import_donor_proposals SET profile=?::jsonb,version=version+1,updated_by=?,updated_at=now() WHERE batch_id=? AND id=? RETURNING *"
           (imports/json profile) actor batch id]))
(defn decision [tx batch worksheet row]
  (one tx ["SELECT r.*,u.display_name AS reviewer_name,coalesce(d.name,p.profile->>'name') AS target_label FROM import_review_decisions r JOIN users u ON u.id=r.reviewer_id LEFT JOIN donors d ON d.id=r.donor_id LEFT JOIN import_donor_proposals p ON p.id=r.proposal_id WHERE r.batch_id=? AND r.worksheet_index=? AND r.row_number=?" batch worksheet row]))
(defn all-decisions [tx batch]
  (query tx ["SELECT * FROM import_review_decisions WHERE batch_id=? ORDER BY worksheet_index,row_number" batch]))
(defn save-decision! [tx batch worksheet row actor input complete?]
  (one tx ["INSERT INTO import_review_decisions(batch_id,worksheet_index,row_number,version,kind,donor_id,proposal_id,target_version,financial,donation_id,donation_version,complete,reviewer_id,accepted_catalog_version) VALUES (?,?,?,1,?,?,?,?,?::jsonb,?,?,?,?,?) ON CONFLICT(batch_id,worksheet_index,row_number) DO UPDATE SET version=import_review_decisions.version+1,kind=EXCLUDED.kind,donor_id=EXCLUDED.donor_id,proposal_id=EXCLUDED.proposal_id,target_version=EXCLUDED.target_version,financial=EXCLUDED.financial,donation_id=EXCLUDED.donation_id,donation_version=EXCLUDED.donation_version,complete=EXCLUDED.complete,reviewer_id=EXCLUDED.reviewer_id,reviewed_at=now(),accepted_catalog_version=EXCLUDED.accepted_catalog_version RETURNING *"
           batch worksheet row (:kind input) (:donor-id input) (:proposal-id input) (:target-version input)
           (some-> (:financial input) imports/json) (:donation-id input) (:donation-version input) complete? actor (when complete? (:catalog-version input))]))
(defn advance-batch! [tx batch]
  (query tx ["UPDATE import_batches SET version=version+1,updated_at=now(),reviewed_rows=(SELECT count(*) FROM import_review_decisions d JOIN staged_rows r USING(batch_id,worksheet_index,row_number) WHERE d.batch_id=import_batches.id AND d.complete AND r.validation_state='valid') WHERE id=?" batch]))
(defn event! [tx event]
  (query tx ["INSERT INTO import_review_events(id,batch_id,actor_id,event_type,proposal_id,preview_id,worksheet_index,row_number,before_version,after_version,before_value,after_value) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)"
             (:id event) (:batch-id event) (:actor-id event) (:event-type event) (:proposal-id event) (:preview-id event)
             (:worksheet-index event) (:row-number event) (:before-version event) (:after-version event)
             (some-> (:before event) imports/json) (imports/json (:after event))]))
(defn payment
  ([tx id] (payment tx id true))
  ([tx id lock?]
   ;; The runtime cannot update donations. Future compensation must share this key.
   (when lock? (query tx ["SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))" (str "review-payment:" id)]))
   (one tx ["SELECT * FROM active_donations WHERE id=?" id])))
(defn payments [tx donor limit offset]
  {:payments (query tx ["SELECT * FROM active_donations WHERE donor_id=? ORDER BY received_on DESC,id LIMIT ? OFFSET ?" donor limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM active_donations WHERE donor_id=?" donor]))})
(defn source-rows [tx batch]
  (query tx ["SELECT * FROM staged_rows WHERE batch_id=? ORDER BY worksheet_index,row_number" batch]))
(defn events [tx batch limit offset]
  {:events (query tx ["SELECT e.*,u.display_name AS actor_name FROM import_review_events e JOIN users u ON u.id=e.actor_id WHERE batch_id=? ORDER BY occurred_at,id LIMIT ? OFFSET ?" batch limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM import_review_events WHERE batch_id=?" batch]))})
(defn insert-preview! [tx input]
  (one tx ["INSERT INTO import_previews(id,batch_id,batch_version,created_by,rule_version,effect_digest,summary,item_count,catalog_version) VALUES (?,?,?,?,'review-preview-v2',?,?::jsonb,?,?) RETURNING *"
           (:id input) (:batch-id input) (:batch-version input) (:created-by input) (:effect-digest input)
           (imports/json (:summary input)) (:item-count input) (:catalog-version input)]))
(defn insert-item! [tx preview item]
  (query tx ["INSERT INTO import_preview_items(preview_id,ordinal,kind,target_id,worksheet_index,row_number,payload) VALUES (?,?,?,?,?,?,?::jsonb)"
             preview (:ordinal item) (:kind item) (some-> (:target-id item) java.util.UUID/fromString)
             (:worksheet-index item) (:row-number item) (imports/json (:payload item))]))
(defn preview [tx batch id]
  (one tx ["SELECT * FROM import_previews WHERE batch_id=? AND id=?" batch id]))
(defn items [tx preview limit offset]
  (query tx ["SELECT * FROM import_preview_items WHERE preview_id=? ORDER BY ordinal LIMIT ? OFFSET ?" preview limit offset]))
