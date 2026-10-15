# Alma Spei — Donor Management System

A Polish-language internal donor CRM for Alma Spei hospice, with a strong focus on reliable consolidation of donor and donation data from multiple sources.

**Status:** requirements agreed in discovery; implementation not started. This repository is a planning baseline, **not** authorization to implement the entire MVP.

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
- [Security and operations](docs/security-operations.md)
- [Decision log and open questions](docs/decisions.md)
- [Implementation roadmap](docs/implementation-plan.md)
