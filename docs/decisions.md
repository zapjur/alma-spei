# Decisions and open questions

Updated 2026-10-25 from stakeholder instructions. CONFIRMED denotes explicit requirements, PROPOSED denotes unapproved design, and OPEN denotes missing evidence or approval. This update authorizes documentation only.

## CONFIRMED — product and engineering

- Central CRM with individuals, businesses and organizations; seed from the master then reconcile tax designation and bank transfer sources through distinct adapters sharing common services.
- Source descriptions are preliminary; inspect actual workbooks securely before final fields, mappings, identifiers, matching thresholds or deduplication rules.
- Donor profiles and contribution histories are separate. Preserve relevant transaction detail and provenance; do not retain every input field by default.
- Separate donor identity from correspondence recipients/groups. Prevent duplicate spouse/group mail without combining donor financial histories or treating shared addresses as identity.
- Manual donor corrections, explicit duplicate merges, archiving, certain-only auto-linking, human review for ambiguity, no silent conflicting overwrites.
- Monthly/annual summaries, tax-designation identification evidence, names/postal-address mailing exports and CSV/XLSX exports are MVP scope.
- Preview and explicit approval before transactional import commit; import history, repeated-file detection, payment deduplication and actor/time/field before/after audit.
- Undo only the latest committed import through import-specific compensation. Stop on conflict, preserve later edits, and keep audit history. Backups are a separate recovery mechanism.
- Individual accounts, equivalent initial staff capabilities, no self-registration. MVP uses local username/password authentication, secure hashing and server-managed sessions.
- Replaceable/configurable authentication boundary; stable internal users, external-identity mappings, provider-independent sessions and app-owned authorization. External OIDC/LDAP/AD/SSO implementations are deferred.
- Preferred production direction is hospice-owned/administered infrastructure, on-premises support and expected internal-network access; no mandatory cloud. Final hosting design requires hospice IT consultation.
- TLS, network restrictions, protected PostgreSQL, daily and pre-import backups, reproducible installation, upgrade/restore/rollback and operational handover are required. Internal networking does not replace security.
- Synthetic development/test data; hospice confirms organizational/GDPR requirements before real personal data. No invented legal retention periods or compliance claims.
- Monorepo, modular monolith, Clojure, ClojureScript with Reagent/re-frame, PostgreSQL, `src/api`, `src/ui`, `src/common` and portable shared `.cljc` models/Malli schemas/utilities. Malli preferred; precise dependencies/versions need approval.
- Unit/integration tests from inception; English technical artifacts and Polish UI. Plan and explicit approval before substantial coding/dependency decisions. Initial implementation is a scaffold, not the entire CRM.

## PROPOSED — requires approval before implementation

- Module and API boundaries, Clojure CLI/shadow-cljs build strategy and supporting directories in [architecture](architecture.md).
- Candidate relational entities, separate unresolved aggregate observations, provenance/rule versions and synthetic generator design in [data model](data-model.md).
- Staging lifecycle, preview version checks, serialized commit/undo checks and atomic compensation in [import workflow](import-workflow.md). Transactional commits and safe compensation themselves are confirmed requirements.
- Small provider interface, identity resolver, mapping uniqueness, server session service, Argon2id, local provisioning/reset mechanics and configuration described in [architecture](architecture.md). Authentication requirements are confirmed, exact implementation is not.
- Reverse proxy/application/PostgreSQL topology; host/VM versus containers; Docker Compose as a local-development option only.
- Protected deployment artifacts, backup verification gates, compatible schema upgrades and procedures in [operations](operations.md); exact tooling and policy values need review.

## OPEN — hospice staff / data owners

- Secure access to the three files (not present in repository); worksheets, layouts, formulas, dates, sizes, malformed values and source identifiers.
- Are 2021–2025 values indicators, amounts or mixed? What do 2026 monthly cells and summary represent? Do they overlap bank or tax data?
- Taxpayer/spouse identity and amount attribution, tax/receipt year, purpose semantics and financial overlap; what constitutes confirmed tax identification?
- Approved matching evidence, address normalization, payment identity, genuine repeated identical payments, corrections/refunds and currency/date basis for summaries.
- Mailing recipient conventions, spouse/group consent or instructions, shared address exceptions, archive behavior and export templates.
- Deliberate reprocessing and undo/reimport policy; latest-eligible-import behavior after an undo; source/staging retention and audit visibility.
- Local account provisioner/reset authority, deactivation process and future identity infrastructure.
- Lawful basis, privacy notices, access/export/deletion handling, retention and legal holds; hospice privacy owner approval before real data.

## OPEN — hospice IT / operational owners

- Available host/VM, OS/resources, runtime/orchestration, internal DNS/TLS, firewall and any remote-access policy.
- PostgreSQL administration, protected backup storage, encryption/key custody, retention, pre-import backup latency and restore drills.
- Recovery point/time objectives, monitoring destination, incident contacts, maintenance windows and software/security update ownership.
- Export delivery/expiry and secure temporary-file cleanup; audit and credential storage protections.
- Exact library versions and compatibility evidence, installation artifacts and release support policy.

## Decision process

For each implementation milestone, document outline, affected files, schema/API changes, dependencies/versions, tests, risks and questions, then obtain explicit approval. Record date, approver, rationale and tradeoffs when accepting a proposal. Do not mark proposals accepted merely because they appear in this repository.
