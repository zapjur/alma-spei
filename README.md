# Alma Spei — Donor Management System

A Polish-language internal donor CRM for Alma Spei hospice, with a strong focus on reliable consolidation of donor and donation data from multiple sources.

## Agreed stack and development approach

- Monorepo with Clojure modular monolith (backend), ClojureScript with Reagent/re-frame (frontend), PostgreSQL, and shared `.cljc` code.
- Prefer Malli schemas for shared domain contracts and validation; confirm precise library choices and version compatibility in a proposal before implementation.
- Tests from day one, especially unit and integration tests around matching, imports, auditing, and rollback.
- Desktop-first, Polish-language application; English identifiers, technical documentation, agent instructions and prompts.
- Small controlled delivery phases. Codex must propose a plan and wait for approval before material implementation.

## Documentation

- [Requirements](docs/requirements.md)
- [Architecture](docs/architecture.md)
- [Data model](docs/data-model.md)
- [Import workflow](docs/import-workflow.md)
- [UX strategy](docs/ux-strategy.md)
- [Operations, security and handover](docs/operations.md)
- [Decision log and open questions](docs/decisions.md)
- [Implementation roadmap](docs/implementation-plan.md)

## Development foundation

Milestone 1 provides a local application skeleton and synthetic-only test harness.
See [Local development](docs/development.md) for prerequisites, startup, migrations,
configuration, tests, CI and current limitations. It is not ready for production
or real donor data.

## Launch locally

Prerequisites: JDK **25.0.5+8.0.LTS**, Clojure CLI **1.12.5.1664**, Node **24.20.0**
(with npm), Babashka **1.12.218**, and running Docker Desktop/Engine with Compose v2.
Select these tool versions first, then run commands from the repository root.
Babashka installation options are in the [official guide](https://book.babashka.org/#_installation);
CI installs the pinned release with checksum verification.

In terminal 1, prepare PostgreSQL, apply migrations, and start the backend:

```sh
bb init
npm ci --ignore-scripts
bb db-up
bb migrate
bb repl
```

In terminal 2, start the frontend compiler with hot reload:

```sh
bb ui
```

After compilation completes, open **http://127.0.0.1:3000**. The Polish shell
should display “Aplikacja jest gotowa.” Keep both terminal processes running.
The backend command opens REPLy and starts an nREPL with cider middleware on
loopback. Save a `.clj`/`.cljc` file under `src/api` or `src/common` to reload the
backend inside the same JVM. Application components briefly stop and restart;
in-memory component state may reset, while database contents remain intact.
`bb repl` is equivalent and uses reproducible CLI settings.

In VS Code with Calva, use **Connect to a Running REPL Server in the Project**,
select the Clojure project, and use the port in `.nrepl-port`. The terminal REPL
and editor share the JVM. Run `(user/reload)` for a manual reload. A syntax error
leaves the REPL/watcher running; fix the file and save to recover. The backend
stays stopped until a reload succeeds.

Changes to dependencies, development tooling, or configuration resources require
restarting the development command. Frontend save/reload is handled by `bb ui`.
For an optimized build without a watcher, run `bb build` before `bb api`,
instead of starting `bb ui`.

Local passwords are stored in ignored `.secrets/` files. `bb init` preserves
existing files; rerunning it does not rotate passwords in an existing database.
The tasks supply local secret-file paths automatically; no manual exports are
needed. Explicit environment overrides still take precedence.

## Run checks

Run these commands from another terminal at the repository root:

```sh
# Lint, formatting, JVM/JavaScript tests, and optimized frontend build.
# Stop bb ui first: watcher and release builds write to the same directory.
bb check

# PostgreSQL integration tests; bb db-up must have completed.
bb integration

# HTTP smoke checks; backend/database must be running, with compiled assets.
bb smoke
```

Run `bb tasks` to list available commands. Tasks are declared in `bb.edn`, with
Clojure helper functions under `dev/alma_spei/dev/tasks.clj`. They use Babashka
built-ins; no additional Clojure or npm libraries are required for the helpers.
Node remains necessary for the frontend. Integration tests use isolated synthetic
databases.

Exit REPLy with **Ctrl+D** or `(exit)`; stop `bb ui` with **Ctrl+C**. Then stop PostgreSQL:

```sh
bb db-down
```

This preserves the database volume. Repeat the launch commands to restart.
See [Local development](docs/development.md) for configuration and troubleshooting.
