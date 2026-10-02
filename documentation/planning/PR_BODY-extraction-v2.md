## Summary
This PR stacks on #1 (`feat/on-device-ai`); its base is that branch. It replaces the single "document type" the extractor guessed with two things the app now reads separately and shows for review.
- **What a document is and what it is about.** 12 families (9 scored for incoming mail, `free_form` as the "none of these" outcome) and 14 topics.
- **A structured address** for the recipient and the sender, checked against per-country formats.
- **A composed title and a gated summary.** The title is built from verified fields. The summary is written from verified facts, checked by code, and falls back to a template.
- **A rebuilt Extracted tab.** Inline Confirm / Edit / Ignore on every row, "check these first", alternatives, "Show on page", and an editable family chip.
- **Review state and merge protection.** What a person confirmed, edited, ignored or wrote is never overwritten by a re-read.
- **Database v15.** Additive, with one table dropped.
- Extractor version `extraction-v2-2`. On the 16 recorded benchmark letters: fields 86.8%, roles 86.7%, hallucination 1.4% (previous recordings: 82.4%, 86.7%, 1.6%, so not strictly comparable; see the roadmap).
- 141 commits on top of #1. Room schema migrates additively to **v15**.

## What changed, by area
**Families and topics** (`core/domain/.../extraction/v2`, `zones`)
- `ExtractionSchema` is the one owner of both. A family decides which slots are asked; up to two topics add their slots.
- `FamilyClassifier` scores "Is this document <family>?" and "Does this document concern <topic>?" in one batch. `free_form` is never scored: it is what is taken when no family beats the threshold.
- Topics are read in the second stage for Qwen3.5-0.8B (`ModelProfile.topicsInFirstStage = false`): in the first stage they cost about 17 s on the phone.
- `email_printout` was removed from the scored families (rare for postal mail; it took two letters wrongly).
- Sensitive families and topics (`medical`, `health`) are kept out of the all-documents chat (titles, retrieved chunks and starter questions), and stay available in the document's own chat.

**Layout conventions** (`LayoutTemplates`)
- A layout is a convention, so a convention is a template: DIN 5008 A/B, `UK_LETTER`, `US_BLOCK`, `RTL_DIN`, invoice table, receipt, form.
- `LocaleHint` (countries and scripts, as data) is only a tie-break in the matcher; geometry decides.

**Structured address** (`extraction/address`)
- Shape pass for the country, postcode, street and number lines. Remaining lines get their labels by scoring (person, organisation, department, routing; post office box or locker for a street-shaped line), never by generation.
- `AddressVerifier` checks the postcode shape, required parts, one block, and the addressee's name, and caps confidence for each failed check.
- A generic retry reads the runner-up template's address region when the chosen one holds no postcode line. `SenderAddressPicker` chooses among letterhead, return line and footer; the others become alternatives.
- Formats are data (`address/formats.json`: DE, GB, US, AE, SA, EG). The data derives from Google's libaddressinput (CC BY 4.0); the attribution is in `documentation/THIRD_PARTY.md` and `AddressFormats.ATTRIBUTION`.
- Stored as ordinary rows (`addressee.street`, `sender.postcode`, ...), so review, merge and history apply with no special case.

**Title and summary** (`extraction/text`)
- `TitleComposer`: a coded title `"{family} · {sender} · {subject}"`, rendered from string resources; no model call.
- `SummaryWriter` and `SummaryGate`: numbers, dates and capitalised names must occur in the letter or the verified facts, and a summary that copies one line is rejected. After one retry the summary is a template rendered from the verified fields, so a summary always exists.

**Two stages and recovery**
- Stage 1 stores the family, parties, slots and addresses and shows the document. Stage 2 (quiet, per-document unique work) writes the extras, language, subject and summary.
- A second stage that was lost is rebuilt on start (`enrichmentPending`). It is bounded: after three attempts the document settles on the template summary.

**Review state and reprocessing**
- `extracted_data.reviewState` (`UNREVIEWED` / `CONFIRMED` / `EDITED` / `IGNORED`) is the single owner. A protected row keeps its value; an ignored row is a tombstone.
- A stored row that shares a fresh row's name is paired by the merge instead of being replaced by the insert (this could otherwise delete a confirmed row).
- `ReprocessOverwritePolicy` writes the document-level half: the family and topics only while the model chose them, the title only where `DocumentTitlePolicy` allows, the summary unless a person wrote it.

**Extracted tab** (`feature/documents`)
- Sections come from `FamilyPresentation` (data, one spec per family). Letter-like families show a Recipient block and a Sender block, then Action, References, Dates and Other details.
- "Check these (n)" first, then "Confirm n confident" or "Confirm all". Ignored rows sit in a collapsed footer with Restore.
- The Edit sheet shows alternatives and "Show on page" (the shared `PagePreviewDialog` with the field's box highlighted). Edit state survives rotation.
- The family chip menu has "Change type" and "Read again as...". The summary card shows a badge for where the summary came from and a pencil to write your own.
- The "is this you?" proposal cards and their whole stack are removed.

**Docs.** `documentation/07-document-pipeline.md` (section 12) now describes the implemented design, including a data-only checklist for adding a country or a language. `08-extraction-optimization-roadmap.md` has the post-release list. `THIRD_PARTY.md` is new.

## Database v15 (`MIGRATION_14_15`)
- **Additive.** New columns are nullable or have defaults. The one drop is `entity_proposals`: the proposals went with the code that wrote them. `dismissed_entities` stays.
- `extracted_data`: `reviewState` and `alternatives`.
- `documents`: `topics`, `familySource`, `titleSource`, `summarySource`, `summaryCode`, `summaryArgs`, `layoutTemplate`, `enrichmentAttempts`, `enrichmentPending`.
- **Backfills:**
  - A confirmed value becomes `CONFIRMED`, or `EDITED` if it differs from the machine's value (or has none); a user-deleted field becomes `IGNORED`.
  - `titleSource`: a person's title becomes `USER`, a coded default `DEFAULT`, other real words `MODEL`.
  - An existing summary becomes `MODEL`.
  - Legacy type ids are rewritten to family ids with their topics (one mapping, `LegacyTypes`), so old documents render before they are re-read.
- A migrated document owes no second stage (`enrichmentPending = 0`).
- Verified by the instrumented `MigrationTest` on a device: 16/16, including each backfill and the dropped table. Schema `15.json` is exported.

## Behaviour on upgrade
- Documents read before this version are re-read in the background. They are not trashed and are EXTRACTED, at most **5 per app start**, newest first, as low-priority work that waits for a charger or for the device to be idle (and not for a low battery). It needs a model installed and the existing "Update older letters automatically" setting on. A re-read that fails twice is left alone.
- Until a document is re-read it renders from its stored data, with its old type mapped to a family and topics.
- **Protected on every re-read:** confirmed, edited and ignored rows, a title a person set, a family a person chose (Change type, Read again as), and a summary a person wrote.
- **Sensitivity is sticky.** A re-read cannot clear a sensitive family or topic; only a person's action can.

## Privacy
- Everything runs on the device. No new network path, permission or dependency on a remote service; the address formats are a bundled JSON file.
- Sensitive documents (a `medical` family, a `health` topic, or the legacy health type) are kept out of the all-documents chat: its title list, its retrieved passages and its suggested starter questions.
- Known limit: the rule keys on what the model chose. A document stored before this version with no type is chat-visible until it is re-read, and a sensitive letter the model files elsewhere would be too. A user-settable "sensitive" flag is the durable fix (follow-up below).
- Reading traces and logs keep to structure and counts, not letter text.

## Test plan
- JVM gate, no device needed: `./gradlew testDebugUnitTest :architecture-test:test compileDebugAndroidTestKotlin`. This includes `BenchmarkGateTest` (candidate recall and zones on the recorded fixtures), the decoder golden, and Konsist's architecture guards. 1,650+ unit tests were green at the last full run on this branch.
- Instrumented, on a device: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.postsaimanager.core.data.database.MigrationTest` (16/16 on the reference phone).
- Recorded benchmark: the 16 letters were re-recorded on the phone for `extraction-v2-2` (fields 86.8%, roles 86.7%, halluc 1.4%; family 9/13 on the letters with a known kind; template summary fallback on 2 of 16 letters). `tax-long-7p` is left out of full replays until it is re-recorded.
- Not run for this PR: new timing runs and benchmarks (deliberately skipped to get to a releasable state), and an app-level Compose test run on a device beyond what the JVM (Robolectric) tests cover.

## Known limits and follow-ups (parked in the roadmap, doc 08)
- **Family accuracy is 9/13** on the letters with a known kind. Next: better family descriptions and a re-record.
- **`tax-long-7p` needs a re-record** on the phone.
- **Thresholds for topics and address labels are all 0.0**; a fit on 16 letters did not beat the fixed value out of sample. It needs more letters.
- **Topics are read in the second stage**, so a topic found there adds no slots to a letter already read (stage 1 cost about 17 s).
- **Arabic letters cannot be read yet.** ML Kit has no Arabic recognizer. The templates, address formats (AE, SA, EG) and the right-to-left layout are in place, but recognition is not. The researched route is PP-OCRv5 (detector plus Arabic recognizer) on the ONNX Runtime the app already ships.
- **UI strings are English only** so far.
- **The libaddressinput attribution string exists** (`settings_address_data_attribution`) **but no screen shows it yet**. It should go in Settings > About before release.
- A user-settable "sensitive" flag (see Privacy).
- Cleanups: a hand-added field from before v15 is backfilled as `CONFIRMED` (protected either way); delete the old `KEY_TYPE_ID` worker shim; remove the dead `EntityLinkingUseCase.Action.Propose` branch; a charging re-read holds `processingMutex`, so a scan waits for it (pre-existing).
- The prefix tree (`ScoringProfile.prefixTree`) stays off: it is faster, but on the 16 letters the best candidate changed in 25 of 138 questions.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
