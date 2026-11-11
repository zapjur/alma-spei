# Task #29 — shared donor contracts

Implemented on `trello/29-donor-contracts`, based on the completed #28 branch,
under the approved Trello plan and the user's instruction to proceed to #29.
No new dependency, database migration, HTTP route or UI is introduced.

## Contract boundaries

`src/common/alma_spei/common/domain/donors.cljc` contains portable nonblank-text
and Polish postcode predicates plus the three approved entity types.
`src/common/alma_spei/common/contracts/donors.cljc` defines closed Malli DTOs:

- `create-donor`: required `entity-type`, combined display `name`, and `city`;
  optional nullable `first-name`, `street`, `postal-code`, and `province`.
- `update-donor`: the same full profile input plus the expected positive `version`.
  This is not a partial PATCH. Missing optional fields are treated as absent input;
  #30 must define replacement/clearing behavior explicitly in the service/API.
- `donor`: explicit read DTO with UUID string ID, profile fields, archive state and
  version. Nullable stored address fields remain readable. Audit actors/internal
  persistence fields are not automatically exposed. Payment/detail/audit envelopes
  follow with their corresponding #30 endpoints rather than exposing database rows.
- `donor-response` / `donors-response`: profile and list envelopes.
- `list-query`: typed search (at most 200 characters), entity/status filters,
  allowlisted sort column/direction and bounded pagination (limit 1–100,
  nonnegative offset). HTTP decoding/defaults remain backend work.

Version/offset/count integers are bounded by JavaScript's safe integer maximum.
Optimistic concurrency still requires an atomic backend version check; a valid
input version is not proof that an edit is current.

## Validation policy

Manual creation/editing requires name, locality and entity type as specified by
the card. Whitespace-only Unicode names/localities fail on JVM and JavaScript.
The combined name is never split or used to infer donor type. No uniqueness or
matching rule is introduced.

An omitted/null postcode is permitted; a supplied postcode must have ASCII
`XX-XXX` format. Empty string is invalid. This is the manual-entry rule requested
by #29, not a universal address rule: read DTOs preserve stored foreign/incomplete
addresses, and import/international editing policy still needs explicit design.
Do not apply the manual-create schema automatically to source staging records.

The shared `validation/errors` function returns only `field` paths, stable codes
(`required`, `unknown-field`, `invalid`) and Polish messages. It does not return
rejected values, Malli schema objects or full explanation data. Unknown fields
receive a form-level error without echoing their arbitrary key/value. Authoritative
server validation and application authorization remain #30 responsibilities.

## Verification

Synthetic `.cljc` tests run on both JVM and JavaScript. They cover supported types,
missing/blank fields, Unicode whitespace, Polish diacritics, postcode edge cases,
closed inputs, forged internal fields, version/pagination bounds, nullable reads,
no name inference, and deterministic Polish errors without rejected values.

Verified on 2026-11-07: `bb check` passed lint/format, 19 JVM tests/159 assertions,
1 reload test/10 assertions, 15 JavaScript tests/113 assertions and the optimized
frontend build. The six new portable donor tests contribute 69 assertions on each
platform. `bb integration` passed 16 tests/110 assertions after starting local
PostgreSQL. `git diff --check` passed. No source workbook was needed for these tests.
