CREATE TABLE import_files (
 id uuid PRIMARY KEY,
 file_hash_sha256 text NOT NULL UNIQUE CHECK (file_hash_sha256 ~ '^[0-9a-f]{64}$'),
 byte_count bigint NOT NULL CHECK (byte_count > 0),
 created_at timestamptz NOT NULL DEFAULT now(),
 last_attempt_at timestamptz NOT NULL DEFAULT now()
);
--;;
CREATE TABLE import_batches (
 id uuid PRIMARY KEY,
 file_id uuid NOT NULL REFERENCES import_files(id) ON DELETE RESTRICT,
 attempt_number bigint NOT NULL CHECK (attempt_number > 0),
 retry_of uuid UNIQUE,
 source_type text NOT NULL CHECK (source_type IN ('master', 'tax', 'bank')),
 original_filename text NOT NULL CHECK (
  length(btrim(original_filename)) BETWEEN 1 AND 255
  AND length(original_filename) <= 255
  AND original_filename NOT IN ('.', '..')
  AND original_filename !~ '[/\\[:cntrl:]]'
 ),
 created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
 state text NOT NULL DEFAULT 'reserved' CHECK (state IN ('reserved', 'staging', 'review', 'approved', 'committed', 'failed', 'cancelled', 'undone')),
 parse_version text CHECK (parse_version IS NULL OR length(btrim(parse_version)) BETWEEN 1 AND 64),
 mapping_version text CHECK (mapping_version IS NULL OR length(btrim(mapping_version)) BETWEEN 1 AND 64),
 failure_code text CHECK (failure_code IN ('parse-failed', 'unsupported-format', 'invalid-structure', 'resource-limit', 'processing-interrupted')),
 total_rows bigint,
 valid_rows bigint,
 invalid_rows bigint,
 reviewed_rows bigint,
 UNIQUE (file_id, attempt_number),
 UNIQUE (file_id, id),
 FOREIGN KEY (file_id, retry_of) REFERENCES import_batches(file_id, id) ON DELETE RESTRICT,
 CHECK ((attempt_number = 1 AND retry_of IS NULL) OR (attempt_number > 1 AND retry_of IS NOT NULL)),
 CHECK ((state = 'failed') = (failure_code IS NOT NULL)),
 CHECK (
  (total_rows IS NULL AND valid_rows IS NULL AND invalid_rows IS NULL AND reviewed_rows IS NULL)
  OR (total_rows IS NOT NULL AND valid_rows IS NOT NULL AND invalid_rows IS NOT NULL AND reviewed_rows IS NOT NULL
      AND total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0 AND reviewed_rows >= 0
      AND total_rows::numeric = valid_rows::numeric + invalid_rows::numeric AND reviewed_rows <= valid_rows)
 ),
 CHECK (state NOT IN ('review', 'approved', 'committed', 'undone') OR total_rows IS NOT NULL),
 CHECK (state NOT IN ('approved', 'committed', 'undone') OR (invalid_rows = 0 AND reviewed_rows = valid_rows))
);
--;;
CREATE UNIQUE INDEX import_batches_one_pending ON import_batches(file_id)
 WHERE state IN ('reserved', 'staging', 'review', 'approved');
--;;
CREATE INDEX import_batches_created ON import_batches(created_at, id);
--;;
CREATE TABLE import_batch_events (
 id uuid PRIMARY KEY,
 batch_id uuid NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
 actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 batch_version bigint NOT NULL CHECK (batch_version > 0),
 event_type text NOT NULL CHECK (event_type IN ('reserved', 'failed', 'retry-requested', 'staged', 'reviewed', 'approved', 'committed', 'cancelled', 'undone')),
 previous_state text CHECK (previous_state IN ('reserved', 'staging', 'review', 'approved', 'committed', 'failed', 'cancelled', 'undone')),
 new_state text NOT NULL CHECK (new_state IN ('reserved', 'staging', 'review', 'approved', 'committed', 'failed', 'cancelled', 'undone')),
 reason_code text CHECK (reason_code IN ('parse-failed', 'unsupported-format', 'invalid-structure', 'resource-limit', 'processing-interrupted'))
);
--;;
CREATE INDEX import_batch_events_batch ON import_batch_events(batch_id, occurred_at, id);
--;;
CREATE TABLE staged_rows (
 batch_id uuid NOT NULL REFERENCES import_batches(id) ON DELETE RESTRICT,
 worksheet_index integer NOT NULL CHECK (worksheet_index > 0),
 row_number bigint NOT NULL CHECK (row_number > 0),
 normalized_payload jsonb NOT NULL CHECK (jsonb_typeof(normalized_payload) = 'object'),
 validation_state text NOT NULL CHECK (validation_state IN ('valid', 'invalid')),
 validation_codes jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(validation_codes) = 'array'),
 rule_version text NOT NULL CHECK (length(btrim(rule_version)) BETWEEN 1 AND 64),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (batch_id, worksheet_index, row_number),
 CHECK ((validation_state = 'valid' AND jsonb_array_length(validation_codes) = 0)
        OR (validation_state = 'invalid' AND jsonb_array_length(validation_codes) > 0)),
 CHECK (NOT jsonb_path_exists(validation_codes, '$[*] ? (@.type() != "string" || !(@ like_regex "^[a-z][a-z0-9-]{0,63}$"))'))
);
--;;
GRANT SELECT, INSERT ON import_files, import_batches, import_batch_events, staged_rows TO alma_spei_app;
--;;
GRANT UPDATE (last_attempt_at) ON import_files TO alma_spei_app;
--;;
GRANT UPDATE (updated_at, version, state, parse_version, mapping_version, failure_code, total_rows, valid_rows, invalid_rows, reviewed_rows)
 ON import_batches TO alma_spei_app;
