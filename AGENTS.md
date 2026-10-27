# Instructions for Codex and other coding agents

## Mission

Build a maintainable, low-operations-cost internal donor CRM for Alma Spei hospice.

**Use English** for code, identifiers, comments, commit messages, technical documentation, and agent responses.

All end-user UI content, validation messages, navigation, and help text must be **in Polish**.

## Authority and scope

1. Read this file, `README.md`, `docs/decisions.md`, and all relevant `docs/*` before proposing changes.

2. Separate **CONFIRMED**, **PROPOSED**, and **OPEN** decisions. Do not silently treat a proposal as a requirement.

3. **Plan first, implement second.** For each milestone, present an implementation outline, affected files, schema/API changes, tests, risks, and unanswered questions. Wait for explicit user approval before substantial coding or dependency decisions.

4. Favor a modular monolith. Do not introduce microservices, message brokers, Kubernetes, or infrastructure complexity without a demonstrated need.

5. The agreed stack is Clojure, ClojureScript (Reagent/re-frame), PostgreSQL, a monorepo, and shared `.cljc` code. Malli is the preferred schema and validation approach. Propose exact libraries and versions for approval before bootstrap.

6. Follow the confirmed source organization:

   - `src/api/` — Clojure backend.
   - `src/ui/` — ClojureScript frontend.
   - `src/common/` — shared cross-platform Clojure/ClojureScript code.

   Place portable domain functions, Malli schemas, and compatible API contracts in `src/common/**/*.cljc`.

   Common code must remain compatible with both Clojure and ClojureScript.

   Do not place JDBC, browser-only APIs, filesystem access, or JVM-only imports in common code.

   Keep backend-only utilities in `src/api`, even if multiple backend modules use them.

   Keep server-side authorization, persistence, and authoritative validation in the backend, even when schemas are shared.

   Both backend and frontend build configurations must include `src/common` in their source paths.

7. Maintain reproducible local development and explicit database migrations. Prefer hospice-owned/administered on-premises infrastructure and internal-network access; final hosting, runtime and orchestration require hospice IT consultation. Local username/password authentication is confirmed for MVP behind a replaceable provider boundary; external identity integrations remain deferred. Do not assume internet-facing hosting. Read `docs/requirements.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/import-workflow.md`, and `docs/operations.md` for current requirements and proposed designs.

## Donor data and imports

8. Actual spreadsheets must be inspected in an authorized secure environment **before** finalizing fields, mapping rules, matching thresholds, or donor identifiers. Do not invent source columns.

9. Support individuals, businesses, and organizations. Keep donor profiles separate from individual donation/payment records.

10. Seed the canonical dataset from the hospice's existing main dataset, then reconcile additional sources into it.

11. Only demonstrably certain matches may be auto-linked. Uncertain cases require human review; unlinked donors can be created after validation. No silent overwrites of conflicting values.

12. Require import preview and approval before applying changes. Applying the approved import must be transactional. Record provenance and decisions; detect duplicate file submissions and avoid double-counting payments.

13. Record who imported which filename and when, plus outcome, row counts, and matching/review outcomes. Full donor change history must include actor, time, changed fields, and before/after values while minimizing exposure of sensitive data.

14. Support undoing the **most recent committed import only**, using import-specific compensating actions rather than restoring the entire database. Never overwrite later manual changes. On a conflict, stop and surface an actionable explanation.

15. Treat database backups as a separate recovery mechanism. Plan automated daily backups and a pre-import backup. Retention, encryption, storage, and restore drills require deployment decisions.

16. Archive donors (soft deletion) instead of routine hard deletion.

## Quality and safety

17. Include unit and integration tests from the beginning. Prioritize matching ambiguity, idempotence, payment deduplication, review decisions, atomic commit, undo conflicts, audit history, and permissions.

18. Never embed real personal data in fixtures, commits, screenshots, logs, exceptions, prompts, or telemetry. Use synthetic test data, least-privilege accounts, hashed passwords, secret management, and secure defaults.

19. Account for GDPR requirements and donor retention policies without inventing legal retention periods. Avoid putting unredacted personal data in application logs. Protect exports and backup files.

20. Every staff member initially has equivalent application capabilities. Accounts are individual, not shared. Use local username/password authentication for MVP, with stable internal users, provider-independent server sessions and application-owned authorization. Existing identity infrastructure remains unresolved; do not implement external integrations or self-registration in MVP.

21. Update relevant English documentation when a decision changes, and keep deferred functionality explicitly marked.

## Before writing code

For the first task, respond with a **planning-only architecture and bootstrap proposal**.

Include:

- Suggested dependencies and compatible versions.
- Confirmed source layout using `src/api`, `src/ui`, and `src/common`.
- Clojure and ClojureScript build configuration strategy.
- Shared Malli schemas and API contract organization.
- Backend module boundaries.
- PostgreSQL schema and migration strategy.
- Local development workflow.
- Unit and integration testing strategy.
- Milestone acceptance criteria.

Do not create application code until the user explicitly approves the proposal.

## Additional stakeholder constraints

- Keep donor identity and financial histories separate from correspondence recipients/groups; shared addresses and spouse/group membership do not justify donor or financial merges.
- Monthly/annual contribution summaries, tax-identification evidence and mailing CSV/XLSX exports are MVP requirements. Historical yearly field semantics remain OPEN; do not invent transactions or double-count overlapping sources.
- Use three source-specific adapters sharing normalization, matching, validation and persistence. Stakeholder column descriptions are preliminary until secure workbook inspection.
- `docs/operations.md` is the operational source of truth. Hospice organizational/GDPR requirements must be confirmed before real personal data is processed; development/tests use synthetic data.
- Documentation updates do not authorize application implementation or dependency installation. Preserve explicit milestone and dependency approval gates.
