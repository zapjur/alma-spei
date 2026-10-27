#!/usr/bin/env bash
set -euo pipefail

# Read separately so failures cannot be hidden by a successful psql invocation.
migration_password="$(cat /run/alma-spei/migration_password)"
app_password="$(cat /run/alma-spei/app_password)"
[[ -n "$migration_password" && -n "$app_password" ]] || { printf 'PostgreSQL role passwords must not be empty.\n' >&2; exit 1; }

# psql variables quote passwords as SQL literals; never interpolate them into SQL.
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migration_password="$migration_password" \
  --set=app_password="$app_password" <<'SQL'
CREATE ROLE alma_spei_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migration_password';
CREATE ROLE alma_spei_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE alma_spei_dev TO alma_spei_migrator, alma_spei_app;
GRANT USAGE, CREATE ON SCHEMA public TO alma_spei_migrator;
GRANT USAGE ON SCHEMA public TO alma_spei_app;
SQL
