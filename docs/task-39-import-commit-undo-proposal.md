# Task 39 — transactional import commit and latest-import compensation

Status: **APPROVED — the user explicitly approved this proposal with “apply” on 2026-11-15.**
Prepared 2026-11-15. Branch `trello/39-import-commit-undo`, based on
`trello/38-review-decisions` (`cc50767`). Assigned contributor: Piotr Zapiór.
Card: <https://trello.com/c/t3gffrjz>. No new dependency is proposed.

## CONFIRMED requirements and evidence

The card requests POST `/api/imports/commit`, import-batch attribution on created
profiles/payments, and POST `/api/imports/undo-last`. Its deletion of payments,
profiles and batch history conflicts with the approved AGENTS.md, task 34 and
operations requirements. Those require retained attempts/audit, routine donor
archiving, and import-specific compensation that never overwrites later work.
The proposal below follows those approved requirements; the card is not edited.

Task 38 provides immutable complete previews and versioned manual decisions.
It writes no canonical records. Runtime donations are currently immutable;
there is no payment-correction, import commit, compensation or backup service.
The existing catalogue contains independently managed donors/payments. Master
seeding remains the real-data prerequisite; bank-only synthetic development does
not authorize bypassing that requirement or processing actual donor data.

## APPROVED delivery boundary

Implement backend commit, provenance, immutable compensation records, read-only
outcome/eligibility APIs and explicit reimport after undo. Extend review contracts
and the existing editor only where required to bind financial evidence to current
canonical state. Final commit/undo confirmation and history screens remain #40.
No guessed matching, automatic duplicate suppression, donor merging, existing-profile
field overwrite, refunds, correspondence, reporting or new source adapters.

Use existing Clojure/JDBC/Malli/PostgreSQL dependencies. No package installation,
external backup command, production destination or backup freshness policy is
selected by this approval. Synthetic commits require an explicitly configured
synthetic backup adapter. Normal configuration fails closed without a real approved
backup provider; there is no request-level skip/force flag.

## APPROVED catalogue revision and concurrency contract

A selected-record version alone does not detect a newly added overlapping payment
or donor elsewhere. Introduce a monotonically increasing canonical catalogue
revision. Donor creation/edit/archive, payment insertion and payment compensation
advance it through database triggers; staging, proposals, decisions and previews do
not. Existing manual writes and the synthetic demo loader must join the protocol.
A database trigger also advances the revision for privileged maintenance writes;
maintenance outside application protocols is not a supported online workflow.

All application canonical writers acquire the same PostgreSQL transaction advisory
lock before reading/locking canonical entities. Review writes and preview creation
acquire it too. Order: authenticate/CSRF, catalogue gate, file, batch, selected donors
in sorted UUID order, proposals in sorted order, then `review-payment:<UUID>` locks
in sorted order. Services must never acquire the catalogue gate after a donor/file/
batch lock. Reads use consistent snapshots; no global lock spans user interaction
or backup execution. Audit-only appends do not themselves advance the catalogue.
Tests must cover the demo loader and manual donor update versus commit/undo, not
only two requests to the import service.

Decision GET/lookup responses expose current `catalog-version`. The editor captures
it when loading a row and submits that exact revision on save. Auxiliary reads must
not silently replace it. Persist `accepted_catalog_version` on complete decisions.
Every complete decision must match the current revision at preview and commit.
After any canonical change, staff explicitly reload/recheck/resave affected decisions;
preparing a fresh preview alone cannot renew old financial evidence. Incomplete
identity decisions may still be saved; they never count as ready. Creation of a
profile proposal must also supply the revision under which existing profiles were
checked. No-op equality includes accepted revision; an explicit re-review can
therefore create a new decision version/event even when its target/facts agree.

This deliberately conservative whole-catalogue invalidation can require re-review
of unrelated rows. It avoids inventing a payment identity or silently trusting old
overlap assertions. It is proposed for this small-staff MVP, not a claim of automatic
financial reconciliation. Exact equality remains insufficient to collapse payments.

Use preview rule `review-preview-v2`, binding the catalogue revision in the digest
and header. Retain v1 snapshots as readable history, but disallow committing them.
Existing complete decisions have no accepted revision after upgrade and require
explicit re-review. The shared contracts must accept historical v1 reads and show
fixed Polish staleness/blocker explanations; no silent data rewrite/backfill of
review evidence. The existing review UI needs this compatibility work in #39.

## APPROVED additive migration

Add a new migration pair; never edit migrations already applied by tasks 28–38.
Six new tables plus additive columns, constraints, triggers and a view:

| Object | Purpose and constraints |
| --- | --- |
| `canonical_catalog_state` | Singleton, positive revision; runtime SELECT and narrowly scoped revision UPDATE used by triggers. No DELETE. |
| `import_backup_runs` | Immutable server-generated backup receipt: UUID, batch/preview/digest/catalogue revision, requesting actor/time, adapter and policy version, success evidence, opaque artifact reference, completion/expiry timestamps. No credentials, paths or client-attested success. |
| `import_commits` | Immutable UUID; UNIQUE batch; UNIQUE monotonic commit ordinal; accepted preview/digest/revision and backup receipt; approving actor/time; resulting batch version and exact summary. Global serialization makes ordinal order authoritative; timestamps do not determine latest eligibility. |
| `import_commit_items` | Immutable mapping from every preview ordinal to its applied donor/payment or unchanged/duplicate reference. Composite batch/preview/ordinal FKs; typed donor/donation references, source locator, proposal mapping and exact accepted/result versions. Unique source-payment effect within a committed batch. |
| `import_undos` | Immutable UUID, UNIQUE original commit, undo actor/time, expected commit/batch version and resulting version, compensation counts. Original commit/items/events remain intact. |
| `donation_reversals` | Append-only compensation: UNIQUE donation FK, undo FK, original import FK, actor/time and fixed reason `import-undo`. Only a donation created by that original import can be compensated; enforce typed composite references and service validation. No negative invented payment, DELETE or donation UPDATE. |

Add nullable `import_batch_id` FKs to donors and donations. NULL denotes manual/demo
records; imported creates carry the originating batch ID. Existing selected donors
and duplicate-reference payments retain their original attribution. Do not relabel
an existing donor merely because a new import references it. Use composite unique
keys/FKs to tie new provenance mappings and reversal subjects to the correct batch.
Attribution is immutable through service contracts and restricted column grants;
narrow the existing broad donor UPDATE grant to approved mutable columns.

Add nullable accepted catalogue revision to decisions and nullable catalogue revision
to preview headers, preserving legacy records. Extend rule CHECK to v1/v2. Add
`active_donations` view selecting original donation facts without reversal records.
All normal payment reads, existing-payment selectors, duplicate-reference validation
and demo consistency checks must use active payment semantics. Raw originals and
reversal records remain available only through protected provenance/history APIs.
Avoid a second unfiltered financial path that could double-count undone payments.

New journal/provenance/backup/compensation tables grant runtime SELECT/INSERT only.
No runtime DELETE/TRUNCATE or immutable-journal UPDATE. Existing profile/audit grants
support audited archival; no new donor audit action is needed for archive. Payment
compensation audit is the typed reversal/undo ledger, not a donor-field mutation.
Down migration is disposable-test-only and must refuse when commit/undo/reversal
records exist; removing compensation filtering from a used database would silently
reactivate financial facts. Production recovery is a separately authorized operation.

## APPROVED backup boundary

POST `/api/imports/:id/previews/:preview-id/backup` authenticates staff, checks CSRF,
expected batch/catalogue versions and readiness, and requests the configured provider
against that exact preview. Execute the backup outside the canonical-write transaction.
After completion, recheck preview/version/catalogue state before persisting a usable
receipt. A backup that raced with a canonical change is unusable for that preview.
No database change is allowed just because a backup succeeded.

The provider returns server-verified success, opaque artifact identity, policy version
and an explicit expiry. Missing provider, failure, expiry or mismatched binding blocks
commit. Real providers must verify artifact completion/integrity and follow an IT-
approved policy; a staff checkbox or arbitrary client string is not backup evidence.
Infrastructure administration remains outside ordinary staff application permissions.

For this task only, implement a clearly labelled `synthetic-test-v1` adapter with a
five-minute receipt lifetime as a development/test constant, never a production
policy. It creates no real dump and claims no recoverability. Activation requires
explicit synthetic-mode configuration in the already restricted development/test
runtime; default is disabled. Receipts/outcome DTOs label the adapter/synthetic status
so #40 can explain the limitation. Real-data and production processing remain blocked
until approved backup/privacy/hosting integration. Tests can inject failure/expiry
without sleeping. Do not execute shell commands built from HTTP input.

## APPROVED atomic commit

POST `/api/imports/commit` consumes a closed map containing batch ID/version,
preview ID/effect digest, catalogue revision, backup receipt ID and `confirmed: true`.
It does not accept target profiles, source amounts, actors, effect counts or a backup
success assertion. Require staff session, completed password change, exact Origin,
signed and session-bound CSRF, 16 KiB JSON limit and safe Polish errors.

Under the global gate and file/batch/target locks:

1. Verify this bank batch is still in review, the supplied v2 preview belongs to it,
   the expected versions/digest match and the backup receipt is successful, unexpired
   and bound to the exact current preview/catalogue/policy.
2. Revalidate source row counts/locators, every complete decision, active target and
   payment version, explicit financial facts/overlap evidence, proposal profiles and
   the full deterministic effect digest. Use active payment references. A new payment
   elsewhere invalidates evidence through the catalogue revision; it is not silently
   classified as duplicate. Invalid/negative rows remain blocking.
3. Record approval as an attributed lifecycle event, create each referenced proposal
   donor once with audited field values, insert every new payment separately with
   import attribution, and map duplicate/existing references without modifying them.
4. Write commit header/items, all canonical/audit/provenance records and committed
   lifecycle metadata in the same SQL transaction. Approval and committed transitions
   are atomic: review -> approved -> committed, each with its versioned event. A failed
   commit leaves the batch in review with no durable approval or partial business write.

Generate stable canonical IDs within the transaction and persist their proposal/source
mapping. A lost response cannot cause duplicate insertion. An exact repeat of the
same successful commit request returns the original receipt, without additional
writes; mismatched preview/approval context returns 409. Replaying a commit that has
since been undone returns an already-undone outcome, never reapplies it. Failed
transactions leave no success receipt and may be explicitly retried after checking
state. No automatic client mutation retry is introduced.

Only the originally verified receipt permits replay after the ordinary current-version
check would otherwise fail. Always reauthenticate, check CSRF and compare the exact
stored request context. Expose current batch state alongside the immutable receipt.
Canonical revision increases caused by this transaction do not invalidate its own
preflight; no other canonical writer can interleave before it completes.

## APPROVED latest-only undo

Latest means the highest successfully committed ordinal **ever**, including an import
already undone. After undoing B, A does not become eligible: single-step undo cannot
walk backward through history. A later new commit C establishes the next eligible
head. This resolves task 34's explicitly OPEN earlier-import policy conservatively.

POST `/api/imports/undo-last` requires expected batch ID/version, commit ID and
`confirmed: true`. Under the same gate/lock order, check that exact commit is the head
and has no undo. Preflight all effects before any compensation:

- Imported donors must still match their recorded post-create version/state/fields;
  later edits or archive actions block the entire undo. Check accepted versions of
  existing referenced donors too, conservatively rejecting intervening profile edits.
- Imported payments must still be active and match original donor/date/amount/currency/
  version/batch attribution. Never reverse an explicitly referenced pre-existing payment.
- An import-created donor must have no payments beyond this import's own new payments,
  no later canonical/provenance dependency and no other batch's live review decision
  referencing it or its payments. Future correspondence/merge/evidence modules must
  extend the dependency check before they are enabled; unknown dependencies fail closed.
- Changed or new references produce bounded, paged conflict codes/entity locators and
  fixed Polish guidance. Never advise discarding later work to force eligibility.

On success append one reversal per imported payment, archive each unchanged imported
new donor with normal version increment and actor/time/before-after audit, append
undo and lifecycle records, and mark the batch undone. Leave existing profiles and
pre-existing payments intact. Preserve source, decisions, previews, commit mappings,
original payment records and audit history. All compensation is one transaction;
any failure/conflict leaves everything unchanged. There is no snapshot restore.

An exact repeat returns the existing undo receipt without further changes. A repeat
with different context is a conflict. Undo does not require a pre-import backup
receipt; operational daily backup/recovery requirements remain separate. No unaudited
hard-delete permission or force flag is added.

## APPROVED reimport and read APIs

Extend the existing explicit versioned multipart retry flow to the latest successfully
undone attempt of the same file. Verify original SHA/length/source, confirm intent,
keep old history, create a successor attempt and restage uploaded bytes. Never copy
old decisions, currency assertions, proposal targets, approval or backup evidence.
Fresh review/preview/backup/confirmation are required; archived originals are visible
for comparison and are never silently revived. Concurrent successors serialize on the
file and existing uniqueness constraints. Ordinary identical-byte upload remains 409.

Read-only APIs for #40: bounded GET `/api/imports` history; GET
`/api/imports/:id/outcome`, `/commit-items` and `/undo-eligibility` with paged blockers.
Responses expose current batch version/state, immutable commit/undo summaries,
review/approval/commit/undo actors/times, source provenance, explicit per-currency
counts/totals, backup adapter status and latest-head eligibility. GET eligibility is
advisory; POST undo repeats all checks. Reversal filtering changes normal donor payment
counts/history, while these protected APIs retain original/reversed effect details.
No new commit/undo UI in this task. Existing preview/review/history reads remain usable.

## Affected files and verification

- New additive migration pair and disposable downgrade/grant/constraint tests.
- `src/api/.../imports/{commit,undo,backup,execution_repository}.clj` and a small
  backend catalogue gate/revision module; keep HTTP, services and persistence separate.
- Extend HTTP import routes, retry/upload path, Integrant/configuration and closed
  portable execution/decision/preview contracts under `src/common/**/*.cljc`.
- Update donor/manual-write and demo services for lock order; all payment read/reference
  repositories for compensation filtering. Crypto/JDBC stay backend-only.
- Update the current review editor/effects for captured catalogue versions and legacy
  preview staleness. No new frontend/backend dependency. All user messages Polish.
- Update development, data model, import workflow, operations and decisions docs;
  preserve real-data and deployment gates.

Tests: portable contracts on JVM/JS; migration upgrade with legacy previews/decisions;
privilege and NULL/FK constraint enforcement; duplicate-reference versus genuine equal
payments; repeated/lost-response commits and undo; shared proposal creates one donor;
canonical/provenance/audit/counter fault injection at each phase; competing commits,
undo, reimport, manual edits and demo writes; newly added overlap invalidation; backup
failure/expiry/misbinding/races/default-disabled mode; later donor/payment changes and
new references blocking all undo; only latest-ever eligibility; compensation excluded
from every normal payment path; exact totals without mixing currencies; provenance
retained after undo; reimport creates fresh attempt/decisions; authorization/CSRF/body
limits, safe errors and full HTTP round trip. Browser checks cover only the necessary
existing-review compatibility changes; final confirmation UI is #40.

Acceptance: `bb check` and `bb integration` pass on the pinned JDK, no partial canonical
writes under failures, no double apply under races/replays, no loss of later work,
immutable history/provenance retained, and no active financial double-counting after
undo/reimport. Use synthetic data exclusively. No tests/dependency installation are
needed for this planning document; run `git diff --check`.

## Risks, OPEN decisions and approval record

This is larger than the card's nominal four-hour estimate: safe undo affects every
payment read and canonical writer, not just two endpoint handlers. Global serialization
and catalogue-wide re-review favor correctness over throughput; measure before any
future relaxation. Append-only compensation retains protected personal data; retention
and lawful handling remain hospice decisions. Future modules need dependency checks
and the same writer protocol. No policy for historical yearly fields, refunds, actual
source overlap, daily backups, encryption/storage or production restores is invented.

The user approved this synthetic implementation package: six-table/additive schema, v2 catalogue-
bound review/preview compatibility, explicit default-disabled synthetic backup adapter,
atomic commit/provenance, append-only payment reversals and donor archiving, conservative
latest-ever-only undo, and fresh-attempt reimport. Production backup adapter and policy,
real evidence/matching standards and operational/privacy readiness remain OPEN.

## Delivery notes — 2026-11-15

Implemented the sixth migration, catalogue gate/revision triggers, v2 review/preview
compatibility, protected execution/read APIs, synthetic backup receipts, atomic
canonical/provenance/audit writes, compensation and fresh-attempt reimport. No new
dependency. No production backup command, storage choice or real-data authorization.

Runtime donation rows remain immutable. `active_donations` consistently excludes
reversals in donor pages, payment lookup/reference validation and demo consistency
checks. Applied-item composite FKs distinguish newly created subjects from unchanged
references. Exact same-payment references in multiple source rows remain permitted;
new payments always have separate source effects. Undo also checks other batches'
live references to imported payments on pre-existing donors, not just new donors.
Unknown donor/payment FK modules fail closed until their dependency policy is added.

Replay compares the complete original request context after authentication/CSRF and
returns the immutable receipt with current batch state. A stored undo receipt never
reactivates an earlier commit. Only a successfully compensated latest file attempt
with freshly verified bytes can be reimported. No old review/approval is copied.

Backup execution releases catalogue/file/entity locks before running the provider,
then authenticates and revalidates again. Receipts record original requesting actor/
time and completion/expiry; future, expired or malformed provider results are rejected.
The only implemented provider is explicitly labelled `synthetic-test-v1`, disabled
by default and enabled by `ALMA_SYNTHETIC_IMPORTS=true` in development/test settings.
It creates no recoverable backup. Real provider integration remains a separate gate.

The existing review editor captures catalogue revision on row load. Neither auxiliary
reads nor a proposal-edit response can renew its old financial evidence. Legacy v1
preview/decision data stays readable and explicitly requires new review before commit.
Final confirmation/history/undo UI remains #40; no misleading controls were added.

Chrome acceptance used the optimized UI, real loopback HTTP and a disposable synthetic
PostgreSQL database. It passed explicit proposal/target/currency/overlap choices,
proposal reuse, save/reload, dirty-navigation cancellation, v2 previews/history/blockers,
keyboard focus, 390px layout without horizontal overflow, logout purge, and direct
same-origin API backup/commit/exact replay/undo/outcome checks. Post-run invariants:
two donor records (one original and one archived imported profile), two retained
original payments, zero active imported payments, one commit, one undo and two
reversals. The database and private temporary browser session material were removed.

Verification used the pinned Temurin JDK 25.0.5+8. JavaScript checks/browser automation
used available Node 23.11.0; pinned Node 24.20.0 was not independently verified locally.
Upstream logging/Unsafe and optional test source-map-support notices remain; no new
library was introduced to suppress them. Final automated counts follow below.

Final checks: `bb check` passed lint/format, **46 JVM tests / 381 assertions**, the
reload lifecycle test / 10 assertions, **78 JavaScript tests / 509 assertions**, and
the optimized build with zero compiler warnings. `bb integration` passed **87 tests /
731 assertions**, covering upgrade/guarded downgrade, legacy evidence, grants,
transaction faults including a second-row failure, exact replays, distinct competing
imports, multi-user manual edits, demo-loader serialization, backup failure/expiry/
races, later profile/payment/dependency conflicts, latest-ever undo, real HTTP and
fresh reimport. No failures/errors. `git diff --check` passed.
