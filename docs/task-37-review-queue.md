# Task 37 — persisted review queue and comparison

The user instructed proceeding after #36. Branch `trello/37-review-queue` is
based on `trello/36-import-upload`; contributors are Piotr Zapiór and stas.

## CONFIRMED scope and implementation outline

Add authorized, bounded review reads and a Polish queue/comparison UI. Reuse task
34 batches/staged rows and task 30 donor profiles. No dependency or migration is
needed. Files: common review contracts; backend import review service/repository
and HTTP routes; `ui/imports/review_*`; auth/navigation integration and scoped CSS;
portable, API/PostgreSQL and UI tests plus synthetic Chrome acceptance.

API: GET `/api/imports/review` lists bank batches currently awaiting review;
GET `/api/imports/:id` reads their current metadata; existing paged rows remain;
GET `/api/imports/:id/rows/:worksheet/:row/comparison?donor-id=...` returns the
specified source row and a manually selected donor profile, with current versions.
Comparison adds no financial or credential fields. Existing donor search supports
explicit submitted queries with active/archived/all filtering, no inferred sender
parsing, automatic matching, scores, automatic candidate selection or linkage.

Server batch counts own progress; merely browsing cannot mark rows reviewed.
Saving decisions, version-conflict writes and reviewer attribution are #38. This
read-only task does not establish multi-user editing or financial overlap policy.
Bank text retains its approved source fields, unparsed; compare it alongside the
approved donor fields without inventing field equivalences. All invalid rows stay
visible. A UUID-only batch/page URL restores the server-backed batch after reload;
no source values, search terms, credentials or decisions enter browser storage.

Acceptance: bounded/authenticated queue and comparison reads; distinct same-name
and shared-address donor IDs; accurate persisted counts and reload; stale-request
and session cleanup; no canonical, review-count or audit mutations from browsing;
Polish keyboard/focus and narrow-layout checks. Real-data privacy/operations and
matching policy remain OPEN under the existing deployment gates.

## Delivered API and state boundaries

The queue lists only bank batches in `review`, ordered by creation time and UUID
for a deterministic tie-break. Pagination defaults to 25 and is bounded at 100;
query maps are closed. The batch endpoint can report other current bank states so
stale links can explain that review is unavailable. Comparison requires an actual
row locator and existing donor UUID and rejects a batch no longer in review.
Individual, business and organization profiles, including explicitly searched
archived profiles, remain distinct. Equal names/addresses never choose a profile.

Queue counts/rows and comparison metadata come from PostgreSQL, not browser
counters. New review service reads use repeatable-read snapshots; session activity
is touched before the snapshot so concurrent requests from one staff session do
not contend through repeatable-read session updates. Standard authorization still
requires an active session and completed password change. Comparison returns only
approved source-row/profile DTOs and their current batch/donor versions. No payment
history, credentials, internal persistence fields or file hashes are exposed.

The router explicitly marks only the intentional literal `/review`, `/upload`
and `/:id` overlap and orders literal paths first; all other conflict checks remain
active. The existing upload/row API behavior and body limits remain intact.

## Polish workflow and resumption

Open **Weryfikacja importów** or **Otwórz kolejkę weryfikacji** on Start. A successful
upload report also links directly to its review. Select a persisted batch, browse
all staged rows (including invalid ones), submit a donor-name search, and choose
**Porównaj** to fetch that profile alongside the original source row. Search and
results page separately from the source rows; active/archived/all is explicit.

There is no automatic donor search, sender decomposition, confidence score,
profile preselection, field-conflict inference or saved linkage. Source sender and
address stay one text value. Original invalid date/amount lexemes and fixed Polish
validation errors remain visible. Currency stays unresolved. The two panels show
source evidence and the donor's approved fields without pretending that unparsed
text establishes structured field equivalence.

Progress uses persisted `reviewed-rows` and `valid-rows`, with invalid-row counts
separate. Browsing/searching/comparing does not update them. Until #38 provides a
decision service, newly staged batches correctly show zero reviewed rows. API/UI
tests use explicit synthetic persisted-count changes to prove server ownership;
these are fixtures, not an application decision-writing path.

Only a batch UUID and row-page offset enter the `#review/<uuid>/<offset>` URL.
Reload restores the batch and page from the server; the first row on that page is
selected for reading, while donor search/preview resets. Login/required-password
change preserve an intended review route. Hash navigation updates the existing
screen without an old component cleanup erasing the new request. Ordinary
navigation/logout clears the active review URL; no source values, search terms or
credentials are written into the route or browser storage. The existing donor
search API still carries submitted search terms as request query parameters.

Monotonic request IDs and per-request-kind AbortControllers reject stale source,
search and comparison responses. Row/page/filter changes clear prior candidate
results and profile preview. Session failures purge all review data and abort
remaining reads; other navigation unmounts/cleans the module. Closed/missing batches
and unavailable/malformed responses have fixed Polish messages and explicit refresh.
The browser validates response identity, locators, requested page/counts and schema.

## Remaining scope

This completes #37's persisted read queue and comparison surface. Saving an
existing/new-donor decision, attributed review events, optimistic write conflicts,
complete preview and approval remain #38 and later tasks. Their exact schema and
financial/currency/overlap semantics retain approval gates. Real personal-data
processing and deployment/retention policy are not authorized by this task.

## Verification (2026-11-08)

- `bb check`: lint/format pass; 41 JVM tests / 341 assertions, reload 1 / 10,
  65 JavaScript tests / 434 assertions and optimized frontend build all pass.
- `bb integration`: 54 tests / 472 assertions pass in isolated synthetic databases.
  New review tests cover bounded queue/current progress, exact source locators,
  same-name/shared-address distinct donors, archived profiles, invalid rows,
  missing/closed/non-bank batches, authorization, concurrent session reads and
  unchanged canonical, review-count and audit snapshots after browsing.
- Headless Chrome with independent synthetic same-origin APIs: upload-to-review
  handoff, queue/row/donor-search pagination, explicit donor choice with no automatic
  search/selection, distinct same-name IDs, original invalid values and escaped
  text, saved progress fetched from the server, UUID-only deep link/reload/hash
  navigation, archived profiles, late comparison rejection, closed batches, safe
  errors/refresh, session/logout cleanup and GET-only review interaction all pass.
  Mobile 390px layout was visually inspected; no runtime/console errors occurred.
- Checks used Temurin 25.0.5+8 and existing Node 23.11.0/Chrome, without adding any
  dependency. This does not independently verify pinned Node 24.20.0. Browser
  artifacts remain temporary local synthetic files. `git diff --check` passes.
