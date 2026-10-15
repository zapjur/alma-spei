# Domain and data model — preliminary

## Confirmed concepts

- A canonical **donor** may be an individual, a company, or another organization.
- A donor has zero or more **donations/payments**, retained as discrete historical entries.
- Source data must retain provenance: source file and original row/record association.
- Each import has lifecycle states, review decisions, and an auditable set of changes.
- Manual edits, merges, archives and import operations produce an audit trail.

## Candidate entities (PROPOSED; do not finalize until spreadsheets are examined)

- `donors`: canonical donor identity, type and archive status.
- `donor_attributes` or typed donor columns: choose after reviewing real fields.
- `donations`: individual contributions tied to donor with source/provenance and dedup identity.
- `source_systems`: distinct import origins.
- `source_records`: original source identity and link to canonical donor.
- `import_batches`: file hash, filename, source, actor, timestamps, state, counts.
- `import_rows` / `staged_records`: validated row payload and classification.
- `match_candidates`: possible donor links plus evidence and decision state.
- `review_decisions`: employee action and timestamp, possibly corrected target.
- `audit_events` / `audit_changes`: complete change sets with actor, timestamp, before/after and cause.
- `import_operations`: changes required to support safe compensation of the most recent import.
- `users` / `external_identities`: final design depends on identity decision.

## Requirements for modeling

- Preserve provenance and raw source values in access-controlled staging/audit storage, with retention to be agreed.
- Support company/organization as first-class donors rather than treating them as people.
- Maintain stable IDs for canonical donors and donations; merging donors must preserve history and traceability.
- Keep matching decisions distinct from edits to donor attributes.
- Support conflict-safe undo when later manual edits affect the same entities.
- Never infer donor identity from name alone without a verified rule.
- Add constraints/indexes based on measured keys and query patterns; no imaginary source fields.

## Next prerequisite

Obtain sanitized file specifications or securely inspect the three real sources and produce a field catalog with example value patterns, null rates, candidate identifiers and data-quality issues. Seek approval of mapping and matching rules before building production import logic.
