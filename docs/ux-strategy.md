# UX strategy (confirmed direction)

## Audience and language

Hospice staff, potentially with varied technical skills. Keep all end-user content in Polish, with clear language, predictable actions and accessible form controls. Desktop-first with basic responsive behavior.

## Confirmed screens

- **Start:** simple hub of key tasks, not a dense analytics dashboard.
- **Donors:** sortable/filterable/searchable table.
- **Donor details:** dedicated page with profile, separate donation history and change log.
- **Import wizard:** upload, analysis, matching review, full change preview and explicit approval.
- **Matching review:** list/queue of uncertain records plus two-record side-by-side comparison with differences highlighted.
- **Import history:** record of who imported what and when, state, results and latest-import undo action.
- **Summaries:** monthly and annual contributions with traceable facts and clearly unresolved historical semantics.
- **Correspondence:** explicit recipients/groups and mailing preview, without combining donor finances.
- **Export:** Excel/CSV flows including mailing names/postal addresses and group-aware duplicate prevention in the MVP.

## Interaction guidance (PROPOSED)

- Avoid destructive actions without clear confirmation and explanation.
- Show row counts: added/linked/updated/reviewed/skipped/invalid; exact metrics finalized with source inspection.
- Show why a match was proposed; allow selecting another donor or marking as new.
- Display preview of donor-field conflicts and donation changes before committing.
- Provide useful empty, error, loading, and success states.
- Surface archived status and historical provenance clearly.
- Protect sensitive information from inadvertent exposure in large tables and exports.
- Prefer a straightforward Polish design language and accessibility over visual ornamentation.

## Open UX details

Actual filters/columns, export selections, accessibility target, navigation structure, visual identity and whether employees require printouts depend on stakeholder review.
