# Task #34 — import identity, attempts and staging proposal

Status: APPROVED by the user on 2026-11-07 and implemented. Branch:
`trello/34-import-batches`, based on completed #33. Assigned contributors: Jakub
Płoskonka and Piotr Zapiór. The reviewed design wording below records the approved
scope; delivery details follow at the end.

## Decision needed: unique file identity with retained attempts

Recommend one permanent SHA-256 file identity and separate processing attempts.
The hash is UNIQUE in `import_files`; `import_batches` references that identity.
This deliberately changes #34's literal UNIQUE-on-batch wording so retry and #40's
reimport-after-undo requirement do not require deleting history or weakening
duplicate detection. No Trello cards are changed by this proposal.

Proposed behavior:

1. An ordinary upload of bytes already registered returns 409, regardless of
   filename, source label or the previous attempt's outcome. Hash checks precede
   parsing. Different bytes are not proof of different payments.
2. An explicit, authenticated retry selects the latest failed attempt and expected
   version, confirms intent and creates a new batch linked to its predecessor.
   Prior filename, actor, error outcome, counts and events remain intact.
3. An explicit reimport after a successful undo uses the same mechanism and file
   identity, creating a new batch. This becomes usable only with approved #39 undo
   implementation and financial-deduplication rules; #34 cannot mark a batch undone
   or commit any canonical changes.
4. Pending, staged, reviewed/approved or committed attempts cannot be retried.
   An old failed/undone attempt cannot bypass a newer attempt. No automatic retry,
   overwrite, batch deletion, force flag or hash release occurs.
5. Retry preserves source type. Reinterpreting an identical file as another source
   is not a retry and requires a separate approved reprocessing policy.
6. Serialize reservation/retry on the file identity in PostgreSQL; unique indexes
   provide the final concurrency guard. Failed transactions leave no partial attempt.

Earlier-import eligibility after undo remains OPEN for #39. This proposal does not
authorize walking backward through multiple imports or decide retention periods.

## Proposed migration

Add `202611070002-import-foundation.up.sql` and a disposable-test down migration;
leave all applied migrations unchanged. UUID primary keys and user references use
the existing conventions. Foreign keys restrict deletion; runtime grants are
explicit. No change to existing donor/payment facts.

| Table | Proposed columns and constraints |
| --- | --- |
| `import_files` | `id` UUID; `file_hash_sha256` required UNIQUE lowercase 64-hex text; positive `byte_count` bigint; `created_at`, `last_attempt_at` timestamptz. Hash/byte count are immutable to the runtime role. |
| `import_batches` | `id` UUID; `file_id` FK; positive `attempt_number`; nullable UNIQUE `retry_of` self-reference; `source_type` (`master`, `tax`, `bank`); `original_filename` required basename, at most 255 characters, no path/control characters; `created_by` user FK; `created_at`, `updated_at`; positive `version`; lifecycle `state`; nullable parse/mapping version, safe failure code and row counts. UNIQUE `(file_id, attempt_number)`; partial UNIQUE on `file_id` while an attempt is pending. |
| `import_batch_events` | `id` UUID; batch/user references; timestamp; batch version; allowlisted event type, previous/new states and optional safe reason code. Append-only to the runtime role; no raw exceptions, workbook rows, credentials or repeated donor values. |
| `staged_rows` | Composite identity `(batch_id, worksheet_index, row_number)` with positive 1-based locators; JSONB object `normalized_payload`; `validation_state` (`valid`, `invalid`); JSONB array of safe validation codes; nonblank `rule_version`; timestamp. One staging row per physical source row; a payload can later represent multiple source-specific observations without making multiple payments. No inferred source columns or matching fields are defined here. |

Lifecycle vocabulary: `reserved`, `staging`, `review`, `approved`, `committed`,
`failed`, `cancelled`, `undone`. #34 exposes reservation and failure/retry foundation
only. Parsing/staging completion belongs to #35, saved review/approval to #37/#38,
canonical commit/undo to #39. Services enforce legal transitions and expected
versions; storing a future state name does not enable its business operation.

Row counts remain null until known, rather than equating unknown with zero.
When parsing completes, total equals valid plus invalid physical source rows.
Reviewed counts cannot exceed valid rows. Invalid rows block approval; correction/
rejection UX remains a #35/#38 decision. Cancellation is not silently made retryable.

Grant the runtime role SELECT/INSERT for identity/batch/event/staging tables, UPDATE
only lifecycle/count/version fields on batches and `last_attempt_at` on identities.
No runtime updates to fingerprint, original actor/source metadata or prior events;
no routine DELETE/TRUNCATE. Staging cleanup and retention grants/procedures require
later privacy/operations decisions before any real data can be accepted.

Staged payloads are permitted only under approved source-specific schemas. #34 tests
use synthetic payloads; it adds no endpoint that stores real files or personal rows.
Candidate selection, decisions, previews, provenance and compensation will receive
their own reviewed additive schemas when their contracts are concrete. Batch/file
IDs and versioning are their foundation, not a substitute for that later work.

## Source layout, service and contract changes

- `src/api/alma_spei/api/imports/fingerprint.clj`: JDK MessageDigest streaming
  SHA-256 over bytes, reporting lowercase hash and actual byte count. Require the
  caller's positive maximum byte limit, read with a bounded buffer and detect limit
  overflow without trusting Content-Length or retaining the whole file. Caller
  owns stream closing. Empty files are rejected when reserving a batch. This does
  not choose production upload-size limits or parse CSV/XLSX.
- `api/imports/repository.clj`: parameterized reservation, file/attempt lookup and
  event persistence. Database constraints and row locking cover concurrent callers.
- `api/imports/service.clj`: existing principal authorization, source/basename/
  fingerprint metadata validation, initial reservation, safe failure recording and
  explicit expected-version retry. Service-supplied actor IDs are authoritative.
- `src/common/alma_spei/common/contracts/imports.cljc`: portable closed metadata,
  lifecycle/version/count/error DTOs. No JDBC, file APIs or source-column guesses.
- Unit/common tests on JVM/JavaScript; PostgreSQL migration/grant/service tests;
  register suites and update data-model/import/development/decision documentation.

No new dependency or exact-version decision: use the existing JDK, JDBC, Malli,
Migratus and auth/session services. No upload/history/retry HTTP endpoint or UI in
#34; those arrive with their planned tasks. Existing public HTTP APIs stay unchanged.
Equivalent staff capabilities apply; account administration is not required to import.

## Acceptance and tests

- Known synthetic SHA-256 vectors, non-ASCII byte sequences, multi-buffer streams,
  exact-boundary/over-limit inputs and actual byte counts. No real files or row values.
- Fresh migration, upgrade preserving existing auth/donor/audit data, test-only
  down/reapply, indexes, immutable identity fields and least-privilege audit grants.
- Same bytes/different filename and source still collide; concurrent ordinary
  reservations create one attempt and return a safe duplicate outcome to the other.
- A failed latest attempt can be explicitly retried once; simultaneous retry calls
  cannot create competing successors. Wrong/stale versions, nonlatest or otherwise
  ineligible batches fail atomically and preserve original history.
- Missing/expired/deactivated/temporary-password sessions fail server authorization.
- Injected failure rolls back reservation/event writes. No donor/payment/audit-profile
  changes occur during hashing, reservation, failure or retry.
- Shared contracts execute on JVM and JavaScript; `bb check`, `bb integration` and
  `git diff --check` pass. No real processing or production operating claim.

## Remaining gates

The file/attempt separation, retry policy and migration above were approved.
Actual staging field contracts, parser dependencies and
limits, review ownership/expiry, financial overlap/deduplication, retention and
pre-import backup/commit/undo rules retain their documented later approval gates.

## Implemented boundary

Migration `202611070002-import-foundation` creates the four tables with explicit
runtime column grants, append-only events, the unique fingerprint, same-file retry
foreign key, single-successor/attempt uniqueness and one-pending-attempt index.
Count checks preserve unknown versus zero, and approved/committed/undone states
cannot carry invalid or unreviewed rows. Staging accepts only object payloads,
positive locators and valid/invalid states with matching safe-code arrays.

The backend provides `fingerprint/measure`, `service/reserve!`, `fail-batch!` and
`retry!`. Reservation receives server-measured bytes separately from closed source/
filename metadata; the future upload handler must authenticate before hashing and
must never accept a client-supplied digest as authoritative. Fingerprinting consumes
at most the limit plus one byte, handles streams returning zero from buffered reads,
does not close the caller's stream and replaces IO errors with a safe explanation.

Failure and retry lock the file first, then the batch, and recheck expected version.
Retry of a failed latest attempt appends an event to its retained history and creates
a new reserved attempt with the current staff actor. The predecessor's metadata,
state, version and failure remain unchanged. Reservation, transition and event writes
are transactional. The batch DTO excludes the internal file hash; validation errors
exclude submitted values and raw Malli explanations.

No staging-write service, upload/history/retry HTTP endpoint, parser or UI is added.
The table's generic JSON object constraint is not a source-schema validator; #35
must validate approved adapter payloads before writing them. Reimport of an undone
batch is deliberately rejected until #39 supplies the approved undo/deduplication
workflow. Real-source retention, cleanup and operational gates remain unchanged.

Retry allocates metadata only; it does not assert that file bytes were retained.
Before parsing a retry, the future handler must server-measure a newly supplied
file or verify a protected retained copy against the immutable file identity. It
must enforce the existing HTTP Origin/CSRF boundary as well as service authorization.

## Verification (2026-11-07)

- `bb check`: lint/format pass; 31 JVM tests/248 assertions, 1 reload test/10
  assertions, 41 JavaScript tests/257 assertions and optimized frontend build pass.
- Final `bb integration`: 38 tests/328 assertions pass, including eight new import
  foundation tests for upgrade/reversal, preserved auth/domain data, duplicate and
  retry races, metadata/history preservation, rollback, authorization/lifecycle gates,
  immutable column/audit/staging grants, pending uniqueness and same-file lineage.
- The existing migration tests now account for four migrations. Applied prior
  migration files were not modified. Fingerprints use synthetic byte vectors, and
  database checks create/drop only isolated synthetic test databases.
- `git diff --check` passes. No new dependency, production processing or development
  database migration was required; there is no UI change requiring a browser check.
