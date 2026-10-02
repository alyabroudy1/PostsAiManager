# ARCHITECTURE-FINAL: general, document-type-first extraction

Status: CONFIRMED by the user on 2026-09-30 ("do what u see fit"). Implementation started.

USER DECISIONS (2026-09-30, binding):
- Language/country scope for v1: **German (DE), English (GB, US) and Arabic (RTL; AE, SA, EG)**. The others come later,
  so every registry must be extensible by data only (a new country = a new template + a new formats.json entry + strings,
  with no code change). Clean, maintainable architecture over breadth.
- The 3 open questions (§F) are decided by the planner with the recommendations: a reminder is folded into invoice_bill;
  topics run in stage 1 (flag to move them); "Read again as <family>" is in v1.

PLANNER ADJUSTMENTS for the scope:
- P5 conventions = UK_LETTER + US_BLOCK (+ the existing DIN5008 A/B and RTL_DIN). CH/FR are dropped for now; the
  registry must allow them later.
- The labeler's "CH retry" becomes a GENERIC data-driven retry: if the address zone has no postcode line and the
  runner-up template is within the margin, label the runner-up's address zone once. No country names in code.
- formats.json v1: DE, GB, US, AE, SA, EG (AT/CH later as data).
- P2's storage lines in DocumentProcessingPipeline move to P4 (that file belongs to the speed workstream until it merges).
- **Gap found: Arabic OCR.** `OcrService` uses only the ML Kit Latin recognizer, and ML Kit has no Arabic-script
  recognizer. So Arabic letters can't be read today. A new phase **P7 (Arabic OCR)** puts the recognition behind a
  script-keyed recognizer registry (the port lives in the domain; the engines are in core/data), picked from research
  (Tesseract `ara`, PaddleOCR PP-OCRv5 Arabic, or PaddleOCR-VL as a fallback). The research runs now in parallel.

PROGRESS:
- Wave 1 (started 2026-09-30, JVM only, one worktree each; rules in AGENT-RULES.md):
  - arch/p0-family (P0);
  - arch/p0b-db15 (P0b; the entity_proposals drop is moved to P4, which amends the unreleased MIGRATION_14_15);
  - arch/p1-address (P1);
  - arch/p2-titlesum (P2);
  - Arabic OCR research → RESEARCH-ARABIC-OCR.md.
- Privacy guard added to P0: the all-documents chat hides a document when its family or topic is sensitive
  (medical / health), including legacy "health" docs, via `ExtractionSchema.isSensitive` + LegacyTypes.
- NO device installs of arch/* builds until P4: v15 is not final before then.
- Wave 2 after the wave-1 merges: P3 (Extracted tab), P5 (UK_LETTER + US_BLOCK templates), P7 (Arabic OCR spike).
- Then P4 (integration, after the speed merge), with a device run.
- 2026-10-01:
  - The wave-1 review fixes (B1, S1–S8 + LATER items) are committed on arch/wave1, with 1518 JVM tests green.
  - P5 (UK_LETTER, US_BLOCK, LocaleHint) is merged into arch/wave1 (4f8c6e6).
  - P3 (arch/p3-extracted) and P4 (arch/p4-integration, with the device re-record via library test APKs only; NO app APK
    install, since v15 isn't final) were launched in parallel from 4f8c6e6.
  - Cleanup after both merge: delete the EntityProposal model/DAO/entity + the linker's proposal half, drop
    entity_proposals in MIGRATION_14_15, then a Fable review, then an app install only after the user OKs the v15
    migration of their real DB.
- P3 is done on arch/p3-extracted (3 commits, ~1585 JVM tests green). Notes for the CLEANUP:
  - delete EntityProposalService + ProfileMatchingService with their impls (EntityProfileLinker proposal half,
    ProfileMatcher if unused) and the DataModule bindings;
  - `ExtractedData.reviewState` is a constructor property, so `copy(isConfirmed=…)` desyncs it. Make
    isConfirmed/deletedByUser derived, or make the copy paths consistent;
  - P3 declared the Robolectric/compose-ui-test/vintage test deps directly in feature/documents/build.gradle.kts.
    Move them to libs.versions.toml;
  - P3 broke rule 3 once (a heredoc for a new file; the content was fine). It also briefly created and then removed a
    stray symlink inside the main repo's llama.cpp dir; the main repo was verified clean.
- 2026-10-01, user: "skip measuring or benchmarking, we need to get production ready". P4 was told to wrap up: no
  more recordings, sweeps or timing runs; only the device MigrationTest. A Mac recording harness was declined. Family
  accuracy (9/13 on the new questions) and the topic stage placement are left for after release (roadmap 08).
- P4 is done on arch/p4-integration (d9f98bc, b69494f, 189b594, b8a6c2e). The JVM gate is green, MigrationTest is
  16/16 on the device, and ExtractorVersion is extraction-v2-2.
  - Metrics: fields 86.8%, roles 86.7%, halluc 1.4%; family 9/13 (N2/N4 → official_letter; N6/tax → email_printout);
    the summary template fallback is 2/16.
  - Stage 1 grew by ~17 s with topics in stage 1.
  - PLANNER DECISIONS (production readiness, no more measuring):
    - topicsInFirstStage = false (the agreed rule: >6 s growth → flip);
    - email_printout is removed from the scored v1 families (rare for postal mail; it took 2 letters wrongly), with
      free_form as the fallback;
    - stage-2 retries are bounded (after N failed attempts → the template summary; settled).
  - Leftovers: SlotLabels uses the removed legacy constants (merge with P3); ScratchDebugTest rename;
    the entity_proposals drop; 532 MB staged model in /data/local/tmp/z10 on the phone.
- Integration + cleanup done on arch/wave1 (55bb7b0..6eedda1): 1652 JVM tests green; the device MigrationTest is
  16/16 with the entity_proposals drop + enrichmentAttempts in MIGRATION_14_15.
  - topicsInFirstStage = false; email_printout is removed (12 families, 9 scored); stage-2 retries are bounded at 3.
  - The proposal stack is deleted. /data/local/tmp/z10 on the phone was emptied.
  - Open: tax-long-7p needs a re-record (PENDING_RERECORD; parked, no measuring); EntityLinkingUseCase.Action.Propose
    has no consumer.
  - Next: the Fable production-readiness review, then the user's OK for the app install (v15 migration of the real DB).
- 2026-10-01, the user authorized the app install ("ok, install it once the review is done and proceed"). Order:
  1. the Fable production-readiness review;
  2. fix its BLOCKERs;
  3. `install -r` of the app APK built from arch/wave1 (v15 migration of the real DB);
  4. a light phone check;
  5. the PR prep (the user pushes).
- 2026-10-01: the prod-review fixes were applied (B1 sticky sensitivity, B2 merge pairs colliding names, S1 rotation,
  S2 enrichmentPending in v15); the device MigrationTest is 16/16.
  - INSTALLED on the user's phone: `install -r`, app-debug from arch/wave1 @ 0cc7de6, DB v15. It launched, with no
    crash or migration error, and ReprocessDocumentWorker jobs are running.
  - feat/extraction-v2 was fast-forwarded to 0cc7de6. The docs + PR body (into feat/on-device-ai, stacked on PR #1)
    are in progress; the user pushes.
- Smoke fixes installed (1c7dddc, 9962361, f3bb21d on feat/extraction-v2; 2585 JVM tests green):
  - only VERIFIED address blocks are stored and shown (origin ADDRESS_VERIFIED; the old ADDRESS rows are hidden);
  - the scroll is kept after ✓/✕/Restore;
  - FAB bottom padding.
  Known quality cause (parked): LetterLayoutAnalyzer.collectDown/fallbackField pull "Kundennummer:/Rechnungsdatum:"
  lines into the address zone.
- New feature designed: plans/FORM-ASSIST.md (form filling with profiles + clarifying questions); awaiting the user's OK.
- RESUMED 2026-10-01 (user: "ok continue"): the fix-up and P5 agents were resumed with their context.
- (was) ON HOLD (user, 2026-09-30: "hold things now till i confirm"). Two agents were stopped mid-work, leaving uncommitted changes:
  - wave1: the review fix-ups, 17 files, partial and untested;
  - p5-conventions: 3 files, partial.
  The speed workstream is merged into arch/wave1 (5c67e58). Resume only after the user confirms.
- P7 research done (RESEARCH-ARABIC-OCR.md):
  - **Pick:** PP-OCRv5 mobile det (~4.8 MB) + the Arabic rec (~8 MB) on the ONNX Runtime the app already ships
    (1.24.3, core/ai/embed). Apache-2.0; re-export the models ourselves and pin checksums.
  - **Why not the others:** v6 has no Arabic. Tesseract (~44% CER) is only the fallback. The VLM is an optional pass
    for hard pages.
  - **Routing:** a shared detector + per-line script from recognizer confidence + the Unicode script of the output
    (no keyword lists). ML Kit stays the default for Latin pages.
  - **Ports:** PageTextRecognizer / TextLineDetector / LineRecognizer / ScriptClassifier + a Hilt-multibound
    RecognizerRegistry.
  - **Also needed:** `DocumentLayout.readingOrder` needs a page direction.
  - **Spike DoD:** Arabic CER ≤8% median, ≥85% exact amounts/dates/refs, 0 German pages misrouted, German ≤0.5 pt
    worse, ≤4 s per page.
  - **Test data:** the spike needs real Arabic pages from the user (plus synthetic rendered pages first).

Original status line: final architecture + phased plan (Fable architect, 2026-09-30).
Checked against `worktrees/extraction-v2` @ `0ab8d9e` (DB v14). Items the architect could not verify are marked **[unverified]**.
Inputs: RESEARCH-TAXONOMY-ADDRESS.md, ENVELOPE-DESIGN.md (user corrections binding), PLAN/PHASE1/PHASE2, roadmap 08.

## 0. Corrections to the inputs (what is wrong or over-engineered)

1. **A second "Convention registry" with log-odds priors on the LLM scores is over-engineered.** `LayoutTemplate` already *is*
   the convention: a geometry signature → zone hints → slot placements, all data and all priors. **Convention = LayoutTemplate.**
   New conventions are new templates (CH left/right, UK, US block, FR).
2. **Having the LLM generate an address-line label by index contradicts the measured lesson** (label-token bias; generated
   answers scored 45%/13% vs scoring's 68%/87%). Line labels are **scored** instead, one Yes/No per (line, label) under
   `PromptSession.scoreGrid`. Structural lines (postcode+city, street+number, country) are found by shape. The country
   metadata only **verifies**.
3. **Clustering senders by postcode+street** and per-sender learning are parked (roadmap 08 §5). v1 uses a data preference
   order (letterhead > return line > footer). The other candidates become the `alternatives` chips.
4. **The research's confidence bands (≥90/70–89/<70) would give confidence a second owner.** Keep `ScoreCuts` →
   HIGH 0.9 / MEDIUM 0.7 / LOW 0.4 (`ConfidenceCombiner`) and `REVIEW_BELOW = 0.75`.
5. **Deciding `note_handwritten` by "low OCR confidence" is a static rule deciding meaning.** Fold it into `free_form` for
   v1, and advertisement/manual too.
6. **`OTHER` is scored with a neutral description**, so it gets a middling Yes on every letter and wins → **`free_form` is
   never scored; it is the abstain outcome** (best family score ≤ the `family` threshold).
7. **The current type list mixes two axes** (`bill` and `receipt` are families; `health`, `school`, `authority_tax` and
   `insurance_contract` are topics). This is the root of the 9/13 type accuracy.
8. **ML Kit LanguageIdentification is not wired.** The language comes from the stage-2 `lang` ask. Country detection
   uses the postcode shape + the country line for now.
9. The gate is "no regression from what `ScoringShippedReportTest` prints on the merged speed branch".
10. The title needs no new mechanism: `Document.titleCode/titleArgs` already renders a coded title. The composed title
    is a coded title.

## A. Final architecture

### A.1 Pipeline stages, owners, data flow

Everything sits behind the existing ports in `core/domain/.../extraction/v2/Ports.kt`. The new decisions are small
collaborators, each with its own file and test. `ZoneScoringInterpreter` gains three call sites.

| # | Stage | Owner | Input → Output | Scoring cost |
|---|---|---|---|---|
| 1 | OCR | `OcrService` | pages → `OcrBlock`s | 0 |
| 2 | Layout | `LetterLayoutAnalyzer` → `LetterLayout` (zones are priors) | blocks → `LayoutLine(zone)` | 0 |
| 3 | Convention match | `TemplateMatcher` over `LayoutTemplates.ALL` (extended) | → `TemplateMatch` (top-2 kept) | 0 |
| 4 | Candidates | `CandidateExtractor` / `CandidateTable` | → `OfferedCandidates` | 0 |
| 5 | Open the letter session | `ZoneScoringInterpreter.openLetter` | one prefill | 1 prefill |
| 6 | **Family + topics** | NEW `FamilyClassifier` | → `Classification(family, confidence, topics[])`; `free_form` on abstain | 10 + 14 = **24 scores, one batch** (was 9) |
| 7 | Schema | `ExtractionSchema.slotsFor(family, topics)` | → `List<SlotKey>` | 0 |
| 8 | Parties + slots, joint decode | existing `partyAsk/slotAsk/prescore/redecide`, `JointAssignment`, `SharingRules` | unchanged | ≈ today |
| 9 | **Structured address** (families with a recipient block) | NEW `AddressLineLabeler` + NEW `AddressFormats` (data) → `PostalAddress` | lines → labelled, verified address | ≤16 recipient + ≤8 sender |
| 10 | **Sender reconciliation** | NEW `SenderAddressPicker` (pure) | return line / letterhead / footer → sender address + alternates | 0 |
| 11 | Verification | `SelectionVerifier` + NEW `AddressVerifier` → `ConfidenceCombiner` | → `ExtractionV2Result` | 0 |
| 12 | **Stage 1 stored** | `DocumentProcessingPipeline` | visible result | — |
| 13 | Stage 2: language, extras, **summary**, questions | `enrich` + NEW `SummaryWriter` + NEW `TitleComposer` (pure) | → `Enrichment` | lang 1 + extras ≤6 + summary 1(+1) + questions 1; **title ask removed (−1)** |
| 14 | Storage | `DocumentProcessingPipeline` + `MergeExtractionUseCase` + `UnderstandingToFields` | fields, family/topics, title code, summary + provenance | — |

The net new scoring per letter is ≈ +35–40 forward passes, all tree-shared, ~3–6 s **[unverified, to be measured]**.
A data switch, `ModelProfile.topicsInFirstStage`, moves the 14 topic scores to stage 2 if the time budget is exceeded.

### A.2 The registries (all in `core/domain`, all data)

Each concept has one owner:
- **Family + Topic** → `ExtractionSchema.kt`.
- **Convention** → `LayoutTemplates.kt`.
- **Address format** → NEW `AddressFormats.kt` + a JSON resource.
- **Presentation per family** → NEW `FamilyPresentation.kt`.

**`ExtractionSchema.kt`:** `DocType` becomes `DocFamily`
```
data class DocFamily(id, slots: List<SlotKey>, legacy: DocumentType, actionable, description,
                     directions, hasRecipientBlock: Boolean, presentation: PresentationId)
data class Topic(id: String, description: String, slots: List<SlotKey> = emptyList())
class ExtractionSchema(val families: List<DocFamily>, val topics: List<Topic>) {
  fun family(id); fun familiesFor(direction); fun slotsFor(family, topics); legacyType(id)
  companion: FREE_FORM (unscored, slots = CORE), OFFICIAL_LETTER, INVOICE_BILL, RECEIPT, FORM_APPLICATION, STATEMENT,
             CONTRACT_POLICY, CERTIFICATE_ID, MEDICAL, TICKET_BOOKING, EMAIL_PRINTOUT, OUTGOING_LETTER, PAYMENT_PROOF
}
```
| family | slots beyond `Slots.CORE` | recipient block | legacy |
|---|---|---|---|
| official_letter | APPOINTMENT, EFFECTIVE_DATE, OBJECTION_DEADLINE | yes | OFFICIAL_LETTER |
| invoice_bill (absorbs bill + reminder_dunning) | INVOICE_NO, FEE, ORIGINAL_DUE_DATE | yes | INVOICE |
| receipt | RECEIPT_NO | no | RECEIPT |
| form_application | (core) | no | FORM |
| statement | PREVIOUS_AMOUNT | yes | NOTICE |
| contract_policy | CONTRACT_NO, CONTRACT_END, EFFECTIVE_DATE, NEW_AMOUNT | yes | CONTRACT |
| certificate_id | EFFECTIVE_DATE (issue), CONTRACT_END (expiry) | no | CERTIFICATE |
| medical | APPOINTMENT | yes | NOTICE |
| ticket_booking | EVENT_DATE | no | OTHER |
| email_printout | (core) | no | OTHER |
| free_form (abstain) | CORE only | no | OTHER |

**Topics (14):** government, tax, health, insurance, bank_finance, housing_utilities, work, school_education, vehicle,
telecom, shopping, travel, legal, personal.
- **Topic slots (data):**
  - tax → TAX_NO, CASE_NO;
  - government → CASE_NO, OBJECTION_DEADLINE;
  - insurance → POLICY_NO, NEW_AMOUNT, PREVIOUS_AMOUNT, CONTRACT_END;
  - school_education → EVENT_DATE;
  - telecom/housing_utilities → CONTRACT_NO;
  - health → APPOINTMENT.
- **Multi-valued:** every topic above its threshold is kept, but **at most the best 2 contribute slots**.

**`LayoutTemplates.kt`:** the convention registry.
- New templates:
  - `CH_LEFT` (DIN5008_B geometry);
  - `CH_RIGHT` (address field `Region(0.5,0.07,1,0.28)`, info block left);
  - `UK_LETTER` (sender top-right, address field left below);
  - `US_BLOCK` (everything left);
  - `FR_LETTER` (recipient right-of-centre, soft).
- An optional `locale: LocaleHint(countries, scripts)` acts only as a tie-break weight in `TemplateMatcher.score`.
- RTL stays `RTL_DIN` (a mirror). No language words anywhere.

**`AddressFormats.kt` + `core/domain/src/main/resources/address/formats.json`:** a subset of the libaddressinput data
(CC-BY-4.0, with attribution in Settings › About).
```
data class AddressFormat(iso2, postcodeRegex: Regex?, postcodeBeforeCity: Boolean, requires: Set<AddressPart>,
                         countryNames: List<String>, lineOrder: List<AddressPart>)
object AddressFormats { fun of(iso2); fun byPostcodeShape(line): List<AddressFormat> }
```
It covers ~40 countries (EU/EEA + CH, UK, US, CA, TR, AE/SA/EG) and is loaded via the classloader (domain purity stays).

**`PostalAddress` (core/model, @Serializable):**
- `lines` (raw, always kept);
- per part, an `AddressPartValue(value, lineIdx, bbox, confidence)` for: recipientNames[], salutation, organisation,
  department, careOf, attention, street, houseNumber, addressExtra, postcode, city, region, countryIso2, poBox, packstation;
- plus `formatId`, `verified` and `notes`.

**`FamilyPresentation.kt`:** `PresentationSpec(sections)`, where each `Section` has a kind (RECIPIENT_BLOCK | SENDER_BLOCK |
ACTION | REFERENCES | DATES | PARTIES | TEXT | EXTRAS) and slot keys. `ExtractedPresenter` reads it instead of the
`groupOf` when-chain.

### A.3 Stage details

**Family/topics (`FamilyClassifier`):**
- **One `scoreBatch`:** "Is this document <family description>?" for each family, and "Does this document concern <topic>?"
  for each topic.
- **Decision:** the family is the argmax if it beats `threshold("family")`, else `free_form`. The confidence comes from
  `ScoringProfile.confidence(margin)`. The topics are all those above `threshold("topics")`.
- **Recording and override:** the recording is named `score:family`. A family forced by the user skips the family scores
  (`familySource = USER`).

**Structured address (`AddressLineLabeler`):**
- **Input:** the `ADDRESS_FIELD` lines of page 1 plus the chosen addressee party.
- **(a) Shape pass:** find the postcode+city line (reuse `isPostcodeLine`), the street+number line, the country line (checked
  against the `AddressFormat.countryNames` data) and the PO-box line.
- **(b) Scoring pass on the word-only lines:** `scoreGrid(heads=lines, asks=[PERSON, ORGANISATION, DEPARTMENT, ROUTING])`, at
  most 16 cells.
- **(c) `AddressVerifier` checks:**
  - the postcode regex;
  - that the required parts are present;
  - that it is one block;
  - that the addressee's name is among the name lines (else confidence is capped).
- **Country:** from the country line, else the unique format whose postcode shape matches, else null (confidence capped at
  MEDIUM).
- **CH retry:** if there is no postcode line and the runner-up template is `CH_RIGHT`/`RTL_DIN`, label its zone once more.

**Sender address (`SenderAddressPicker`, 0 scores):**
- **Candidates:** the return line split at its separators, the letterhead stack and the footer stack.
- **Choice:** the data order `[LETTERHEAD, RETURN_ADDRESS_LINE, FOOTER]` applies. The first candidate that verifies wins;
  the rest become `alternatives`.

**Title (`TitleComposer`, pure, 0 asks):**
- **Code and render:** `titleCode = "composed"` with `titleArgs = [familyId, senderName?, subject?]`, rendered as
  "{family label} · {sender} · {subject}".
- **Subject:** the existing quote-verified `text:subject` ask (≤8 words).
- **Policy:** `DocumentTitlePolicy` is unchanged, so a user title is never touched and a legacy real-words title is never
  touched.
- **Removed:** the `text:title` ask.

**Summary (`SummaryWriter`, ≤2 asks):**
- **The ask:** "FACTS: sender …; addressed to …; amount … due …; subject … Write 1–2 sentences (≤30 words) in the letter's
  language, using only these facts."
- **Gate (code):**
  - every number, amount and date must occur in the OCR text or in the facts;
  - every capitalised multi-word span must be a verified name or an OCR substring;
  - anti-copy: reject the answer if one OCR line covers ≥70% of it.
- **On failure:** retry once, then fall back to a **template summary** (`summaryCode="template"`, rendered from string
  resources).
- **Provenance:** `summarySource ∈ {MODEL, TEMPLATE, USER}`.
- **Removed:** the "all sentences quoted = better" preference (it rewards copying).

## B. Storage: DB v15 (`MIGRATION_14_15`, additive except one drop)

**`extracted_data`:**
- **`reviewState`** (`UNREVIEWED | CONFIRMED | EDITED | IGNORED`) becomes the single owner of review state.
  - Backfill: deletedByUser → IGNORED; USER + confirmed → EDITED; confirmed → CONFIRMED.
  - The old columns stay; `isConfirmed` and `deletedByUser` are derived from it.
- **`alternatives`** (JSON, the top-3 runner-ups) feeds the Edit chips.
- **Structured address as rows:**
  - slot keys `addressee.name|organisation|department|care_of|street|house_number|extra|postcode|city|region|country|po_box|raw`,
    and the same under `sender.`;
  - merge, confirm, history and reprocess protection come for free;
  - the existing `sender`/`addressee` rows stay as the party name rows.

**`documents`:**
- `extractionType` now holds the **family id**.
- New columns:
  - `topics` (JSON);
  - `familySource` (MODEL|USER);
  - `titleSource` (DEFAULT|COMPOSED|MODEL|USER, backfilled);
  - `summarySource`, `summaryCode`, `summaryArgs`;
  - `layoutTemplate`.
- `entity_proposals` is dropped (directive 7); `dismissed_entities` is kept for Phase 2.

**Old documents and reprocessing:**
- **Legacy types** are mapped in the migration SQL, so the Extracted tab can render old documents before the re-read:
  bill/reminder_dunning → invoice_bill; authority_tax/health/school/info_no_action → official_letter;
  insurance_contract → contract_policy; other → free_form.
- **Version:** `ExtractorVersion.CURRENT = "extraction-v2-2"`, bumped only at the end of P4.
- **Protection on reprocess:**
  - `MergeExtractionUseCase.isProtected` becomes `reviewState != UNREVIEWED`, so IGNORED rows are tombstones;
  - the family is overwritten only when `familySource == MODEL`, and the summary only when `summarySource != USER`;
  - the title follows the policy.

## C. Extracted tab UX (inline only, `feature/documents`)

1. **Header:** an editable family chip ("Rechnung ▾"), read-only topic chips and a confidence dot. The chip menu offers
   "Change type", which re-presents the sections at once, and "Read again as …", which re-reads with that family while
   the merge protects reviewed rows.
2. **Sections per family:**
   - **Letters, invoices, statements, contracts and medical:** a structured **Recipient** block (names / organisation /
     street + number / postcode city / country, with uncertain parts underlined), then a **Sender** block. After them come
     **Action** (due date, amount, IBAN, objection deadline), **References**, **Dates** and **Other details** (collapsed).
   - **Receipts:** Merchant, Total & payment, Date, References.
   - **free_form:** today's grouping.
3. **Check first:** a "Check these (n)" group at the top holds the uncertain fields, expanded. The main button reads
   "Confirm n confident" while uncertain fields remain and "Confirm all" when none do. The undo snackbar stays.
4. **Inline actions on every row:**
   - Every row ends in ✓ Confirm, ✎ Edit and ✕ Ignore, which replace the dropdown menu.
   - An address block has block-level Confirm/Ignore plus Edit per part.
   - Confirmed rows collapse to one line.
   - Ignored rows move to a collapsed "Ignored (n)" footer with Restore. They are never re-asked or brought back by a
     re-read.
5. **Edit sheet:** a prefilled value, a row of alternative chips (value + page) and "Show on page", which opens the page
   preview with the bbox highlighted. The chat's `CitationPreviewDialog` moves to `core/designsystem` as
   `PagePreviewDialog`, and `GetDocumentPreviewUseCase.forField` is added. **[unverified: the image-loading dependency in
   core:designsystem]**
6. **Summary card:** the composed title, a badge ("AI summary" or "Summary from the fields"), "Summary coming…" during
   stage 2, and a pencil that sets `summarySource=USER`.
7. **Removed:** the EntityProposalCard, the ProfileSuggestionCard and the "is this you?" flow. After Phase 2, a silent
   "Me"/member chip appears on the recipient block, plus one grouped question on Home for a genuinely new name at your
   address.
8. **Strings:** all text comes from string resources.

## D. Mapping from the current code

**KEEP:**
- LetterLayoutAnalyzer/LetterLayout (with the shape helpers made internal);
- TemplateMatcher, LayoutTemplate (+ `locale`), ZonedLetter, ZonePlan, SlotPlacements, SlotDecoder, JointAssignment,
  DecoderSpec/SharingRules;
- ScoringProfile (+ the `family`/`topics`/`addr` thresholds), ModelProfile (+ `topicsInFirstStage`), ZonePrompt;
- candidates/*, PromptSession;
- SelectionVerifier (summary preference edit only), ConfidenceCombiner, QuoteVerifier;
- MergeExtractionUseCase (`isProtected`), DocumentTitlePolicy;
- ReprocessOutdatedDocumentsUseCase, ReprocessDocumentWorker, DocumentEnrichmentWorker;
- the benchmark (gate, fixtures, recordings) and PartyFields.

**CHANGE:**
- ExtractionSchema (DocFamily, Topic, slotsFor, FREE_FORM unscored, LegacyTypes);
- LayoutTemplates (+5);
- ZoneScoringInterpreter (three call sites + alternatives, title ask dropped; done LAST, after the speed merge);
- ExtractionV2Model (family, topics, addresses, alternatives);
- ExtractionV2Adapter/UnderstandingToFields (addressee.*/sender.* facts);
- DocumentUnderstanding/Document/ExtractedData (ReviewState);
- DocumentProcessingPipeline (family/topics/title/summary provenance; the proposal path deleted; organisations stay
  auto-linked until Phase 2);
- PamMigrations/PamDatabase/Entities/DocumentDao/mappers (v15);
- DocumentRepository (setFieldReviewState, setDocumentFamily, updateSummary, confirmAll(onlyConfident));
- feature/documents per C;
- TypeAccuracyTest → FamilyAccuracyTest (with remapped expectations);
- ExtractorVersion → extraction-v2-2.

**DELETE:**
- the proposal half of EntityProfileLinker, EntityProposalService, EntityProposal, EntityProposalDao/Entity and the
  `entity_proposals` table;
- ProfileMatchingService, EntityCoverageFilter and DocumentDetailViewModel.runProfileMatching (ProfileMatcher stays until
  Phase 2);
- QuestionnairePrompt.title() / otherLabel();
- the old types: the scored OTHER, REMINDER_DUNNING, AUTHORITY_TAX, HEALTH, INSURANCE_CONTRACT, SCHOOL, INFO_NO_ACTION.

## E. Phased plan (each phase = one PR into feat/extraction-v2, in its own worktree, Sonnet-sized)

Global gates for every phase:
- BenchmarkGateTest green (no metric below baseline.json);
- DecoderGoldenTest unchanged;
- Konsist green;
- unit tests for every new pure class;
- no English literals stored as data.

| Phase | Scope | Can run in parallel? |
|---|---|---|
| **P0** Registries + classification (domain) | ExtractionSchema, FamilyClassifier, ScoringProfile, ModelProfile, SlotLabels ids, FamilyAccuracyTest, manifests. DoD: slotsFor tests; FREE_FORM never scored; family accuracy on the mapped recordings ≥9/13; ≤24 classification scores | yes |
| **P0b** DB v15 + model types | migrations, entities, DAO, mappers, ReviewState, Document, repository, merge protection, MigrationTest. DoD: all 4 backfill cases, proposals dropped, legacy mapping, merge protection tests. No version bump here | yes (with P0) |
| **P1** Structured address (domain) | AddressFormats + json, PostalAddress, AddressLineLabeler, AddressVerifier, SenderAddressPicker; adapter emits addressee.*/sender.*. DoD: the fixtures' address parts verify; PO box/UK/US synthetic zones; CH retry; RTL doesn't crash; ≤16+8 scores | yes (needs P0b types) |
| **P2** Title + summary (domain) | TitleComposer, SummaryWriter, SummaryGate; the verifySummary change; the title/otherLabel asks dropped. DoD: composer rules (user/legacy titles untouched); gate → retry → template; anti-copy; Arabic digits; a summary always exists | yes |
| **P3** Extracted tab (feature) | FamilyPresentation, ExtractedPresentation, DetailScreen/VM, strings, PagePreviewDialog moved, forField preview; proposals UI removed. DoD: presenter tests per family, check-first ordering, ignored footer, address block; VM state transitions; Compose test for the 3 actions | yes (needs P0b) |
| **P4** Integration (after the speed merge) | ZoneScoringInterpreter, ExtractionV2Pipeline/Ports, DocumentProcessingPipeline, EntityProfileLinker trim, version bump, device re-record. DoD: 16 letters with fields/roles/halluc not below the last report; family ≥11/13; time to visible result ≤ the speed target + 6 s (else flip topicsInFirstStage); reprocess leaves confirmed/edited/user-title values untouched; RecordingCompareTest | sequential |
| **P5** Conventions (data) | +5 templates, the matcher tie-break, synthetic CH-right/UK/US/FR fixtures. DoD: zone accuracy 1.0 on the new fixtures without lowering the 16; a golden template table | after P1 |
| **P6** Silent identity (Phase 2, slimmed) | the profiles columns, profile_aliases, document_parties, ResolveParties as the single writer (matching ladder; no link, no question otherwise), onboarding "Who are you?" prefilled from confirmed addressee rows, one grouped Home question for a new name at your address. ProfileMatcher/EntityLinkingUseCase/the rest of EntityProfileLinker deleted | after P4 |

**Scoring budget per letter:**
- Stage 1: prefill 1, family+topics 24, parties ≈ as today, slots as today, address ≤24 (letters only).
- Stage 2: lang 1, extras ≤1 batch + ≤6 asks, subject 1, summary ≤2, questions 1.
- The title costs 0.

**Risks and mitigations:**
- **0.8B family accuracy / "other" winning:** abstain replaces the scored other; one-line descriptions with a golden test;
  the chip is the user's escape hatch; measured on the device before the version bump.
- **Topic noise:** the threshold is fitted on the recordings; a wrong topic costs one unfilled slot, never a wrong value.
- **Time:** every new cost is tree-shared; profile flags move work to stage 2 without code changes.
- **Multi-page:** the header zones are page 1 only.
- **Non-Latin scripts:** the pipeline is script-neutral and has an RTL template and AE/SA/EG formats. Recognizer routing
  stays parked, and the Arabic fixture stays in the gate.
- **Reprocess of old documents:** the bump comes last in P4; protection by reviewState; legacy mapping; no questions ever.
- **Speed-agent conflicts:** P0–P3 don't touch ZoneScoringInterpreter/PromptSession/ZonePrompt/ExtractionV2Pipeline/
  DocumentProcessingPipeline. Only P4 edits them.
- **Recording continuity:** LegacyTypes keeps FamilyAccuracyTest meaningful until the P4 re-record.

## F. Open questions for the user
1. Fold "Mahnung/reminder" into invoice_bill? (Recommended: yes)
2. Topics in stage 1 (+14 scores, ~+2 s) or stage 2? (Recommended: stage 1, measured in P4)
3. "Read again as <family>" in v1? (Recommended: yes)
