CREATE TABLE canonical_catalog_state (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
--;;
INSERT INTO canonical_catalog_state(singleton) VALUES(true);
--;;
GRANT SELECT,UPDATE(revision) ON canonical_catalog_state TO alma_spei_app;
--;;
CREATE FUNCTION advance_canonical_catalog() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE canonical_catalog_state SET revision=revision+1 WHERE singleton;
 RETURN NULL;
END $$;
--;;
CREATE TRIGGER donors_catalog_revision AFTER INSERT OR UPDATE OR DELETE ON donors FOR EACH ROW EXECUTE FUNCTION advance_canonical_catalog();
--;;
CREATE TRIGGER donations_catalog_revision AFTER INSERT OR UPDATE OR DELETE ON donations FOR EACH ROW EXECUTE FUNCTION advance_canonical_catalog();
--;;
ALTER TABLE donors ADD COLUMN import_batch_id uuid REFERENCES import_batches(id) ON DELETE RESTRICT, ADD UNIQUE(import_batch_id,id);
--;;
ALTER TABLE donations ADD COLUMN import_batch_id uuid REFERENCES import_batches(id) ON DELETE RESTRICT, ADD UNIQUE(import_batch_id,id);
--;;
REVOKE UPDATE ON donors FROM alma_spei_app;
--;;
GRANT UPDATE(entity_type,name,first_name,street,postal_code,city,province,is_active,version,updated_at,updated_by) ON donors TO alma_spei_app;
--;;
ALTER TABLE import_review_decisions ADD COLUMN accepted_catalog_version bigint CHECK(accepted_catalog_version>0);
--;;
GRANT UPDATE(accepted_catalog_version) ON import_review_decisions TO alma_spei_app;
--;;
ALTER TABLE import_previews DROP CONSTRAINT import_previews_rule_version_check,
 ADD CONSTRAINT import_previews_rule_version_check CHECK(rule_version IN ('review-preview-v1','review-preview-v2')),
 ADD COLUMN catalog_version bigint CHECK(catalog_version>0),
 ADD UNIQUE(batch_id,id,effect_digest,catalog_version),
 ADD CHECK(rule_version<>'review-preview-v2' OR catalog_version IS NOT NULL);
--;;
CREATE TABLE import_backup_runs (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL, preview_id uuid NOT NULL, effect_digest text NOT NULL,
 catalog_version bigint NOT NULL CHECK(catalog_version>0), batch_version bigint NOT NULL CHECK(batch_version>0),
 requested_by uuid NOT NULL REFERENCES users(id), requested_at timestamptz NOT NULL DEFAULT now(),
 adapter text NOT NULL CHECK(adapter='synthetic-test-v1'), policy_version text NOT NULL CHECK(policy_version='synthetic-test-v1'),
 synthetic boolean NOT NULL CHECK(synthetic), artifact_ref uuid NOT NULL,
 completed_at timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK(expires_at>completed_at),
 FOREIGN KEY(batch_id,preview_id,effect_digest,catalog_version) REFERENCES import_previews(batch_id,id,effect_digest,catalog_version),
 UNIQUE(batch_id,id,preview_id,effect_digest,catalog_version)
);
--;;
CREATE TABLE import_commits (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL UNIQUE REFERENCES import_batches(id),
 ordinal bigint GENERATED ALWAYS AS IDENTITY UNIQUE CHECK(ordinal>0),
 preview_id uuid NOT NULL, effect_digest text NOT NULL, catalog_version bigint NOT NULL,
 backup_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 request_context jsonb NOT NULL CHECK(jsonb_typeof(request_context)='object'),
 batch_version bigint NOT NULL CHECK(batch_version>0), summary jsonb NOT NULL CHECK(jsonb_typeof(summary)='object'),
 FOREIGN KEY(batch_id,backup_id,preview_id,effect_digest,catalog_version) REFERENCES import_backup_runs(batch_id,id,preview_id,effect_digest,catalog_version),
 UNIQUE(batch_id,id,preview_id), UNIQUE(batch_id,id)
);
--;;
CREATE TABLE import_commit_items (
 batch_id uuid NOT NULL, commit_id uuid NOT NULL, preview_id uuid NOT NULL, ordinal bigint NOT NULL,
 kind text NOT NULL CHECK(kind IN ('new-donor','existing-donor-reference','new-payment','duplicate-reference')),
 donor_id uuid NOT NULL REFERENCES donors(id), donation_id uuid REFERENCES donations(id),
 proposal_id uuid, created_donor_batch_id uuid, created_payment_batch_id uuid, worksheet_index integer, row_number bigint,
 donor_version bigint NOT NULL CHECK(donor_version>0), donation_version bigint CHECK(donation_version>0),
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
 PRIMARY KEY(commit_id,ordinal),
 FOREIGN KEY(batch_id,commit_id,preview_id) REFERENCES import_commits(batch_id,id,preview_id),
 FOREIGN KEY(preview_id,ordinal) REFERENCES import_preview_items(preview_id,ordinal),
 FOREIGN KEY(batch_id,proposal_id) REFERENCES import_donor_proposals(batch_id,id),
 FOREIGN KEY(created_donor_batch_id,donor_id) REFERENCES donors(import_batch_id,id),
 FOREIGN KEY(created_payment_batch_id,donation_id) REFERENCES donations(import_batch_id,id),
 FOREIGN KEY(batch_id,worksheet_index,row_number) REFERENCES staged_rows(batch_id,worksheet_index,row_number),
 CHECK((kind IN ('new-donor','existing-donor-reference') AND donation_id IS NULL AND donation_version IS NULL AND worksheet_index IS NULL AND row_number IS NULL)
    OR (kind IN ('new-payment','duplicate-reference') AND donation_id IS NOT NULL AND donation_version IS NOT NULL AND worksheet_index IS NOT NULL AND row_number IS NOT NULL)),
 CHECK((kind='new-donor')=(proposal_id IS NOT NULL)),
 CHECK((kind='new-donor' AND created_donor_batch_id IS NOT NULL AND created_donor_batch_id=batch_id) OR (kind<>'new-donor' AND created_donor_batch_id IS NULL)),
 CHECK((kind='new-payment' AND created_payment_batch_id IS NOT NULL AND created_payment_batch_id=batch_id) OR (kind<>'new-payment' AND created_payment_batch_id IS NULL))
);
--;;
CREATE UNIQUE INDEX import_new_payment_once ON import_commit_items(batch_id,donation_id) WHERE kind='new-payment';
--;;
CREATE INDEX import_commit_items_donor ON import_commit_items(donor_id);
--;;
CREATE TABLE import_undos (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL, commit_id uuid NOT NULL UNIQUE,
 actor_id uuid NOT NULL REFERENCES users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 request_context jsonb NOT NULL CHECK(jsonb_typeof(request_context)='object'), batch_version bigint NOT NULL CHECK(batch_version>0),
 reversed_payments bigint NOT NULL CHECK(reversed_payments>=0), archived_donors bigint NOT NULL CHECK(archived_donors>=0),
 FOREIGN KEY(batch_id,commit_id) REFERENCES import_commits(batch_id,id), UNIQUE(batch_id,id)
);
--;;
CREATE TABLE donation_reversals (
 donation_id uuid PRIMARY KEY, import_batch_id uuid NOT NULL, undo_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES users(id), occurred_at timestamptz NOT NULL DEFAULT now(),
 reason text NOT NULL CHECK(reason='import-undo'),
 FOREIGN KEY(import_batch_id,donation_id) REFERENCES donations(import_batch_id,id),
 FOREIGN KEY(import_batch_id,undo_id) REFERENCES import_undos(batch_id,id)
);
--;;
CREATE TRIGGER reversals_catalog_revision AFTER INSERT OR UPDATE OR DELETE ON donation_reversals FOR EACH ROW EXECUTE FUNCTION advance_canonical_catalog();
--;;
CREATE VIEW active_donations WITH (security_invoker=true) AS SELECT d.* FROM donations d WHERE NOT EXISTS(SELECT 1 FROM donation_reversals r WHERE r.donation_id=d.id);
--;;
GRANT SELECT ON active_donations TO alma_spei_app;
--;;
GRANT SELECT,INSERT ON import_backup_runs,import_commits,import_commit_items,import_undos,donation_reversals TO alma_spei_app;
--;;
GRANT USAGE,SELECT ON SEQUENCE import_commits_ordinal_seq TO alma_spei_app;
