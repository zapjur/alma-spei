# Task 01 — Architecture and bootstrap proposal (PLAN ONLY)

Read `AGENTS.md`, `README.md` and all `docs/*.md` first.

Produce an English architecture proposal for Alma Spei's donor CRM. **Do not implement code, run scaffolding, install dependencies, create migrations or alter project files in this task.** Ask for approval before implementing.

## Confirmed constraints

- Monorepo; modular Clojure monolith backend; ClojureScript frontend with Reagent/re-frame; PostgreSQL.
- Shared `.cljc` code for portable domain logic and cross-platform Malli validation/contracts (Malli preferred).
- A Polish-language, desktop-first staff interface.
- Initial implementation milestone consists of backend/frontend skeleton, PostgreSQL, migrations, a basic page, and functioning unit/integration test setup.
- Tests from the beginning. Maintainable low-cost architecture; no unnecessary microservices.
- Exact library choices are NOT approved; propose them and give concise justifications, alternatives and compatibility considerations.
- Production hosting, network exposure and identity-provider integration remain open.
- Source files have not yet been inspected; do not invent donor fields, column mappings or matching thresholds.

## Required proposal sections

1. Suggested project directory tree and ownership boundaries, including `shared/` `.cljc` and test placement.
2. Suggested tooling/dependencies with justification and any compatibility caveats: Clojure build/deps, HTTP/router, lifecycle, DB access, migration tool, Malli, frontend build, Reagent/re-frame, testing, local PostgreSQL and development tooling.
3. Module boundaries and data flow, showing how frontend/backend reuse shared validation and how backend remains authoritative.
4. Local development start/stop/test/migrate commands, proposed only (do not run).
5. Safe first migrations (infrastructure/schema versioning only until source data reviewed), and how later domain migrations will be designed.
6. Error handling, logging/privacy, API conventions and configuration/secrets approach.
7. Explicit acceptance criteria and test matrix for the **first scaffold milestone only**.
8. Open decisions and risks, prioritizing auth/SSO, hosting and source-file discovery.
9. A short, ordered implementation checklist for milestone 1, with the exact folders/files you expect to create.

Finish by asking for approval of your dependency and scaffold proposal. Avoid implementing any part of the CRM in this step.
