# Incremental implementation plan

Codex must produce a proposed plan and obtain approval at each milestone before executing it. This roadmap is sequencing guidance, not a blanket build order.

## Milestone 0 — architecture and dependency proposal (NEXT; planning only)

Deliverables: monorepo layout, module boundaries, proposed Clojure/ClojureScript toolchain and libraries (including Malli), migration approach, frontend build approach, dev environment strategy, first tests, security constraints and open risks. Explicitly distinguish confirmed vs proposed. **No code generation yet.**

## Milestone 1 — approved development skeleton

- Backend health endpoint and basic modular application structure.
- ClojureScript + Reagent/re-frame bootstrap with Polish welcome/navigation screen.
- Shared `.cljc` package with a representative pure Malli schema/validator.
- PostgreSQL local development service and migration runner.
- Unit and integration test harness with smoke tests.
- Reproducible local development commands and contributor README.
- No real donor data and no fabricated production donor schema.

Acceptance: clean checkout can start local dependencies, apply migrations, run tests and launch UI/API; code is documented and appropriately isolated.

## Milestone 2 — discovery of real data and approved schema

Obtain authorized source samples; enumerate fields and formats, propose canonical model, provenance strategy, matching evidence and donation deduplication. Approve migration and import behavior before implementing live imports.

## Milestone 3 — donor profile, donation history, audit and archiving

Implement confirmed model with safe create/edit/list/detail/merge/archive, input validation, pagination, and tests. Auth implementation timing is gated on the identity-provider decision; do not expose an unprotected production system.

## Milestone 4 — staging and import preview

Build parsing, validation, fingerprinting, staging, classification, review queue and side-by-side diff UI. Test unusual and malicious inputs, boundaries and repeat files.

## Milestone 5 — import commit, undo and export

Atomic confirmed import, audit/provenance and payment deduplication, import history and conflict-safe undo of last import, Excel/CSV export and additional integration tests.

## Milestone 6 — deployment hardening and handover

Confirm hosting and authentication, backups/restores, logging policy, access controls, GDPR operations, test recovery and staff usability.

## Deferred unless approved

Complex permission roles, self-registration, dashboards/advanced reports, asynchronous distributed infrastructure and internet-facing deployment.
