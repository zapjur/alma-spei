# Task 50 — Windows private files and shell line endings

Status: **APPROVED — the user explicitly approved this proposal on 2026-11-15. Native Windows acceptance remains OPEN.**
Prepared 2026-11-15. Branch `trello/50-windows-file-permissions`, based on task 40
(`0be489f`). Card: <https://trello.com/c/JqtSjbAt>.
No assigned Trello members; no co-author identity is inferred. No new dependency,
database migration, application API or financial/domain-policy change is proposed.

## CONFIRMED evidence and scope correction

A fresh Trello read shows task 41 in Done. Its 2026-11-15 closing comment transfers
external QA to cards [47](https://trello.com/c/fVhKsHkk),
[48](https://trello.com/c/6324j4oR) and [49](https://trello.com/c/hRvlC1Vl) in
<https://github.com/AntoniLenart/alma-spei-tests>. Application transaction and undo
coverage already ships with tasks 39/40. Do not create a duplicate task 41 branch
or claim the external QA cards passed. The previous local remaining-task count
was based on the older plan and is superseded by this board evidence.

The next identified open application blocker is task 50. QA reports Windows 11 /
Temurin 25 throwing `UnsupportedOperationException` for POSIX creation attributes.
Current upload code and `bb init` unconditionally request those attributes. The repo
also lacks `.gitattributes`, so `core.autocrlf=true` can produce CRLF shell scripts.
These observations establish a credible cause, not a native Windows reproduction
on this macOS workstation. No Trello comments/statuses are changed by this proposal.

## APPROVED implementation

1. Introduce a small backend-only private-file helper using existing Java NIO APIs.
   Detect supported views on the actual target filesystem/store, not by guessing
   from the OS name. On POSIX retain directory 0700/file 0600 creation attributes.
   Do not catch arbitrary I/O/permission errors and silently retry insecurely.
2. For a Windows ACL filesystem, create an empty directory/file without unsupported
   POSIX attributes, restrict and verify access for the owning application account
   before any upload bytes or secret values are written, and use inherited private
   child access as appropriate. Preserve CREATE_NEW semantics, symlink rejection,
   random temporary paths and cleanup. If the filesystem cannot enforce the private
   policy, fail closed with a sanitized explanation instead of accepting public data.
   This is a stronger protection than merely assuming every non-POSIX temp folder
   is private. Administrator/OS recovery privileges are not an application ACL bypass.
3. Reuse the helper for upload temporary directory/input creation and `bb init`.
   Keep JVM/filesystem code out of `src/common`. If shared through `src/api`, add
   that source path to Babashka's development paths and load only the dependency-free
   file helper, never the HTTP/JDBC application. Verify Babashka's NIO/ACL support.
4. Existing secret files must never be replaced, printed or rotated implicitly.
   Preserve repeat-safe initialization. Reject unsafe existing paths with a useful
   message; do not change ownership or weaken access on an existing installation.
   Tests must use temporary synthetic secrets rather than the user's `.secrets`.
5. Add `.gitattributes` with `*.sh text eol=lf` and normalize only the tracked shell
   scripts if needed. Verify entrypoint/init scripts receive LF even in a temporary
   checkout configured with `core.autocrlf=true`. Do not blanket-renormalize the repo.
6. Make permission tests capability-aware. On POSIX assert existing 0700/0600;
   on ACL filesystems assert the private ACL and successful upload/cleanup. Test
   unsupported-provider and failed-permission enforcement paths without asserting
   that a mock constitutes native Windows validation.

Existing upload authentication/CSRF, byte/format limits, duplicate detection,
transactional staging and parser behavior remain authoritative. Invalid input must
continue returning the existing safe Polish 400/409/413 responses, not blanket 500.
Secret initialization remains an English developer command. No real workbooks or
donor data, production hosting decision, external service or package installation.

## Affected files and verification

- Add the dependency-free backend file helper and focused JVM/development tests.
- Update `src/api/alma_spei/api/http/imports.clj`, `dev/alma_spei/dev/tasks.clj`
  and `bb.edn` only as needed for helper reuse and temporary test initialization.
- Add `.gitattributes`; inspect `infra/postgres/entrypoint.sh` and
  `infra/postgres/init/01-roles.sh` without unrelated formatting changes.
- Update upload permission/cleanup integration tests and development/operations/
  decision/Trello documentation with platform evidence and unresolved native checks.

Tests: successful CSV/XLSX upload and safe rejection cases; temporary-data cleanup
on success/failure; POSIX and ACL policy branches; unsupported attribute avoidance;
permission failure before writing bytes; symlink/existing-file protection; repeat
initialization preserving synthetic secret bytes; no secret values in errors/output;
and LF checkout verification. Run `bb check`, `bb integration` and synthetic
`bb init`/PostgreSQL startup checks appropriate to the available platform.

Native Windows 11 / JDK 25 / Babashka plus QA cards 43–45 must verify actual Windows
uploads, `bb init` and PostgreSQL startup. This workstation cannot establish those
results; record that limitation and a concrete Windows verification command/checklist.
Do not mark Windows acceptance complete based solely on simulated provider tests.

## Risks, OPEN questions and approval record

ACL APIs and Babashka native-image support need confirmation; network/FAT or other
providers without usable private access controls must fail closed. Shared/group
service-account deployments require a separately approved access policy. A Windows
QA environment and its final validation remain OPEN; no production/GDPR sign-off
is implied.

The user approved conditional POSIX/verified ACL private-file handling, safe initialization
reuse, LF shell attributes and regression tests on this branch. This authorizes a
bounded compatibility/security fix, not duplicate QA implementation or weakening
private-data protection to make an upload pass.

## Delivery details

A dependency-free JVM helper under `src/api/alma_spei/api/fs` now chooses POSIX or
ACL creation attributes from the target store, verifies ownership/access and rejects
symlink/unsafe existing leaves. New empty ACL paths receive an owner-only ACL with
directory inheritance; existing paths are never chmodded, reowned or overwritten.
ACL readback must pass before any source/secret data is written. Unsupported providers
and failed enforcement fail closed; upload errors use a fixed safe Polish message.

Babashka 1.12.218 cannot load the required Java ACL classes. Following the approved
compatibility risk, `bb init` now invokes a lean JVM `:init` alias with only existing
Clojure 1.12.6. The helper is shared without adding API paths to Babashka, loading the
application or invoking Windows shell permission commands. Clojure CLI/JDK were
already development prerequisites. `--directory` permits safe synthetic verification
without touching the actual project secrets. Repeat initialization verifies and
preserves existing nonempty owner-only files; unsafe material is rejected.

`.gitattributes` pins shell scripts to LF. The two tracked PostgreSQL scripts already
contained LF, so no unrelated normalization was needed. A test checks real Git
checkout behavior with `core.autocrlf=true` in a separate temporary repository.
The upload cleanup test supports POSIX and ACL policies instead of unconditionally
reading POSIX attributes. All original upload/domain/authorization rules remain.

[Windows verification](windows-verification.md) gives concrete native checks for
initialization, ACL protection, repeated initialization, LF checkout, PostgreSQL
startup, valid/invalid uploads and external QA. Native Windows results remain OPEN;
no board completion/comment or external-QA success is claimed.

## Local verification

On macOS with Temurin 25.0.5+8, `bb check` passed: 56 JVM tests / 425
assertions, the reload check / 10 assertions, and 92 ClojureScript tests / 583
assertions, plus lint, formatting and application compilation. `bb integration`
passed 90 PostgreSQL tests / 759 assertions. All reported zero failures/errors.
`bb db-up` confirmed the local database healthy. Two real `bb init --directory`
runs against a disposable synthetic directory preserved all four existing values
and printed no secrets. Git whitespace checks passed. These results do not replace
the native Windows acceptance checklist.
