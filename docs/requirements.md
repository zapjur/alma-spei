# Product requirements

Status: documentation baseline updated from stakeholder feedback on 2026-10-25. This is not implementation approval. See [decisions](decisions.md) for CONFIRMED, PROPOSED and OPEN items.

## Confirmed purpose and priorities

The MVP maintains a central donor database, reconciles three spreadsheet sources, supports staff corrections and review, produces monthly and annual contribution summaries, records identification in 1.5% tax designation data, and exports postal mailing lists. Correct attribution and traceability take priority over automatic matching.

All capabilities below are MVP requirements unless explicitly deferred. Delivery order is in [implementation-plan.md](implementation-plan.md); it does not authorize implementation.

## Users and security

- Individual staff accounts, equivalent initial application capabilities, no self-registration.
- Local username/password authentication for MVP; replaceable authentication providers, stable internal users, independent server-managed sessions and application-owned authorization.
- Future OIDC, LDAP/Active Directory or other organizational SSO integrations are deferred.
- Prefer hospice-owned/administered production infrastructure and internal-network access. Final hosting and network design require IT consultation. Internal access does not replace authentication or application security.
- Polish UI, validation and help; English code and technical documentation. Desktop-first with basic responsive support.

## Sources and discovery status

No Excel files were found in the repository during this update. The following are stakeholder observations, not inspected column contracts. Do not infer exact headers, worksheet names, types, nullability, identifiers or matching thresholds. Inspect actual files only in an authorized secure environment; do not reproduce personal data in repository artifacts or agent prompts.

| Source | Preliminary observations | Unresolved interpretation |
| --- | --- | --- |
| `Baza docelowa(1).xlsx` — master | Full name; street, postal code, city, province; gender; yearly indicators or amounts for 2021–2025; 1.5% indicator; monthly amounts for 2026; summary | Exact layout, formulas, yearly indicator versus amount semantics, monthly aggregation, summary meaning and necessary retained fields |
| `baza 1,5%(1).xlsx` — tax designation | Taxpayer first name/surname/address; spouse first name/surname/address; designated purpose; transferred amount | Attribution between taxpayer/spouse, tax versus receipt year, meaning of purpose, relationship to bank receipts and disclosure limits |
| `baza przelewów.xlsx` — bank | Four headerless columns: transaction date; combined sender identity/address; transaction description; amount | Date/amount formats, sender parsing, shared senders, reversals and stable transaction identity |

Use one adapter per source and shared normalization, validation, matching and persistence services. Seed canonical donors from the master before reconciling tax and bank sources. Preserve relevant transaction detail and provenance without retaining every source field indefinitely. Historical yearly columns remain unresolved and must not be converted into invented payments or totals.

## Workflows and acceptance criteria

| Workflow | Required behavior and acceptance |
| --- | --- |
| Donor management | Search/filter/sort a donor table; show details, discrete contributions and change history; support individuals, businesses and organizations, corrections, explicit duplicate merges and archiving. Changes retain actor, time, changed fields and before/after values. |
| Correspondence | Manage recipients/groups separately from donors. Preview mailing names and postal addresses; spouses or grouped recipients receive one item per selected group. Sharing an address never automatically merges donors or financial histories. Distinct households at one address remain distinguishable. |
| Initial seed and reconciliation | Master is the initial canonical source; each adapter reports invalid/unsupported rows and uncertain parsing. Additional sources use approved common rules; no silent field overwrites. |
| Import review | Upload, validate, stage, show candidates and conflicts, resolve ambiguity, preview the complete change set and explicitly approve. Only demonstrably certain matches auto-link; creating an unlinked donor requires validation. No canonical mutation before approval. |
| Atomic commit | Commit the approved changes, provenance, audit and undo metadata in one transaction. Stale review or failed validation leaves no partial canonical changes. Concurrent attempts cannot double-apply the batch. |
| Import history and deduplication | Record actor, filename, source, time, outcome, row counts and matching/review results. Detect previously submitted files and overlapping payments across different files; changed filenames do not evade byte-identical detection. Financial deduplication is independent of donor matching. Rules remain subject to discovery. |
| Latest import undo | Only the most recent committed import is eligible. Compensate its changes transactionally; preserve later manual changes. On conflicts, stop the entire undo and present actionable reasons; no partial compensation or database snapshot restore. Retain original import and undo history. |
| Summaries | Show monthly and annual contributions derived from approved contribution facts with traceable source detail. Do not double-count master aggregates and transaction feeds. Before report implementation, staff approve date/year basis, currency handling, tax attribution and aggregate overlap rules; unresolved historical fields are clearly excluded or labeled. |
| Tax identification | Show whether donor identification in 1.5% records is supported by imported evidence and its applicable period when known. Distinguish unknown from a verified negative. Taxpayer/spouse ambiguity requires review; identification does not itself allocate a donation. |
| Exports | CSV and Excel exports, including mailing names and postal addresses, use selected filters and recipient/group rules. Preview record counts and group handling; enforce authorization and protect downloads and spreadsheet cells. Exact templates need staff approval. |
| Accounts and audit | Provision/deactivate named local accounts administratively. Backend enforces authorization on all protected actions including exports, imports and undo. Audit profile changes, financial corrections, recipient grouping, review, import, undo and account/security events without logging credentials or raw personal data to operational logs. |
| Operations | Daily and pre-import backups, tested restore, reproducible installation, protected secrets, TLS and network controls; approved ownership and policies before real data. See [operations](operations.md). |

## Quality and release gates

Unit and integration tests start with the scaffold. Cover matching ambiguity, address collisions, spouse/group behavior, duplicate submissions, payment overlap, atomic commit/undo, stale approvals, audit and permissions. Development and automated tests use synthetic data only; see [synthetic dataset plan](data-model.md#synthetic-dataset-plan-proposed).

Real personal data may be processed only after the hospice confirms organizational/GDPR requirements and IT approves the operating environment. No claim of GDPR compliance is implied. Retention, lawful basis, request handling and incident responsibilities require hospice decisions.

## Open and deferred scope

OPEN: secure file inspection; field catalog; matching and financial deduplication rules; historical annual/monthly semantics; tax attribution; reporting dates and overlap; mailing conventions and templates; source/staging retention; operational policies and IT capacity.

Deferred: external authentication integrations, complex role hierarchies, self-registration, public internet deployment, advanced analytics and distributed infrastructure. Required monthly/annual summaries are not deferred as advanced analytics.
