# Architecture — constraints and proposals

## Confirmed architectural choices

- Monorepo, modular monolith.
- Clojure backend.
- ClojureScript frontend using Reagent and re-frame.
- PostgreSQL datastore.
- Application source code organized under `src/api`, `src/ui`, and `src/common`.
- Shared `.cljc` code in `src/common` for pure cross-platform data definitions, Malli schemas, API contracts, validation, and portable domain utilities.
- Unit and integration tests from the beginning.
- Codex proposes exact dependencies and versions; the user approves before implementation.

## Confirmed source layout

```text
src/
├── api/             # Clojure backend
│                    # HTTP API, application services,
│                    # PostgreSQL adapters, business logic
│
├── ui/              # ClojureScript frontend
│                    # Reagent views, re-frame events,
│                    # subscriptions and UI components
│
└── common/          # Shared Clojure/ClojureScript
                     # Malli schemas, API contracts,
                     # pure validation and domain utilities
```

Suggested repository-level organization (details subject to approval):

```text
alma-spei/
├── src/
│   ├── api/
│   ├── ui/
│   └── common/
├── test/
├── resources/
│   └── migrations/
├── infra/
├── scripts/
├── docs/
├── deps.edn
├── shadow-cljs.edn
├── README.md
└── AGENTS.md
```

The `src/api`, `src/ui`, and `src/common` boundaries are confirmed. Supporting directory placement, build configuration, and test organization remain subject to the approved implementation proposal.

## Suggested backend modules

- Identity and users (provider-independent at the application boundary)
- Donors and organizations
- Donations
- Imports and provenance
- Matching and manual review
- Audit and history
- Export

Keep database access, external IO, business rules, and HTTP routing clearly separated within the modular monolith.

Prefer pure matching, normalization, and domain functions for testability.

## Common code policy

Place portable, cross-platform code in `src/common/**/*.cljc`.

Appropriate responsibilities include:

- Malli schemas for shared request and response contracts.
- Portable domain data definitions.
- Pure validation and normalization functions.
- Shared constants and enumerations.
- Pure domain utilities needed by both frontend and backend.

Rules:

- Shared code must remain compatible with both Clojure and ClojureScript.
- Do not introduce JDBC, filesystem access, browser-only APIs, or JVM-only imports into common code.
- Avoid platform-specific dependencies unless explicitly isolated through reader conditionals and justified.
- Server-side authorization and authoritative validation must remain in the backend.
- Do not automatically expose persistence entity schemas as public API contracts.
- Keep database access and infrastructure concerns in `src/api`.
- Keep UI state management, browser interactions, Reagent components, and re-frame logic in `src/ui`.
- Avoid placing code in `src/common` solely because multiple backend modules use it. Backend-only utilities should remain within the backend.

Both backend and frontend build configurations must include `src/common` in their source paths.

Namespace naming should reflect the logical module boundaries, for example:

```clojure
alma-spei.api.donors.service
alma-spei.ui.views.donors
alma-spei.common.schemas.donor
```

## Integration boundaries and operations

- PostgreSQL migrations are explicit, versioned, and reversible where feasible.
- Local containerized development is a **PROPOSAL**, not a confirmed deployment environment.
- Production host, deployment method, network access, authentication system, backup storage, and monitoring remain **OPEN**.
- Do not implement a public deployment or choose an identity provider without a decision.

## Dependencies to evaluate in the first proposal

- HTTP server and router
- System lifecycle management
- SQL access
- Database migration tooling
- Malli integration and schema organization
- Authentication and session mechanics (design only, pending identity decision)
- ClojureScript build tool
- Reagent and re-frame versions
- Component and UI approach
- Test libraries
- Integration test infrastructure and fixtures
- XLSX and CSV processing

Do not choose versions by guesswork. Validate compatibility before implementation.