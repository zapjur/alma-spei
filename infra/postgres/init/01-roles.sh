#!/usr/bin/env bash
set -euo pipefail
# psql variables quote passwords as SQL literals; never interpolate them into SQL.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migration_password="$(cat /run/secrets/migration_password)" \
  --set=app_password="$(cat /run/secrets/app_password)" <<'SQL'
CREATE ROLE alma_spei_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migration_password';
CREATE ROLE alma_spei_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE alma_spei_dev TO alma_spei_migrator, alma_spei_app;
GRANT USAGE, CREATE ON SCHEMA public TO alma_spei_migrator;
GRANT USAGE ON SCHEMA public TO alma_spei_app;
SQL
