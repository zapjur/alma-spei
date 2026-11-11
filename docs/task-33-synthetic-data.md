# Task #33 — deterministic synthetic population

Implemented on `trello/33-synthetic-data`, based on #32, following the user's
instruction to continue. Assigned contributors: Jakub Płoskonka and Paweł
Piwowarczyk. No new dependency, schema migration, import adapter or production API.

## Local command

After initializing local secrets, starting PostgreSQL, applying migrations and
creating an individual account administrator, run:

```sh
bb seed-demo synthetic.admin
```

Replace `synthetic.admin` with an existing active administrator's username in the
synthetic development environment. The command creates no account, password,
session or external identity. The selected account supplies the stable audit actor;
this developer command uses local database credentials, not an authenticated staff
session or password verification. Complete any required first-use password change
before viewing the data through the application.

The Babashka task forwards to `clojure -Srepro -M:seed-demo`. Existing protected
runtime-secret defaults and explicit configuration overrides apply. It does not
start the database, apply migrations or run on application startup. Success prints
only a status and dataset counts:

```clojure
{:status :loaded, :donors 20, :payments 30}
;; An unchanged repeat performs no inserts or updates:
{:status :unchanged, :donors 20, :payments 30}
```

Counts describe this dataset, not all rows in the database. Failures return a
nonzero exit status and a safe explanation without SQL exceptions, donor values,
credentials or process-command dumps.

## Dataset contract

`dev/alma_spei/dev/demo_data.clj` generates version-1 fixtures and an independent
expected-outcome manifest:

- 20 profiles: 12 individuals, 4 businesses, 4 organizations; 18 active and 2 archived.
- All display names explicitly start with **DANE SYNTETYCZNE**. Names, cities,
  addresses and organization labels are invented; no workbook rows were sampled,
  transformed or copied. Unicode Polish characters exercise the actual storage/UI.
- Two distinct profiles share a full name but have different addresses; two others
  share an address without becoming a household, merged donor or financial identity.
- 30 positive payment facts, explicitly dated in 2026 with currency PLN, total
  **2650.0000 PLN**. Ten pairs deliberately have equal donor/date/amount/currency
  values but distinct payment IDs. They remain 30 separate facts, not 20 deduplicated
  payments. Archived donors retain their payments.
- UUIDs derive from a fixed synthetic version namespace and ordinal; profile/payment
  IDs, fields, money and dates are deterministic. Decimal strings/dates use the
  existing approved contracts; postcode formatting uses a locale-independent format.

These are canonical demonstration facts, not a mapping of historical annual/monthly
workbook observations or evidence for actual payment attribution, PLN defaults,
deduplication thresholds or source column order. Synthetic XLSX generation and real
source semantics remain separate discovery/approval work.

## Guardrails and repeat behavior

The loader in `dev/alma_spei/dev/demo.clj` permits only:

- `:development` at the exact database name `alma_spei_dev`, or `:test` at
  `alma_spei_test_` followed by 32 lowercase hexadecimal digits;
- loopback connection hosts (`127.0.0.1`, `localhost`, `::1`);
- the `alma_spei_app` runtime role, verified again with `current_user` and the actual
  connected database name inside the transaction;
- an existing active account administrator as the explicitly selected audit actor.

Invalid configuration is rejected before connecting. This is a development guard,
not production authorization or proof that an operator has kept real data out of
a development database. Real-data processing remains prohibited by operations policy.

One PostgreSQL transaction and a separate developer-only advisory lock serialize
seed attempts. The loader first inspects its fixed IDs. If none exist, it inserts
all profiles, payments and field-level creation audit together. A repeat succeeds
without changes only when all fixture records still match, versions remain 1 and
every profile has creation audit. Partial data, collisions or later profile/payment
changes stop the entire operation; there is no upsert, repair, overwrite, truncate,
delete, reset or force option. Original actors/timestamps/audit remain unchanged.
Unrelated donors, payments, accounts and histories are never cleared or rewritten.

An intentionally changed demo should be preserved. Use a separate disposable
development/test database when a fresh dataset is needed; this command is not an
import undo or database recovery tool.

## Source/build/test boundaries

Only developer paths expose the loader: a new `:seed-demo` alias and existing test/
development classpaths. Production `:run` and browser source paths do not include
the generator. `:test` now includes `test/dev` for focused developer-tool unit tests;
no dependency versions or frontend build settings change.

Tests cover deterministic counts/types/money and ambiguity counterexamples,
configuration guards before connection, actor/connected-target checks, actual
Babashka CLI first/repeat/error behavior, existing unrelated data, edited profiles
and payments, partial IDs, concurrent attempts, and rollback after partial payment
insertion or audit failure. All database tests use isolated synthetic databases.

## Verification (2026-11-07)

- `bb check`: lint/format pass; 25 JVM tests/199 assertions, 1 reload test/10
  assertions, 38 JavaScript tests/225 assertions and optimized frontend build pass.
- Final `bb integration`: 30 tests/246 assertions pass. The eight new integration
  tests cover preservation, conflicts, financial edits, rollback, concurrent loads,
  actor/connection guards and the real `bb seed-demo` command (load, repeat, usage error).
- `bb tasks` includes the documented command; final format/lint and
  `git diff --check` pass. No dependency or schema change was needed.
- No rows were seeded into the configured development database. Integration tests
  created and dropped only their generated test databases. No real workbook data,
  production environment, UI change or new browser dependency was involved.
