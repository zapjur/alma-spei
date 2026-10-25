# Architecture — confirmed constraints and proposed design

## Status and confirmed constraints

Monorepo, modular monolith, Clojure backend, ClojureScript with Reagent/re-frame frontend, PostgreSQL, shared `.cljc` domain models/Malli schemas/portable pure utilities, and unit/integration tests from the beginning are CONFIRMED. Malli remains the preferred schema tooling; exact dependencies and versions require explicit approval. No implementation or dependency selection is authorized by this document.

Local username/password MVP authentication with a replaceable provider boundary is CONFIRMED. Hospice-owned/administered on-premises infrastructure with internal-network access is the preferred deployment direction; final topology, runtime and orchestration are OPEN.

## Source layout and build strategy

```text
src/api/       Clojure: HTTP, application services, authorization, persistence, IO
src/ui/        ClojureScript: Reagent views, re-frame events/subscriptions, browser IO
src/common/    .cljc: portable models, Malli request/response schemas and pure utilities
```

PROPOSED supporting layout: `test/`, `resources/migrations/`, `infra/`, `scripts/`, `deps.edn`, `shadow-cljs.edn`. Propose Clojure CLI aliases for backend/dev/unit/integration workflows and a shadow-cljs browser build; both builds include `src/common`. Approval must cover compatible JDK, Clojure/ClojureScript, Node/build tooling, Reagent/re-frame, Malli, HTTP/router, lifecycle, SQL, migrations, XLSX/CSV, password hashing and tests, with exact verified versions. No versions are selected here.

Shared contract namespaces should separate donor, contribution, correspondence, import/review and API envelopes. Do not expose persistence records automatically. Common code cannot contain JDBC, filesystem/browser APIs or JVM-only imports. Backend-only utilities stay in `src/api`; backend authorization and authoritative validation apply even where client validation shares schemas.

## Logical flow and module boundaries (PROPOSED)

```text
Polish UI -> HTTP/API contracts -> session lookup + authorization -> application services
                                                                  -> PostgreSQL
Upload -> source adapter -> staging -> common normalization/validation/matching
       -> staff review -> approved preview -> transactional commit + audit/provenance
Contributions -> approved aggregation rules -> summaries
Donors -> explicit correspondence memberships -> mailing selection -> export
```

| Backend module | Responsibility |
| --- | --- |
| Authentication | Provider adapters, credential verification, identity mapping; no donor business rules |
| Users, authorization and sessions | Stable users, account lifecycle, app permissions, session issuance/revocation |
| Donors | Identity, attributes, corrections, explicit merges and archiving |
| Correspondence | Recipients/groups, memberships, mailing address and duplicate-mail rules; no financial merging |
| Contributions and reporting | Transaction facts, source aggregate observations, approved attribution and summaries |
| Imports and provenance | Three source adapters, staging, file fingerprints, validation, approval and commit orchestration |
| Matching and review | Evidence, uncertainty, conflict resolution and recorded staff decisions |
| Audit and undo | Protected before/after changes, import compensation and conflict/dependency checks |
| Exports | Authorized CSV/XLSX generation, mailing group selection and safe downloads |

Keep HTTP, business rules and persistence adapters separate within these modules. Import adapters must not persist canonical changes directly. Use in-process services and PostgreSQL; no broker or microservice is needed.

## Data model and migrations (PROPOSED)

Detailed candidate entities and discovery constraints are in [data-model.md](data-model.md). Stable internal IDs identify donors and users. Donors, correspondence groups, group membership and contribution attribution are separate relationships. Shared address/group membership is never financial identity evidence by itself.

Preserve discrete bank/tax source transaction facts where meaningful. Keep unresolved master annual/monthly observations separate from transaction facts; do not fabricate dates or sum overlapping representations. Attribution and deduplication can require review independently of donor matching. Tax-identification evidence is separate from amount attribution and correspondence grouping.

Import batches, source row locators, mapping/rule versions, review decisions and audit changes provide provenance. Persist only necessary source values under a documented retention/access policy; provenance need not mean indefinite raw workbook storage. Import-specific operations and entity versions support conflict-safe compensation of the latest committed import. Proposed transaction and concurrency details are in [import-workflow.md](import-workflow.md).

Use explicit ordered PostgreSQL migrations committed with reviewed changes. Initial scaffold migrations cover infrastructure/versioning only until data discovery and schema approval. Apply migrations once using controlled credentials; app credentials have least required privileges. Validate migration application and recovery against synthetic data. Destructive schema changes need backup and a tested recovery plan; reversible SQL alone is not a rollback guarantee. Prefer compatible additive changes and staged removal across releases.

## Provider-based authentication (PROPOSED interfaces)

Requirements are confirmed; the following interface shapes, tables and security mechanics require design approval. Keep this a small application boundary, not a general IAM framework.

- An authentication provider exposes `begin-authentication(context)`, `complete-authentication(context, input)` and optional provider logout. A local provider completes by verifying a username/password; future adapters may use a redirect/callback or directory bind. Return verified `{provider-id, subject}` or a generic failure. Providers never assign application permissions or issue application sessions.
- An identity resolver maps the verified pair to a stable internal user. Proposed `external_identities` has a unique `(provider_id, subject)` and a user reference. Local credentials reference the same user; user IDs and audit actors survive username or provider changes. Do not auto-link by matching email/name or auto-create users. Administrative linking requires identity verification, audit and uniqueness checks.
- A session service issues opaque random session identifiers after successful resolution and active-account checks. Store sessions server-side, rotate on login, expire idle/absolute lifetimes and revoke on logout/deactivation or security events. Provider tokens/credentials do not become business-service inputs. Session lifetime and storage details require approval.
- Each request resolves the session to an internal principal. Authorization checks application permissions independently of provider claims. All initial staff receive equivalent capabilities; account provisioning remains controlled, with administration responsibility still to be assigned. Future roles remain inside the application model.
- Local credentials use a vetted password-hashing library with a modern salted password hash (Argon2id proposed, parameters benchmarked before approval). No plaintext passwords, custom crypto or credential logging. Propose administrative provisioning/reset and first-use password change without self-registration; recovery mechanics remain OPEN.

Proposed configuration: enabled provider IDs, default login mechanism, local-login availability, session timeout/cookie settings, public base URL and trusted proxy settings. External issuer/client/directory settings would be added only with an approved adapter; secrets are injected separately and startup fails on invalid configuration. MVP enables local authentication only. Keep a deployment stable provider ID so renaming a display label cannot orphan mappings.

Use TLS, Secure/HttpOnly/SameSite cookies, CSRF protection on state-changing cookie-authenticated requests, login throttling, generic failure messages and protected security audit records. Exact mechanisms/libraries require approval. Application logout always revokes the app session; future provider-wide logout is separate and provider-specific.

Limitations: abstraction does not implement OIDC/LDAP, MFA, identity synchronization, federation, automatic account linking or organization-wide logout. Each integration needs its own threat review, mapping/provisioning policy, outage behavior and tests. Minimal changes means business services continue to consume the same internal principal; adapter/configuration and login UI work will still be needed.

## Deployment topology (PROPOSED)

```text
Staff browser on permitted network
  -> TLS endpoint / reverse proxy
  -> application process serving API and frontend assets
  -> PostgreSQL on protected host/network
  -> restricted backup destination
```

Support reproducible on-premises installation without mandatory cloud services. PostgreSQL is reachable only by authorized application/administration paths, never directly by staff devices. TLS may terminate at an IT-managed proxy; trusted proxy behavior must be explicitly configured. A dedicated host/VM or containers are options; runtime, OS and orchestration remain OPEN. A network drive may be evaluated as protected backup storage, not assumed to be an application host or suitable PostgreSQL storage.

IT must confirm DNS/certificates, firewall/VPN policy, service accounts, resources, storage, backup/restore capability and operational ownership. Internal access still requires full authentication and application security. [Operations](operations.md) defines the proposed installation, upgrade, recovery and handover procedures.

## Verification and approval gates

Unit tests cover portable normalization and contracts on JVM and JavaScript, matching/review rules, group selection and aggregation. PostgreSQL integration tests cover constraints, transactions, provenance, concurrent commits, undo conflicts, sessions and authorization. Test a fake future provider against the same internal-principal contract to demonstrate business-logic independence without implementing SSO. Use synthetic fixtures only.

Before coding, approve a milestone outline with affected files, schema/API changes, exact dependencies/versions, tests, risks and open questions. Before real data, approve source mappings, privacy policies and deployment/operational readiness. See [implementation-plan.md](implementation-plan.md).
