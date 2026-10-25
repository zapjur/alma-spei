# Decisions and open questions

Updated 2026-10-25 from stakeholder instructions. CONFIRMED denotes explicit requirements, PROPOSED denotes unapproved design, and OPEN denotes missing evidence or approval. The baseline initially authorized documentation only. The Milestone 1 approval below authorizes the foundation implementation.

## CONFIRMED — product and engineering

- Central CRM with individuals, businesses and organizations; seed from the master then reconcile tax designation and bank transfer sources through distinct adapters sharing common services.
- Source descriptions are preliminary; inspect actual workbooks securely before final fields, mappings, identifiers, matching thresholds or deduplication rules.
- Donor profiles and contribution histories are separate. Preserve relevant transaction detail and provenance; do not retain every input field by default.
- Separate donor identity from correspondence recipients/groups. Prevent duplicate spouse/group mail without combining donor financial histories or treating shared addresses as identity.
- Manual donor corrections, explicit duplicate merges, archiving, certain-only auto-linking, human review for ambiguity, no silent conflicting overwrites.
- Monthly/annual summaries, tax-designation identification evidence, names/postal-address mailing exports and CSV/XLSX exports are MVP scope.
- Preview and explicit approval before transactional import commit; import history, repeated-file detection, payment deduplication and actor/time/field before/after audit.
- Undo only the latest committed import through import-specific compensation. Stop on conflict, preserve later edits, and keep audit history. Backups are a separate recovery mechanism.
- Individual accounts, equivalent initial staff capabilities, no self-registration. MVP uses local username/password authentication, secure hashing and server-managed sessions.
- Replaceable/configurable authentication boundary; stable internal users, external-identity mappings, provider-independent sessions and app-owned authorization. External OIDC/LDAP/AD/SSO implementations are deferred.
- Preferred production direction is hospice-owned/administered infrastructure, on-premises support and expected internal-network access; no mandatory cloud. Final hosting design requires hospice IT consultation.
- TLS, network restrictions, protected PostgreSQL, daily and pre-import backups, reproducible installation, upgrade/restore/rollback and operational handover are required. Internal networking does not replace security.
- Synthetic development/test data; hospice confirms organizational/GDPR requirements before real personal data. No invented legal retention periods or compliance claims.
- Monorepo, modular monolith, Clojure, ClojureScript with Reagent/re-frame, PostgreSQL, `src/api`, `src/ui`, `src/common` and portable shared `.cljc` models/Malli schemas/utilities. Malli preferred; precise dependencies/versions need approval.
- Unit/integration tests from inception; English technical artifacts and Polish UI. Plan and explicit approval before substantial coding/dependency decisions. Initial implementation is a scaffold, not the entire CRM.

## PROPOSED — requires approval before implementation

- Module and API boundaries, Clojure CLI/shadow-cljs build strategy and supporting directories in [architecture](architecture.md).
- Candidate relational entities, separate unresolved aggregate observations, provenance/rule versions and synthetic generator design in [data model](data-model.md).
- Staging lifecycle, preview version checks, serialized commit/undo checks and atomic compensation in [import workflow](import-workflow.md). Transactional commits and safe compensation themselves are confirmed requirements.
- Small provider interface, identity resolver, mapping uniqueness, server session service, Argon2id, local provisioning/reset mechanics and configuration described in [architecture](architecture.md). Authentication requirements are confirmed, exact implementation is not.
- Reverse proxy/application/PostgreSQL topology; host/VM versus containers; Docker Compose as a local-development option only.
- Protected deployment artifacts, backup verification gates, compatible schema upgrades and procedures in [operations](operations.md); exact tooling and policy values need review.

## OPEN — hospice staff / data owners

- Secure access to the three files (not present in repository); worksheets, layouts, formulas, dates, sizes, malformed values and source identifiers.
- Are 2021–2025 values indicators, amounts or mixed? What do 2026 monthly cells and summary represent? Do they overlap bank or tax data?
- Taxpayer/spouse identity and amount attribution, tax/receipt year, purpose semantics and financial overlap; what constitutes confirmed tax identification?
- Approved matching evidence, address normalization, payment identity, genuine repeated identical payments, corrections/refunds and currency/date basis for summaries.
- Mailing recipient conventions, spouse/group consent or instructions, shared address exceptions, archive behavior and export templates.
- Deliberate reprocessing and undo/reimport policy; latest-eligible-import behavior after an undo; source/staging retention and audit visibility.
- Local account provisioner/reset authority, deactivation process and future identity infrastructure.
- Lawful basis, privacy notices, access/export/deletion handling, retention and legal holds; hospice privacy owner approval before real data.

## OPEN — hospice IT / operational owners

- Available host/VM, OS/resources, runtime/orchestration, internal DNS/TLS, firewall and any remote-access policy.
- PostgreSQL administration, protected backup storage, encryption/key custody, retention, pre-import backup latency and restore drills.
- Recovery point/time objectives, monitoring destination, incident contacts, maintenance windows and software/security update ownership.
- Export delivery/expiry and secure temporary-file cleanup; audit and credential storage protections.
- Exact library versions and compatibility evidence, installation artifacts and release support policy.

## Decision process

For each implementation milestone, document outline, affected files, schema/API changes, dependencies/versions, tests, risks and questions, then obtain explicit approval. Record date, approver, rationale and tradeoffs when accepting a proposal. Do not mark proposals accepted merely because they appear in this repository.

## CONFIRMED — Milestone 1 approval (2026-10-25)

The user explicitly approved all decisions in section 9 of the Milestone 1
proposal and instructed implementation. This approval covers the proposed exact
dependencies, Jetty, plain EDN configuration with explicit overrides/protected
secret files, host JVM/Node plus Compose PostgreSQL, same-origin assets/API,
explicit migrations and separate database roles, infrastructure-only schema,
built-in tests with test.check/PostgreSQL/Node, and GitHub Actions without deployment.
The user separately approved `process@0.11.10` after the browser release build
identified React's process shim requirement. No domain implementation is authorized.

Exact dependency pins are in `deps.edn`, `package.json` and `package-lock.json`.
JDK Temurin 25.0.5+8, Node 24.20.0 and Clojure CLI 1.12.5.1664 are the reproducible
toolchain target. PostgreSQL 18.6 is pinned by multi-platform digest in Compose.
Rationale and workflow are in [development.md](development.md). The selected
versions resolved successfully; cross-platform compilation and foundation tests
provide compatibility evidence, not a guarantee for future feature dependencies.

Remaining proposals in this document and other architecture sections retain
their prior status. Production topology, domain schema, authentication mechanics,
workbook mappings and privacy/operational policy values remain unapproved.

## CONFIRMED — development helpers without Python

At the user's request, local secret initialization and smoke-response checks use
Node built-in APIs instead of Python. Node is already required by the approved
frontend toolchain; no additional npm dependency is introduced. Launch and test
instructions are included directly in the README.

## CONFIRMED — Babashka development automation

The user approved replacing Bash/Node developer helpers with Babashka. Pin
**1.12.218**, matching the installed and verified release. This supersedes the
Node helper choice above. `bb.edn` declares tasks; `dev/alma_spei/dev/tasks.clj`
uses built-in filesystem, cryptography, process, HTTP and JSON facilities.
No extra Clojure/npm dependencies are needed. CI verifies the pinned archive
checksum. Node remains the frontend build/test runtime.

Tasks cover initialization, database start/stop, migration, backend, frontend,
checks, integration and smoke verification. Local secret defaults are injected
into child processes while preserving explicit overrides. The old `scripts/`
helpers and leftover Python cache are removed; production infrastructure and
business scope are unchanged.

## CONFIRMED — REPLy and backend reload-on-save

The user approved a development entry point matching `clojure -M:repl`, with
REPLy, nREPL, cider middleware and automatic namespace reload. Exact development
pins are `reply/reply` 0.5.1, `nrepl/nrepl` 1.8.0, `cider/cider-nrepl` 0.63.1,
and `org.clojure/tools.namespace` 1.5.1. They are scoped to the `:repl` alias.

A development-only polling watcher refreshes backend/shared source namespaces
and rebuilds application components in the existing JVM. Syntax errors leave the
REPL/watcher available and the backend stopped until a successful retry. State
inside rebuilt components may reset; database contents are preserved. The nREPL
binds only to loopback and writes its ephemeral port to ignored `.nrepl-port`.
No API/schema, production deployment, authentication or domain scope changes.

## CONFIRMED — CI Java setup correction

The user requested fixing the Java setup failure. Both CI jobs now use
`actions/setup-java` v5 pinned to commit
`b6effb05e454b25005698d916606bdc6ffcbf961`. The exact Temurin version selector is
`25.0.5+8.0.LTS`, matching the provider's available version identifier; the earlier
abbreviated `25.0.5+8` selector did not resolve. This corrects the selection of the
same approved JDK release. The README and development prerequisite list use the
full identifier. GitHub-hosted Ubuntu runners provide the Node 24 action runtime;
no application dependency or schema changes are involved.
