# Task 38 — saved review decisions and versioned previews

Status: **APPROVED — the user explicitly approved this proposal with “apply” on 2026-11-15.**
Prepared 2026-11-15. Branch `trello/38-review-decisions`, based on
`trello/37-review-queue`. Assigned contributor: Piotr Zapiór.

## CONFIRMED requirements and current evidence

Task 37 provides read-only staged bank rows, current donor comparisons and saved
batch counters. No decision, proposed-profile or preview tables exist yet. Task 34
preserves attempts/events and immutable staged rows. Existing donors, donations,
correspondence groups and source evidence remain separate.

Review must record actor/time/version, survive reload, reject stale writes and
produce a complete preview before explicit approval/transactional commit. Creating
a proposed donor must not create a canonical donor. Invalid rows cannot disappear.
No automatic identity matching, sender decomposition, conflicting field overwrite
or fabricated financial fact is authorized. The inspected bank source does not
establish currency or a stable payment identifier. Matching/overlap and negative
payment policy remain OPEN; this proposal does not declare them solved.

## APPROVED delivery boundary

Implement bank review decisions, manually entered donor proposals, their Polish
UI and a server-generated immutable preview. Reuse existing dependencies, source
roots and manual donor validation. No new library. Commit/undo remain #39; final
approval/history UI remains #40. No canonical writes or production processing.

The development workflow supports positive bank payment proposals with explicit
reviewer-supplied currency and payment disposition. Invalid parser rows and
negative observations remain visible and block a ready preview. Do not rewrite
staging to make them pass. The user can correct the source and upload a genuinely
changed file; no row deletion/exclusion bypass is introduced. Refund treatment
requires its separate policy decision before these rows can proceed.

## APPROVED decision rules

### Donor identity and new profiles

For each parser-valid row, staff explicitly choose an existing active donor UUID,
a new donor proposal, or unresolved. An archived profile remains comparable but
cannot be selected for a new payment in this subset; staff must first resolve its
archive state through an independently authorized donor workflow. No unarchive is
performed by review. This conservative restriction is included in the approved synthetic subset.

New proposals use the existing closed create-donor contract (type/name/locality,
optional approved address fields). Staff type fields; combined sender text never
prefills a parsed name/address. Proposals have stable IDs and can be reused by
multiple rows in the same batch, avoiding a new donor for every payment. Proposal
IDs cannot cross batches. Staff explicitly confirm checking existing profiles and
other proposals before requesting a new profile. Equal names/shared addresses
never automatically combine targets. No new numerical matching threshold.

Existing profiles stay unchanged by import. The preview states this explicitly
and retains necessary row evidence beside the selected profile. Because bank
sender text is unstructured, the app must not invent name/address field conflicts
or claim equality. Staff can leave a row unresolved. Canonical profile corrections
use the existing audited donor editing flow and require fresh review of its version.

### Financial facts and overlap

A complete row decision must include an explicit three-letter uppercase currency
and a short evidence note (1–500 characters); there is no default PLN. These are
reviewer assertions for synthetic development, not a finding about actual files.
Source amount and parsed receipt date remain immutable. No currency conversion,
rounding, date invention or financial totals across currencies.

Payment disposition is independent of donor identity:

- **New payment:** explicit confirmation that overlap was checked, including genuine
  equal payments and other source representations. This is a human decision, not
  an automatic duplicate key. Equal amount/date/name alone never suppresses a payment.
- **Already represented:** explicitly reference an existing canonical donation UUID
  and expected donation version. The server verifies that donor, exact amount,
  receipt date and supplied currency match. Staff provide an evidence note; matching
  values alone do not prove duplication. The preview creates no payment for that
  row and retains the chosen provenance reference. Two source rows may reference
  one existing payment only through explicit decisions, never inferred collapse.

A duplicate reference must belong to the selected existing donor; it cannot target
a not-yet-created proposal. New-payment decisions for different same-batch rows
remain distinct even if their facts are identical. No automatic financial identity
rule or silent skip is introduced. Approving this manual workflow for synthetic
implementation does not authorize real reconciliation or resolve cross-source
accounting policy. Before real use, hospice data owners must approve evidence and
overlap rules. Until then, operational/privacy gates continue to forbid real data.

Identity selection can be saved while financial review is incomplete. Such a row
is marked incomplete, contributes no complete reviewed-row count and blocks preview.
All proposed input maps are closed; client actor/time/counts are rejected.

## APPROVED additive migration

Add `202611150001-review-decisions.{up,down}.sql`; do not edit applied migrations.
Five tables are proposed:

| Table | Keys and contents | Runtime privileges |
| --- | --- | --- |
| `import_donor_proposals` | UUID PK, batch FK, unique `(batch_id,id)`, version > 0, validated profile JSON object, creator/updater user FKs, UTC timestamps | SELECT/INSERT; UPDATE only profile/version/updater/time |
| `import_review_decisions` | PK `(batch_id,worksheet_index,row_number)` and FK to `staged_rows`; version > 0; kind unresolved/existing-donor/new-proposal; nullable donor FK or composite batch/proposal FK; expected target version; structured financial-resolution JSON; completeness flag; reviewer user FK and UTC time | SELECT/INSERT; UPDATE only decision payload/version/reviewer/time/completeness |
| `import_review_events` | UUID PK, batch/actor FKs, UTC time, fixed event type, affected source locator or proposal/preview reference, before/after versions and necessary bounded JSON snapshots | SELECT/INSERT only |
| `import_previews` | UUID PK, batch FK, exact batch version, creator/time, rule version, SHA-256 effect digest, counts/per-currency exact string totals | SELECT/INSERT only |
| `import_preview_items` | PK `(preview_id,ordinal)`, preview FK, fixed kind and validated JSON object; unique target references or source locators as appropriate | SELECT/INSERT only |

Unresolved decisions have no target or financial payload. Existing-donor decisions
require a donor FK and version and prohibit a proposal target; proposal decisions
require a same-batch proposal FK/version and prohibit an existing target. Duplicate
payment references use an explicit donation FK/version, not an arbitrary JSON ID.
Financial JSON contains bounded currency/evidence/confirmation fields only; exact
source amount/date are reread from staging. Add typed donation-reference columns
to the decision table for FK/constraint enforcement. Completeness is server-derived.

Events identify proposal-created/updated, decision-saved/cleared and preview-created.
Use appropriate nullable typed references with CHECK constraints and FKs to their
subjects; snapshots contain no uploads, credentials or arbitrary parser diagnostics.
Before/after snapshots provide attributed review history; they are protected
personal data subject to future retention policy, not operational log entries.

Indexes cover `(batch_id, worksheet_index, row_number)`, proposal batch/name lookup,
events by batch/time/ID, previews by batch/time/ID and item ordinal paging. No routine
DELETE/TRUNCATE grants; immutable preview/event UPDATE is denied. Replacing or
clearing a decision retains history. Down migration is test-only and drops these
new tables in dependency order; production rollback remains an operational decision.
Existing import constraints and grants remain. New authoritative services validate
closed JSON payload schemas; SQL JSON-object checks alone are not validation.

## APPROVED concurrency and atomicity

Every mutation authenticates and checks Origin, signed CSRF and session-bound CSRF.
All equivalent staff may edit; no exclusive reviewer ownership or silent takeover.
Require expected batch version and expected decision/proposal version (zero means
no decision yet). Follow existing file-first/batch-second lock order, then lock
referenced donors/donations/proposals in stable order. Recheck state `review`,
source validity, target versions and active donor state before writing.

One transaction saves the proposal/decision, appends its immutable review event,
recomputes complete reviewed-row count, increments batch version and appends the
existing lifecycle event where appropriate. Proposal-only edits do not fabricate
row-review events. A lifecycle `reviewed` event accompanies actual decision changes.
Any failure rolls back all affected business metadata. A current identical no-op
adds no event or version; a stale replay returns 409 and requires rereading.

Two staff editing the same or different rows with the same batch version cannot
silently overwrite: one wins, the other gets 409, keeps the local draft and explicitly
reloads. This conservative whole-batch serialization is a deliberate MVP tradeoff.
Invalid parser rows cannot receive a complete decision. Clearing a decision is a
versioned unresolved record with an event, not a physical DELETE.

Target/proposal changes after a decision do not silently refresh accepted versions.
Queue progress represents complete saved decisions, not a guarantee that every
reference is still current. Preview rechecks every referenced version and exposes
stale-target blockers requiring explicit re-review. UI labels distinguish saved
review progress from readiness. Proposed-profile edits similarly invalidate any
referencing decision's accepted version for preview purposes.

## APPROVED preview contract

POST preview requires expected batch version and performs a complete server-side
preflight: all nonblank rows accounted for, no invalid rows, every decision complete,
no unresolved currency/payment disposition, supported positive amount, current
active donor/proposal versions and valid duplicate references. Missing/stale evidence
returns 409 with safe blocker counts and a bounded first page; authenticated GET
blockers exposes the full current blocker list with limit/offset (maximum 100).
There is no ready preview, approval token or state promotion when blocked.

When ready, one transaction records an immutable preview header/items/event.
Items contain new donor profiles once per distinct proposal, existing target/version
references, each planned new payment, and explicit existing-payment references.
Preserve every source locator and decision/proposal/rule version. Existing donor
field effects are empty; totals exclude duplicate-reference rows and are grouped
by explicit currency. No source annual/monthly observations enter these totals.

The digest uses a versioned, deterministic ordered representation of every planned
effect, reference and version, computed by backend SHA-256. Do not hash unordered
map printing or assume a digest alone grants approval. Preview creation leaves
batch state `review` and counts/decision versions unchanged. It is not an `approved`
transition. A returned preview ID/version/digest identifies a reviewable snapshot;
#39 must revalidate versions, effect digest, financial evidence, session and backup
requirements during explicit approval/commit. Later changes make old previews stale.

Preview items are persisted and paged (25 default, 100 maximum) rather than placing
50,000 expanded rows into one response. No source text is duplicated unnecessarily;
source locators and permitted authoritative records remain available for review.
Preview generation is bounded by the existing maximum batch size. #40 provides
final confirmation; this task offers a clearly labelled read-only preview view.

## APPROVED API and Polish UI

- POST/GET `/api/imports/:id/donor-proposals`: create/list same-batch profile proposals;
  PUT `/api/imports/:id/donor-proposals/:proposal-id`: versioned edits.
- GET/PUT `/api/imports/:id/rows/:worksheet/:row/decision`: authoritative saved decision
  plus actor/time/version; PUT handles explicit existing/proposed/unresolved choice.
- GET `/api/imports/:id/payments`: bounded manual lookup of existing canonical payments,
  scoped to an explicitly selected donor. No public export or arbitrary SQL filters.
  This supplies the explicit duplicate-reference choice with exact facts/version.
- GET `/api/imports/:id/review-blockers`: paged current readiness blockers.
- POST `/api/imports/:id/preview`: expected-version preflight and immutable snapshot.
- GET `/api/imports/:id/previews/:preview-id` and `/items`: authorized metadata and
  paged effects. GET review events provides bounded attributed history.

Reuse 16 KiB JSON limits and fixed Polish errors. Reads do not change review
counts. Mutations return current safe DTOs; invalid input 400, missing subject 404,
stale/ineligible/incomplete review 409, unauthenticated/forbidden 401/403. No raw
rejected values, SQL, parser paths or unbounded diagnostic payloads in errors.

Extend #37's side-by-side screen with explicit save/clear and new-profile proposal
forms, reusable proposal selection, financial evidence/disposition controls and
saved reviewer/time/status. Selecting a profile remains distinct from saving a
link. Never preselect currency, duplicate treatment or inferred donor fields.

Re-frame mirrors persisted decisions and preserves a local draft on conflict or
uncertain outcome. No automatic mutation retry. Explicit reload resolves unknown
outcomes; batch/row changes confirm before discarding dirty drafts. Late responses
cannot populate another row; session/logout clears source, draft and preview data.
No new personal data in route/browser storage. Preview shows all effect pages and
blockers; it offers no pretend commit/undo action.

## Affected files and verification

Add the migration and backend modules under `src/api/alma_spei/api/imports/` for
proposals, decision persistence/journal and preview planning; extend HTTP import
routes/repositories while preserving upload/review literal routing and limits.
Add portable closed decision/proposal/financial/preview contracts under
`src/common/alma_spei/common/contracts/`; no JDBC/Java crypto in common code.
Extend `src/ui/alma_spei/ui/imports/review_*`, scoped CSS and relevant English docs.

Synthetic tests must cover migration/grants/upgrade and test-only reversal; all
three donor types; explicit same-batch proposal reuse; cross-batch/reference/actor
spoofing rejection; two-user stale writes; genuine identical payments versus manual
duplicate references; exact decimal/currency facts; unknown currency and negative
observation blockers; invalid rows remaining present; donor/proposal/payment version
changes; atomic counter/journal writes and rollback fault injection; deterministic
complete preview effects and paging; unauthorized/CSRF/session failures; reload,
conflict and uncertain-outcome UI; no canonical mutation or invented source fields.

Run portable JVM/JS tests, `bb check`, PostgreSQL/HTTP integration and synthetic
Chrome keyboard/focus/narrow-layout acceptance. Record final counts and limitations.
The original planning-only document required no dependency installation. Delivery
verification is recorded below.

## Approval record and remaining OPEN decisions

The user approved the five-table additive schema, conservative whole-batch version checks,
active-donor-only selection, reusable proposed profiles, explicit currency/evidence
and manual new-versus-existing-payment review for synthetic development, and
immutable ready-preview contract. Negative observations and invalid parser rows
remain blocking rather than receiving guessed treatment. No new dependency.

Real currency/source semantics, payment-overlap evidence standards, refunds,
retention and production privacy/operations remain hospice decisions. #39's exact
commit/undo schema, backup integration and latest-import eligibility still require
its own concrete proposal. This approval does not authorize canonical application
of imports or real personal-data processing.

## Delivery and verification — 2026-11-15

Implemented all five additive tables, protected decision/proposal/payment/history
and preview routes, portable contracts and the Polish review editor. Existing
profiles are never edited by review. Proposed profiles are reusable within one
batch and only become canonical through a future separately approved commit.
History keeps before/after snapshots; preview pages show each target and payment.

Writes use file/batch locks and expected subject versions. Donations remain
immutable to runtime; per-payment advisory locks avoid expanding UPDATE privileges.
Future compensation must cooperate with `review-payment:<UUID>` locks. Preview
digests bind selected reference versions and source/effect context, not all records
that may later enter the canonical catalogue; #39 must revalidate current financial
overlap before applying. Structurally complete saved-row counters can include a
subsequently stale target; full preflight blockers determine current readiness.

Validation used the pinned Temurin JDK 25.0.5+8. `bb check` passed lint/format,
44 JVM tests / 365 assertions, the reload lifecycle test / 10 assertions,
76 JavaScript tests / 498 assertions, and the optimized build with no compiler
warnings. JavaScript tests/browser automation used locally available Node 23.11.0;
the pinned Node 24.20.0 was not independently verified on this machine. Existing
upstream JVM logging/Unsafe notices and optional test source-map-support notice
remain; no dependency was added to suppress them.

Real Chrome acceptance used the optimized frontend, loopback HTTP and a disposable
PostgreSQL database with synthetic data. It passed manual proposal creation,
explicit target/currency/overlap choices, same-proposal reuse across two rows,
save/reload attribution, cancelled dirty-row navigation, complete preview effects,
history, stale snapshot detection, blockers, keyboard focus, 390px layout without
horizontal overflow, and logout purge. The post-run database check confirmed one
original donor, zero canonical payments, one immutable preview and six review events.
Temporary browser session material was private and removed when the server stopped;
the disposable database was dropped. No real donor data or workbook cells were used.

Final `bb integration`: **64 tests / 581 assertions**, zero failures/errors,
including migration upgrade/test-only reversal, grants, malformed preview locator
constraints, concurrent reviewers, transactional fault injection, current labels,
manual duplicate references and immutable/stale paged previews. Final lint,
formatting and `git diff --check` also passed.
