# Task #31 — donor table UI

Implemented on `trello/31-donor-list`, based on #30, at the user's instruction to
proceed. Contributors: Piotr Zapiór and stas (user-confirmed Git attribution).
No new dependency, migration, backend endpoint or source-data processing.

## Behavior

Authenticated staff who have completed the required password change can open
**Darczyńcy** from navigation or the Start task button. The screen consumes
GET `/api/donors` using the shared response contract and displays only name,
entity type, locality and archive status. People, businesses and organizations
remain distinct. Details/editing are task #32, so this table has no placeholder
links or pretend row actions.

- Search submits through the form (Enter or **Szukaj**), trims surrounding input
  whitespace, and URL-encodes Polish characters and literal query characters.
  It does not issue a request per keystroke. Maximum input length is 200 characters.
- Type and active/archived/all filters reload immediately. Filters and sorting
  reset pagination to the first page; clearing filters restores defaults.
- Click name, type or locality headers to select ascending order, then toggle it.
  The current direction is visible and exposed via `aria-sort`.
- Pages contain at most 25 records, matching the API's bounded default. Previous/
  next actions are disabled while loading and at their respective boundaries.
  Empty later pages after concurrent deletions recover to the first page.
- Loading, empty, invalid-response, network/server and expired/forbidden session
  states have Polish text. Retry preserves the current query; refresh is explicit.
  Session failures hide all rows and offer a session refresh through existing auth.

## State and privacy

`ui/donors/events.cljs` holds pure state transitions; registrations explicitly
extract the application DB from re-frame coeffects. `effects.cljs` performs GET
requests with same-origin credentials and no-store caching and validates decoded
responses with shared Malli. `subs.cljs` exposes screen state; `views.cljs` renders
the accessible toolbar, table, errors and pagination.

Each request has a monotonically increasing ID. Starting a request clears old
rows so they cannot appear under new filters. A response updates state only when
its ID matches the current pending request. Leaving the screen clears the donor
state and invalidates pending responses; reentering cannot reuse an old ID.
Neither donor data nor session credentials are persisted to browser storage.
Failures dispatch only fixed error categories, not raw bodies or exceptions.

The health refresh now preserves application DB state rather than resetting it,
including the monotonic donor request sequence. Authentication remains in its
existing component; the donor screen does not duplicate credentials/session state.
All server-side authorization remains authoritative.

## Files and acceptance

New `src/ui/alma_spei/ui/donors/{events,effects,subs,views}.cljs` and
`test/ui/alma_spei/ui/donors_test.cljs`; auth navigation, health initialization,
health state regression test and scoped CSS are updated. Table headers/caption,
explicit form labels, keyboard controls, focus indicators and an overflow region
support desktop and smaller screens without exposing extra donor attributes.

Tests cover filter/search/sort/page transitions, stale and cross-screen responses,
registered re-frame event dispatch, session-error clearing, disappearing pages,
URL encoding, response validation and asynchronous request failures. Browser
acceptance must cover rendered navigation, Polish text, sorting indicators,
pagination, empty/error/retry, focus, narrow layout and session/logout cleanup.
Synthetic browser fixtures check the UI independently; the real API remains covered
by the PostgreSQL-backed #30 suite. No real workbook values are used.

## Verification (2026-11-07)

- `bb check`: lint/format pass; 22 JVM tests/172 assertions, 1 reload test/10
  assertions, 25 JavaScript tests/167 assertions and optimized frontend build pass.
- `bb integration`: 22 tests/199 assertions pass against isolated synthetic databases.
- Headless Chrome with synthetic same-origin API fixtures: navigation, 25-record
  paging, Polish search, type/status filters, both sort directions/ARIA, empty/error/
  retry, keyboard focus, 390px layout and keyboard horizontal scrolling, expired
  session refresh and logout cleanup pass, with no console/runtime errors. Visual
  inspection confirmed readable columns in the narrow overflow region.
- The browser driver used preinstalled Chrome and local Node 23.11.0; no dependency
  was added. This run does not independently verify the pinned Node 24.20.0 runtime.
- `git diff --check` passes. Browser fixtures and screenshots remain temporary local
  artifacts; no real donor data or development database rows were used.
