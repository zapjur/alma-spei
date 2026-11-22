ALTER TABLE import_batches ADD CONSTRAINT import_batches_id_source_key UNIQUE (id, source_type);
--;;
CREATE TABLE source_observations (
 batch_id uuid NOT NULL,
 source_type text GENERATED ALWAYS AS ('master'::text) STORED,
 worksheet_index integer NOT NULL,
 row_number bigint NOT NULL,
 column_index integer NOT NULL CHECK (column_index BETWEEN 1 AND 16384),
 observed_value text NOT NULL CHECK (length(btrim(observed_value)) BETWEEN 1 AND 32767),
 source_value_type text NOT NULL CHECK (source_value_type IN ('text', 'number', 'boolean')),
 observed_year smallint CHECK (observed_year BETWEEN 1 AND 9999),
 observed_month smallint CHECK (observed_month BETWEEN 1 AND 12),
 PRIMARY KEY (batch_id, worksheet_index, row_number, column_index),
 FOREIGN KEY (batch_id, source_type) REFERENCES import_batches(id, source_type) ON DELETE RESTRICT,
 FOREIGN KEY (batch_id, worksheet_index, row_number)
  REFERENCES staged_rows(batch_id, worksheet_index, row_number) ON DELETE RESTRICT
);
--;;
CREATE TABLE tax_identification_evidence (
 batch_id uuid NOT NULL,
 source_type text GENERATED ALWAYS AS ('tax'::text) STORED,
 worksheet_index integer NOT NULL,
 row_number bigint NOT NULL,
 subject_role text NOT NULL CHECK (subject_role IN ('taxpayer', 'spouse')),
 donor_id uuid REFERENCES donors(id) ON DELETE RESTRICT,
 tax_year smallint CHECK (tax_year BETWEEN 1 AND 9999),
 PRIMARY KEY (batch_id, worksheet_index, row_number, subject_role),
 FOREIGN KEY (batch_id, source_type) REFERENCES import_batches(id, source_type) ON DELETE RESTRICT,
 FOREIGN KEY (batch_id, worksheet_index, row_number)
  REFERENCES staged_rows(batch_id, worksheet_index, row_number) ON DELETE RESTRICT
);
--;;
CREATE INDEX tax_identification_evidence_donor ON tax_identification_evidence(donor_id)
 WHERE donor_id IS NOT NULL;
--;;
GRANT SELECT, INSERT ON source_observations, tax_identification_evidence TO alma_spei_app;
