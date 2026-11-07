CREATE TABLE users (
 id uuid PRIMARY KEY,
 username text NOT NULL UNIQUE CHECK (username = lower(username) AND username ~ '^[a-z0-9._-]{3,64}$'),
 display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 120),
 active boolean NOT NULL DEFAULT true,
 administrator boolean NOT NULL DEFAULT false,
 security_version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
--;;
CREATE TABLE local_credentials (
 user_id uuid PRIMARY KEY REFERENCES users(id),
 password_hash text NOT NULL,
 password_change_required boolean NOT NULL DEFAULT true,
 credential_version bigint NOT NULL DEFAULT 1,
 changed_at timestamptz NOT NULL DEFAULT now()
);
--;;
CREATE TABLE external_identities (
 provider_id text NOT NULL,
 subject text NOT NULL,
 user_id uuid NOT NULL REFERENCES users(id),
 PRIMARY KEY (provider_id, subject),
 UNIQUE (provider_id, user_id)
);
--;;
CREATE TABLE sessions (
 token_digest text PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id),
 security_version bigint NOT NULL,
 csrf_digest text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 last_activity timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL DEFAULT now() + interval '8 hours'
);
--;;
CREATE INDEX sessions_user ON sessions(user_id);
--;;
CREATE TABLE auth_throttle_buckets (
 bucket text PRIMARY KEY,
 attempts integer NOT NULL DEFAULT 0,
 expires_at timestamptz NOT NULL
);
--;;
CREATE TABLE security_audit_events (
 id uuid PRIMARY KEY,
 actor_id uuid REFERENCES users(id),
 target_id uuid REFERENCES users(id),
 event_type text NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 changes jsonb NOT NULL DEFAULT '{}'::jsonb
);
--;;
GRANT SELECT, INSERT, UPDATE ON users, local_credentials, external_identities TO alma_spei_app;
--;;
GRANT SELECT, INSERT, UPDATE, DELETE ON sessions, auth_throttle_buckets TO alma_spei_app;
--;;
GRANT SELECT, INSERT ON security_audit_events TO alma_spei_app;
