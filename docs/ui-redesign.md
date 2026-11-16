# Guided workspace — first approved slice

## CONFIRMED scope

The user approved redesigning the application shell, Start screen and bank upload
step after reporting that the interface was confusing and visually unappealing.
This branch is based on task 50. The approved direction uses a sidebar, a task-led
home screen, a guided import overview, clear action hierarchy, warm neutral surfaces
and teal accents. All end-user text remains Polish. There are no new dependencies,
schema changes, backend API changes or changes to financial rules.

## Implementation

- `src/ui/alma_spei/ui/auth/views.cljs`: persistent grouped sidebar, current-page
  indication, account area, compact expandable mobile menu, and home task cards for
  donor lookup and starting an import. Resume actions open the existing review queue
  or history; no invented counts or automatic batch selection.
- `src/ui/alma_spei/ui/views.cljs`: skip-to-content link and exceptional connection
  notice. Routine connectivity diagnostics no longer compete with everyday actions.
- `src/ui/alma_spei/ui/imports/views.cljs`: five-step import overview for upload and
  analysis, expandable file-format guidance, selected filename, primary submission
  button, and a prominent next-step link after analysis. Narrow screens show the
  current and completed steps rather than compressing all five labels.
- `resources/public/css/app.css`: shared type, spacing, surfaces, controls, responsive
  shell and focused home/upload layouts. System fonts and a simple local monogram
  require no remote assets; the monogram is not an approved hospice logo.

Existing upload/retry/duplicate protections, mandatory password change, account
authorization, unsaved-review navigation guards, in-flight navigation restrictions,
session handling, explicit commit/undo consent and logout purge remain in use.
Account administration now sends its list request only when navigation succeeds.

## Acceptance and risks

The home screen offers two clear starting tasks and explicit resume links. Upload
explains what happens next, requires the existing file/separator checks, and never
implies that staging creates payments. The current page/step is exposed semantically.
Keyboard focus outlines, skip navigation, native file selection and expandable
guidance remain usable. A single main landmark contains the active screen.

Global control styling also reaches existing donor, review and history screens;
the real synthetic import journey must be checked through commit/undo, not only
the newly styled upload page. Screenshots and browser fixtures must contain only
synthetic data.

## OPEN follow-up

This is the first visual/workflow slice, not a complete redesign of every screen.
Review, donor details and confirmation/history retain their existing information
layout inside the new shell. Extending the guided layout across those screens,
final hospice branding and staff usability feedback remain subsequent work.
Native Windows acceptance from task 50 remains separately open.

The separately approved [second slice](guided-import-review.md) now extends the
guided workflow through import review, summary, confirmation and history. Donor
details and final branding remain separate follow-up work.

## Verification

`bb check` passed on the existing Temurin 25.0.5+8 setup: lint/format, 56 JVM
tests / 425 assertions, reload check / 10 assertions, 92 JavaScript tests / 583
assertions and optimized application compilation. The unchanged PostgreSQL suite
also passed 90 tests / 759 assertions, with no failures or errors.

Headless Chrome against real HTTP and an isolated synthetic PostgreSQL database
verified home actions, sidebar/current page, skip navigation, the expandable mobile
menu, upload prerequisites and step changes. The full import regression journey
passed: review/new-profile reuse, explicit preview/confirmation, disabled mode,
stale evidence, receipt expiry, lost-response recovery, repeated-click protection,
reload, undo modal focus/cancel, compensation, preserved history, fresh reimport,
later-edit conflict and logout purge. No uncaught browser exceptions were reported.
The fixture's final database invariants passed and its disposable database was removed.
Desktop and 390px home/upload screenshots were visually inspected; the narrow layout
had no document-level horizontal overflow. These are synthetic local checks, not
staff usability acceptance or native Windows verification.
