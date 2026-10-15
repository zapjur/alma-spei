# Decisions and open questions

Use `CONFIRMED` only for decisions explicitly agreed. `PROPOSED` is design guidance; `OPEN` requires external input or user approval.

## CONFIRMED — product and data

- CRM plus robust imports; donor and individual donation history.
- Initial canonical dataset from the hospice main spreadsheet/database; two other sources mapped into it.
- Individuals, companies and organizations supported.
- Field schema based on real source files, preserving relevant information.
- Certain matches may auto-link; ambiguous matches require staff decisions.
- Staff can edit donor profiles and merge duplicates manually.
- Import preview must precede approval and commit.
- Duplicate-file detection and import history are needed.
- Last committed import can be undone safely without wiping later edits.
- Full audit trail of old and new values, actor and time.
- Archive donors, rather than routine hard delete.
- Daily and pre-import backups.
- Several employees with individual accounts and equal initial permissions.
- MVP includes donor list/details, import workflow, review queue, history and CSV/XLSX export.
- Simple Polish-language start page, donor table with sorting/filters/search, donor detail page, side-by-side comparison, desktop-first UI.

## CONFIRMED — engineering process

- Monorepo, modular monolith, Clojure, ClojureScript + Reagent/re-frame, PostgreSQL.
- Shared `.cljc` code across backend and frontend, with Malli as preferred schema tooling.
- Unit and integration tests from the start, especially imports.
- Plan/approval before each significant implementation step.
- First implementation milestone: application scaffold, PostgreSQL, migrations and basic UI.
- Codex proposes the specific library ecosystem and obtains approval.
- English technical docs and code; Polish user-facing UI.

## OPEN — awaiting hospice or real data

- Existing internal auth/SSO system? If none, local admin-provisioned accounts are the working fallback (not yet final).
- Deployment platform and whether app access is local-network-only or remote.
- Three actual source files, fields, formats, sizes and quality.
- Verified donor matching rules, confidence criteria, payment deduplication keys, column conflict handling.
- Backup location/retention, sensitive-data retention, GDPR procedures.
- Exact export fields and business reporting expectations.

## PROPOSED — implementation details, subject to review

- Docker Compose for local development only.
- Portable Malli schemas in shared `.cljc`, enforced again on backend.
- Transactional batch commits and staged preview workflow.
- Import-specific compensation for the last import, with conflict detection.
- Identity provider abstraction to avoid coupling business logic to local login/SSO.

## Decision process

Before implementing unresolved behavior, add an ADR or update this file with context, alternatives, decision, tradeoffs and date of approval. Avoid turning assumptions into hidden defaults.
