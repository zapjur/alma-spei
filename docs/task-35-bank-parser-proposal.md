# Task 35 — bank parser and upload proposal

Status: **APPROVED — user explicitly approved the proposal before implementation.**
Delivered and verified on 2026-11-08; implementation notes below distinguish scope limits.
Branch: `trello/35-bank-parser`, based on `trello/34-import-batches`.
Card: https://trello.com/c/XgGFFatr. Contributor: Piotr Zapiór.

## CONFIRMED scope

Reuse task 34 file identities, immutable attempts, lifecycle events and staging.
Authenticate uploads, enforce CSRF, measure bytes and hash on the server, reject
ordinary duplicate uploads with 409, and preserve source row locators. Stage only;
do not create donors/payments or infer identity, currency or payment uniqueness.
The inspected workbook is headerless A=date, B=sender/address text candidate,
C=description candidate, D=amount candidate. Keep its first populated row, even
when its physical row number is 2. B/C interpretation still relies on stakeholder
descriptions; retain separate text fields without splitting names or addresses.

## CONFIRMED dependencies

Add only these direct JVM dependencies to `deps.edn`:

| Coordinate | Version | License | Purpose |
| --- | --- | --- | --- |
| `org.clojure/data.csv` | `1.1.1` | EPL-1.0 | Quoted CSV reader; consume lazy results while reader is open |
| `org.apache.poi/poi-ooxml` | `6.0.0` | Apache-2.0 | XLSX package/date support and event-based XML reading |

Prefer direct POI access over a Clojure workbook wrapper: the adapter needs explicit
formula, sparse-row, date-epoch and resource policies rather than a materialized
workbook abstraction. No custom CSV parser, new multipart library or UI dependency.
Reuse Ring core 1.15.6 multipart parsing with a custom bounded temporary-file store.

Primary references checked on 2026-11-07:
[CSV repository](https://github.com/clojure/data.csv),
[POI release](https://poi.apache.org/download.html),
[POI changes](https://poi.apache.org/changes.html),
[POI security guidance](https://poi.apache.org/security.html), and
[published POI POM](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/6.0.0/poi-ooxml-6.0.0.pom).
POI 6 requires Java 17 or newer; the project pins Java 25. Compatibility with our
actual classpath remains to be demonstrated after approval, not assumed tested.

The published POI OOXML POM declares poi/poi-ooxml-lite 6.0.0, XMLBeans 5.4.1,
Commons Compress 1.28.0, Commons IO 2.22.0, curvesapi 1.08,
Commons Collections 4.6.0 and Log4j API via BOM 2.26.1. Optional signing/rendering
dependencies are not requested. After approval, inspect the full resolved tree,
including conflicts with Ring's Commons dependencies, licenses and security
advisories before implementation proceeds. Record the resulting versions. A
material override or additional dependency requires a revised proposal.

## CONFIRMED source contract

- Four columns, no automatically detected or skipped header. XLSX supports exactly
  one worksheet; reject merged cells, formulas (including cached results), external
  links, encrypted workbooks, macros and other unsupported package structures.
  Reject populated columns outside A–D. Traverse sparse rows using physical indices.
- CSV: strict UTF-8, optional initial BOM, double-quote escaping; caller explicitly
  chooses comma or semicolon separator. No delimiter or encoding guesses. A logical
  record number is the CSV locator (quoted multiline text can span physical lines).
- Dates: CSV uses strict `yyyy-MM-dd` or `dd.MM.yyyy`; XLSX accepts the same text or
  numeric date cells with date formatting, using the workbook's 1900/1904 epoch.
  Reject fractional-day serials, invalid dates and fictitious 1900-02-29. Verify
  inspected workbook epoch locally without emitting values before parser delivery.
- Amounts: exact decimal strings/BigDecimal from source numeric lexemes; signed,
  nonzero, at most four fractional digits and within canonical numeric(20,4) range.
  Text allows one decimal dot or comma, no grouping, currency suffix or exponent.
  Do not round or pass amounts through binary floating point. Preserve negative
  observations for review; their later canonical treatment remains unresolved.
- Sender text required, description optional; trim surrounding whitespace only.
  Currency remains explicitly unknown. A technically valid row is not approved for
  financial commit; later review must resolve missing financial facts.
- Skip fully empty records, count them separately in the upload response, and keep
  gaps in locators. Partially empty records become invalid staged rows. Reject an
  entirely empty file. Safe validation codes accompany permitted bounded source
  fields; never persist arbitrary XML, parser exceptions or whole workbook content.

These are synthetic-development format choices for approval, not claims that two
inspected rows establish all production formats.

## CONFIRMED limits and temporary storage

Initial fixed, documented development limits: 5 MiB file; 6 MiB entire multipart
body; one file and at most five small metadata parts; 50,000 logical/physical row
index ceiling; 4 columns; 4,096 characters per cell; 128 ZIP entries; 32 MiB total
actual expanded ZIP bytes and 8 MiB per entry. Limit shared-string count/total text
to the same row/cell and expansion budgets before constructing POI structures.
Reject excessive compression ratio (more than 100:1 for entries over 1 KiB).
Check actual streamed bytes, not only declared sizes, dimensions or Content-Length.

Authenticate session and validate Origin/header CSRF before multipart decoding or
temporary-file creation. Bound the request stream before Ring reads form fields;
its per-file option alone does not bound form-field allocations. Keep the existing
16 KiB JSON limit for every other route. Enforce a single active parser per process
with a busy response rather than an unbounded upload queue. Parse outside database
transactions; recheck session authorization when reserving and staging.

Use a private owner-only temporary directory/file, random server filenames, no
client-derived paths, and deterministic `finally` cleanup on success/failure.
Do not retain raw uploads after processing; no new raw-file retention policy is
implied. Protected staging data still requires an approved production retention
policy. Disable DTDs, entities and external XML access. Use bounded ZIP preflight
and SAX/event reading, not a fully materialized XSSFWorkbook or formula evaluator.

These limits reduce exposure but do not guarantee a hard CPU/heap bound for every
malicious document. POI explicitly warns about process impact. Production parser
containment, host limits and supported volumes remain deployment decisions; a
thread timeout must not be described as reliable process termination.

## CONFIRMED API and persistence

`POST /api/imports/upload`: multipart file plus `source=bank`, explicit CSV
separator when relevant, optional retry batch UUID and expected version. Retry
also requires explicit confirmation. Accept only .csv/.xlsx with matching actual
structure; reject spoofed/unsupported types. Use existing session/CSRF conventions.

Measure/hash the temporary file before parsing. Reserve the attempt using task 34;
an ordinary existing hash always returns 409 before parser invocation. An explicit
retry must match the original measured hash/byte count, target the latest eligible
failed attempt and use its expected version. Do not allow replacement bytes or
change its original filename/source metadata. Reuse existing retry service policy.

After reservation, parsing failures mark that attempt failed with a safe code.
Invalid individual rows instead produce a complete staged validation report. In
one transaction insert all rows, set parse/mapping versions and counts, transition
to review, and append the lifecycle event. Any staging failure rolls back every row;
record safe failure separately where possible. On restart, reserved/staging attempts
left by interruption must be identified and failed safely before accepting a retry.
Never silently discard invalid rows or leave partial review-ready results.

Success: 201 with batch DTO, skipped-empty count and first bounded page (25 rows)
of normalized source fields, locators and Polish validation errors. Add authenticated
`GET /api/imports/:id/rows` with bounded pagination (maximum 100) so #36 can display
the complete validation report. This is a staging read API; candidate matching and
review decisions remain #37–38. Safe 400/401/403/409/413/415 and busy 503 responses
contain no parser paths, raw rejected values or internal exceptions.

No migration is expected: task 34 already supports row JSON, codes, versions,
counts and events. Existing reviewed-count semantics must remain zero until an
actual human decision; parser-valid does not mean reviewed. No approval/commit API
or donor/payment mutations are included.

## Files and verification

Expected changes: `deps.edn`; new `src/api/alma_spei/api/imports/adapters/bank.clj`
and bounded XLSX/CSV helpers under the backend import module; import service and
repository staging operations; `src/api/alma_spei/api/http/imports.clj`; HTTP route
and body-limit wiring; portable import row/response contracts under `src/common`.
Update relevant development, import, source-inspection and decision documentation.

Add independent synthetic CSV/XLSX fixtures and expected results. Cover first row,
sparse rows, both epochs, leap-date and fractional-date rejection, exact amounts,
quoted multiline CSV, malformed UTF-8, blank/invalid cells, formulas/cached values,
ZIP/XML attacks, spoofed extensions and every resource limit. Verify portable DTOs
on JVM/JS. PostgreSQL and actual HTTP multipart tests cover authentication/CSRF
before parsing, measured oversize with unknown/false Content-Length, duplicate
concurrency, retry-byte mismatch, atomic staging/failure events, pagination,
temporary cleanup and unchanged canonical data. Run `bb check` and `bb integration`.

Acceptance: bounded authorized synthetic uploads produce complete persisted
reports; duplicate files cannot bypass reservation; no invalid row disappears;
all failures are safe and all canonical data remains unchanged.

## OPEN beyond this approval

Production formats/volumes, currency, sender interpretation, financial overlap and
payment identity, negative-payment policy, operational containment/retention and
privacy authorization remain unresolved. The master dataset must seed canonical
data before real bank/tax reconciliation. Approval here authorizes synthetic task
35 implementation and the two exact dependencies, not real-data processing.


## Delivery notes

Implemented the approved synthetic bank upload/staging scope with no migration or
canonical writes. The metadata part cap is five (six parts including the file),
because CSV retry needs source, separator, retry-batch-id, version and confirmation.
Each metadata value is at most 255 characters; the whole body is bounded before
Ring allocation. The implementation also drains multipart epilogue bytes through
the same budget. Raw source text cannot contain NUL, which JSONB cannot retain.

POI's XSSFReader selects package parts; secure SAX callbacks read workbook metadata,
styles, shared strings and cells without constructing XSSFWorkbook. ZIP contents
are checked before POI opens the package. Bounded row collections are materialized
before the staging transaction. The adapter supports ordinary transitional XLSX;
unsupported embedded media/comments/custom parts are rejected by an allowlist.
Numeric XLSX amount exponents are converted with BigDecimal and checked for exact
numeric(20,4) fit; textual amounts still reject exponents. No formula evaluation.

Successful uploads return 201 and a 25-row page. Parse/staging failures return a
safe error plus batch-id, and the failed batch/version when bookkeeping succeeded,
so callers can explicitly retry identical bytes. If database failure prevents
bookkeeping, the reserved attempt is recovered at restart. A failure after staging
commits does not overwrite the review state. Blank-row counts are returned by
upload, while persisted batch totals count all nonblank valid and invalid rows.
Review counts stay zero. Read pagination uses limit/offset with a maximum of 100.

Restart recovery runs before the HTTP listener starts, marks interrupted reserved/
staging attempts failed, and preserves actor/event history. An unmigrated database
can still expose readiness failure. Recovery assumes one application process;
rolling/multiple-instance deployment is not supported by this startup recovery
policy. A database connection/recovery failure prevents startup. Operating-system
process death can leave a private temporary directory; operational owners must
approve crash cleanup and containment before real-data deployment.

Resolved additions: data.csv 1.1.1; poi/poi-ooxml/poi-ooxml-lite 6.0.0;
XMLBeans 5.4.1; Commons Compress 1.28.0, Collections 4.6.0, Codec 1.23.0,
Lang 3.18.0, Math 3.6.1; SparseBitSet 1.3; curvesapi 1.08; Log4j API 2.26.1.
Commons IO remains Ring's existing 2.22.0. No optional POI signing/rendering stack
was added. curvesapi declares BSD; the Apache components/SparseBitSet use
Apache-2.0 and data.csv EPL-1.0 (including inherited project metadata). Existing
Buddy/Bouncy Castle dependencies are unchanged. Primary POI, Commons Compress
and Commons IO security notices were checked; this is not a claim of a complete
security audit or production approval.

The integration run exposed nondeterministic test-only Migratus rollback ordering
when applied timestamps tie. Disposable fixtures now call explicit down IDs in
reverse numerical order. Applied migration files and production rollback behavior
were not modified.

Verification uses synthetic fixtures, including a real HTTP multipart upload,
JVM/JavaScript contracts, resource/malformed-input tests, retry/duplicate guards,
transaction rollback, session expiry, temporary permissions/cleanup and restart
recovery. `bb check` and `bb integration` were run on the pinned Temurin 25.0.5+8;
see development notes for final counts and local Node qualification. No real file
was uploaded, copied into fixtures or parsed into staging. Only its date epoch was
inspected separately as structural metadata.
