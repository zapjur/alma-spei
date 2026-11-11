CREATE TABLE donors (
 id uuid PRIMARY KEY,
 entity_type text NOT NULL CHECK (entity_type IN ('individual', 'business', 'organization')),
 name text NOT NULL CHECK (length(btrim(name)) > 0),
 first_name text,
 street text,
 postal_code text,
 city text,
 province text,
 is_active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 updated_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
--;;
CREATE INDEX donors_name_lower ON donors (lower(name));
--;;
CREATE INDEX donors_active_type_id ON donors (is_active, entity_type, id);
--;;
CREATE TABLE donations (
 id uuid PRIMARY KEY,
 donor_id uuid NOT NULL REFERENCES donors(id) ON DELETE RESTRICT,
 amount numeric(20, 4) NOT NULL CHECK (amount <> 0 AND amount <> 'NaN'::numeric),
 currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
 received_on date NOT NULL CHECK (isfinite(received_on)),
 version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
 created_at timestamptz NOT NULL DEFAULT now(),
 created_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT
);
--;;
CREATE INDEX donations_donor_date_id ON donations (donor_id, received_on, id);
--;;
CREATE TABLE donor_audit_events (
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 donor_id uuid NOT NULL REFERENCES donors(id) ON DELETE RESTRICT,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 action text NOT NULL CHECK (action IN ('create', 'update', 'archive', 'merge'))
);
--;;
CREATE INDEX donor_audit_events_donor_time_id ON donor_audit_events (donor_id, occurred_at, id);
--;;
CREATE TABLE donor_audit_changes (
 event_id uuid NOT NULL REFERENCES donor_audit_events(id) ON DELETE RESTRICT,
 field_name text NOT NULL CHECK (field_name IN (
  'entity_type', 'name', 'first_name', 'street', 'postal_code', 'city', 'province', 'is_active'
 )),
 before_value jsonb NOT NULL,
 after_value jsonb NOT NULL,
 PRIMARY KEY (event_id, field_name)
);
--;;
GRANT SELECT, INSERT, UPDATE ON donors TO alma_spei_app;
--;;
GRANT SELECT, INSERT ON donations, donor_audit_events, donor_audit_changes TO alma_spei_app;
