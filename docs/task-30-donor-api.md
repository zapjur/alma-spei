# Task #30 — donor persistence and API

Scope: the user instructed execution of #30 after #28/#29. Implementation is on
`trello/30-donor-api`, based on #29. Reuses installed JDBC, Reitit and Muuntaja;
no new dependency, migration, frontend, import or production processing.

## Modules and transaction boundary

- `api/donors/repository.clj`: parameterized PostgreSQL queries, typed row mapping,
  profile persistence and bounded donor/payment/audit reads.
- `api/donors/service.clj`: session authorization, authoritative shared validation,
  explicit DTOs, optimistic edits and transaction orchestration.
- `api/audit/donors.clj`: allowlisted field differences and protected before/after
  writes using the existing donor audit tables.
- `api/http/donors.clj`: HTTP query decoding, same-origin/signed/session-bound CSRF,
  endpoint status codes and safe field errors; wired into existing routes.
- Shared `.cljc` contracts now include paged detail and history envelopes.

The service authorizes each action inside its database transaction. Active staff
have equal donor capabilities; account-administration permission is not required.
Temporary-password sessions must first change their password. Expired, inactive,
revoked or otherwise invalid sessions cannot access donor data. Domain services do
not acquire the global authentication advisory lock.

Create/update and all field audit writes commit together. Update locks the donor
row, compares the submitted version and increments it only for an actual change.
An unchanged request still verifies the version but adds no spurious audit event.
Audit failure rolls back the profile and version. Initial creation records populated
profile fields and active state; null-to-null fields have no change entry. Actor
references come from the session, never the request body. HTTP responses contain
only explicit DTOs; no password hashes or internal persistence columns.

## HTTP contract

All responses are JSON with no-store headers. Existing 16 KiB request-body limit,
same-origin policy and safe diagnostics remain in force. Malformed request JSON
now yields generic Polish 400 errors without exposing decoder/request details.

| Method / path | Behavior |
| --- | --- |
| GET `/api/donors` | `{donors, total}` with bounded filtering/sorting/paging |
| POST `/api/donors` | Validate/create profile and audit; 201 `{donor}` |
| GET `/api/donors/:id` | `{donor, donations, donations-total}` with paged read-only payments |
| PUT `/api/donors/:id` | Full editable-profile replacement with expected version; 200 `{donor}` |
| GET `/api/donors/:id/history` | `{events, total}` with actor ID, timestamp, action and field before/after values |

List parameters: `search`, `entity-type`, `status` (`active` default, `archived`,
`all`), `sort-by` (`name` default, `city`, `entity-type`), `sort-direction` (`asc`
default, `desc`), `limit` (25 default, 1–100), `offset` (0 default, nonnegative
safe integer). Detail/history accept only `limit`/`offset`. Reject duplicate numeric
parameters, unsupported parameters and invalid values rather than passing them to
SQL. Defaults are not written into the shared request schemas.

Search is literal, case-insensitive substring matching on the combined name using
PostgreSQL lower/strpos, preserving Polish diacritics. `%` and `_` are literal, not
wildcards. Do not claim fuzzy search, accent folding or arbitrary-substring index
acceleration. Sort SQL comes only from fixed allowlists; UUID breaks ties. Null
localities sort last. Page and count reads use the ordinary transaction isolation;
concurrent changes can affect pagination between requests. Performance/pooling
needs evidence against expected volumes before a later infrastructure decision.

PUT requires `entity-type`, `name`, `city`, `version`. Omitted optional fields clear
to null. `is-active`, IDs, actors, financial data and unknown fields are rejected;
archival/merge remains a later task. First names are never inferred from name text.
Read DTOs permit nullable/international stored addresses; the current manual-entry
postcode rule remains Polish-only as documented in #29. International editing
policy is still OPEN. Profile edits never mutate payments or correspondence.

Payment amounts are four-decimal strings, never JSON floating-point numbers;
currency and established receipt date are explicit. Payments sort newest first,
then UUID, without deduplicating equal facts or deriving payments from aggregates.
Audit values are typed string/boolean/null values. History batches its field-change
query across the selected events instead of querying once per event.
Task #32 subsequently adds `actor-name` (the current staff display label) alongside
the unchanged stable `actor-id`; see [donor details](task-32-donor-details.md).

Errors: 400 invalid input (Polish field paths/codes/messages, no rejected values),
401 invalid session, 403 CSRF or mandatory password change, 404 missing/malformed
donor ID, 409 stale edit, 413 oversized body. Unexpected errors remain generic 500.
Neither operational logs nor exception metadata receive donor payloads from these
services. Protected audit records contain the necessary approved before/after data.

## Verification strategy

Unit tests cover safe query decoding and independent field differences. Portable
tests reject floating-point amounts in payment DTOs. PostgreSQL-backed Ring tests
exercise full create/detail/edit/history, literal Polish search, filtering, sorting,
paging, missing resources, safe validation, authorization and CSRF, concurrent
version conflicts, no-op edits, audit-failure rollback and exact large payments.
A loopback HTTP server test verifies the wired donor detail route over a real socket.
All data is synthetic. No donor UI or real-workbook processing is tested or enabled.

Verified on 2026-11-07:

- `bb check`: lint and format pass; 22 JVM tests/172 assertions, 1 reload test/10
  assertions, 16 JavaScript tests/117 assertions and optimized frontend build pass.
- `bb integration`: 22 tests/199 assertions pass, including six donor API integration
  tests, competing edits, transactional audit failure and a real loopback HTTP read.
- `git diff --check`: passes. No new dependencies, development database mutations,
  real donor data or production deployment were required.
