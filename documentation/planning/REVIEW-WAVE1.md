# Fable third-eye review of wave 1 (arch/wave1), 2026-09-30

Verdict: mergeable after B1 + S1–S8. Migration order, legacy mapping, merge protection, the title policy and the chat corpus
paths are verified correct.

## BLOCKER
B1. The address label threshold is never set in the production profile, so it falls back to -12.0.
- Where: AddressLineLabeler uses `LineAsk.LABEL_ASK = "addr_label"`, but ModelProfile sets `ScoringProfile.ADDR ("addr")`.
- Fix:
  - delete `ScoringProfile.ADDR` and `addrThreshold`;
  - the profile sets `LineAsk.LABEL_ASK to 0.0`;
  - FamilyClassifierTest asserts the `LABEL_ASK` and `DELIVERY_ASK` keys;
  - add a labeler test with `ModelProfiles.QWEN35_08B.scoring` in which a line no label wins stays raw.

## SHOULD-FIX
- **S1.** `DocumentMapper.titleSourceOf` maps any titleCode to DEFAULT, so COMPOSED is erased. Fix: `titleCode == TitleComposer.CODE`
  → COMPOSED. Change the test fixture to titleCode="composed".
- **S2.** ObserveSuggestedQuestionsUseCase bypasses the chat privacy rule (it relies on `actionable` + DEFAULT).
  - An official_letter with the topic health would leak, and migrated ids get no starter questions.
  - Fix:
    - filter with `ObserveChatVisibleDocumentsUseCase.isChatVisible` first, then `actionable` via V2 with a LegacyTypes fallback;
    - add a test;
    - add the same LegacyTypes fallback in ExtractedPresentation.kt:62,70.
- **S3.** SummaryOrigin duplicates SummarySource. Delete SummaryOrigin.
- **S4.** The shape helpers have several owners (COUNTRY_CODE_PREFIX, SEPARATORS, STRONG_SEPARATOR, TRAILING/LEADING_NUMBER). Move
  them all into AddressShapes.
- **S5.** Every pre-v15 confirmation backfills as EDITED (the confirm path set source=USER).
  - Fix: `CASE WHEN fieldValue = machineValue THEN CONFIRMED ELSE EDITED`.
  - Add a migration fixture.
  - Align `ReviewState.fromFlags`.
- **S6.** The SummaryGate name check rejects German summaries ("Sie Ihre Rechnung").
  - Fix: reject a span only when one of its folded words is absent from the corpus token set.
  - Add a DE test.
  - P4 reports the template fallback rate.
- **S7.** A locker is stored under po_box. Give it its own PACKSTATION row.
- **S8.** The address row labels are missing in SlotLabels. Add an AddressPart → @StringRes data map with role prefixes, and a guard
  test.

## LATER (P4 / minor)
- SelectionVerifier falls back to `family("other")`. In P4, use the unscored family.
- The pipeline nulls titleCode/titleArgs on a model title. P4 stores composed + args; the English idAsWords is a fallback only.
- FieldAlternative has no producer. Add `alternatives` to FieldProvenance now (model) and thread it in P4.
- A country inferred from the postcode shape gets HIGH. Cap it at MEDIUM.
- `setFieldReviewState(EDITED)` without a value: reject EDITED there (Edit goes through updateExtractedField).
- confirmAllExtractedFields and FakeDocumentRepository filter on the old flags. Switch them to reviewState.
- PostalAddress salutation/attention are dead fields. Drop them (they can be added later with their own labels).
- ReprocessOverwritePolicy and hasRecipientBlock are unused until P4.
- FamilyAccuracyTest is in-sample. The thresholds get refit in P4.
