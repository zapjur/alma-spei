# Synthetic manual test files

These files contain invented data only. Use the task 50 branch in a disposable
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

Open **Import przelewów**. CSV files are UTF-8, have no header and use `;` as the
explicit separator. The four columns are date, sender, description and amount.
Do not open/resave the supplied files in Excel before the first test.

| File/action | Expected result |
| --- | --- |
| `bank-valid.csv` | Upload succeeds, two valid staged rows, amounts `12.3400` and `-2.50`. |
| Same CSV again, even after renaming a copy | Duplicate rejected; no second successful staging or payment creation. API 409. |
| `bank-valid.xlsx` | Upload succeeds, two staged rows with physical worksheet row numbers 2 and 3. |
| `bank-invalid-rows.csv` | Upload succeeds with two staged rows: one valid, one invalid. The second has invalid date, missing sender and invalid zero amount. Invalid rows remain visible. |
| `bank-wrong-columns.csv` | Structural rejection with a Polish message; API 400. No canonical donor/payment writes. |

Successful staging returns API 201 even when individual rows are invalid. This is
different from a structurally unreadable file. Negative amounts are accepted by the
parser; staging does not approve their financial interpretation or final commit.
Use browser Developer Tools → Network to inspect statuses if needed.

After upload, open **Weryfikacja importów**, reopen the batch, navigate rows and
reload. The staged batch should persist. Donor/payment totals must remain unchanged
by uploading or browsing. Commit is disabled by default; its separate synthetic
test configuration is documented in task 39 and must not be mistaken for a real backup.

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
