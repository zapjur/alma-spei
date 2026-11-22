# Synthetic manual test files

These files contain invented data only. Use the `fix/automatic-import-reconciliation` branch in a disposable
development database. No production data or accounts are needed.

## Start the application

Follow the pinned prerequisites in the repository README. From the repository root:

```sh
bb init
npm ci --ignore-scripts
bb db-up
bb migrate
bb create-admin
bb repl
```

`bb create-admin` is first setup only, on a database with no existing accounts.
Choose a synthetic username such as `synthetic.admin`; retain the temporary password
locally. In a second terminal run `bb ui`, then open http://127.0.0.1:3000,
sign in and change the temporary password. Keep both terminals running.

Optional: `bb seed-demo synthetic.admin` adds 20 synthetic donor profiles and 30
payments, using your actual synthetic administrator username. This helps test donor
browsing and manual matching; uploading alone needs no seeded donors.

## Upload checks

Open **Nowy import** in the sidebar (or **Importuj przelewy** on Start). CSV files are UTF-8, have no header and use `;` as the
explicit separator. The four columns are date, sender, description and amount.
Do not open/resave the supplied files in Excel before the first test.

| File/action | Expected result |
| --- | --- |
| `bank-valid.csv` | Upload succeeds, three valid staged rows: Jan Kowalski / 125 PLN, Jakub Nowak / 75 PLN and Helena Nowicka / 50 PLN (currency must be explicitly confirmed during review). |
| Same CSV again, even after renaming a copy | Duplicate rejected; no second successful staging or payment creation. API 409. |
| `bank-valid.xlsx` | Upload succeeds, three staged rows with physical worksheet row numbers 1, 2 and 3. |
| `bank-invalid-rows.csv` | Upload succeeds with two staged rows: one valid, one invalid. The second has invalid date, missing sender and invalid zero amount. Invalid rows remain visible. |
| `bank-negative.csv` | Parses successfully, but its negative amount blocks final approval. Use this separate file only to test the explanation. |
| `bank-wrong-columns.csv` | Structural rejection with a Polish message; API 400. No canonical donor/payment writes. |

Successful staging returns API 201 even when individual rows are invalid. This is
different from a structurally unreadable file. Negative amounts are accepted by the
parser; staging does not approve their financial interpretation or final commit.
Use browser Developer Tools → Network to inspect statuses if needed.

After upload, open **Do weryfikacji**, reopen the batch, navigate rows and
reload. The staged batch should persist. Donor/payment totals must remain unchanged
by uploading or browsing. Commit is disabled by default; its separate synthetic
test configuration is documented in task 39 and must not be mistaken for a real backup.

## Complete the guided review

All names and address combinations were invented. They were not copied from
people, workbooks or external sources. Familiar names can coincide with real names;
these records must never be treated as verified identities or mailing recipients.

On a fresh demo database, the seeded data contains two different Jan Kowalski
profiles. Search for Jan Kowalski, inspect the address and select the profile at
**ul. Słoneczna 1/4, 32-020 Wieliczka**. Jakub Nowak has that same address but is a
separate donor. Select him independently; shared addresses do not justify a merge.
Helena Nowicka is absent from the seed and exercises the new-donor choice.

1. Open **Nowy import** and select `bank-valid.csv` with separator **Średnik (;)**.
2. Confirm `PLN` once for the file. For this fictional dataset, choose **Osoby** as the type of new donors. These values are source settings, not guessed columns.
3. Click **Prześlij i dopasuj automatycznie**. The app opens the summary automatically.
4. Expect **2 matched transfers, 1 new donor, 3 new payments, 0 exceptions**, totalling **250 PLN**. You should not see a transfer-by-transfer wizard or fill any donor/payment form. The full name/address selects the correct Jan; Jakub remains a separate donor; Helena is prepared automatically.
5. Click **Sprawdź zmiany i zatwierdź** to inspect the full immutable change plan. Only the final explicit confirmation writes canonical donors/payments.

With the valid sample, no human matching decision is necessary. For exceptions,
use `bank-ambiguities.csv`: one transfer is prepared automatically; a non-exact
Jan Kowalski identity and two repeated Jakub payments require decisions. Only these
three exceptions appear. **Wyjaśnij tę pozycję** opens a relevant comparison. After
an explicit identity choice, the system runs payment checks itself and returns to
the summary if no financial ambiguity remains. A possible duplicate still needs an
explicit financial decision; equal amounts/dates alone do not prove duplication.

If you leave the new-donor type unknown, existing donors can still match; only new
untyped donors need review. Unsupported sender text, conflicting identities,
archived matches, invalid rows and negative amounts are exceptions. No name-based
entity-type inference or default currency is used.

Do not upload both valid CSV and XLSX as independent real financial sources: they
contain the same invented transfers. Separate bytes can both stage; financial
review is still required. Use a separate disposable database for the other format
if testing the whole commit flow.

### Optional synthetic final confirmation

Commit remains disabled by default. To test final confirmation, explicitly enable
the existing synthetic-only adapter **before starting the backend**, in a disposable
development environment containing fictional records only:

```powershell
$env:ALMA_SYNTHETIC_IMPORTS = 'true'
bb repl
```

On macOS/Linux, use `ALMA_SYNTHETIC_IMPORTS=true bb repl` instead. Restart an already
running backend for the configuration to take effect. In confirmation, click
**Przygotuj zatwierdzenie testowe**, read the test-only explanation, check consent,
then **Zatwierdź import testowy**. This creates no actual backup. The result should
show the saved transfers. The separate confirmed undo flow remains available only
when server eligibility permits it. If commit is disabled, the screen explains the
configuration limitation; repeated clicks cannot enable it.

Existing seeds are not overwritten. After updating from an older seed, a repeat
may report a fixture conflict; keep that data and use a fresh disposable database
rather than deleting or overwriting existing records.

## Windows acceptance and automated checks

For task 50, perform these steps on native Windows 11 / NTFS. Follow
[Windows verification](../windows-verification.md), including repeat-safe temporary
secret initialization and LF checkout checks. Expect no POSIX-attribute exception,
raw filesystem path or internal diagnostic from upload errors.

Stop `bb ui` before `bb check`, because both write frontend build output:

```sh
bb check
bb integration
```

Keep Docker running. Integration tests create isolated synthetic databases. Record
the commit, operating system, tool versions, failed command or file, and visible
message. Native Windows acceptance remains pending until these checks actually run
there. Stop the backend with Ctrl+D and the UI with Ctrl+C; `bb db-down` stops the
database while preserving its volume.
