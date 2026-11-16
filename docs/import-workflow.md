# Import, matching and rollback workflow

Current development delivery: [automatic reconciliation](automatic-reconciliation.md)
supersedes manual review of every transfer. Certain matches and clear new profiles
are staged automatically under explicit file settings; only exceptions require
human decisions. The full summary and explicit transactional approval remain.

## Confirmed interaction contract

1. The hospice's primary existing dataset is the initial source of canonical donors.
2. Subsequent sources are reconciled into the same donor base.
3. Employees upload a file, review a preview and explicitly approve the entire import before any canonical changes are applied.
4. Only certain matches are automatic; uncertain matches go to staff review. Clearly new donors can be added.
5. New donation rows become separate history entries; never replace prior donations.
6. Each import has file/user/date/status history and possible undo (latest committed import only).

## Candidate implementation flow (PROPOSED)

```text
Upload -> identify source -> fingerprint and duplicate check
  -> parse/validate -> normalize into isolated staging
  -> classify candidate matches (certain / uncertain / new / invalid)
  -> resolve human-review queue with side-by-side differences
  -> preview complete planned change set
  -> explicit confirmation -> atomic database commit
  -> import summary + audit + undo metadata
```

- Ordinary identical-byte submissions are duplicates independent of filename/source.
  The approved #34 design permits explicit versioned retries of the latest failed
  attempt while preserving all history. Reimport after undo remains activation-gated
  on #39; other deliberate reprocessing policies remain OPEN.
- Staging must not mutate canonical donors or donations.
- Review resolution must be recorded and revalidated before commit to avoid races/stale previews.
- Define a bounded upload size and robust parser failure handling after inspecting actual files.

## Matching

- Automatic linkage only with explainable, rigorously validated evidence.
- Uncertain cases demand manual selection or creation of a new donor.
- Attribute disagreements must be shown to the user and never silently overwrite data.
- Rules, confidence categories, normalization and field precedence cannot be finalized before source inspection.
- Payment deduplication needs a separate identity policy from donor matching.

## Safe undo of the last committed import

Undo only the most recent successfully committed import. Compute an inverse of its own changes (including donation creation, donor creation and allowed attribute updates), subject to dependency/conflict checks. **Do not restore a whole-database snapshot to undo an import.** If later user edits or other dependencies would be lost or corrupted, stop the entire undo atomically and provide an actionable conflict report; do not overwrite later edits. Define exact resolution behavior and test it before enabling undo.

## Testing priorities

- Importing the same file twice and importing different files that contain identical donations.
- Certain vs ambiguous matching, mixed donor types, missing fields, conflicting attributes.
- Complete preview and reviewer decisions.
- Atomic commit under invalid data, database errors and stale review state.
- Undo with and without later edits, audit integrity and source provenance.
- Concurrency: multiple users reviewing/importing at once.

## Source adapters and financial semantics

CONFIRMED: separate master, tax designation and headerless bank adapters share normalization, matching, validation and persistence services. File descriptions in [requirements](requirements.md) are preliminary; no workbooks were available in the repository. Inspect securely before final mappings or parsing assumptions. Adapters emit proposed staging records with source/worksheet/row locators, typed observations, parse uncertainty and rule versions; they do not directly write canonical entities.

Seed from the master before additional reconciliation. Preserve meaningful transaction detail, keep unresolved annual/monthly observations separate, and approve cross-source overlap rules before summaries. Donor matching, financial deduplication, tax attribution and correspondence grouping are separate decisions. Taxpayer/spouse presence or a shared sender address must not silently merge donors, allocate the same amount twice or create a mailing group.

PROPOSED: record content fingerprint independently of filename and retain a uniqueness/commit guard for batches. A file hash detects identical bytes, not reordered/re-saved files; approved row/payment evidence must detect overlaps while preserving genuine identical payments. Deliberate reprocessing and reimport after undo remain OPEN.

The file identity/attempt portion above is superseded by the approved
[#34 design](task-34-import-foundation-proposal.md): SHA-256 is UNIQUE on
`import_files`, with retained `import_batches`, events and immutable staged rows.
Ordinary duplicate requests do not create another batch. Retry has its own explicit
authorization/version/eligibility checks and creates a successor instead of resetting
the failed attempt. File and batch locks plus indexes serialize competing attempts.
This foundation has no upload endpoint or canonical commit/undo operation. Actual
payment overlap rules and post-undo activation remain gated, not inferred from hashes.

PROPOSED: commit revalidates approved preview version, row/review decisions, authorization, current entity versions and required pre-import backup evidence; stale state requires a fresh preview/approval. Serialize competing commit/undo eligibility checks in PostgreSQL, then write all canonical changes, audit and compensation metadata in the same transaction. Undo also rechecks latest-batch status and every dependency/version before any compensation; one conflict rolls back the entire undo. Exact locking/version design requires approval and concurrency tests.

Protect and expire uploaded workbooks/staging under approved policy; keep only necessary provenance and evidence. See [operations](operations.md) for backup gating and [data-model](data-model.md#synthetic-dataset-plan-proposed) for synthetic replay/undo scenarios.

## Delivered task 35 — synthetic bank upload

The approved [bank parser contract](task-35-bank-parser-proposal.md) now provides
session/CSRF-protected multipart POST `/api/imports/upload` and authenticated GET
`/api/imports/:id/rows`. CSV requires an explicit separator; XLSX retains physical
row indices. Valid and invalid nonblank rows are staged atomically with exact
amount strings, safe validation codes and parser/mapping versions. Source currency
remains unknown. Parser validity never counts as human review or commit approval.

Whole-file duplicate protection runs before parsing. Explicit failed-attempt
retry verifies original server-measured hash and length in the same locked
transaction as retry creation. Parse failures preserve an auditable failed attempt;
responses expose its ID/version when available. Single-process startup recovery
marks interrupted attempts failed before opening the listener. No donor/payment
writes, financial deduplication, review decisions or commit/undo are implemented
by this task. The [#36 upload screen](task-36-import-upload.md) consumes these APIs, displays
all validation rows and supports explicit failed-attempt retry. Matching, human
review decisions and final approval are separate later steps.

## Delivered task 37 — persisted review reads

The [review queue](task-37-review-queue.md) provides authorized bank-batch discovery,
current metadata and source/profile comparison. The Polish UI resumes a batch/page
from a UUID-only link, includes invalid rows and shows persisted reviewed/valid/
invalid counts. Manually submitted donor search and explicit profile comparison do
not create links, review decisions or canonical changes. Original combined sender
text is not decomposed or treated as identity evidence. The attributed decision/
preview service remains #38; comparison is not an approval step.

## Delivered task 38 — saved decisions and immutable previews

The [approved review workflow](task-38-review-decisions-proposal.md) adds explicit
existing-active-donor, reusable proposed-profile and unresolved decisions. New
profiles are typed manually; sender text never prefills inferred fields. Staff
confirm checking current profiles/proposals before creating a proposal. Identity
choices may be saved incomplete. Financial completion requires explicit currency,
evidence, new-versus-already-represented disposition and overlap confirmation. An
existing-payment reference must exactly match selected donor/date/amount/currency
and version; equal payments are never automatically collapsed.

Session/CSRF-protected writes require current batch and subject versions. File-first
then batch locks serialize competing reviewers; stale writes return 409. Current
identical no-ops add no journal record/version. Clear writes an unresolved version
and retains history. Every actual save, counter change and event is atomic.

All invalid rows, negative amounts, incomplete decisions and stale references block
a ready preview. Full blocker/effect/history pages remain available. Creating a ready
preview atomically records immutable effects, per-currency totals and a versioned
SHA-256 digest; the batch stays `review`. Old snapshots remain readable and are
labelled stale when their accepted context changes. This is preparation, not approval.
The UI preserves drafts on conflicts/uncertain outcomes, never retries mutations
automatically, confirms dirty navigation, and purges review data on session loss/logout.

Task 39 must revalidate the complete preview and current canonical financial state,
including newly introduced overlap, before any canonical application. The digest
binds accepted references/versions, not the entire donor/payment catalogue. Existing
payments are immutable to the runtime; review uses per-payment advisory locks
(`review-payment:<UUID>`) rather than granting payment UPDATE for row locks. Any
future compensation changing/removing payments must cooperate with this lock policy.
Backup/approval/commit/undo operations remain deferred and require their own approval.

## Delivered task 39 — atomic execution and compensation

The [approved package](task-39-import-commit-undo-proposal.md) adds explicit POST
`/api/imports/commit` and `/api/imports/undo-last`. Commit accepts exact batch/preview/
digest/catalogue/backup identity plus confirmation, revalidates all immutable effects,
and atomically writes approval, canonical records, provenance, audit and committed
state. Failure leaves the batch in review. Exact successful request replay returns
the same receipt and current state; it never inserts twice or reapplies an undone
commit. A changed request returns a conflict.

Financial evidence captures the catalogue version when a row is loaded and saved.
Any canonical change requires explicit re-review; a new preview alone cannot renew
old evidence. Existing profiles never receive import field overwrites. Each reused
proposal creates one donor; genuinely separate equal payments remain separate.

Undo preflights the latest-ever commit, exact facts/versions and dependencies under
the same writer gate. It appends payment reversals and archives unchanged created
donors, preserving all originals/history. Any later edit/reference or unknown FK
module blocks the whole compensation. Undoing B does not make A eligible. Exact
undo replay is safe. Future writers/dependency modules must join this protocol.

Explicit reimport accepts only the latest successfully undone attempt with verified
original bytes/source and confirmation. It creates a successor with fresh staging
and zero reviewed rows; old decisions, approval and backup are not copied. Ordinary
identical-byte uploads remain duplicates. Read-only history/outcome/applied-items/
undo-eligibility APIs support #40; GET eligibility never replaces POST preflight.

Backup requests run outside the write transaction and are revalidated afterward.
Default-disabled synthetic receipts expire after five minutes and are bound to the
exact preview/catalogue. They create no actual backup. Real operational/privacy
approval and a real provider remain required before real-data execution.

## Delivered task 40 — explicit staff confirmation and history

The [approved Polish UI](task-40-import-summary-proposal.md) connects existing
transactional execution to final preview/consent, attributed history/outcomes,
paged original effects, undo eligibility and a confirmed undo dialog. Merely opening
a screen, paging or reading a result never creates backup or canonical records.
Capability reads explain disabled/test mode without enabling it. Uploader labels
are bounded to visible history pages; outcome labels refer to current account names.
No core batch schema, database migration or dependency changes.

Approval binds exact preview/digest/catalogue/batch and unexpired receipt identities.
Context changes clear consent. Repeated clicks are guarded; unknown network results
require an explicit outcome read and, when needed, an explicit retry of the retained
original request. No automatic write retry, force flag or optimistic success.

Undo cancellation sends no write. Confirmed undo repeats backend preflight; conflicts
show fixed Polish explanations and all blocker pages. Retained originals and archive/
reversal effects remain visible. A new reimport requires a freshly selected file and
explicit attempt confirmation; the successor inherits no previous decisions. Real
source reconciliation, backups and production/privacy readiness remain gated.
