# Import, matching and rollback workflow

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
