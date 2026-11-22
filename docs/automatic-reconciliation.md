# Automatic import reconciliation

## CONFIRMED direction and scope

The user rejected the manual-first transfer wizard and explicitly requested a new
branch implementing automatic matching/preparation, a change summary and human
review of ambiguities only. `fix/automatic-import-reconciliation` is based on
`ux/guided-import-review` and supersedes its normal transfer-by-transfer flow.
No schema migration, library or dependency version changes are required.

This implements a bounded development rule for the existing four-column bank
adapter. It does not finalize uninspected production sender conventions, financial
overlap standards, refund rules, production backups or real-data authorization.

## Processing and evidence

Upload confirms currency once for the file. New-donor entity type is an optional
explicit source setting; absence sends only untyped new donors to review. Neither
currency nor entity type is inferred from names or missing columns. Successful
upload hands these transient settings to the batch analysis automatically; they
are never written into the URL or browser storage.

`bank-exact-identity-v2` supports the explicit text layout
`full name, street and number, NN-NNN locality`. It preserves the full name and
does not split first/surname or infer household membership. Exact comparison normalizes Unicode composition, case, whitespace,
Polish postcode presentation and supported street prefixes. It preserves
accents, name punctuation and building/unit detail. Exactly one
active canonical profile matching all four identity fields can link automatically.
Duplicate exact identities, name/address conflicts, inactive profiles, conflicting
incoming identities and unsupported text become exceptions. The report ranks up to five active profiles using exact identity, accent-folded
full name, full address and the last name token of individuals. Each suggestion
has a Polish explanation. Approximate matches need staff review before a new
profile is proposed. Suggestions are read from the report’s catalogue snapshot,
and normal donor search remains available.

A complete valid identity with no related canonical donor and an explicitly
confirmed source type can produce a new staged profile. Repeated use of the same
new identity reuses its proposal; conflicting proposal types are not overwritten.
No donor fields are silently overwritten. New canonical profiles are created only
at final commit, never during analysis.

Payment analysis compares active donor/date/exact amount/currency candidates and
repeated normalized identity/date/amount combinations inside the file. Possible
duplicates are exceptions, not automatic skips: those columns cannot prove a stable
bank transaction identity or distinguish genuine identical payments. Positive rows
with a resolved identity and no such conflict prepare new payments. Invalid and
negative rows stay visible as blockers. All canonical writers share the catalogue
lock; preview/commit recheck versions and financial context before application.

## Persistence and concurrency

One authorized, CSRF-protected transaction prepares decisions/proposals and their
audit events under catalogue/batch locks. Failure rolls back all staging changes.
Audit metadata records the rule, explicit file policy and initiating actor, rather
than claiming every automatic decision was made manually. Existing provenance,
source rows and file fingerprint remain intact.

Repeat analysis is idempotent with current versions. Catalogue refresh can renew
only records whose latest decision version has the engine's automation provenance.
Manual saves, cleared decisions and explicitly confirmed identity choices are
preserved. Changed file policy is rejected rather than silently reinterpreted.
Unknown network outcomes require a report read; writes are never automatically
retried. Immutable previews and existing consent, commit and compensation remain
the only path to canonical mutation.

## API and UI

- `GET /api/imports/:id/reconciliation`: read-only paged exceptions, exact totals,
  counts, confirmed source policy and readiness from one database snapshot.
- `POST /api/imports/:id/reconciliation`: bounded staged reconciliation using the
  expected batch version and explicit source policy.
- `POST /api/imports/:id/reconciliation/identity`: explicit version-checked human
  target choice followed by automatic payment analysis. If payment evidence is
  unambiguous, return to the summary immediately. Otherwise ask only the remaining
  financial question.

Shared contracts and pure normalization/matching live under `src/common`; JDBC,
authorization and transactions remain under `src/api`. New UI controls lead from
upload directly to counts/totals and an exceptions list. Certain rows have no form
or required per-row click. Complete analysis leads to the existing full change
preview/confirmation. Exception resolution returns to the same summary. Source
settings can also be supplied once when reopening a previously staged file.

## Testing and remaining acceptance

Unit and cross-platform tests cover strict parsing, exact unique identity, duplicate
names, shared addresses, missing types and no first-name inference. PostgreSQL tests
cover the 250 PLN sample, idempotence, policy/version conflicts, authorization/CSRF,
pagination, rollback, manual-decision preservation, catalogue refresh, incoming
identity conflicts, new-profile reuse and possible duplicate payments. Browser
verification exercises real upload-to-summary, explicit commit/undo and exception
resolution using fictional Polish data only. See the
[sample guide](testing-samples/README.md) for exact clicks and expected results.

Actual staff usability feedback, production source-policy confirmation and native
Windows acceptance remain separate. Current catalogue scans and synchronous bounded
analysis require representative volume verification before production deployment.

## Local verification

`bb check` passed lint/format, 60 JVM tests / 446 assertions, reload / 10 assertions,
103 ClojureScript tests / 648 assertions and the optimized application build.
`bb integration` passed 100 tests / 821 assertions. All reported zero failures or
errors. The direct invalid/negative identity API tests also verify that no staged
write bypasses source validation and a legacy complete negative decision cannot
make the summary ready.

A clean Chrome run against real HTTP and a fresh fictional Polish seed verified
automatic upload handoff, 2 exact matches, 1 prepared donor, 3 payments / 250 PLN,
and zero row forms or exceptions for the valid sample. It continued through the
immutable preview, explicit commit and confirmed undo. A non-exact identity needed
one target choice with automatic payment checks; repeated payments remained
exceptions until explicitly resolved. Dirty-navigation cancellation, refreshed
counts, logout purge and 390px overflow checks passed without uncaught browser
exceptions. Desktop/mobile screenshots were visually inspected. Final database
invariants confirmed the original 30 payments remained active after compensation;
the disposable database and temporary server session were removed.
