#!/usr/bin/env bash
set -euo pipefail

# Compose file-backed secrets retain host ownership on Linux. Copy them into
# container memory before the official entrypoint drops privileges to postgres.
install -d -m 0700 -o postgres -g postgres /run/alma-spei
for name in admin_password migration_password app_password; do
  source_file="/run/secrets/$name"
  [[ -s "$source_file" ]] || { printf 'Required PostgreSQL secret is missing or empty: %s\n' "$name" >&2; exit 1; }
  install -m 0600 -o postgres -g postgres "$source_file" "/run/alma-spei/$name"
done

export POSTGRES_PASSWORD_FILE=/run/alma-spei/admin_password
exec /usr/local/bin/docker-entrypoint.sh "$@"
