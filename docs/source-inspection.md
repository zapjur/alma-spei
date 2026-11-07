# Source workbook inspection — sanitized structural findings

Inspected locally on 2026-11-07 at the user's direction. Files remain outside the
repository in Downloads; no workbook, donor cell values, payment amounts, names,
addresses or transaction descriptions were copied into this document or fixtures.
Inspection used Python standard-library ZIP/XML readers in memory, with allowlisted
header-term classification and structural/type counts as output. No dependency was
installed and no workbook was modified. This is development discovery, not approval
of production processing or a claim that the operating environment is approved.

## Observed structure

The filenames differ from the preliminary names with `(1)` suffixes in earlier
requirements. These are the files the user identified for this inspection.

| File | Structural observations |
| --- | --- |
| `Baza docelowa.xlsx` | One worksheet; used range A1:Z2. Row 1 contains headings. A indicates combined first name/surname; B street; C postcode; D locality; E province; F gender. G–K headings are 2021–2025; L references 1.5% tax. M–X form a 12-column block with month-related headings recognized in N, Q, S and V; precise labels/year basis require confirmation. Y is a sum heading with a formula in row 2. Z has a numeric cell in row 1 without an identified heading. No merged ranges. |
| `baza 1,5%.xlsx` | One worksheet; used range A2:I4. Row 2 contains text in A–C and must not be assumed to be the field header or a donor row. Row 3 has headings: A ordinal; B taxpayer first name; C taxpayer surname; D taxpayer address; E spouse first name; F spouse surname; G spouse address; H designated purpose; I transferred amount. Row 4 is the only apparent data row. Amount uses a numeric cell with two-decimal display format. No merged ranges. |
| `baza przelewów.xlsx` | One worksheet; used range A2:D3. Two physical rows, each with numeric A, text B/C and numeric D. A uses Excel built-in date format 14. No heading row was identified; do not skip the first populated row just because its worksheet row number is 2. D is the amount-position candidate, consistent with repository preliminary notes and inconsistent with the order listed in task #35. No merged ranges or formula cells observed. |

## What this evidence does not establish

- A single apparent master/tax record and two bank rows do not establish required
  fields, allowed types, uniqueness, production volumes or complete date/amount
  conventions. Worksheet dimensions are not reliable data-row counts by themselves.
- Do not split the combined master name into an inferred first name/surname or
  classify people/businesses/organizations from its text automatically.
- Text/numeric variation in yearly fields does not establish whether they are
  indicators, amounts or mixed observations. The sum formula is not a policy for
  combining master observations with bank/tax transactions.
- A tax spouse is not automatically another donor, joint financial owner or
  correspondence-group member. Tax year, receipt year and attribution remain OPEN.
- Bank B/C meaning is still based on stakeholder description; their raw values were
  not emitted for interpretation. Sender parsing and stable payment identity remain
  OPEN. A follow-up metadata-only inspection on 2026-11-08 verified the bank workbook uses the 1900
  epoch (`date1904` is false). Supported date-only formats remain an explicit
  task 35 development contract, not an inferred production rule.
- Absence of currency headings does not establish PLN. No default currency or
  generated payment date is authorized by these files.
- No matching threshold, payment deduplication key, retention period, permissible
  financial overlap or reimport policy is established by this inspection.

## Next decisions

Review the minimum domain proposal in [task 28](task-28-schema-proposal.md).
Confirm the monthly block/year, historical meanings, tax attribution and bank
format conventions before their corresponding adapters/reports are implemented.
Keep unresolved historical observations out of discrete payment facts.
