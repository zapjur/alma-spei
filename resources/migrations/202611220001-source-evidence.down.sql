DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM source_observations) OR EXISTS (SELECT 1 FROM tax_identification_evidence)
 THEN RAISE EXCEPTION 'Cannot downgrade a database with source observations or tax identification evidence'; END IF;
END $$;
--;;
DROP TABLE tax_identification_evidence;
--;;
DROP TABLE source_observations;
--;;
ALTER TABLE import_batches DROP CONSTRAINT import_batches_id_source_key;
