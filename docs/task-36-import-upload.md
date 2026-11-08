# Task 36 — bank upload and validation UI

The user instructed proceeding with #36 after #35 was completed and pushed.
Branch: `trello/36-import-upload`, based on `trello/35-bank-parser`.
Contributors: Piotr Zapiór and stas (user-confirmed Git attribution).

## CONFIRMED scope and implementation outline

Use the approved #35 upload/row APIs for a Polish bank CSV/XLSX screen. Add
`src/ui/alma_spei/ui/imports/{events,effects,views}.cljs`, navigation/start entry,
scoped CSS and `test/ui/alma_spei/ui/imports_test.cljs`. No dependency, migration,
API or production-processing decision changes. File selection, explicit CSV
separator, native upload progress, analysis status, duplicate/format/resource/
session failures and a complete paged validation report form the acceptance scope.

Test pure state transitions and native-XHR effects, then exercise rendered flow,
keyboard/focus, paging, failure recovery and narrow layout using synthetic Chrome
fixtures. Run `bb check`; backend behavior remains covered by the #35 integration
suite. Risks: losing an upload response does not establish server failure; navigation
cannot reverse processing already begun. No automatic upload retry. Matching,
review decisions and commit remain #37 onward. Real-data operations remain gated.

## Delivered behavior

Authenticated staff who have changed any temporary password can open **Import
przelewów** in navigation or **Prześlij plik bankowy** on Start. The screen accepts
one nonempty CSV/XLSX file up to 5 MiB, rejects unsupported filenames/extensions
and requires an explicit CSV separator. Client checks supplement the server;
server file/resource/format validation remains authoritative. The screen supports
bank uploads only; master/tax adapters remain deferred to their approved tasks.

Native XMLHttpRequest and FormData provide real byte-upload progress and the
browser-generated multipart boundary. The current session CSRF value is supplied
only to the transient upload event/effect. After bytes finish sending, the UI says
analysis is underway rather than presenting 100% as completed import approval.
Double submissions and file/format changes are blocked while a request is active.
Leaving the screen aborts the client transport and clears its state, with an
explicit note that this cannot reverse server processing already begun.

Success shows filename, attempt/state, all nonblank/valid/invalid counts and the
upload's skipped-empty count. Report pages contain 25 rows and include invalid
rows with original worksheet/row locators, permitted sender/description text,
source dates/amounts and fixed Polish error descriptions. Valid amounts remain
exact decimal strings and use a Polish decimal separator without Number conversion.
Invalid amount lexemes are displayed unchanged. Currency is explicitly unresolved.
Source text is escaped by React; arbitrary server messages are never rendered.

The report validates the shared schema, bank identity, requested offset/limit,
row count and batch totals. Incomplete/malformed success is an uncertain outcome,
with a validated batch ID retained for explicit GET recovery where available.
Page failures clear displayed rows and offer a read-only report refresh. No
invalid-row filtering or matching/approval/commit control is introduced.

Duplicate/size/type/busy/format failures have distinct Polish explanations.
Known failed attempts offer explicit confirmation and re-selection of identical
bytes for a versioned retry; the server checks content identity and eligibility.
A recorded 500 failure is distinguished from an unknown upload outcome. Network,
timeout (120 seconds), abort or unconfirmed success never trigger an automatic
POST retry. Starting another upload after an uncertain outcome requires confirmation;
identical bytes remain protected by the server's duplicate guard.

## State, cleanup and remaining scope

Pure transitions live in `events.cljs`; registered handlers extract re-frame DB
from coeffects. Monotonic request IDs, native transport cancellation and terminal
callback guards ignore stale progress/results after close/reopen or replacement.
Session failures clear rows, filename metadata and batch state and require the
existing session refresh. Authentication remains in the existing auth component.
Native File objects remain in the file input and transient event/XHR path, never
application DB or browser storage. No raw response or exception enters error state.

The active report is intentionally screen-local; leaving/reloading the whole app
clears its browser state while persisted staging remains on the server. Broader
batch discovery/resumption, candidate comparison and decisions are #37 onward;
import history and commit/undo flows remain later tasks. No real-data processing,
financial interpretation or retention decision changed.

## Verification (2026-11-08)

- `bb check`: lint/format pass; 40 JVM tests / 333 assertions, reload 1 / 10,
  56 JavaScript tests / 369 assertions and optimized frontend build all pass.
- `bb integration`: 48 tests / 409 assertions pass in isolated synthetic databases,
  including the real HTTP bank upload and canonical-data invariants from #35.
- Headless Chrome with independent synthetic same-origin APIs: Start/navigation,
  heading focus, file preflight and explicit CSV separator, browser multipart/CSRF,
  byte progress followed by server analysis, double-submit prevention, exact amount
  strings, complete invalid-row paging and unchanged invalid source amounts,
  escaped source text, page errors/recovery, duplicate handling, confirmed failed
  retry, recorded server failure, malformed-success GET recovery, uncertain outcome
  without automatic retry, session purge, stale navigation response cancellation,
  logout and 390px horizontal/keyboard scrolling pass. No runtime/console errors.
  Mobile screenshot was visually inspected. Browser fixture XLSX selection tests
  the client transport; actual XLSX decoding remains covered by #35 parser tests.
- Checks used checksum-verified Temurin 25.0.5+8 and existing Node 23.11.0/Chrome,
  without installing a UI/browser dependency. This does not independently verify
  the pinned Node 24.20.0 runtime. Browser scripts/screenshots remain temporary
  local synthetic artifacts. `git diff --check` passes.
