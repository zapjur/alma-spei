# Local development — Milestone 1

## Scope

The foundation is a synthetic-data-only modular monolith: a Ring/Jetty backend,
Reagent/re-frame browser shell, shared Malli health contract, explicit PostgreSQL
migrations, and automated checks. Local authentication and all donor, financial,
correspondence, import, audit and export features require later milestone approval.
The unauthenticated shell and health endpoints bind to loopback. This is not a
production deployment. Operations and privacy authority remains `operations.md`.

## Prerequisites and pinned versions

- Eclipse Temurin JDK **25.0.5+8** (JDK 25 LTS).
- Clojure CLI **1.12.5.1664**; the project pins Clojure **1.12.6** separately.
- Node **24.20.0**, with its bundled npm; `.nvmrc` records the version.
- Babashka **1.12.218** for development tasks (`bb.edn`).
- Docker Engine/Desktop with Compose v2. Local Compose was verified with
  **2.22.0-desktop.2**. Production containers/orchestration remain undecided.
- PostgreSQL **18.6**, pinned to a multi-platform image digest in `infra/compose.yaml`.

No global npm packages, Leiningen or editor integration are required.
Builds download Maven/Clojars/npm dependencies; runtime requires no cloud service.
Babashka is pinned in CI, with a matching minimum version in `bb.edn`.
Direct dependencies have exact versions in `deps.edn` and `package.json`;
`package-lock.json` locks the npm graph. Use `-Srepro` to exclude user Clojure
configuration. Maven transitive versions come from release POMs; there is no
claim of a full Maven lockfile or offline build.

## First start

Run commands from the repository root. Select the pinned JDK and Node using your
preferred runtime manager first. Docker Desktop must be running.

```sh
bb init
npm ci --ignore-scripts
bb db-up

bb migrate
clojure -M:repl
```

The backend task supplies local runtime credentials. The listener is
`http://127.0.0.1:3000`. In another terminal, run:

```sh
bb ui
```

Open the application after the first browser compilation. Jetty serves both API
and assets; shadow-cljs supplies hot reload. Restarting the browser root is
idempotent. Exit REPLy with Ctrl-D or `(exit)`; shutdown stops the backend,
watcher and nREPL and removes `.nrepl-port`. `bb repl` runs the same entry point
with reproducible CLI settings. `bb api` remains available without REPL tooling.

`bb init` creates random local credentials, preserves existing
files, and prints no values. Do not replace these files while retaining the
existing database volume: PostgreSQL bootstrap scripts run only on first
initialization, so changing files does not rotate database passwords.

## Configuration

Babashka tasks supply local secret-file paths when not explicitly configured,
using child-process environment variables. They never change your shell environment.
`bb integration` sets `ALMA_ENV=test` for its child process only.

Application precedence: `resources/config.edn`, optional EDN file named by `ALMA_CONFIG_FILE`,
then explicit environment overrides. Local EDN may override non-secret settings;
its `:database` map must not contain `:password`.

| Setting | Environment override | Default |
| --- | --- | --- |
| Environment | `ALMA_ENV` | `development`; `test` is also supported |
| HTTP host | `ALMA_HTTP_HOST` | `127.0.0.1`; only loopback permitted |
| HTTP port | `ALMA_HTTP_PORT` | `3000` |
| Database host | `ALMA_DB_HOST` | `127.0.0.1` |
| Database port | `ALMA_DB_PORT` | `5432` |
| Database name | `ALMA_DB_NAME` | `alma_spei_dev` |
| Runtime database user | `ALMA_DB_USER` | `alma_spei_app` |
| Runtime password | `ALMA_DB_PASSWORD_FILE` or `ALMA_DB_PASSWORD` | Required; mutually exclusive |
| Migration user | `ALMA_MIGRATION_USER` | Required by migration command |
| Migration password file | `ALMA_MIGRATION_PASSWORD_FILE` | Required by migration command |
| Integration administrator password file | `ALMA_TEST_ADMIN_PASSWORD_FILE` | Required only by integration suite |

Database connection and query timeouts default to 3 and 2 seconds; configure
`:database :connect-timeout` and `:query-timeout` in local EDN, each 1–30 seconds.
Invalid or missing configuration fails with a safe error. Backend configuration
is never sent to the browser. Keep secrets in ignored protected files, never in
shell history, committed examples or logs. `.secrets` is created with mode 0700
and new files with mode 0600.

For a non-default PostgreSQL port, set `ALMA_DB_PORT` for both Compose and the
application. Optionally copy `infra/compose.env.example` to `.env.compose.local`
and pass `--env-file .env.compose.local` to Compose. Compose does not export
settings into the JVM process.

## Database and migrations

The bootstrap administrator owns database creation. `alma_spei_migrator` can
create application tables in `public`; `alma_spei_app` has only explicitly granted
runtime privileges. No application credentials are superusers. The initial
migration creates `application_metadata`, containing one foundation version, and
grants runtime SELECT access. Migratus owns `schema_migrations` history.

```sh
bb migrate
# Local/test database only: reverse the latest migration.
# Direct CLI use requires the runtime and migration variables listed above.
clojure -Srepro -M:migrate down
```

Migrations never run during application startup. Keep applied migrations immutable
and add ordered `*.up.sql`/`*.down.sql` files for approved schema changes. A down
migration is not a production recovery procedure. Backups, deployment migrations,
and application import undo are separate mechanisms described in `operations.md`.

The JDBC DataSource opens short-lived connections without a pool. Connections and
transactions are scoped by next.jdbc. Pooling requires a later approved dependency
before business workloads; no connection state is shared between requests.

## Automated verification

```sh
bb check
bb integration
```

`bb check` runs lint, formatting verification, JVM unit/common tests, Node UI/common
tests, and an optimized browser release. Individual commands:

```sh
clojure -Srepro -M:lint
clojure -Srepro -M:format check
clojure -Srepro -M:format fix
clojure -Srepro -M:test
clojure -Srepro -M:ui -m shadow.cljs.devtools.cli compile test
clojure -Srepro -M:ui -m shadow.cljs.devtools.cli release app
```

`bb integration` supplies `ALMA_ENV=test` and local secret-file defaults.
Direct invocation of the Clojure integration alias requires those environment
variables explicitly (see the configuration table). Integration tests create
unique `alma_spei_test_<uuid>` databases through the separate
administrator connection, then remove only those generated databases in a
`finally` block. They do not clear or migrate the configured development database.
Do not supply production database credentials. All fixtures are synthetic.

After migrating development and starting the backend, verify optimized assets:

```sh
bb smoke
```

Browser acceptance: open the page, check Polish loading/ready/error states,
keyboard focus and the retry button; confirm one mounted application after hot
reload. Node tests do not replace this DOM check. Browser developer tools should
show successful JavaScript/CSS requests and no console errors.

Health endpoints:

- `/api/health/live`: 200 while HTTP is running, independent of database state.
- `/api/health/ready`: 200 with the expected foundation marker, 503 when the
  database/schema is unavailable; database work has bounded timeouts.
- Unknown `/api/*`: JSON 404; no HTML fallback.

The shared JSON contract is `{:status "ok"}` or `{:status "unavailable"}` after
keyword-key decoding. Closed Malli validation runs on JVM and JavaScript.
Operational details and exceptions are excluded from responses.

## Stop and restart

Stop shadow-cljs and the backend, then:

```sh
bb db-down
```

The volume persists. Restart with `bb db-up`; `bb migrate` is idempotent.
Do not use `down --volumes` unless intentionally discarding all local database data.
CI uses that option only for its own disposable environment.

## CI

GitHub Actions runs quality/build and PostgreSQL integration/HTTP smoke jobs.
It uses pinned JDK, Node, Babashka and CLI versions, immutable action SHAs, a verified
CLI installer and Babashka archive, the same Compose file, disposable generated secrets, read-only
repository permissions and job timeouts. npm caches are keyed by the lockfile.
No deployment or production secrets are involved. GitHub-hosted CI is a build-time
choice, not a production hosting decision. Do not upload database files, raw
application exceptions or secret-bearing artifacts.

## Known boundaries

Authentication, secure sessions, business permissions, domain tables, workbook
parsing, backups and production logging/monitoring are not implemented here.
The SLF4J API currently uses its default no-operation provider; a logging provider
and redaction policy require an approved follow-up. Startup failures and HTTP
errors intentionally expose only generic diagnostics. This foundation must not
process real personal data or listen on a public interface.

## Verification evidence (2026-10-25)

Verified locally using Temurin 25.0.5+8, Clojure CLI 1.12.5.1664, Node 24.20.0
and Compose 2.22.0-desktop.2 with the pinned PostgreSQL image:

- Lint: zero errors/warnings; formatting check and `git diff --check` passed.
- JVM: 6 tests, 29 assertions, zero failures/errors.
- JavaScript: 5 tests, 13 assertions, zero failures/errors.
- PostgreSQL: 3 integration tests, 19 assertions, zero failures/errors.
- Optimized frontend and development watcher compiled with zero compiler warnings.
- HTTP smoke verified health, Polish HTML, optimized JavaScript, and correct
  JavaScript/CSS content types under `nosniff`.
- Temporary headless Chrome verified mounted Polish ready/error/invalid-response
  states, retry recovery, and one root after repeated mount calls, with no
  JavaScript runtime exceptions.

The compiler dependency emits a JVM `sun.misc.Unsafe` deprecation notice, and the
Node test runner reports optional source-map support is absent. Neither caused a
test/build failure; no unapproved diagnostic dependencies were added.
GitHub Actions itself has not been run remotely; its equivalent local commands
passed. Interactive keyboard/accessibility review and a second person's clean
checkout rehearsal remain manual review tasks.

Babashka migration verification: `bb tasks`, `bb init`, `bb db-up`, `bb migrate`,
`bb api`, `bb check`, `bb integration`, and `bb smoke` were verified locally with
Babashka 1.12.218. Checks covered generated secret permissions, preservation of
existing credentials, and rejection of invalid or failed HTTP responses. The
unit/integration counts above remain unchanged. CI is updated to install the
pinned checksum-verified Babashka archive and use the same tasks; it has not been
run remotely.

## Backend REPL and reload-on-save

`clojure -M:repl` (or `bb repl`) starts the application, REPLy terminal, a
loopback-only nREPL on an ephemeral port, and a development source watcher.
The alias adds only development dependencies: `reply/reply` 0.5.1,
`nrepl/nrepl` 1.8.0, `cider/cider-nrepl` 0.63.1, and
`org.clojure/tools.namespace` 1.5.1. Ordinary application and frontend classpaths
do not include these direct development dependencies.

The entry point defaults to `.secrets/app-password` when neither runtime secret
environment variable is set. Explicit overrides take precedence. `bb init`,
`bb db-up` and `bb migrate` are still required on a fresh checkout. Do not run
`bb api` at the same time as the REPL on the same HTTP port.

Connect Calva using **Connect to a Running REPL Server in the Project**, select
Clojure, and use the port in `.nrepl-port`. The editor and REPLy share a JVM.
No extension is required to trigger save/reload; the backend watcher runs locally.

The watcher polls `src/api` and `src/common` every 300 ms, waits for a stable
file snapshot, stops the application, refreshes changed namespaces and their
dependents, then starts the application again. There is a brief HTTP interruption
and in-memory component state may reset. Database contents persist. Only `.clj`
and `.cljc` source files are watched. No migrations run during reload.

A syntax/compile error leaves the backend stopped while the REPL and watcher
remain available. Fix and save the file to retry, or call `(user/reload)`.
`@alma-spei.dev.reload/status` reports `:ready`, `:error`, or `:stopped`;
`@alma-spei.dev.reload/last-error` retains the last failure for local inspection.
Do not copy sensitive exception data into logs or issue reports.

Manual lifecycle helpers remain `(user/start)`, `(user/stop)` and `(user/restart)`.
When the watcher is active, another source save will start the application again.
Restart the entire development command after changes to dependencies, `dev/`
tooling, or resources/configuration. Frontend changes remain handled by `bb ui`.

`clojure -Srepro -M:repl:test-repl` runs the synthetic reload integration test.
It is included in `bb check` and CI; it verifies dependency-aware HTTP handler
reload, recovery after a malformed source file, and release of the listener on
shutdown without changing application source files or accessing PostgreSQL.

REPL workflow verification: REPLy terminal evaluation and the cider `info`
operation were verified against the running local nREPL. A saved backend file
triggered successful reload and the liveness endpoint responded afterward.
`bb check` passed with 6 JVM unit tests/29 assertions, 1 reload test/10 assertions,
5 JavaScript tests/13 assertions, lint/format checks, and the optimized build.
