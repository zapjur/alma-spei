# Security, data protection and operations

## Confirmed decisions

- Named staff accounts; no shared login.
- Equal permissions initially; no complex authorization roles in MVP.
- Full actor/time/old-value/new-value audit history for donor modifications.
- Donor removal in routine UI is archive/soft delete.
- Automated daily database backups and backups before import.
- Undo is based on import-specific changes and must preserve later edits.

## Critical safeguards (PROPOSED implementation requirements)

- Secure password hashing if local accounts are approved; never store plaintext passwords or password-equivalent initial credentials.
- Force initial-password reset if local credential provisioning is adopted.
- Session protections, CSRF considerations, secure cookies, transport encryption and rate limits must match the eventual hosting model.
- Sanitize uploads, file metadata and exports; limit uploaded file sizes; protect against CSV formula injection when exporting spreadsheet-compatible files.
- Do not log donor names, addresses, financial identifiers, complete donor records or uploaded files by default.
- Restrict who can access imported original files, staging tables, backups and audit before/after values.
- Use synthetic sample data for development and testing.
- Provide a documented recovery procedure and test restoring backups regularly.
- Encrypt backups and define retention, external/offsite storage and access control after the deployment target is known.
- The hospice must define lawful basis, privacy notices, retention periods and requests handling under GDPR; do not guess legal policy.

## Open operational decisions

- Deployment location: server/on-premises/managed/cloud undecided.
- Network access: internal-only vs remote access undecided.
- Identity provider / SSO availability pending a response from hospice.
- Backup destination, encryption keys, retention and recovery goals undecided.
- Audit log retention and employee access undecided.

Do not postpone security design entirely, but avoid locking in infrastructure and authentication specifics before organizational input.
