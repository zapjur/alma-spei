# Incremental implementation plan

Every implementation milestone requires an outline, affected files, schema/API changes, exact dependencies/versions, tests, risks and unanswered questions, followed by explicit user approval. This roadmap is not permission to code. Current work ends with documentation.

## Milestone 0 — bootstrap proposal and secure discovery (NEXT; planning only)

Present the dependency/version compatibility proposal, source/build/test layout, modules, API conventions, local workflow, migration strategy and first scaffold plan. Clojure CLI and shadow-cljs are proposals; local PostgreSQL may be native or containerized, pending approval. In parallel, arrange authorized workbook inspection and document a sanitized field/semantics catalog; do not put actual personal values in prompts or the repository.

Affected artifacts: architecture/decision/requirements/data-model/operations documentation and a reviewed scaffold outline. No migrations, dependencies or application code yet. Acceptance: approval-ready exact tool versions, compatibility evidence, test matrix and affected-file list; explicit unresolved schema, attribution and IT/privacy questions; approved secure inspection arrangements. Risks: unavailable files, ambiguous financial semantics and unsupported hosting assumptions.

## Milestone 1 — approved development skeleton

Proposed affected files: `deps.edn`, `shadow-cljs.edn`, `src/api`, `src/ui`, `src/common`, `test`, `resources/migrations` and development instructions. Provide backend health API, Polish basic UI, representative shared Malli contract, PostgreSQL migration runner and unit/integration harness. Both builds include `src/common`; no fabricated production donor schema.

Acceptance: clean checkout follows documented start/stop/migrate/test commands; shared contracts execute on JVM/JavaScript; PostgreSQL integration tests pass. Exact commands and dependencies must be approved before implementation. No production exposure or real data.

## Milestone 2 — approved domain and security foundation

Use inspected files to approve schema, provenance/retention, source adapter contracts, matching/attribution/dedup rules, historical observations, group membership and report overlap rules. Approve local authentication/provider/session/authorization and account lifecycle designs. Implement only the approved subset, with migrations and API contracts reviewed first.

Acceptance: named accounts and backend permission checks; provider-independent internal principals; synthetic schema/normalization and persistence tests; no unresolved historical semantics silently converted into amounts.

## Milestone 3 — donors, correspondence and audit

Profile create/edit/list/detail, explicit merge/archive, separate recipients/groups and protected change history. Acceptance: shared addresses/groups never merge financial histories; mailing membership changes are auditable; manual changes validate and enforce authorization. Synthetic unit/integration tests cover conflicts and group exceptions.

## Milestone 4 — adapters, staging and review

Implement three adapters with common normalization/matching/validation, repeat-file detection, immutable/versioned preview and human review. Generate approved synthetic workbook fixtures and expected outcomes. Acceptance: no canonical writes during staging; uncertainty/conflicts shown; malformed/headerless inputs handled; repeat and overlap cases tested.

## Milestone 5 — atomic commit, undo, summaries and export

Transactional commit with provenance/audit, payment deduplication, latest-import atomic compensation, monthly/annual summaries, tax identification and mailing CSV/XLSX exports. Acceptance: failure/concurrency leaves no partial writes; later edits block conflicting undo; summaries use approved date/overlap rules; groups produce intended mail count without financial merging; secure authorized exports.

## Milestone 6 — deployment hardening and handover

Consult IT, approve topology/runtime, privacy policies and named owners. Rehearse install/upgrade/rollback, daily/pre-import backups and restore, incident handling and staff workflows. Acceptance: tested recovery objectives, monitoring/maintenance responsibilities, secure network/TLS and account controls, privacy approval before real data.

## Deferred unless approved

External OIDC/LDAP/SSO adapters, complex role hierarchy, self-registration, advanced analytics, public internet access and distributed infrastructure. Required monthly/annual contribution summaries remain MVP scope.
