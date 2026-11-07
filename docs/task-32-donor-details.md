# Task #32 — donor details, history and editing

Implemented on `trello/32-donor-details`, based on #31, following the user's
instruction to proceed. Contributors: Piotr Zapiór and stas. No new dependency,
migration, source adapter, financial mutation or production processing.

## User flow

Click a donor row or activate its named button with the keyboard to open a separate
card screen. The heading receives focus. The card presents approved profile and
address fields, active/archive status, read-only payments and profile-change history.
Back returns to the filtered/sorted donor list and refreshes it.

Payments and change history page independently in groups of 25. Each has its own
loading, empty, error and retry state. Payment amounts remain exact decimal strings
and display a Polish decimal separator with the explicit currency; no numeric
conversion, rounding, aggregation or payment editing is introduced. Receipt dates
are formatted as dates without timezone conversion. Audit timestamps use the
browser's local time and Polish formatting. Changes show field labels and typed
before/after values, distinguishing null, empty text and false.

The shared history DTO now adds `actor-name`, resolved from the current staff
display name in the same protected history query. Stable `actor-id` and the original
audit record remain unchanged; this is a current display label, not an assertion of
the name used at event time. No credential or extra donor data is exposed. The
API integration test verifies actor ID and display label together.

## Editing and conflicts

The form uses the approved shared Malli update contract: type, combined name and
locality are required; supplied Polish postcodes must match XX-XXX. Inline Polish
messages and invalid-field attributes accompany errors; invalid forms cannot send
a save request. Optional empty inputs map explicitly to null, while required text
is not silently rewritten or split. First names are supplied explicitly. The
manual postcode limitation for international addresses remains as recorded in #29;
existing foreign values are never silently cleared to make a form valid.

The submitted version is captured when editing begins. Browsing payment history
cannot advance that version or overwrite the draft. Save uses PUT with transient
session CSRF and a closed profile payload; IDs, actor fields, status and financial
data are excluded. Duplicate submissions are blocked while pending.

- Success closes editing, announces the save and rereads profile/payments, audit
  history and the existing list query.
- A 400 response preserves the draft and displays only allowlisted Polish field
  messages. JSON string field paths are normalized through a fixed map; arbitrary
  server messages and rejected values are not reflected.
- A 409 conflict preserves the draft and original version and disables further
  save attempts until explicit reload. The user can retain needed edits before
  choosing to discard the draft and read the latest card.
- Network failures, malformed success responses and unexpected server failures
  leave the outcome unconfirmed. The draft remains, no automatic retry occurs,
  and explicit reload is required to discover the actual persisted state.
- Back/reload confirm before discarding a changed draft. Cancel explicitly discards
  the form. Nothing is persisted to browser storage. Other application navigation
  or logout unmounts the module and clears its data; it does not save a draft.

## State and privacy boundaries

New `ui/donors/detail_events.cljs`, `detail_effects.cljs`, `detail_views.cljs` and
shared UI `labels.cljs` extend the existing donor module. Read/save requests use
distinct monotonic IDs; responses for a previous card, closed screen or completed
save cannot repopulate state. A detail/save response must match the selected donor
ID as well as its shared schema. Payment and audit requests do not block one another.

An unauthorized/forbidden response from any donor request, including a background
list refresh, clears profile, payments, history, draft and list rows and offers the
existing session refresh. No CSRF value is stored in application DB; it exists only
in the transient save event/effect and existing auth component. Server permissions,
version checks and transactional audit remain authoritative.

## Acceptance and verification scope

Portable/state/effect tests cover validation gating, explicit null clearing, exact
payload/version, duplicate saves, immutable edit version during paging, stale and
post-close responses, independent history failure, three-way refresh after save,
session purging, conflict/unconfirmed outcomes and JSON field-error handling.
PostgreSQL-backed tests continue to cover actual persistence, authorization,
concurrency, audit and rollback. Synthetic Chrome acceptance checks the rendered
card/form and deliberately simulates server conflicts/validation/unconfirmed saves.
No source workbook or real donor values are used in these checks.

## Verification (2026-11-07)

- `bb check`: lint/format pass; 22 JVM tests/172 assertions, 1 reload test/10
  assertions, 38 JavaScript tests/225 assertions and optimized frontend build pass.
- `bb integration`: 22 tests/200 assertions pass, including the added actor-name
  response assertion against the real protected history endpoint.
- Headless Chrome with synthetic same-origin APIs: keyboard/row card opening,
  heading focus, exact read-only amounts, actor labels, independent payment/history
  paging, required/postcode validation, versioned PUT and card/list refresh,
  retained conflicts, rejected duplicate saves, explicit reload/discard confirmation,
  normalized server field errors, unconfirmed-save behavior, 390px form layout,
  expired-draft cleanup and logout pass. No runtime/console errors were observed;
  the mobile screenshot was visually checked.
- Browser automation used existing Chrome and Node 23.11.0, without installing a
  browser dependency. This does not independently verify pinned Node 24.20.0.
- `git diff --check` passes. Test fixtures, browser artifacts and credentials are
  synthetic; no workbook data or development donor rows were processed.
