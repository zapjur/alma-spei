# Milestone 2A — authentication implementation proposal

## Status and scope

The user explicitly approved this technical proposal and instructed implementation
on 2026-10-25. The account workflows, dependency and implementation choices below
are approved for Milestone 2A. Sections originally marked PROPOSED preserve the
reviewed outline; implementation evidence and limits are recorded at the end.

Scope: local authentication, named accounts, server sessions, account
administration, security audit and Polish UI. Donor tables, spreadsheet adapters,
financial rules and production deployment remain outside this milestone.

## CONFIRMED — user decisions

- Administrator-assigned, immutable, unique, case-insensitive usernames; editable
  display names.
- Equal CRM capabilities for staff, with separate account-administration
  permissions and multiple designated administrators.
- A server command provisions the first administrator. Subsequent accounts are
  managed in the Polish UI.
- Generated temporary passwords are displayed once for secure handover; first
  login requires a password change. Forgotten passwords use administrator reset.
- Passwords contain at least eight characters, no spaces, at least one uppercase
  letter, one number and one symbol. Users change their own password by supplying
  the current password.
- No persistent browser login. Idle timeout: 30 minutes. Absolute timeout: eight
  hours. Password changes/reset revoke other sessions; deactivation revokes all
  sessions immediately.
- Preserve historical actor attribution after deactivation. Prevent deactivating
  or removing administrator permission from the last active administrator.

## PROPOSED — dependencies and compatibility

Add only `buddy/buddy-hashers` **2.0.167** to the backend dependencies for vetted
Argon2id derivation and verification. Its published dependencies include
`buddy/buddy-core` 1.11.423 and `clojurewerkz/scrypt` 1.2.0. The project retains
its existing Clojure 1.12.6 pin. Review the resolved dependency tree and test on
the pinned JDK 25 before accepting compatibility; compatibility is not yet
verified in this repository.

Use Argon2id with random 16-byte salts, 65,536 KiB memory, two iterations and
parallelism one as the initial benchmark configuration. Measure verification
latency and memory use locally; report results before changing these costs.
Limit concurrent password derivations to bound resource use. Never implement
password hashing ourselves.

Use existing Ring, Reitit, Malli, next.jdbc, Migratus, Reagent and re-frame.
Use JDK SecureRandom for opaque tokens and standard SHA-256 for token digests;
token hashing is distinct from password hashing. No JWT, email service, SSO,
additional frontend library or infrastructure service.

References:
- https://clojars.org/buddy/buddy-hashers/versions
- https://github.com/funcool/buddy-hashers/blob/master/doc/user-guide.md

## PROPOSED — backend and shared boundaries

- `src/api/alma_spei/api/auth/`: password adapter, local provider, identity
  resolution, sessions, authorization, persistence and application services.
- `src/api/alma_spei/api/http/auth.clj`: request/response translation, cookies,
  CSRF and authentication routes; business rules stay in services.
- `src/api/alma_spei/api/accounts/`: administration, bootstrap command and
  security audit persistence.
- `src/common/alma_spei/common/contracts/auth.cljc`: explicit request/public
  response schemas and portable password validation. Never expose hashes,
  session digests or persistence rows through public contracts.
- `src/ui/alma_spei/ui/auth/` and `accounts/`: Polish forms, events and views.

Providers return a verified provider/subject pair. Identity resolution yields a
stable internal user; session issuance and permissions belong to the application.
MVP enables only the local provider. Test a fake provider at the same boundary
without implementing external integrations.

## PROPOSED — schema and migrations

Add an ordered migration after the existing foundation migration:

| Table | Purpose and constraints |
| --- | --- |
| `users` | UUID ID, immutable canonical username with unique constraint, display name, active flag, account-administrator flag, timestamps |
| `local_credentials` | One credential per user, encoded Argon2id hash, required-password-change flag, credential version and change timestamp |
| `external_identities` | Unique provider/subject mapping to internal user; local subject uses immutable user ID |
| `sessions` | Token digest only, user ID, credential version, creation/last-activity/absolute-expiry timestamps and CSRF token digest |
| `auth_throttle_buckets` | Expiring per-username and client-address attempt counters; global concurrency bounded separately |
| `security_audit_events` | Actor/target IDs, event type, timestamp and allowlisted account-change metadata; no credentials or session tokens |

Separate runtime privileges from migration ownership. Runtime may append audit
events but cannot update/delete them. Accounts are deactivated, not deleted.
Account changes, session revocation and audit writes share one transaction.
Serialize administrator-membership changes with a transaction-level lock and
recheck the last-active-administrator invariant. Lock/version credential changes
so concurrent login/reset cannot issue a session against stale credentials.

Throttle login and current-password verification. Initial proposal: five failed
attempts per username per 15 minutes and 30 per client address per 15 minutes;
expire counters and cap request body size at 16 KiB. Use the direct peer address
in local development; do not trust arbitrary forwarded headers. Retention of
security audit and throttle metadata remains a production privacy decision.

## PROPOSED — HTTP and browser flow

| Endpoint | Behavior |
| --- | --- |
| `GET /api/auth/session` | Return anonymous state or current public principal and password-change requirement; establish CSRF context |
| `POST /api/auth/login` | Verify username/password, rotate session, return public principal or generic failure |
| `POST /api/auth/logout` | Revoke current session and clear cookie |
| `POST /api/auth/password` | Verify current password, validate replacement, replace hash, rotate current session and revoke others |
| `GET /api/accounts` | Administrator-only account listing |
| `POST /api/accounts` | Administrator-only creation; return generated temporary password once |
| `PATCH /api/accounts/:id` | Administrator-only display name, active state and administrator permission changes |
| `POST /api/accounts/:id/reset-password` | Administrator reset; revoke all target sessions, require first-use change, return temporary password once |

Return 401 for unauthenticated protected requests, 403 for insufficient permission,
409 for invariant/uniqueness conflicts, 422 for validation failures and 429 for
throttling. Use generic Polish errors for failed authentication. Closed Malli
contracts reject unexpected fields; server validation is authoritative.

Restrict username input to 3–64 ASCII letters/digits plus `.`, `_` and `-`,
canonicalized to lowercase. Display names accept 1–120 trimmed characters.
Passwords accept 8–128 Unicode code points, reject all Unicode whitespace,
require an uppercase letter, a decimal digit and a punctuation/symbol character.
Do not trim or normalize passwords. Keep JVM/JavaScript validation consistent.
Generated temporary passwords use at least 20 random characters and satisfy
the same policy; reject reuse of the current password.

Use an HttpOnly, SameSite=Strict session cookie without Max-Age/Expires, Path=/,
and Secure outside explicit loopback HTTP development. Browser session restore
can preserve a session cookie; server expiry remains authoritative. Rotate tokens
on login and password change. Store only token digests in PostgreSQL.

Use a random CSRF context and required header for state-changing requests,
including login; validate configured same-origin requests as an additional
defense. CSRF context for anonymous requests is signed with a protected,
file-injected server secret. Session and temporary-password responses use
Cache-Control: no-store. Never store passwords or session tokens in localStorage,
URLs, re-frame persistent state, logs or audit metadata. Clear password form state
after submission and temporary-password UI on dismissal/navigation.

A required-password-change session may only inspect its own session, change its
password or log out, even when its user is an administrator. Show the Polish
login form, mandatory-change form, signed-in shell, own-password form and
administrator account list/create/edit/reset flows. Confirm resets/deactivation
and explain session expiry in Polish. Existing health routes remain public and
do not reveal account information.

The first-administrator command requires an interactive terminal, accepts no
password argument, generates a temporary password and displays it once. It uses
a transaction lock and refuses to run if any account already exists. It uses
the normal application services and audit semantics, with an explicit bootstrap
event. The command is not a general recovery bypass.

## Affected files and local workflow

Add the namespaces and migration above; modify `deps.edn`, `bb.edn`,
`dev/alma_spei/dev/tasks.clj`, configuration loading/schema, system composition,
HTTP routes, frontend shell/events/subscriptions, styles, test runner discovery
as needed and synthetic fixtures. Update README, development, architecture,
decisions, data-model, operations and implementation-plan documentation.

Keep existing source paths and Clojure CLI/shadow-cljs build strategy.
Extend `bb init` to create the protected CSRF signing secret without printing
it; preserve existing secrets. Run `bb migrate`, then the new interactive
`bb create-admin`, and start with existing backend/UI tasks. Development reload
must preserve PostgreSQL-backed sessions and throttle state.

## Tests and acceptance criteria

- Shared JVM/JavaScript tests cover exact password rules, Unicode whitespace,
  username handling, bounds and public contract validation.
- Password adapter tests verify round trips, wrong passwords, randomized salts,
  temporary-password policy and absence of plaintext in stored credentials.
- Synthetic PostgreSQL tests cover uniqueness, stable identity, bootstrap races,
  first-use restrictions, timeouts, logout, password/session rotation, reset and
  deactivation, and rollback when audit writes fail.
- Concurrency tests cover last-administrator changes and login/reset races.
- HTTP tests cover CSRF including login, anonymous/regular/admin authorization,
  tampered/expired tokens, generic failures, throttling, no-store and cookie flags.
- UI tests cover login/change/reset/account state transitions and clearing secrets;
  browser review covers keyboard use and Polish loading/error/success states.
- Fake-provider test demonstrates provider-independent principal/permissions.
- Existing checks, optimized UI build, PostgreSQL integration and updated smoke
  checks pass; benchmark evidence and unverified manual checks are recorded.

Acceptance: a fresh synthetic setup provisions its first administrator, requires
the password change, creates a second administrator and ordinary staff member,
enforces all agreed account/session rules on the server, and records protected
security events. Existing foundation health behavior remains functional.

## Risks and remaining decisions

The chosen eight-character minimum is a confirmed user decision. Argon2id and
throttling reduce attack feasibility but do not make weak passwords strong.
No MFA or external authentication is included. Account reset authority is
sensitive; the audit records actor and target without the temporary password.
One-time display means the application does not retain a retrievable plaintext
copy; it cannot prevent an authorized administrator recording that display.

The technical proposal and exact new dependency were approved before application
implementation. A replacement dependency still requires approval.

OPEN before deployment: named administrators, secure handover channel, approved
TLS/proxy configuration, privacy/retention policies, benchmark results on target
hardware, and recovery when all administrators lose access. These do not block
synthetic local implementation after technical approval.


## Implementation evidence and limits

Implemented on branch `m2-auth-foundation`. Verification uses the pinned JDK
25.0.5+8 and Node 24.20.0. `clojure -Srepro -Stree` confirmed the new dependency
graph and preservation of the explicit Clojure pin. This is compatibility
verification, not a comprehensive vulnerability audit of all transitive libraries.

- `bb check`: lint and formatting passed; JVM 13 tests / 90 assertions, reload
  1 / 10, JavaScript 9 / 44, and optimized browser build passed.
- `bb integration`: 12 tests / 61 assertions passed in isolated synthetic
  PostgreSQL databases, including last-administrator, bootstrap and login/reset
  concurrency, atomic audit failure and actual Ring JSON/cookie handling.
- Argon2id verification: ten warm samples, mean 113 ms, range 93–191 ms locally;
  65,536 KiB memory, two iterations, parallelism one. Two derivations may run
  concurrently; security transactions additionally serialize password operations.
- Headless Chrome: login, mandatory password change, account creation without
  changing the current principal, one-time password dismissal, logout and
  case-insensitive login passed, with no JavaScript runtime exceptions. Browser
  requests confirmed no-store and rejection of missing CSRF headers.

The UI uses small Reagent components with transient local state and pure tested
state transitions. Passwords go directly from form controls to fetch and are not
stored in re-frame or persistent browser state. Administration results update the
affected account without replacing the signed-in principal.

Current runtime configuration is explicitly loopback HTTP development/test only.
Cookie construction supports Secure, but production TLS/proxy configuration is
not implemented or authorized. The expected Origin is derived from the configured
loopback listener. Use exactly that origin in the browser.

Security audit retention, production dependency/security review, target hardware
capacity and recovery after loss of all administrators remain deployment gates.
No real donor data was processed. Interactive keyboard/accessibility review and
remote GitHub Actions execution remain unverified.

Sessions compare a provider-independent `users.security_version` with the version
stored at issuance. Local credential changes increment both their own credential
version and the user's security version, then revoke all sessions atomically.
A PostgreSQL fake-provider test proves that session issuance and permissions work
without a local credential; this does not implement an external provider.
