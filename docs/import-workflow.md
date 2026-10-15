# Import, matching and rollback workflow

## Confirmed interaction contract

1. The hospice's primary existing dataset is the initial source of canonical donors.
2. Subsequent sources are reconciled into the same donor base.
3. Employees upload a file, review a preview and explicitly approve the entire import before any canonical changes are applied.
4. Only certain matches are automatic; uncertain matches go to staff review. Clearly new donors can be added.
5. New donation rows become separate history entries; never replace prior donations.
6. Each import has file/user/date/status history and possible undo (latest committed import only).

## Candidate implementation flow (PROPOSED)

```text
Upload -> identify source -> fingerprint and duplicate check
  -> parse/validate -> normalize into isolated staging
  -> classify candidate matches (certain / uncertain / new / invalid)
  -> resolve human-review queue with side-by-side differences
  -> preview complete planned change set
  -> explicit confirmation -> atomic database commit
  -> import summary + audit + undo metadata
```

- File fingerprint alerts on an already processed file; the policy for deliberate reprocessing is OPEN.
- Staging must not mutate canonical donors or donations.
- Review resolution must be recorded and revalidated before commit to avoid races/stale previews.
- Define a bounded upload size and robust parser failure handling after inspecting actual files.

## Matching

- Automatic linkage only with explainable, rigorously validated evidence.
- Uncertain cases demand manual selection or creation of a new donor.
- Attribute disagreements must be shown to the user and never silently overwrite data.
- Rules, confidence categories, normalization and field precedence cannot be finalized before source inspection.
- Payment deduplication needs a separate identity policy from donor matching.

## Safe undo of the last committed import

Undo only the most recent successfully committed import. Compute an inverse of its own changes (including donation creation, donor creation and allowed attribute updates), subject to dependency/conflict checks. **Do not restore a whole-database snapshot to undo an import.** If later user edits or other dependencies would be lost or corrupted, block affected undo and provide a conflict report; do not overwrite later edits. Define exact resolution behavior and test it before enabling undo.

## Testing priorities

- Importing the same file twice and importing different files that contain identical donations.
- Certain vs ambiguous matching, mixed donor types, missing fields, conflicting attributes.
- Complete preview and reviewer decisions.
- Atomic commit under invalid data, database errors and stale review state.
- Undo with and without later edits, audit integrity and source provenance.
- Concurrency: multiple users reviewing/importing at once.
