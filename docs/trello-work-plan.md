# Trello delivery plan

Planning snapshot: 2026-11-07, [MPT Projekt 2a](https://trello.com/b/hzhvBlKh).
The user subsequently instructed execution of this plan. Proceed with the ordered
tasks where their prerequisites are satisfied. Missing discovery evidence and
unresolved exact schema/dependency decisions remain OPEN; this approval does not
authorize inventing source semantics, production processing or deployment.

## Status and delivery rules

CONFIRMED by the user:

- Authentication is merged. Start the first task from updated `main`.
- Work sequentially, with one branch per task, each based on its predecessor.
- Attribute task commits to the assigned contributors using the identities supplied
  in this session. Apply the user's assignment correction without dropping tasks.

PROPOSED:

- Preserve Sprint Backlog order (#28–41). Each PR targets the preceding task
  branch; the first targets `main`. Include card URL, scope, validation and blockers.
- Finish and verify a task before starting the next. Review may remain pending
  while the next branch builds on it; a card is not Done until team acceptance.
- Merge from the bottom of the stack. After a parent merges, retarget/rebase its
  child to updated `main` as needed, preserving only that child's changes. Agree
  any published history rewrite before force-pushing; use force-with-lease.
- Approval is per delivery group below, not implicit in a Trello assignment.
  Unresolved schema/dependency decisions retain their own gates.
- Keep supporting tests with their feature branch. #41 adds end-to-end integration
  coverage rather than postponing tests until the end.
- Do not invent sprint calendar dates from relative day numbers. Board estimates
  are planning inputs, not commitments; missing backend/audit work increases scope.

Execution start: fetched `origin` and verified `main` and `origin/main` have zero
divergence at `c6dcc0b` (merged authentication). Created
`trello/28-donor-migrations` from that base, preserving the planning documents.

### Task #28 prerequisite check

The repository has no sanitized source catalog. Rechecked inspection card #9:
all three workbook checklist items are complete, but the card has no comments or
attachments containing inspection findings. This establishes reported completion,
not the fields/formats/semantics needed to write the migration.

The user subsequently identified the workbooks in local Downloads. A structural
inspection now appears in [source-inspection.md](source-inspection.md), without
donor values. The concrete [#28 schema proposal](task-28-schema-proposal.md) is
was explicitly approved by the user. Migration `202611070001-donors` and synthetic
schema tests implement #28; financial source semantics remain unresolved and do
not become inferred payment facts.

## Attribution

Use the Git author configured for the actual commit. Add one
`Co-authored-by: Name <email>` trailer for each other assigned contributor; do not
duplicate the primary author as a co-author. For a task assigned only to the
primary author, the author field provides attribution. Use English commit subjects
with the card reference. These addresses were supplied/confirmed by the user;
GitHub account linkage is not independently verified.

| Key | Name for attribution | Email |
| --- | --- | --- |
| P | Piotr Zapiór | Existing repository Git identity; verify before committing |
| S | stas | User-supplied email available in session; use the user-confirmed Trello name |
| J | Jakub Płoskonka | User-supplied email available in session |
| W | Paweł Piwowarczyk | User-supplied email available in session |
| A | Antoni Lenart | User-supplied email available in session |

Keep the email mapping in the execution session/local Git metadata rather than
adding a second permanent copy to technical documentation. Additional unassigned
product items need owners before commit attribution can be determined.

## Prerequisites and conflicts to resolve

1. **OPEN — discovery evidence.** [#9](https://trello.com/c/kHZIlgGJ) is Done in
   Trello, but `data-model.md` still records discovery as OPEN. Obtain the sanitized
   field/format/semantics catalog from the authorized inspection and reconcile the
   documentation. Never send donor rows or actual workbook contents into prompts.
   This gates final domain migrations and source parsers, not planning.
2. **OPEN — donor schema.** #28 proposes two entity categories, while confirmed
   requirements cover individuals, businesses and organizations. Approve whether
   these are three types or separately represented categories. Approve profile
   requirements, foreign addresses/postcodes, money/date/currency and financial
   identity rules. Do not silently adopt the card's preliminary columns.
3. **CONFIRMED requirement / PROPOSED card correction — undo.** #39/#41 require
   deleting batch history. Preserve batches, provenance and audit instead; record
   compensation and stop atomically on later edits/dependencies. Approve revised
   acceptance criteria before coding those cards. Reimport after undo and whether
   an earlier batch ever becomes eligible are OPEN; no implicit cascading undo.
4. **OPEN — bank layout.** #35 lists date/amount/sender/title, whereas repository
   preliminary notes list date/sender/title/amount. Inspection decides the adapter
   mapping. No positional guess, inferred sender identifier or matching threshold.
5. **PROPOSED — manual-first review.** Every valid bank row requires a decision
   during this sprint, consistent with PBI-3. Later certain-only auto-linking is a
   separate backlog delivery. Backend validation remains authoritative throughout.
6. **OPEN — import policy.** Approve invalid-row correction/rejection rules,
   hash reservation/retry/reimport lifecycle, overlap deduplication, review ownership,
   staging retention, upload limits and preview expiration. A byte hash alone does
   not identify repeated payments in different files.
7. **OPEN — operating environment.** `operations.md` controls privacy, backup,
   deployment and real-data gates. Synthetic local implementation does not permit
   real imports. No real canonical bank reconciliation before the master seed.
8. **Documentation conflict.** `tech-stack.md` describes automatic deployment and
   fuzzy search, whereas approved foundation CI does not deploy and matching rules
   remain OPEN. Reconcile it in a documentation task; it is not implementation
   authority for either behavior.

## Architecture and dependency strategy

Retain the existing modular monolith and exact manifest pins. Both Clojure CLI
and shadow-cljs already include `src/common`; preserve their aliases/source paths.
Keep portable Malli schemas, DTOs, error codes and pure validation in
`src/common/alma_spei/common/{contracts,domain}/` as `.cljc`. Keep database records
distinct from public DTOs, and money representations portable/exact across JVM/JS.

Backend module candidates are `donors`, `audit`, `imports` (adapters, staging,
review, preview, commit/undo) and later `correspondence`, `reports`, `exports`.
Separate HTTP handlers, application services and JDBC repositories. Reuse stable
authenticated actors, server sessions, CSRF and app-owned authorization. Keep
IO, database access and authoritative security out of common code.

Reuse existing next.jdbc 1.4.72, HoneySQL 2.7.1437, PostgreSQL JDBC 42.7.14,
Migratus 1.6.8, Reitit 0.11.0, Malli 0.20.3, Reagent 2.0.1 and re-frame 1.4.7.
These are existing pins, not new version recommendations. No dependency changes
are proposed for #28–34 or the frontend tasks. JDK MessageDigest supplies SHA-256.

CSV/XLSX parsing/writing is not covered by installed libraries. Before #35,
prepare an exact-version proposal comparing maintained CSV tooling and Apache
POI-based XLSX options, covering transitive dependencies, license, security,
streaming/resource limits, date/formula handling and JDK compatibility. Verify
with primary documentation and a synthetic compatibility check after approval.
Do not implement an ad hoc CSV parser or install unspecified versions. Pooling,
browser test tooling and backup tools likewise need explicit dependency decisions
if required; none is assumed by this plan.

Apply additive, ordered SQL migrations without editing the applied foundation/auth
migrations. Include explicit runtime grants, constraints, indexes and migration
tests. Down migrations are for disposable test databases, not production recovery.
Domain audit/versioning must exist before the first profile mutation.

## Ordered sprint branches

Each row's parent is the previous row; #28's parent is `main`.
Contributor keys refer to the attribution table.

| Order / card | Branch | Contributors | Outcome |
| --- | --- | --- | --- |
| 1 / [#28](https://trello.com/c/JphaSLox) | `trello/28-donor-migrations` | J, P | Approved donor/payment schema, versioning and domain audit foundation |
| 2 / [#29](https://trello.com/c/QILMX8ZC) | `trello/29-donor-contracts` | P | Portable create/update/query/detail contracts and Polish validation |
| 3 / [#30](https://trello.com/c/34HNQj2F) | `trello/30-donor-api` | P | Authorized, audited donor persistence and REST API |
| 4 / [#31](https://trello.com/c/gZjDYXcp) | `trello/31-donor-list` | S, P | Search/filter/sort donor table |
| 5 / [#32](https://trello.com/c/sAS4orVO) | `trello/32-donor-details` | S, P | Detail, payment history, edit form and change history |
| 6 / [#33](https://trello.com/c/OAP4BkiG) | `trello/33-synthetic-data` | J, W | Deterministic 20-profile/30-payment development dataset |
| 7 / [#34](https://trello.com/c/8pPjTE9S) | `trello/34-import-batches` | J, P | Import lifecycle, fingerprint guard and persistent staging foundation |
| 8 / [#35](https://trello.com/c/XgGFFatr) | `trello/35-bank-parser` | P | Bounded bank adapter and authenticated upload API |
| 9 / [#36](https://trello.com/c/YPDhqEOO) | `trello/36-import-upload` | S, P | Polish upload/error flow |
| 10 / [#37](https://trello.com/c/TP1JkFVy) | `trello/37-review-queue` | S, P | Side-by-side review with server-backed queue |
| 11 / [#38](https://trello.com/c/ZmwbACAK) | `trello/38-review-decisions` | P | Persisted linking/new-profile decisions and approved-preview preparation |
| 12 / [#39](https://trello.com/c/t3gffrjz) | `trello/39-import-commit-undo` | P | Atomic commit and conflict-safe latest-import compensation |
| 13 / [#40](https://trello.com/c/USpYwY8K) | `trello/40-import-summary` | S, P | Explicit confirmation, import history and undo feedback |
| 14 / [#41](https://trello.com/c/AtjqCzWk) | `trello/41-import-integration` | A, W | Whole-workflow, concurrency, audit and conflict verification |

The empty #27 task template has no executable acceptance criteria; leave it out
of the implementation stack. INFO cards are guidance, not feature tasks. Done
cards are evidence to reconcile, not tasks to implement again.

### Group A — donor foundation (#28–30)

**#28:** Draft the inspected-field schema before migration approval. Proposed tables
are separate donors, donations and domain audit events/changes, with stable IDs,
archive state and concurrency versions. Preserve donor foreign keys with RESTRICT,
exact money and approved period/currency fields. Do not infer payment identity from
amount/date/name. Files: new `resources/migrations/*-donors.{up,down}.sql`,
`test/integration/.../donors_test.clj`, data-model/decision documentation.
Acceptance: fresh and existing-auth databases migrate; constraints, Polish text,
indexes, grants and test-only rollback work; auth data survives the upgrade.
Risk: proposed fields and financial semantics are not yet approved.

**#29:** Add `common/domain/donors.cljc` and `common/contracts/donors.cljc`, extend
shared validation, and add `test/common/.../donors_test.cljc`. Define closed request
and explicit response DTOs, bounded query parameters, stable errors and entity
version fields. Polish postcode validation applies only under the approved address
policy. Acceptance: identical JVM/JS results for missing fields, whitespace,
diacritics, type-specific names, unknown keys and invalid input; no JDBC/browser APIs.
No schema migration; no new dependency. Risk: required-field/type policy needs sign-off.

**#30:** Add `api/donors/{repository,service}.clj`, `api/http/donors.clj` and
`api/audit/` support; wire existing routes/system and shared contracts. Proposed
API: GET/POST `/api/donors`, GET/PUT `/api/donors/:id`, protected change-history
read API. Bound/paginate results and allowlist sorting; parameterize all SQL.
Writes atomically record actor, time and before/after changes; optimistic versions
reject stale edits. Acceptance: 200/201, Polish 400 errors, 404 and 409 behavior,
authentication/CSRF denial, immutable payment history, audit and transaction rollback
tested via unit and PostgreSQL HTTP/service integration tests. Risks: Polish search
collation semantics and expected volume require agreement; no new pooling dependency.

Group acceptance: approved schema/contracts, migrations and grants verified, no
unauthorized writes, no unaudited profile mutation, portable tests pass.

### Group B — donor UI and synthetic population (#31–33)

**#31:** Add `ui/donors/{events,subs,views}.cljs`, wire navigation/effects, update
`resources/public/css/app.css`. Consume #30 query contract; use re-frame state.
Acceptance: table/search/type/archive filters and reversible sorting; Polish
loading/empty/error states, bounded paging and stale-request handling. Test state
transitions and API effects in `test/ui`; manually check browser keyboard/focus and
Polish diacritics. No schema or API change. Risk: don't collapse organizations into firms.

**#32:** Extend donor UI with detail/payment/change-history panels and edit form.
Use #29 shared validation and #30 version checks; never edit payment facts through
the profile form. Acceptance: row opens correct profile; invalid input blocks save;
server errors remain visible; success refreshes details/list; stale edits show a
conflict. Add UI state/error tests and browser acceptance. No new migration.
Risk: display only approved necessary profile/audit fields.

**#33:** Extend `bb.edn` and `dev/alma_spei/dev/` with a development-only seed entry
point and synthetic fixture definitions. Generate 20 profiles across approved types
and 30 payments using deterministic invented values, including shared-address and
same-name counterexamples. Acceptance: one command, repeat-safe transactional load,
explicit development/test environment check and fixed expected counts; never clear
unrelated data. Verify in an isolated PostgreSQL database; document in development
guide. No production seed/mapping claim and no new library. Earlier tasks use small
synthetic fixtures of their own, so this task is not a prerequisite for their tests.

Group acceptance: donor browse/edit workflow works in Polish, audit is visible to
authorized staff, and repeatable demo data is isolated from real-data environments.

### Group C — staging and bank upload (#34–36)

**#34:** Add migration(s) for batches, file fingerprint reservation, staged rows,
review/version metadata and import operation references needed by later work.
Add `api/imports/{repository,service,fingerprint}.clj` and shared import contracts.
Capture filename, source, actor/time, lifecycle/outcome and row metrics. Persist no
canonical mutations. Acceptance: renamed identical files and concurrent submissions
cannot bypass guards; failed parsing/retry follows an approved lifecycle; grants and
audit linkage tested. Files include integration tests and import/data-model docs.
Risk: permanent UNIQUE hash and reimport-after-undo policies must be reconciled first.

**#35:** After catalog and exact parser dependency approval, add
`api/imports/adapters/bank.clj`, parser limits, `api/http/imports.clj` upload route
and portable row/error DTOs. Proposed POST `/api/imports/upload` returns batch ID,
version and paged row/error information; duplicates return 409. Retain row 1 for
headerless inputs, exact decimals and inspected date conventions. Store bounded
staging with source row locators; no donor/payment writes. Acceptance: synthetic
CSV/XLSX cover first row, blank/invalid cells, formulas, malformed/oversized files,
unsupported structures and resource exhaustion; authorization and multipart CSRF
are tested. Adjust the current global 16 KiB JSON body limiter specifically for
bounded upload handling without weakening other routes. Financial overlap policy
is separate from file hashing. No automatic sender identity/address inference.

**#36:** Add `ui/imports/` upload state/effects/views with allowed file selection,
progress, duplicate errors and per-row validation report. Acceptance: failed uploads
recover; server rejects remain authoritative; invalid rows cannot disappear on the
way to review; Polish browser flow tested. Reuse #35 API; no migration/dependency.

Group acceptance: bounded upload and repeat-file detection work, invalid rows are
accounted for, and canonical tables remain unchanged throughout staging.

### Group D — persisted manual review (#37–38)

**#37:** Add paginated GET batch/row review APIs under `/api/imports/:id` and
side-by-side queue UI. Backend owns queue/version/status; re-frame mirrors it rather
than making a local ratom authoritative. Acceptance: raw permitted source values
and candidate profile display together, progress reflects saved decisions, reload
resumes the batch, unauthorized access fails. Add DTO/service/UI tests. Migration
only if #34's approved schema needs an additive adjustment. Risk: multi-user editing
policy and exposing source values must be approved.

**#38:** Add shared decision/preview contracts, review service and proposed PUT
`/api/imports/:id/rows/:row-id/decision`, POST `/api/imports/:id/preview`.
Persist reviewer/time/version and explicit link or validated new-profile proposal.
New profiles remain proposals until commit; record field conflicts without silent
overwrite. Preview binds versions and full planned effects/counts to an approval
token or equivalent approved mechanism. Acceptance: missing/invalid decisions block
preview, duplicate/new-profile ambiguity surfaces for review, stale/concurrent edits
return conflicts, and there are no canonical writes. Add unit, JS and PostgreSQL
tests. Risk: these backend pieces are absent from the frontend-only card wording;
their expanded scope and exact API require approval.

Group acceptance: decisions survive reload, all rows are accounted for, and the
server can produce a complete versioned preview without applying it.

### Group E — commit, compensation and full verification (#39–41)

**#39:** Add `api/imports/{commit,undo}.clj`, HTTP routes and any additive operation/
version migrations. Proposed POST `/api/imports/commit` consumes batch/preview
identity and explicit approval; POST `/api/imports/undo-last` targets the expected
latest batch. Serialize eligibility checks using an approved PostgreSQL locking
design. Revalidate auth, preview/entity versions, every row, financial deduplication
and backup policy before applying. One transaction writes canonical changes,
provenance, audit and compensation metadata. Undo preflights every dependency and
compensates only that batch, preserving history and later edits; any conflict stops
everything. Acceptance: fault injection at each write stage, repeated requests,
competing commits/undo, stale previews, edited donors/payments, new references and
nonlatest batches covered by integration tests. No destructive batch-history delete.
Risk: production requires real pre-import backup evidence; synthetic development
may use an explicitly labelled test backup adapter, never a production bypass.

**#40:** Add final preview/confirmation, outcome/history and undo UI. Proposed
GET `/api/imports` plus detail reads expose filename, actor/time, state, counts and
eligibility with reasons. Include all donor-field changes and financial effects,
not just totals; avoid summing incompatible/unapproved currency/period data.
Acceptance: confirmation sends exact approved preview, double clicks are safe,
stale approval refreshes preview, undo requires confirmation and reports actionable
conflicts, and original import/undo remain visible. Reimport behavior follows the
approved policy, not the card's assumption. Add state tests and browser checks.

**#41:** Add `test/integration/.../imports_test.clj` and independent synthetic
CSV/XLSX fixtures/expected results; extend `dev/.../test_runner.clj` suite registration.
Exercise login → upload → review → preview → approve → commit → history → undo.
Assert correct profiles, exact amounts, provenance and actors, not just row counts.
Business state returns to its prior state only where safe; audit/batch history is
expected to grow. Include different-file payment overlap, genuine equal payments,
invalid data, missing decisions, revoked sessions, stale entities, transaction
failures, concurrent attempts and later-edit undo conflicts. Acceptance: `bb check`
and `bb integration` pass, relevant authenticated HTTP/browser smoke is recorded,
and every revised card criterion has evidence. No new test dependency assumed.

Group acceptance: entire synthetic workflow is atomic, repeat-safe, audited and
conflict-safe; this does not declare the full MVP or production readiness complete.

## Remaining Product Backlog coverage and proposed continuation

The first four product cards are parents of #28–41, not four additional duplicate
implementation branches. The following order is PROPOSED after the sprint stack.
Unassigned cards retain OPEN ownership; no co-author is invented. Split each larger
card into approved concrete child tasks before implementation, with one stacked
branch per child. Names below are branch families, not authorization to bundle tasks.

| Card | Coverage / next work | Proposed affected files, schema/API and acceptance |
| --- | --- | --- |
| [#11 donor register](https://trello.com/c/JoBkej8A) | Parent of #28–33 | Accept only after full donor UI/API criteria and audit coverage pass. |
| [#14 uploads](https://trello.com/c/5D0SHUEk) | Parent of #34–36 | Accept bounded inspected XLSX upload with repeat-file and validation evidence. |
| [#22 manual review](https://trello.com/c/zwGxHIpo) | Parent of #37–38 | Accept persisted decisions and complete preview; backend coverage is essential. |
| [#16 safe undo](https://trello.com/c/Mh4BpFgl) | Parent of #39–41 | Resolve wording and accept preserved history plus atomic conflict handling. |
| [#10 accounts](https://trello.com/c/fjOnvklY) | Already merged; reconcile evidence/status | Check against `milestone-2-auth.md`; Secure-cookie production acceptance waits for TLS/IT approval. Do not rebuild authentication or claim production sign-off. |
| [#24 master and tax adapters](https://trello.com/c/dxyzcULG) | First continuation: `trello/24-*` | Add separate master/tax adapters, shared staging contracts and migrations for approved observations/tax evidence; extend upload/review UI. Master seed precedes bank/tax reconciliation with real data. Test master overlaps, annual ambiguity, taxpayer/spouse separation and no invented payments. Split master seed and tax attribution into separate tasks. |
| [#23 automatic matching](https://trello.com/c/5RiJprCZ) | Next: `trello/23-*` | Add portable normalization/evidence rules plus backend candidate service and versioned decision evidence. Approve inspected rules before code. Unit/property/integration tests prove ambiguous names/shared addresses never auto-link and payment dedup remains separate. No guessed numerical confidence threshold. |
| [#15 review automation](https://trello.com/c/6PesaBJM) | Then: `trello/15-*` | Reconcile overlap with #22/#23; extend existing queue to show auto-link reasons and uncertain candidates. No duplicate review screen/engine. Approve remaining distinct criteria, APIs and tests first. |
| [#12 merge/archive](https://trello.com/c/zZDnzSir) | Then: `trello/12-*` | Add explicit archive/merge service, protected preview/confirm APIs and UI; additive lineage/version metadata. Preserve distinct payment facts, resolve fields explicitly, retain audit and flag undo dependencies. Test same-address nonduplicates, stale merge previews, finance preservation and permissions. Split archive and merge tasks. |
| [#13 correspondence](https://trello.com/c/eF54uJ8n) | Then: `trello/13-*` | Add recipients/groups/membership tables, common contracts, backend API and Polish management/preview UI. Require explicit membership and approved mailing names. Tests cover distinct households at one address, spouses and unchanged financial histories; address equality alone never groups or merges. |
| [#17 financial summaries](https://trello.com/c/SymNhEiZ) | Then: `trello/17-*` | Add reporting service/API and monthly/annual UI plus tax-evidence display. Approve date/currency/period/overlap rules; keep unresolved observations separate. Test independent expected totals, refunds if approved, overlapping sources and unknown versus negative tax evidence. Split reporting rules and views into reviewable tasks. |
| [#18 mailing exports](https://trello.com/c/FAiNtyqb) | Then: `trello/18-*` | Add shared selection/export contracts, protected CSV/XLSX download service and preview UI. Approve templates and exact writer libraries first. Tests cover recipient counts, group exceptions, formula injection, permissions, expiry/cleanup and no financial merging. Split preview/CSV/XLSX tasks as appropriate. |
| [#19 backups](https://trello.com/c/nvc3fzrf) | Design in parallel with planning; implement before real imports: `trello/19-*` | After IT decisions, add protected daily/pre-import backup tooling and evidence checks, deployment/runbook configuration and restore rehearsal. No assumed cloud/orchestrator. Test failed backups block real commit and prove restoration in an authorized environment; retention/encryption/storage/RPO/RTO/owners remain OPEN. Split automation, commit integration and restore rehearsal. |

All continuation groups require an outline with precise child branches, owners,
files, schema/API diffs, exact dependencies, tests and risk decisions for approval.
Deployment hardening/handover remains required by the roadmap even though the board
does not yet contain concrete cards for all TLS, monitoring, privacy and ownership
work. Propose missing cards instead of declaring them delivered under backups.

## Verification and execution handoff

Use the documented local `bb init`, database/migration, backend and UI workflow.
Register new test namespaces in the existing JVM runner; portable common tests
also run in the Node build. Run focused tests during implementation, then required
`bb check` and PostgreSQL integration checks for each relevant branch. Do not run
watcher/release builds concurrently against their shared output directory.

Record manual Polish UI, keyboard and error-flow acceptance where Node tests do
not exercise the browser DOM. Use synthetic data only. No new tests are needed
for this planning document itself; validate links/coverage and `git diff --check`.

Next approval package: Group A plus the sanitized discovery/schema decisions.
Approval of this plan does not resolve missing workbook evidence, install parser
libraries, authorize later milestones, or approve production processing.
