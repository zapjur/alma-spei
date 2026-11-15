CREATE TABLE import_donor_proposals (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
 version bigint NOT NULL DEFAULT 1 CHECK (version > 0), profile jsonb NOT NULL CHECK (jsonb_typeof(profile)='object'),
 created_by uuid NOT NULL REFERENCES users(id), updated_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(batch_id,id)
);
--;;
CREATE INDEX import_proposals_batch_name ON import_donor_proposals(batch_id,(profile->>'name'),id);
--;;
CREATE TABLE import_review_decisions (
 batch_id uuid NOT NULL, worksheet_index integer NOT NULL, row_number bigint NOT NULL,
 version bigint NOT NULL CHECK(version>0), kind text NOT NULL CHECK(kind IN ('unresolved','existing-donor','new-proposal')),
 donor_id uuid REFERENCES donors(id) ON DELETE RESTRICT, proposal_id uuid, target_version bigint CHECK(target_version>0),
 financial jsonb CHECK(financial IS NULL OR jsonb_typeof(financial)='object'),
 donation_id uuid REFERENCES donations(id) ON DELETE RESTRICT, donation_version bigint CHECK(donation_version>0),
 complete boolean NOT NULL DEFAULT false, reviewer_id uuid NOT NULL REFERENCES users(id), reviewed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(batch_id,worksheet_index,row_number),
 FOREIGN KEY(batch_id,worksheet_index,row_number) REFERENCES staged_rows(batch_id,worksheet_index,row_number) ON DELETE RESTRICT,
 FOREIGN KEY(batch_id,proposal_id) REFERENCES import_donor_proposals(batch_id,id) ON DELETE RESTRICT,
 CHECK ((kind='unresolved' AND donor_id IS NULL AND proposal_id IS NULL AND target_version IS NULL AND financial IS NULL AND NOT complete)
     OR (kind='existing-donor' AND donor_id IS NOT NULL AND proposal_id IS NULL AND target_version IS NOT NULL)
     OR (kind='new-proposal' AND donor_id IS NULL AND proposal_id IS NOT NULL AND target_version IS NOT NULL)),
 CHECK ((donation_id IS NULL AND donation_version IS NULL) OR (donation_id IS NOT NULL AND donation_version IS NOT NULL AND kind='existing-donor')),
 CHECK ((financial->>'disposition'='existing') IS NOT TRUE OR donation_id IS NOT NULL),
 CHECK (donation_id IS NULL OR (financial IS NOT NULL AND financial->>'disposition'='existing') IS TRUE),
 CHECK (NOT complete OR (kind<>'unresolved' AND financial IS NOT NULL
   AND financial->>'currency' ~ '^[A-Z]{3}$' AND length(btrim(financial->>'evidence')) BETWEEN 1 AND 500
   AND financial->>'disposition' IN ('new','existing') AND financial->'overlap-checked'='true'::jsonb) IS TRUE)
);
--;;
CREATE TABLE import_previews (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
 batch_version bigint NOT NULL CHECK(batch_version>0), created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), rule_version text NOT NULL CHECK(rule_version='review-preview-v1'),
 effect_digest text NOT NULL CHECK(effect_digest ~ '^[0-9a-f]{64}$'),
 summary jsonb NOT NULL CHECK(jsonb_typeof(summary)='object'), item_count bigint NOT NULL CHECK(item_count>=0),
 UNIQUE(batch_id,id)
);
--;;
CREATE INDEX import_previews_batch_time ON import_previews(batch_id,created_at,id);
--;;
CREATE TABLE import_preview_items (
 preview_id uuid NOT NULL REFERENCES import_previews(id) ON DELETE RESTRICT, ordinal bigint NOT NULL CHECK(ordinal>0),
 kind text NOT NULL CHECK(kind IN ('new-donor','existing-donor-reference','new-payment','duplicate-reference')),
 target_id uuid, worksheet_index integer, row_number bigint,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'), PRIMARY KEY(preview_id,ordinal),
 CHECK ((kind IN ('new-donor','existing-donor-reference') AND target_id IS NOT NULL AND worksheet_index IS NULL AND row_number IS NULL)
     OR (kind IN ('new-payment','duplicate-reference') AND target_id IS NULL AND worksheet_index IS NOT NULL AND row_number IS NOT NULL AND worksheet_index>0 AND row_number>0))
);
--;;
CREATE UNIQUE INDEX import_preview_targets ON import_preview_items(preview_id,kind,target_id) WHERE target_id IS NOT NULL;
--;;
CREATE UNIQUE INDEX import_preview_sources ON import_preview_items(preview_id,worksheet_index,row_number) WHERE row_number IS NOT NULL;
--;;
CREATE TABLE import_review_events (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES import_batches(id), actor_id uuid NOT NULL REFERENCES users(id),
 occurred_at timestamptz NOT NULL DEFAULT now(),
 event_type text NOT NULL CHECK(event_type IN ('proposal-created','proposal-updated','decision-saved','decision-cleared','preview-created')),
 proposal_id uuid, preview_id uuid, worksheet_index integer, row_number bigint,
 before_version bigint NOT NULL CHECK(before_version>=0), after_version bigint NOT NULL CHECK(after_version>0),
 before_value jsonb CHECK(before_value IS NULL OR jsonb_typeof(before_value)='object'),
 after_value jsonb NOT NULL CHECK(jsonb_typeof(after_value)='object'),
 FOREIGN KEY(batch_id,proposal_id) REFERENCES import_donor_proposals(batch_id,id),
 FOREIGN KEY(batch_id,preview_id) REFERENCES import_previews(batch_id,id),
 FOREIGN KEY(batch_id,worksheet_index,row_number) REFERENCES staged_rows(batch_id,worksheet_index,row_number),
 CHECK ((event_type IN ('proposal-created','proposal-updated') AND proposal_id IS NOT NULL AND preview_id IS NULL AND worksheet_index IS NULL AND row_number IS NULL)
    OR (event_type IN ('decision-saved','decision-cleared') AND proposal_id IS NULL AND preview_id IS NULL AND worksheet_index IS NOT NULL AND row_number IS NOT NULL)
    OR (event_type='preview-created' AND preview_id IS NOT NULL AND proposal_id IS NULL AND worksheet_index IS NULL AND row_number IS NULL))
);
--;;
CREATE INDEX import_review_events_batch_time ON import_review_events(batch_id,occurred_at,id);
--;;
GRANT SELECT,INSERT ON import_donor_proposals,import_review_decisions,import_review_events,import_previews,import_preview_items TO alma_spei_app;
--;;
GRANT UPDATE(profile,version,updated_by,updated_at) ON import_donor_proposals TO alma_spei_app;
--;;
GRANT UPDATE(version,kind,donor_id,proposal_id,target_version,financial,donation_id,donation_version,complete,reviewer_id,reviewed_at)
 ON import_review_decisions TO alma_spei_app;
