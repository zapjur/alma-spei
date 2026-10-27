CREATE TABLE application_metadata (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    foundation_version integer NOT NULL CHECK (foundation_version > 0)
);
--;;
INSERT INTO application_metadata (singleton, foundation_version) VALUES (true, 1);
--;;
GRANT SELECT ON application_metadata TO alma_spei_app;
