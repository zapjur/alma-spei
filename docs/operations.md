# Operations, security and handover

## Status and operating assumptions

CONFIRMED: support on-premises installation with no mandatory cloud dependencies; prefer hospice-owned/administered infrastructure and internal-network access. Require named local MVP accounts, application authorization, TLS, network controls, protected PostgreSQL, daily and pre-import backups, reproducible installation, recovery procedures and operational ownership. Final hosting requires hospice IT consultation.

The procedures below are PROPOSED runbook requirements, not executable commands or an approved topology. Runtime/OS, host or VM, containers, orchestration, policy values and tools remain OPEN. A network drive is not assumed capable of hosting the application or database. Internal access is not a substitute for authentication/security.

## Reproducible installation and configuration

Before installation, IT approves capacity/storage, supported OS/runtime, internal DNS, TLS certificate issuance/renewal, firewall routes, service accounts, maintenance windows and backup destination. Build a versioned release artifact from reviewed code with locked approved dependencies and documented build provenance. Provide artifacts/install instructions that do not require cloud runtime services; document any build-time downloads and offline artifact transfer requirements.

Install under a dedicated least-privilege service identity. Only the TLS endpoint is reachable by permitted staff devices; PostgreSQL permits application and explicitly authorized administration traffic only. Separate migration credentials from ordinary application credentials. Use an approved host/proxy trust configuration and restrict administrative access.

Document configuration keys, defaults, validation and version compatibility: database connection, listener/base URL, TLS/proxy settings, enabled auth providers (local only for MVP), sessions, upload limits, backup hooks and logging. Secrets must be injected through IT-approved protected files or a secret store, never committed or printed. Define file permissions, rotation and recovery of keys/secrets. Fail startup on missing/unsafe required configuration.

Installation acceptance: a clean target can install the release, migrate an empty database, provision a named account securely, run synthetic smoke tests, back up/restore and restart automatically. No real data before privacy and operational sign-off.

## Backups and recovery

- Automated daily database backups and a verified successful pre-import backup are required. PROPOSED: block import commit when its required backup fails or becomes stale; bind backup evidence to the approved import and revalidate the preview afterward. IT must decide the freshness window and acceptable latency.
- Include database, required configuration, release/migration version and any retained files necessary for recovery; keep secrets and encryption keys protected separately with a recoverable custody process.
- Restrict and encrypt backups; storage destination, off-host/offsite policy, retention, key access and restore frequency need approval. Do not assume a network share meets these requirements.
- Monitor backup success and age, alert on failure and test restoration into an isolated environment. Agree recovery point/time objectives and verify measured restore times against them.

Proposed restore procedure: authorize an outage, stop writes, preserve incident evidence/current state where safe, select and verify the backup and compatible release, restore database and necessary files/configuration to an isolated protected target, validate migrations/integrity and synthetic recovery checks, then obtain operational approval to reopen access. Record the recovery and reconcile work lost since the recovery point. Treat restored personal data with production protections.

Import undo is not database restore. Undo compensates only the latest committed import and stops atomically on later-change/dependency conflicts; see [import workflow](import-workflow.md).

## Upgrades and release rollback

Proposed upgrade procedure: review release/schema compatibility and security notes, rehearse against synthetic data, take/verify backup, schedule maintenance, restrict writes as needed, apply versioned migrations with controlled credentials, deploy the immutable release, validate health/login/authorization and import/report/export smoke checks, then reopen access. Retain release/configuration identifiers and an operational audit record.

Proposed rollback procedure: stop writes and assess schema compatibility first. Redeploy the previous artifact only if it supports the current schema. Otherwise use an approved forward fix or a rehearsed database recovery with explicit outage/data-loss assessment; never blindly reverse a destructive migration. Test both upgrade and rollback paths before production release. Containerization does not eliminate migration/recovery planning.

## Security and privacy controls

Use least-privilege accounts, protected password hashes, server-managed revocable sessions, secure cookies, CSRF protections and login throttling. All backend endpoints, exports and file downloads require application authorization. Staff initially have equivalent app capabilities; infrastructure administration is separately controlled.

Validate upload type/size and parser resource limits; isolate staging, temporary files and retained uploads. Export CSV/XLSX with formula-injection protections, authenticated delivery and agreed expiry/cleanup. Do not put names, addresses, financial details, credentials, session IDs or workbook contents in operational logs, exception reports or telemetry. Audit records may require personal before/after values; restrict access and do not duplicate them into diagnostic logs.

Data minimization review must justify each retained field, raw workbook, staging value, report/export and audit value. Tax/spouse/designated-purpose fields require particular review of necessity and access. Keep development and automated tests synthetic; production restore drills with real backups require an authorized protected environment, never developer/test fixtures.

Hospice organizational/privacy owners must confirm lawful basis, transparency notices, staff access, retention schedules, legal holds, audit access and data-subject procedures before real personal data. Do not invent legal retention periods or claim GDPR compliance.

Proposed request procedure: verify requester/authority, determine scope, securely export approved records and relevant provenance, record fulfillment without unnecessary copies, and deliver through an approved channel. For correction/deletion, check retention obligations and holds, identify linked donor/contribution/group/audit/source/export data, obtain authorized handling, then erase/anonymize as appropriate. Routine archiving is not legal erasure. Track backup expiry and reapply authorized deletions after restores where required; exact procedure needs privacy approval.

## Monitoring, incidents and maintenance

Monitor service health, certificate expiry, disk capacity, database availability, backup age/failure, failed logins, import failure rates and release versions without personal payloads. Agree alert destinations, thresholds and named responders. Prefer simple IT-managed local tools; no mandatory cloud telemetry.

Proposed incident procedure: report to named IT/privacy contacts; contain access and preserve restricted evidence; assess impact with the hospice privacy owner; rotate affected credentials/keys and patch or recover; validate service before reopening; document timeline and follow-up actions. Notification decisions and applicable deadlines must be determined by the hospice, not guessed in application documentation.

Assign owners and substitutes before launch:

| Responsibility | Required owner (OPEN) |
| --- | --- |
| Business rules, mapping/report/export sign-off | Hospice product/data owner |
| Privacy/retention, requests and incident assessment | Hospice privacy/organizational owner |
| Host/network/TLS/PostgreSQL, backup/restore and alerts | Hospice IT administrator or contracted operator |
| Account provisioning/reset/deactivation | Designated hospice account administrator |
| Dependency/security review, patches, tests, releases and migration support | Named software maintainer with support agreement |

Agree patch cadence, urgent vulnerability response, supported runtime versions, maintenance budget, escalation contacts and coverage during absence. Operational handover includes inventory, architecture/configuration guide, protected secret/key custody, account procedures, backup/restore evidence, upgrade/rollback rehearsal, monitoring checks, source/import limitations, user training and signed ownership. No deployment sign-off until these responsibilities and recovery procedures are accepted.

## Development foundation boundary

Milestone 1 local setup is documented in [development.md](development.md). It binds
HTTP and PostgreSQL to loopback, keeps generated credentials outside version control,
and separates migration/runtime roles. Compose is approved for development only.
The skeleton has no authentication, backup automation or production logging provider;
it must not process real personal data. Production installation, secret rotation,
TLS, backup/restore and privacy approval remain subject to the gates above.
