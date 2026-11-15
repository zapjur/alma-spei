# Task 40 — final import confirmation, history and undo UI

Status: **APPROVED — the user explicitly approved this proposal with “apply” on 2026-11-15.**
Prepared 2026-11-15. Branch `trello/40-import-summary`, based on task 39
(`2d6eada`). Card: <https://trello.com/c/USpYwY8K>.
Assigned contributors: stas and Piotr Zapiór. The implementation commit will include
`Co-authored-by: stas <sniemczewski@onet.pl>`; Piotr remains primary author.
No new dependency or database migration is proposed.

## CONFIRMED requirements and existing behavior

Staff must see the complete planned changes before explicit approval, then see the
persisted result/history. Undo requires confirmation and can compensate only the
latest-ever committed import, with no loss of later work. A successfully undone file
can be explicitly uploaded as a fresh attempt; ordinary duplicate upload remains
blocked. Task 39 already provides these backend behaviors and protected APIs.

The card's wording that records disappear and the file becomes uploadable follows
the approved task 34/39 policy: original payments and history remain, active payment
reads exclude reversals, unchanged imported donors are archived, and reimport must
explicitly identify the latest undone attempt. There is no hard deletion, ordinary
hash bypass, automatic unarchive or walk backward through older commits.

Current review shows paged previews but has no final commit or undo controls.
Commit is disabled by default. The sole implemented backup adapter is synthetic,
creates no real backup and needs explicit development/test configuration. Real
financial evidence, source policy, backups and privacy/production readiness remain
OPEN under operations.md. This UI cannot grant those approvals.

## APPROVED user flow

### Final review and explicit commit

Add **Historia importów** to authenticated navigation and a **Przejdź do zatwierdzenia**
action for a saved v2 preview in the current review screen. Require no dirty draft
or pending review mutation. Entering this screen performs reads only: load the
selected immutable preview/header/effect page, current outcome, and execution mode.
Do not create backup receipts or commit on mount, navigation, reload or pagination.

The Polish confirmation screen shows filename, source, attempt, row counts, new
profile/payment counts, already-represented payments, and exact new-payment totals
separately per currency. Reuse the complete paged preview effects: all new profile
fields, unchanged existing profile references, each source locator and payment,
selected donor/proposal and currency/overlap evidence. No mixed-currency total or
invented updated/skipped counts. Existing profiles explicitly receive no field changes.

A stale/v1 preview or closed batch cannot be approved. Explain why and offer a return
to the actual review workflow; never renew old evidence or create a new preview
silently. Read metadata before opening approval and recheck it before the final POST;
the backend remains authoritative if canonical state changes afterward.

When execution is disabled, show a fixed Polish explanation and disable commit.
When synthetic mode is enabled, clearly state **Tryb testowy — nie powstaje rzeczywista
kopia zapasowa. Używaj wyłącznie danych syntetycznych.** This is a meaningful limitation,
not infrastructure jargon. The browser cannot enable the mode or bypass its gate.

Staff explicitly request the labelled test backup confirmation for this exact preview.
Show its completion/expiry and synthetic status. Do not describe it as a recoverable
backup. A failed/expired/mismatched receipt disables commit. Never refresh it
implicitly; staff request a fresh receipt. Receipt identity and expiry are transient;
page reload requires a fresh request. Backend checks still decide validity.

Require an unchecked explicit approval checkbox acknowledging the displayed complete
preview and synthetic limitation, followed by **Zatwierdź import testowy**. Snapshot
the exact batch version, preview ID/digest, catalogue revision and receipt ID into
that request. Disable duplicate submission and competing navigation while it is in
flight. A backup success never triggers commit automatically. Clear approval whenever
the preview, batch, receipt or screen changes; do not persist consent in browser storage.

On confirmed success show the server outcome and remove old editable review/preview
state. Refresh relevant lists/counts through their normal reads when visited. Never
infer canonical IDs/counts or optimistic success in the browser. The committed batch
leaves the review queue according to server state.

### History and outcome

Provide bounded history pages with filename, source, attempt, upload actor/time,
state and source/review counts. Label upload time distinctly from commit time; a
recently uploaded/staged batch is not the latest committed import. Each entry opens
its protected outcome. Failed/review/committed/undone attempts remain visible.

Outcome shows original upload context, commit/approval actor/time, exact summary,
labelled backup evidence, and undo actor/time/counts if present. Display names are
current account labels alongside stable actor references, following existing audit
read semantics. Paged applied effects show original profile/payment facts, canonical
mapping and reversal status; distinguish original/imported, referenced, reversed
and archived effects without treating them as extra financial transactions. Reuse
existing source/review/preview read links for full evidence where appropriate.

Load undo eligibility from the server on outcome open and on explicit refresh.
Explain nonlatest, already-undone, changed-donor/payment and dependent-record reasons
with fixed Polish messages. Render every blocker page, not only the first bounded
error response. GET eligibility is advisory; a POST must repeat the backend checks.
A review-state outcome offers a return to review; it does not offer a fake undo action.

### Confirmed undo

For an eligible outcome expose **Cofnij ostatni import**. Open an accessible modal
showing exact filename, committed actor/time, new payment/profile counts and the
compensation meaning: payments leave active histories, unchanged created profiles
are archived, and audit/source history remains. Cancellation performs no mutation.

Use native `<dialog>` with labelled heading/description, keyboard focus management,
Escape/cancel behavior, focus restoration and a clearly labelled confirmation
button. No modal library. The final action submits only the captured expected
batch version and commit ID with explicit confirmation. Disable repeated clicks.
If another import wins eligibility or later work creates a dependency, show the
conflict and refresh the paged eligibility read; never offer a force/overwrite action.

On success show the authoritative undone outcome, retained original summary and
attributed compensation counts. Keep history visible and refresh payment/donor reads
on next navigation. Existing unrelated profiles/payments remain in their normal views.
Undo does not require or initiate a database restore or fake backup request.

### Explicit fresh reimport

An undone outcome offers **Wczytaj ten sam plik ponownie**. Open the existing bank
upload form in a labelled reimport context after rereading current batch/outcome.
Show the prior filename/attempt, require staff to select a fresh file and explicitly
confirm a new attempt. CSV still requires a separator; no file is autofilled or
stored across navigation. Use the existing multipart retry fields: `retry-batch-id`,
`version`, `confirmed`, with measured identity checked by the backend.

Extend the upload state machine's failed-retry handling to the approved undone case.
Do not copy old decisions, targets, financial evidence, approval or backup. Successful
upload displays fresh staging/report and offers its review link. Stale predecessor,
newer/pending attempt, wrong bytes/source and network failures get actionable fixed
messages; the browser never treats filename equality as identity or retries uploads
automatically. Ordinary duplicate errors can offer navigation to history.

## APPROVED state, navigation and failure handling

Use scoped re-frame execution state/effects, following existing request-sequence and
response-validation patterns. Validate closed DTOs plus expected batch/preview/commit
identity and page bounds before accepting responses. An old request cannot populate
a different batch, reopened modal, route or session. Native fetch is sufficient;
reuse existing upload XHR for progress and file-size limits. No new library.

Reads may be explicitly retried. Backup, commit, undo and reimport are never retried
automatically. A timeout, lost response, server error or malformed mutation response
shows an uncertain outcome, disables another submission, and offers **Sprawdź wynik**.
That action performs an authenticated outcome read. For a completed commit/undo show
the durable receipt. If it still shows review/committed, explain that an earlier
request may still be running: allow further read refreshes, and only an explicit
user retry of the *exact retained original request* may resolve it through backend
idempotence. Never generate new approval context or a different mutation automatically.
If the backend reports a changed context, return to explicit fresh review/confirmation.

Keep the exact pending request only in transient application memory; never retain
CSRF/session tokens in re-frame business state, URLs, local/session storage or logs.
Pass CSRF transiently to effects. After reload, recover state from outcome/history;
no automatic replay of a lost request is possible. Server idempotence remains the
final double-application guard, including an original request finishing after a read.

Keep mutations stable while in flight: prevent internal route/modal changes that
would replace their context, including browser hash navigation, and add beforeunload
protection. Logout/session loss always clears sensitive state and aborts transports;
client cancellation does not claim server rollback. Clear review/upload/execution
state consistently on auth errors. Polling and automatic mutation retries are absent.
Dirty review navigation still uses the existing discard confirmation.

Use UUID/page-only fragments for reloadable history, outcome and selected preview
routes (for example `#imports/history/0`, `#imports/batch/<uuid>/0`,
`#imports/confirm/<batch-uuid>/<preview-uuid>/0`, `#imports/reimport/<uuid>`).
Reject unknown/malformed fragments and bounds. Reload resolves the current session
before fetching protected data. Routes contain no names, filenames, financial values,
backup receipt or approval flags. Retain existing `#review/...` links. Auth screen
navigation, fragment handling and route guards must share one parser/transition path
to avoid remounts or duplicate requests and preserve focus.

## APPROVED small read API additions

No schema, migration, canonical write rule or new dependency. Reuse all #39 mutation
and history/effect APIs. Two bounded read DTO additions make the UI honest/useful:

1. Authenticated GET `/api/imports/capabilities` returns closed `commit-enabled` and
   mode (`disabled` or `synthetic-test-v1`). Derive both from the same provider setting
   predicate as commit/backup. It reveals no secrets, paths or writable configuration.
   It does not authorize a mutation; disabled state and later server changes still
   fail closed on POST. Keep the literal route ahead of the UUID route and test routing.
2. Add current uploader display labels: a bounded `uploaders` collection of stable
   user ID/display-name pairs for the current history page, and `uploaded-by-name`
   in outcome DTOs. Do not expose usernames, credentials, admin flags or all accounts.
   Keep the shared core batch DTO unchanged so upload/review APIs are not broadened.
   Commit/undo replay responses use the same outcome contract. Protected stable actor
   IDs and existing immutable records remain the authority; display labels can change.

All reads remain staff-authorized with completed password change and no-store headers.
Keep fixed safe Polish errors and existing POST Origin/signed/session-bound CSRF and
16 KiB JSON limits. The extra reads do not create audit, backup or canonical records.

## Affected files, tests and acceptance

- Add UI execution events/effects/views and a small fragment parser under
  `src/ui/alma_spei/ui/imports/`; reuse preview effect rendering and Polish field labels.
- Extend review views/navigation, auth shell, upload state/effects/views and scoped
  CSS. Keep shared draft guards and purge behavior consistent; no business logic in DOM.
- Extend `src/common/.../contracts/execution.cljc` and backend execution read/repository/
  HTTP modules for only the small DTO additions. Reuse the backup predicate; no JDBC
  or provider configuration in common/UI code.
- Update README and development/UX/import workflow/decision/Trello delivery docs.
  Preserve explicit unresolved production/privacy/financial policy gates.

Unit/JS tests: strict fragment parsing, request IDs and identity/page validation;
read-only mounts; exact preview/receipt/consent binding; receipt expiry without real
sleeps; double clicks; modal cancel/focus state; changing preview clears approval;
stale catalogue/preview/backup conflicts; unknown outcomes and explicit exact-request
retry; old responses after navigation; auth/logout purging; failed-versus-undone retry
eligibility; fresh file/separator/confirmation requirements and no copied decisions.

PostgreSQL/HTTP tests: protected no-store capability/uploader reads, literal routing,
no secret/account-list leakage, disabled versus enabled mode, current display labels,
existing replay contract compatibility and no read-side mutations. Reuse #39's
transaction/compensation integration tests; do not duplicate their implementation.

Chrome acceptance against real synthetic PostgreSQL/HTTP: upload -> review -> paged
preview -> explicit synthetic backup -> confirmation -> committed outcome/history ->
modal cancel -> confirmed undo -> retained history -> explicitly selected fresh-file
reimport -> fresh review. Also default-disabled mode, stale preview/receipt, later-work
undo conflict, double click, network-uncertain recovery, session expiry/logout, reloadable
UUID routes, keyboard/modal focus and 390px layout. Use no real workbooks or donor data.

Acceptance: `bb check`, `bb integration` and the relevant browser checks pass; there
is no mutation before explicit intent, no silent replay/optimistic success, no lost
history, and no destructive/production claim. Every effect and blocker page is
reachable. Record actual tool versions/check counts and material limitations.

## OPEN decisions and approval record

The user approved this UI package and the two small read API additions, with no schema/dependency
change. Explicit commit/undo confirmation, synthetic-mode explanation, exact-request
recovery and fresh-attempt reimport follow #39's approved backend rules. Real backups,
production access, source semantics, retention and hospice privacy decisions remain
OPEN. The card's nominal 2.5-hour estimate does not cover the existing failure and
concurrency handling required for safe financial UI; no narrower unsafe behavior
is inferred from it.

## Delivery notes — 2026-11-15

Implemented the approved screens and protected capability/uploader read additions.
No migration or dependency change. Normal configuration still disables commit;
synthetic confirmation never claims to create a recoverable backup. The current
provider predicate is shared by capability and mutation checks.

Final submission captures exact immutable preview/digest/catalogue/batch/receipt
identity, requires unchecked consent and performs a fresh metadata read. Changed
context and expiry clear consent. Double-click guards and server idempotence prevent
double application. Backup/commit/undo/upload never retry automatically. Unknown
outcomes require a read; an explicit retry reuses the exact retained original input.
Business state stores no CSRF/session token; fetch waits are bounded at 120 seconds.

UUID-only fragment routes resume after authentication and reread server state;
reload discards receipts, consent and pending original requests. Private import
state is cleared on logout/session failure, including the nested reimport uploader.
Known successor IDs take precedence over predecessor metadata when recovering an
uncertain upload. No file or approval is retained in browser storage.

The native dialog focuses cancellation first and returns focus to its trigger when
cancelled. It explains payment reversal, donor archiving and retained history.
Later-work conflicts show the full paged server eligibility reasons and provide
no force action. Reimport explicitly selects a fresh file/separator/confirmation
and creates fresh staging with zero inherited review decisions.

Chrome acceptance used the optimized UI, actual loopback HTTP and disposable
synthetic PostgreSQL. It covered upload/review/proposal reuse, disabled mode,
canonical-change invalidation requiring explicit re-review, receipt expiry/unchecked
consent, final submission, a deliberately lost successful commit response, exact
one-submit double-click protection, outcome-read recovery, UUID reloads, modal
focus/cancellation, confirmed undo, retained original effects/history and a fresh
successor upload/review. A second commit followed by a later manual profile edit
correctly blocked undo and retained the correction. Missing-session reads purged
private history, explicit session restoration plus reload recovered history, and
UI logout cleared it. The session-restoration harness needed a real reload after
restoring cookies; changing cookies alone does not reauthenticate an already mounted
login screen. No automatic login or session restoration feature was added.

The 390px native-dialog capture was visually inspected and had no horizontal
overflow; cancellation and confirmation remained visible. Post-run database checks
confirmed two commits, one successful undo, four retained original payment rows,
two active successor payments, two reversal records, two retained attempts and
protected later manual work. Private temporary browser session material was removed
and the disposable database dropped. No real donor data/workbook values were used.

Checks used pinned Temurin JDK 25.0.5+8. Available Node 23.11.0 ran JavaScript/browser
checks; pinned Node 24.20.0 was not independently verified locally. Existing upstream
logging/Unsafe and optional source-map-support notices remain. Final counts follow.

Final `bb check`: lint/format passed, **46 JVM tests / 381 assertions**, reload lifecycle
**1 test / 10 assertions**, **92 JavaScript tests / 583 assertions**, and optimized
build with zero compiler warnings. `bb integration`: **89 tests / 746 assertions**,
zero failures/errors, including protected no-store capability/uploader reads and
all existing execution/compensation tests. `git diff --check` passed.
