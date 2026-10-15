# Product requirements

## Confirmed product goal

Provide Alma Spei hospice with a staff-facing donor CRM that consolidates donor records and retains an individual history of payments/donations. Reliable imports and record reconciliation are core product capabilities, not add-ons.

## Users and access

- Several hospice employees, each using their own account.
- Initially equal application permissions for all staff (no differentiated role matrix in MVP).
- Account provisioning is expected to be centrally managed if local accounts are used; existing organizational identity infrastructure is awaiting clarification.

## MVP capabilities — confirmed

1. A simple start screen linking to key tasks.
2. Donor list with server-side search, filters and sorting (specific filters depend on actual fields).
3. Dedicated donor detail page showing profile data, individual donations and full change history.
4. Manual editing of donor data, merging duplicate donor records and archiving donors.
5. Import existing main hospice dataset as canonical initial records; reconcile two additional sources.
6. Import wizard: file selection, validation/analysis, matching review, final change preview, explicit approval, and result summary.
7. Automatically link only high-confidence matches; queue uncertain cases for human decisions. Review UI must combine a work queue and side-by-side comparisons with highlighted differences.
8. Record donation/payment history as individual entries; importing new donations must not overwrite prior donations.
9. Import history: who, when, source file, state/outcome and summary; warn about repeated submissions, avoid duplicate data and payments.
10. Undo the latest committed import without clobbering changes made afterward.
11. Complete audit history of changed data (before and after values, actor and timestamp).
12. Excel and CSV export in the MVP.
13. Automated daily backups and backups before import (operational details open).
14. Primarily desktop UX with basic responsive support.

## Nonfunctional requirements — principles

- Polish end-user experience; English technical artifacts.
- Correctness and data traceability over aggressive auto-matching.
- Transactional import commits, clear failures, recoverable workflows.
- Privacy by design, least privilege, secure handling of files and exports.
- Small operational footprint and maintainability for a charity.
- Unit and integration tests from inception.

## Not yet specified

- Real source formats, column names, data quality and sizes.
- Matching keys, confidence thresholds, normalization rules, donation identity/dedup keys.
- Which export columns, filters, reports and file formats are actually needed.
- Migration/initial seeding sign-off procedure.
- Hosting, networking, authentication provider and account lifecycle details.
- Retention schedules, backup location, access policies, audit visibility and GDPR workflows.

**Important:** do not infer data schemas or business rules from imaginary sample spreadsheets.
