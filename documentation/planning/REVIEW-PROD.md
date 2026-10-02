# Fable production-readiness review (arch/wave1 @ 6eedda1), 2026-10-01

Verdict: NO-GO until B1 + B2 are fixed. The migration itself was verified safe; the re-read is bounded; the privacy paths
filter with isChatVisible; the UI handles null/unknown inputs; the release hygiene is OK.

## BLOCKER
- **B1.** With topicsInFirstStage=false, the stage-1 re-read wipes the migrated "health" topic
  (`ReprocessOverwritePolicy.applyFamily` copies the empty topics). A health letter re-typed as non-medical could then
  leak into the all-documents chat.
  - Fix: empty means unscored; sensitive topics and families are sticky across model re-reads.
- **B2.** The REPLACE insert on (documentId, fieldName) can delete a CONFIRMED/EDITED row that the staged merge didn't
  show.
  - Fix: `existing` includes stored rows whose names collide with the fresh rows.

## SHOULD-FIX
- **S1.** Edit state is lost on rotation → rememberSaveable.
- **S2.** Startup recovery keys on summarySource IS NULL, so a re-read whose stage 2 was lost is never resumed.
  - Fix: an enrichmentPending column in MIGRATION_14_15. It must go in BEFORE the install, since v15 freezes then.
- **S3.** Pre-v2 docs with a NULL extractionType are chat-visible until re-read; that is a known limit. The real fix is a
  user-settable "sensitive" flag (P2).
  - Tell the user.

## LATER
- A v14 hand-added field backfills as CONFIRMED (the badge is wrong; it is protected either way).
- The uncertain count includes undrawn rows → count the shown rows only. Being fixed now.
- Delete the old KEY_TYPE_ID/... worker shim after this release.
- Remove the dead Action.Propose branch.
- A charging re-read holds processingMutex, so a scan waits on it (pre-existing).

Status: a fix agent was launched for B1, B2, S1, S2 and the uncertain count. After the fixes: a planner diff review →
the app install (authorized by the user) → a light phone check → the PR prep.
