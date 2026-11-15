# Windows verification — task 50

Status: **OPEN native-platform acceptance**. The implementation can be checked on
macOS/POSIX and its ACL policy tested through adapters, but those checks do not
establish Windows 11 / NTFS / Docker Desktop behavior.

Use a clean, disposable Windows 11 development environment, an individual account,
NTFS or another supported Windows ACL filesystem, and synthetic data only. Keep
real donor files, existing service installations and production databases out of
this procedure. Do not paste secrets, hashes, ACL identities or personal paths into
Trello/CI output. Record release/commit, tool versions and pass/fail summaries.

## Prerequisites and checkout

Use the versions already pinned in README/development.md: Temurin JDK 25.0.5+8.0.LTS,
Clojure CLI 1.12.5.1664 with project Clojure 1.12.6, Babashka 1.12.218, Node 24.20.0
and Docker Desktop with Compose v2. Clojure must be an executable reachable by
Babashka, rather than only an interactive PowerShell function. A first initialization
may resolve the already approved Clojure artifact; it loads no HTTP/JDBC dependencies.

Use the task 50 branch or its reviewed successor in a fresh checkout. To exercise
the CRLF case, configure `core.autocrlf=true` before checkout. Verify:

```powershell
git ls-files --eol infra/postgres/entrypoint.sh infra/postgres/init/01-roles.sh
```

Both scripts must report `w/lf` and `attr/text eol=lf`. The JVM unit suite also creates
a separate temporary Git repository and verifies checkout with autocrlf enabled;
it does not change the application's Git configuration or history.

## Protected initialization

First check the real wrapper against temporary synthetic files:

```powershell
$qaSecrets = Join-Path $env:TEMP ('alma-spei-secrets-qa-' + [guid]::NewGuid())
bb init --directory $qaSecrets
$before = @{}
Get-ChildItem -File $qaSecrets | ForEach-Object { $before[$_.Name] = (Get-FileHash $_.FullName).Hash }
bb init --directory $qaSecrets
$unchanged = @(Get-ChildItem -File $qaSecrets | Where-Object { $before[$_.Name] -ne (Get-FileHash $_.FullName).Hash }).Count -eq 0
if ($before.Count -ne 4 -or -not $unchanged) { throw 'Initialization was not repeat-safe' }
Write-Output 'PASS: four synthetic secret files preserved'
```

Successful initialization verifies owner-only ACLs before writing. Inspect the
ACL locally if needed, without copying it into shared logs. An unsafe existing
file/directory, symlink, empty existing secret or unsupported filesystem must fail
with a sanitized message and must not replace existing values or change an existing
installation's ownership/permissions. Do not solve a failed check by granting Everyone
access. New empty ACL paths may assign the current account as owner and restrict
access; existing paths are verification-only. Remove the temporary synthetic test
directory afterward using the normal operator-controlled cleanup process.

Babashka cannot load these ACL classes directly. `bb init` delegates to
`clojure -Srepro -M:init`, whose only dependency is the existing pinned Clojure.
It uses the same JVM file policy as uploads; no Windows shell permissions command.

## Startup and automated checks

In this fresh disposable checkout, run:

```powershell
bb init
bb db-up
bb check
bb integration
```

The Compose PostgreSQL service must become healthy; initialization scripts must not
report CRLF/`pipefail` or unreadable-secret errors. Tests must pass on the actual
filesystem without POSIX attributes being sent to the ACL provider. Review any
symlink test skipped because Windows denies symlink creation without developer
mode/privilege; do not treat that as native symlink coverage.

## Upload smoke and external QA

With only synthetic accounts/data, start the migrated API/frontend according to
README. Through the UI or authenticated API verify:

- A valid four-column headerless CSV and XLSX reach staging with HTTP 201.
- Invalid data/structure and duplicate bytes retain the specified 400/409 behavior;
  an oversized request retains 413. No unsupported POSIX attribute becomes a 500.
- Temporary upload directory/file access is private; successful/rejected uploads
  clean up after themselves. An intentionally unsupported/unsafe storage provider
  fails closed, without writing source bytes or exposing a path/exception diagnostic.
- Current review, preview, explicitly enabled synthetic commit and undo still follow
  their approved rules; this fix does not enable production import execution.

Run external QA cards 43–45 from `alma-spei-tests` against this application fix
checkout, sequentially as documented there (`ALMA_APP_REPO` points to this checkout).
Use current expected UI/contracts when testing the later stacked application;
classify a baseline-version expectation separately from an application regression.
Cards 47–49 own external review/commit/history QA; closed task 41 is not recreated.

Record which native checks actually ran and their results. Do not mark task 50's
Windows acceptance complete until Windows initialization, startup and uploads are
verified. macOS checks and mocked ACL branches are supporting evidence only.
