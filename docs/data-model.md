# Domain and data model — preliminary

## Confirmed constraints

Donors may be individuals, businesses or organizations. Donor identity, financial attribution and correspondence grouping are separate. Preserve meaningful transaction-level detail and source provenance. Routine deletion archives donors. No final columns, identifiers, mapping or thresholds may be selected before authorized inspection of actual spreadsheets.

The three files remain outside the repository. A user-directed local structural
inspection is recorded in [source-inspection.md](source-inspection.md), with an
exact initial schema proposal in [task-28-schema-proposal.md](task-28-schema-proposal.md).
This evidence does not resolve historical yearly meanings or tax/spouse attribution;
those semantics remain OPEN.
The user subsequently approved the exact #28 proposal; the implementation is
described below. The unresolved source semantics remain OPEN.

## Candidate entities (PROPOSED)

| Entity | Responsibility |
| --- | --- |
| `donors` / typed attributes | Stable identity, donor type, approved profile fields and archive/version state |
| `addresses` | Original necessary address representation and normalized comparison form; address equality is not identity |
| `correspondence_groups`, `group_memberships`, `correspondence_recipients` | Explicit donor memberships, mailing display name and delivery address; singleton recipients also supported |
| `contributions` / attribution links | Discrete approved financial facts, amount/date/currency when established, donor attribution and deduplication evidence |
| `source_observations` | Historical/yearly/monthly aggregates or indicators with period/meaning status; never fabricated transactions |
| `tax_identification_evidence` | Source-supported donor identification and period when known, independent of payment allocation |
| `source_systems`, `source_records` | Adapter identity, import/worksheet/row locator, rule/mapping version and necessary source evidence |
| `import_batches`, `staged_records` | File fingerprint, filename, actor/time/state/outcome/counts and bounded validated staging |
| `match_candidates`, `review_decisions` | Candidate evidence, ambiguity, field conflicts, reviewer and chosen outcome |
| `audit_events`, `audit_changes`, `import_operations` | Protected before/after changes and compensation/dependency metadata |
| `users`, `local_credentials`, `external_identities`, `sessions` | Stable internal actors, protected local hashes, provider/subject mapping and independent server sessions |

Table names, cardinalities, keys, required fields and constraints are proposals. A group does not own or combine contributions. A spouse appearing in tax data is not automatically an established donor, group member or co-owner of an amount. Keep unresolved relationships in staging until reviewed.

Reports need an approved authoritative representation for each period/source so master aggregates, tax transfers and bank receipts are not summed twice. Separate observed source values from reportable facts and keep a path from each included fact to its evidence. Do not interpret missing tax evidence as a verified negative.

Keep raw uploads and row payloads only as necessary under approved retention and access controls. Provenance can retain hashes, locators, rule versions and decisions after raw data expiry, subject to policy. Audit before/after values contain personal data and require separate access and retention controls; they do not belong in operational logs.

## Synthetic dataset plan (PROPOSED)

1. Securely inspect each workbook without copying personal values into prompts, commits or reports. Record only approved schema/format descriptions: sheets, header/headerless layout, types, date/decimal conventions, blank/formula patterns, size ranges and unresolved semantics. Approve the catalog before generator implementation.
2. Create a deterministic, seeded synthetic canonical population and a separate expected-outcome manifest. Use invented Polish-style names and fictitious addresses, businesses and organizations, labeled as synthetic; never transform or sample actual donor rows. No dataset is intended to represent a real person.
3. Render three synthetic XLSX sources from that population: master with historical/monthly observations, taxpayer/spouse tax rows, and four-column headerless bank rows. Produce CSV variants only where relevant. Preserve approved formatting patterns without copying real content. Include configurable small review fixtures and larger performance datasets; confirm volume targets after discovery.
4. Add controlled variants: Polish diacritics and Unicode forms, case/spacing, street abbreviations, apartment numbers, postal-code formatting, city/province inconsistencies, missing fields and moved addresses. Keep normalization conservative and originals available as needed; normalization must not collapse distinct units or households.
5. Include same-name unrelated donors, shared addresses with distinct households, spouses with different surnames/addresses, one spouse absent, joint senders, businesses with similar names, ambiguous combined sender text and conflicting profile values. Include explicit group memberships and non-grouped neighbors to prove mail deduplication does not merge finances.
6. Include byte-identical renamed files, reordered/re-saved overlapping files, overlapping sources, exact duplicate rows, genuine separate equal-amount/date payments, invalid dates/amounts, refunds if supported, formula/error cells, mixed yearly indicator/amount cases and missing historical semantics. Include late manual corrections and concurrent review changes.
7. Keep expected matches, required review, conflicts, report totals under explicit approved rules, recipient counts and provenance separate from importer outputs. Test first import, repeat import, rejection, correction, commit failure and undo before/after manual edits. No rule should pass simply by mirroring its implementation.

Generator code/dependencies are not authorized here. Unit tests cover portable functions on JVM/JavaScript; PostgreSQL integration tests cover deduplication, constraints, atomicity, audit, permissions and compensation. Synthetic workbooks and manifests must contain no real personal data.

## Next prerequisite

Approve secure inspection arrangements and a field catalog, then approve canonical schema, attribution, matching, report overlap and retention rules before implementing production imports or domain migrations.


## Approved authentication subset — Milestone 2A

The authentication-only migration implements `users`, `local_credentials`,
`external_identities`, `sessions`, `auth_throttle_buckets` and
`security_audit_events`; see [approved proposal](milestone-2-auth.md). Internal
UUIDs remain stable across display-name changes and account deactivation. Local
identity subjects use internal user UUIDs; usernames are lowercase and immutable
through the application. Password/session secrets are never audit payloads.
This approval does not finalize any donor, contribution or source-data entity.

## Approved initial domain subset — Task #28

Migration `202611070001-donors` implements the
[approved schema](task-28-schema-proposal.md): separate `donors` and `donations`,
plus append-only-to-runtime `donor_audit_events`/`donor_audit_changes`. Name/address
equality is not identity. Profiles support individual, business and organization
types, soft archiving, versions and stable user references. Payments require exact
numeric amounts, explicit currency and established receipt dates; no uniqueness
rule collapses genuine equal payments. JSONB before/after audit values are protected
application data, never diagnostic log payloads. Service-level authoritative
validation and transactional audit writes are implemented under #29/#30, not
database triggers; see [the donor API](task-30-donor-api.md).
Import/provenance/compensation metadata follows under #34 before imports are enabled.

## Synthetic canonical demonstration subset — Task #33

The user authorized the [developer-only demo population](task-33-synthetic-data.md):
20 invented profiles and 30 explicit payment facts, versioned deterministic IDs,
protected creation audit and conflict-safe repeat behavior. This implements a small
canonical fixture subset of the broader synthetic dataset plan, not three source
workbooks or historical mapping/report semantics. No real personal values are copied.

## Approved import metadata subset — Task #34

Migration `202611070002-import-foundation` implements permanent `import_files`,
retained `import_batches` attempts, append-only `import_batch_events` and immutable
`staged_rows` metadata. The approved unique SHA belongs to the file identity, not to
each processing attempt. Retry lineage, pending-attempt uniqueness, row-count checks
and column grants protect this boundary. See
[the approved schema and delivery](task-34-import-foundation-proposal.md).
Source payload validation, review/preview/compensation tables and real-data retention
remain later decisions; a generic JSONB object is not a finalized workbook contract.

## Approved saved review subset — Task #38

Migration `202611150001-review-decisions` adds same-batch `import_donor_proposals`,
current versioned `import_review_decisions`, append-only `import_review_events`,
immutable `import_previews` and ordinal-paged `import_preview_items`. Typed foreign
keys bind decisions to staged rows, selected donors/proposals and explicit existing
donations; source observations remain unchanged. Runtime grants prohibit deleting
review records or updating preview/journal records. No canonical donor/payment write
is part of this subset. See [the approved contract](task-38-review-decisions-proposal.md).

Decision completeness is derived from explicit currency, nonblank bounded evidence,
payment disposition and overlap confirmation. Saved counters describe structural
completeness; referenced versions are revalidated during preview. Review journals
retain protected before/after snapshots and stable actors. Reviewer/actor display
names in reads are current account labels; stored target labels reflect the save.
Preview payloads retain accepted versions, exact source facts and separate effects.

## Approved import execution subset — Task #39

Migration `202611150002-import-execution` adds `canonical_catalog_state`, immutable
`import_backup_runs`, `import_commits`, `import_commit_items`, `import_undos` and
`donation_reversals`. Typed composite references bind receipts/previews/batches,
applied items and import-created subjects; original payment rows and batch history
are retained. Nullable `import_batch_id` on donors/payments distinguishes manual/demo
records from imported creates. Existing reference targets keep their original origin.
Normal payment queries use the `active_donations` view, excluding reversals.

Catalogue triggers advance revision on every canonical donor/payment/reversal row
change. All application writers acquire one advisory gate before entity locks. Review
decisions record accepted catalogue revision; v2 previews bind it in header/digest.
Legacy v1 snapshots remain readable, with NULL revision and explicit staleness; old
decisions require a new human review save. Canonical changes invalidate old overlap
evidence even if their selected targets did not change. This is conservative, with
no automatic payment identity rule.

Commit ordinals, not timestamps, define the latest-ever import. An undo appends typed
reversals and normal donor archive audit, preserves provenance, and rejects later
edits/references. Unknown donor/payment FK modules block compensation until their
dependency policy is implemented. The test-only down migration refuses execution
history; on an unused disposable database it removes v2-only snapshots before
restoring the preceding schema. It is not a production rollback procedure.

## Task #64 source evidence storage

Migration `202611220001-source-evidence` adds two append-only tables for the
application role. Each evidence row references a staged workbook row and an
import batch of the corresponding source type.

- `source_observations` identifies the master-workbook cell by column index
  within its staged row. It preserves a value as text with a source type
  (text, number or boolean). Year and month may be absent. An observation
  does not create a donation or establish a reportable amount.
- `tax_identification_evidence` records taxpayer and spouse roles separately
  within the same staged tax row. Donor links and tax year may be absent.
  The reported amount stays on the source record. A role record alone does
  not allocate money, establish a donor relationship or create a mailing group.

The migration can be rolled back while both tables are empty. Historical
observation meanings, month-to-period mapping and tax attribution remain open.

Import compensation checks for linked tax evidence before archiving a donor
created by an import. Future adapter writes that link evidence to donors must
use the existing catalogue lock and check that the donor is active.
