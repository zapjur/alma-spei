# Guided import review and fictional Polish demo data

## CONFIRMED scope

The user approved one-transfer-at-a-time review, donor choice before payment
checks, confirmed save-and-next, actionable blockers and clearer summary,
confirmation and history. They requested a separate branch and plausible fictional
Polish names/addresses. `ux/guided-import-review` is based on `ux/guided-workspace`.
No dependency, schema, API or financial-rule changes are needed.

## Staff workflow

The existing five-step indicator continues through review, summary and confirmation.
A transfer counter and original-source card provide context. The reviewer first
answers **Kto przekazał tę wpłatę?**, using an existing donor, preparing a new donor,
or reusing a prepared donor within the batch. Search, comparison and selection
remain distinct; no donor is automatically matched. **Zapisz i wybierz darczyńcę**
explicitly selects the returned proposal version; the canonical profile is created
only at final import commit.

Payment fields appear after donor selection. Plain Polish instructions explain
currency evidence and financial overlap. Missing requirements appear beside the
action. **Zapisz i przejdź dalej** requires a complete valid positive row and moves
only after the server confirms a complete save. Failures preserve the draft;
conflicts/unknown outcomes require explicit reload. Stale responses are ignored.
The last save opens the summary. Page boundaries load the next server page without
assuming that physical row numbers are consecutive. Ordinary draft-save remains
available without advancement. Prepared-profile creation never supplies currency
or overlap evidence automatically.

Invalid/negative rows explain why approval is blocked. Reviewers may check other
rows without pretending a blocked row is complete. Summary blockers offer direct
navigation by physical worksheet/row through bounded server pages. Navigation
guards protect unsaved drafts. Secondary row navigation, draft options and audit
history are expandable. Fully reviewed batches offer a prominent summary action.

Confirmation explains the explicit test-receipt, consent and commit sequence.
Disabled execution explains the installation limitation; the UI never enables the
provider. History offers direct continuation for review batches and expandable
metadata. Outcome and undo keep their existing server eligibility and consent rules.

Affected files: import review/decision events and views, execution views, shared
step indicator, auth shell demo label and stylesheet. Backend policy is unchanged.

## Fictional dataset and samples

The seed retains 20 profiles, 30 payments, stable IDs and ambiguity counterexamples.
Ordinary Polish names replace synthetic prefixes inside profile names. Two Jan
Kowalski profiles have different addresses; Jakub Nowak shares one address while
keeping a separate donor and financial history. Invented combinations were not
obtained from people or workbooks. They may coincidentally resemble real details
and are never verified identities. The UI and developer guide label the demo.

The main CSV and XLSX contain the same three positive transfers totalling 250 PLN.
Helena Nowicka is absent from the seed and exercises new-donor preparation.
Currency must still be confirmed manually. Negative amounts have a separate file;
invalid rows and invalid columns have separate examples. Developer parser fixtures
use Polish names while retaining precision and negative-amount cases.

Existing seeds are never rewritten to the new names. An older/edited seed triggers
the existing safe conflict; use a fresh disposable database. This branch performs
no data reset or mutation of the development database. The
[sample guide](testing-samples/README.md) gives exact clicks, expected outcomes and
optional synthetic execution configuration. Real backups/production remain gated.

## Verification and OPEN decisions

Tests cover confirmed versus failed/stale/incomplete saves, invalid/negative rows,
page boundaries, dirty drafts, explicit proposal selection and sparse-row blocker
navigation. Sample parsing verifies equivalent CSV/XLSX content, three positive
rows and 250 PLN. Seed tests retain name/address ambiguity. PostgreSQL tests and
real Chrome/HTTP verification cover the full seeded review/commit/undo journey.
Screenshots and browser fixtures contain fictional data only.

Financial evidence standards, refunds and production backup providers remain OPEN.
Native Windows acceptance remains separate under task 50. Staff usability feedback
is still required before calling this workflow accepted.

Final local verification: `bb check` passed lint/format, 57 JVM tests / 431
assertions, reload / 10 assertions, 97 JavaScript tests / 616 assertions and the
optimized build, with zero failures/errors. `bb integration` passed 90 tests / 759
assertions. Real Chrome/HTTP testing used the actual sample files and an isolated
seeded PostgreSQL database. It verified the two Jan profiles, separate shared-address
donor, blocker return, failed-save retention, dirty-navigation cancellation,
confirmed save-and-next, new donor, 250 PLN summary, disabled execution, explicit
commit, undo cancellation/confirmation, history, byte duplicate, XLSX, negative
blocker and logout purge. No uncaught browser exceptions or document overflow at
390px were reported. Desktop/mobile screenshots were visually inspected. Final
database checks confirmed one new profile, three retained payments and one undo,
with all 30 seed payments still active; the disposable database was removed.
