# Domain and data model — preliminary

## Confirmed constraints

Donors may be individuals, businesses or organizations. Donor identity, financial attribution and correspondence grouping are separate. Preserve meaningful transaction-level detail and source provenance. Routine deletion archives donors. No final columns, identifiers, mapping or thresholds may be selected before authorized inspection of actual spreadsheets.

The three files are not in the repository. Their stakeholder-described fields are cataloged in [requirements](requirements.md); these are not inspected schemas. In particular, historical yearly fields may be indicators or amounts, and tax/spouse attribution remains unresolved.

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
