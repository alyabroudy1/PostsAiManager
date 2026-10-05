# Form assist: the AI finds what must be filled in and prepares the values (design, 2026-10-01)

Status: CONFIRMED by the user on 2026-10-01 ("ok go ahead and let ur agent test it once implementation done").
- 2026-10-01: the user stopped the Fable review ("no third-eye review again for this project, hurry"). The planner
  wrote the contracts on feat/form-assist (63f0ac4, caa1691): FormAssist.kt (model), FormDataKeys, PersonDataSource.
  - F1 (fa/f1-profiles: Profile relationship/birthDate/sensitive, profile_facts, MIGRATION_15_16, the profile detail
    UI + Saved details) and F2 (fa/f2-understanding: domain form understanding + FillValues + AnswerVerifiers) are
    running in parallel.
  - F3 (form tables amended into 15_16, the chat conversation, the fill card, memory, entry points) starts after both
    merge, then the device smoke.
  - Also fixed: feat/extraction-v2 had the llama.cpp submodule replaced by a symlink (8daee8e); it was restored in a
    new commit on feat/extraction-v2.
- Old flow: a Fable review/finalization → F1/F2/F3 via Sonnet agents on branch feat/form-assist (from feat/extraction-v2,
  DB v16) → a device smoke test by an agent with an invented form and invented test profiles.

The user's idea:
- the AI identifies the fillable fields of a form (e.g. a swimming-course registration);
- it asks which managed profile the form is for (e.g. the son Max);
- it fills the child's fields with Max's data and the parent fields with his father's;
- v1 output: field name, location and value (no PDF writing yet); automatic filling later.

## Real-life example
A scanned "Anmeldung Schwimmkurs Seepferdchen" (2 pages):
1. **The offer:** the document opens as family `form_application`. The Extracted tab shows a card: "This is a form to fill
   in. **Help me fill it**".
2. **Who it is for:** a sheet asks "Who is this form for?" with chips for the managed profiles:
   - Max is preselected, because the form says "Kinder 6–10 Jahre" and Max is 7. The reason is quoted from the page.
   - One tap confirms. The parent section is filled automatically with Max's father, because Max has exactly one
     parent profile. With two parents there is one more chip row: "Parent/guardian: Peter · [other parent]".
3. **The fill guide**, grouped by page and section:

| Field (as printed) | Where | Value | Status |
|---|---|---|---|
| Name des Kindes | p.1, top | Max Mustermann | ✓ from Max's profile · copy · show on page |
| Geburtsdatum | p.1, top | 12.03.2019 | ✓ from Max's profile (date in the form's format) |
| Anschrift | p.1 | Musterstraße 12, 54321 Beispieldorf | ✓ household address |
| Name Erziehungsberechtigte/r | p.1, middle | Peter Mustermann | ✓ from the father's profile |
| Telefon (Notfall) | p.1, middle | 0151 … | ✓ from the father's profile |
| Allergien / Hinweise | p.1, bottom | — | ✎ needs your input (with "Save to Max's profile") |
| ☐ Seepferdchen vorhanden | p.2 | — | ☐/☑ your choice |
| Unterschrift | p.2, bottom | — | ✍ sign here (show on page) |
| IBAN (Lastschrift) | p.2 | DE… | ✓ from the father's profile, masked; tap to reveal |

4. **Finishing:** "8 of 11 ready · 2 need you · 1 signature". Each row has copy and "Show on page" (the page with the
   empty field box highlighted, reusing PagePreviewDialog). The user writes the values on paper or in the PDF.
5. **Remembering:** the typed allergy can be saved to Max's profile. The next form fills it automatically.

## AI-MANAGED VIA TOOLS (user decision, 2026-10-01, binding; replaces the code state machine)
The user said: "I want that interaction to be managed by the AI, not a code check. We provide the tools and tell the AI
about them, and the AI manages things."

**The AI agent loop:**
- The chat model gets a system prompt + the tool definitions. It decides each step itself:
  - what to ask, in which order and with which wording;
  - how to interpret the user's reply;
  - when to remember;
  - when it is done.
- **Code's role:**
  - it executes the tools;
  - it guards inputs: tool arguments are validated; a value must come from a profile value, the user's own words or a
    printed option, otherwise the tool returns an error the AI sees and handles;
  - it renders the results (card, chips).
- There is no hard-coded question order, no intent classifier and no question template in the main path.

**Tools** (each small, typed, JSON args/results):
- `read_form()` → the fields (id, label, section, page, kind, options, the suggested key/role, the current value/status),
  from the existing understanding pipeline, cached per document.
- `list_people()` → managed profiles (id, name, relationship, age, isMe).
- `get_person_details(person_id)` → the stored values by key (sensitive ones masked: `***`, usable but not displayed).
- `fill_field(field_id, value, source: profile|user|option, person_id?)` → the verifier checks it (the value must
  match the source; options must match the printed option; dates/IBAN/email shapes) → ok | error(reason).
- `ask_user(question, chips[])` → shows the question with chips in the chat and ENDS the turn; the user's reply comes
  back as the next user message.
- `remember_detail(person_id, key, value)` → after the user agreed (the AI asks first via ask_user).
- `show_fill_card()` → posts/refreshes the live card.
- `show_on_page(field_id)` → a page chip.
- `finish(summary)`.

**Reliability with a 0.8B model** (honest risk: small models are weak at multi-step tool use):
- **Grammar-constrained tool calls:** the engine already supports GBNF grammars. Each assistant step is forced into
  `{"tool": <one of the names>, "args": {...}}` matching the schemas, so the output is always a valid call.
- **A compact context:** a short system prompt, a field summary instead of the raw OCR, the last few turns only.
  KV-cached.
- **Guardrails in the tools:** a value can't be invented; the per-turn step limit is ~6 tool calls; a loop/repeat
  detector returns "you already asked this".
- **A fallback:** if the model produces no valid progress for 2 steps, it gets a hint result ("next open field: X"); it
  still decides the wording.
- **Model profile:** `forms.agentModel` lives in the ModelProfile registry (0.8B default; 2B as the opt-in "thorough"
  profile if 0.8B proves too weak).

**What stays:**
- the understanding pipeline (it becomes the read_form tool);
- the storage (form_fills / form_fields / profile_facts);
- the fill card + chips UI;
- the verifiers (now inside fill_field);
- the memory.

**What goes:**
- FormFillConversation's state machine as the driver;
- the intent classifier;
- the question writer/templates as the main path (kept only as tool-error hints).

Build status: a generic on-device agent framework (core/domain/agent: AgentTool, ToolRegistry, AgentLoop, the
ToolCallFormat port with a Qwen native format, a GBNF from the tool schemas) + the form tools, on branch fa/agent
(from feat/form-assist 6615214), started 2026-10-01. FunctionGemma 270M is noted as a later option for a fine-tuned
"action model" (Google publishes a fine-tuning recipe).

Device status 2026-10-02 (agent-3, HEAD 2dbfaa9):
- **0.8B:** progresses but asks poor questions.
- **2B (side-loaded):** fills 4/14 from the profile, then loops on the payer question after a typed "someone else"
  name.
- **Bugs:**
  - an imported model isn't matched to its catalog descriptor;
  - START_OVER doesn't reset the engine session;
  - a typed-name suggestion loops;
  - role names come from sentence fragments;
  - no error text in the trace.
- An agent patched the device's installed.json via run-as sed (rule 9d added).
- Planner recommendation to the user: hide forms for the first release + continue with dynamic tool exposure per state.

RELEASE DECISIONS (user, 2026-10-02):
- allowBackup stays true; the de/ar locales stay; version 1.0.0 (code 1).
- The release build gets a smoke test on the phone as a separate app (the user uninstalls it later).
- The upload signing key is to be created by the user (keytool); the build reads PAM_UPLOAD_* properties.
USER DECISION 2026-10-02: option 3.
- Form filling is HIDDEN in the first release by a `formFilling` flag (off in release, on in debug) on
  feat/form-assist; profiles + saved details stay.
- In parallel, branch fa/agent-v2: dynamic tool exposure per state (ToolPolicy), import hash matching, the start-over
  reset, typed-name role answers, role names from labels, error text in the trace, a few-shot example.
- A release checklist → plans/RELEASE-CHECKLIST.md.

## IT HAPPENS IN THE AI CHAT (user decision, 2026-10-01, binding)
The whole filling is a conversation in the document's AI chat, like chatting with a modern AI assistant. There is no
separate fill-guide screen.

**Entry points:**
- the card "Help me fill it" on a form opens the document chat with the filling started;
- in any document chat, the user can type something like "help me fill this out" / "fülle das für Max aus" / Arabic.
  The AI recognises the intent.

**Example conversation:**
- AI: "This is a registration for the swimming course *Seepferdchen* (2 pages, 11 fields to fill). Who is it for?"
  [Max] [Sara] [Me] [Someone else]
- User taps Max (or types "für meinen Sohn").
- AI: "I filled 8 of 11 from Max's and your profile:" followed by a **fill card** in the chat (a rich message): a
  table of field · value · page, each with copy and a citation chip that opens the page with the field marked (the
  existing chat citation preview).
- AI: "Does Max already have the Seepferdchen badge?" [Yes] [No]
- User: "nein"
- AI: "Which course slot?" [Mo 16:00] [Mi 15:00] [Sa 10:00]
- User: "Mittwoch". The AI maps it to the Mi 15:00 option, and code checks it is one of the form's options.
- AI: "Any allergies or health notes for the course?"
- User: "Nussallergie". AI: "Done. Should I remember this for Max?" [Yes] [No]
- AI: "All set: 10 of 11 ready. Only the signature is left (page 2, bottom)." [Show on page] [Copy all]
- The user can interrupt at any time, for example "use my work phone", "do it for Sara instead", or "what does
  Haftungsausschluss mean?". The AI answers or adjusts and then continues.

**How it stays reliable with a 0.8B model** (the conversation feels free, but the steps are guided):
- **Code runs the fill as a state machine** over the stored `form_fields`: which field is next, what is done, what is
  missing. The conversation can be resumed later, and the latest fill card is always available in the chat (and from
  the document).
- **The AI does the language:**
  - it understands the form (the pipeline steps below);
  - it writes each question naturally in the user's language;
  - it interprets each user message, by scoring/constrained choice, into an intent: ANSWER (to the current question) /
    CHANGE_SUBJECT / CHANGE_VALUE / SKIP / ASK_ABOUT_FORM (a grounded chat answer, as today) / STOP;
  - it maps a free answer to the field (an option, a date, text).
- **Code verifies and writes every value:** a choice must be an option on the form, a date must parse, a value comes
  from the user, a profile fact or the form's options, never invented. The fill card shows the source of each value.
- **Reuse:**
  - the existing KV-cache chat session, thinking/answer handling and streaming;
  - the citation chips + PagePreviewDialog for "show on page";
  - the suggested-question chips for the answer buttons.

The sections below describe the same pipeline and data. "Fill guide UI" now means the fill card inside the chat.

## Memory: "Remember for Max?" (the user liked this, 2026-10-01; keep it central)
- **What:** every answer the user gives in a fill conversation can be remembered for that person with one tap
  ([Yes] [No]). It is stored as a `profile_facts` entry (key, value, source USER, date, sensitive flag), so the next
  form fills it without asking.
- **Where facts come from:**
  1. a fill conversation ("Remember for Max?");
  2. the person's profile page ("Saved details": add, edit, delete);
  3. (Phase 2) confirmed letters, e.g. an insurance number a confirmed letter shows for Max. These are proposed
     silently as "Found in a letter: save?" on the profile page, never as a per-letter popup.
- **Staleness:** a fact older than ~12 months that can change (phone, school class, address) is shown in the fill card
  as "from 2025 · still right?". The AI asks to confirm it once instead of filling it silently.
- **Privacy and sensitivity:**
  - health-related facts (allergies, conditions, medication) and identifiers (insurance no., IBAN) are marked
    sensitive by the key registry (data);
  - they are masked in the fill card until tapped, never used in the all-documents chat, and kept on the device only.
- **Control:** the user can see, edit and delete every remembered fact on the profile page. "Forget this" works from the
  fill card too.

## Clarifying questions, as modern AI assistants do (user addition, 2026-10-01)
After the automatic fill, the AI **asks the user about the fields it could not settle**, one at a time, in a short
chat-style "Fill together" step inside the fill guide.

**When it asks:**
- **A missing fact:** "Does Max have any allergies or health notes the course should know about?"
- **A choice or checkbox:** "Does Max already have the Seepferdchen badge?" [Yes] [No]. "Which course slot?"
  [Mo 16:00] [Mi 15:00] [Sa 10:00]. The options are read from the form itself and quote-verified against the OCR.
- **A fact that is ambiguous between profiles:** "Telefon: use your mobile 0151… or the landline 0211…?"
- **An unclear role:** "Should the payer (Kontoinhaber) be you or [other parent]?"
- **An unsure key:** "This field says «Verein seit». Is that the date Max joined the club?" [Yes, I'll enter it]
  [Not applicable]

**How:**
- The AI writes the question in the user's UI language from the field's label + section. Answer chips come from the
  form's options or the profile facts.
- Free-text answers are verified by code: a choice must match an option, a date must parse, a phone number has a
  phone shape. A failed check is re-asked once with a hint.
- Each answer fills the field at once and offers "Remember for Max" (`profile_facts`).
- Questions are grouped and ordered by page, and the most important come first (required/marked fields, signature
  last).
- At most ~5 per round, then "4 more questions · continue / I'll do the rest by hand".
- "Skip" leaves the field as "needs your input".
- It uses the existing on-device chat engine in a form mode (a KV-cached session over the form's fields), so follow-ups
  are fast. The AI never invents a value: answers come only from the user, the form's options or profile facts.
- The user can also type a free request, e.g. "fill it for Sara instead" or "use the office address". The AI maps that
  to an action (change subject / change fact) and confirms it.

## Principles (binding directives applied)
- **The AI decides meaning, code only verifies and fills.**
  - The AI decides which blanks are fields, what each field asks for, and whose data it is.
  - Every VALUE comes from a profile fact or the user's own input, copied and formatted by code.
  - The AI never writes a value, so it can't hallucinate one. This holds by construction, and a test proves it.
- **Nothing static:**
  - no keyword lists for labels;
  - the data vocabulary (what a field can ask for) and the role list are data registries;
  - meaning in any language comes from the model plus the multilingual embedding model the app already ships.
- **Low noise:**
  - one question ("Who is this form for?"), plus a second only when the guardian is ambiguous;
  - offered, never pushed: a card on forms, and a menu item "Fill in this form" on any other document (the user can
    always ask).
- **Speed:**
  - it runs ON DEMAND, in the background with progress, never in the scan pipeline, so scanning stays as fast as today;
  - the filled guide is stored, so reopening is instant.
- **Privacy:**
  - everything stays on the device;
  - sensitive facts (health, insurance, IBAN) are masked until tapped;
  - forms of sensitive profiles follow the same chat rules as sensitive documents.

## Pipeline (on demand; all behind ports, one small use case per step)
1. **`FindFillableFields`** (code geometry, a candidate generator like the CandidateExtractor):
   - from the OCR lines and page layout it finds BLANKS:
     - a label followed by empty space to the right margin or to the next label;
     - runs of underscores or dots;
     - box/checkbox glyphs (☐ □ ○, or OCR'd look-alikes, decided by shape);
     - an empty table cell under a header;
     - an empty line under a label.
   - Each candidate holds its label text + bbox, a fill region bbox, a kind hint (TEXT / DATE / CHECKBOX / CHOICE /
     SIGNATURE / TABLE_CELL) and its section (the nearest heading above).
   - Already-filled fields (OCR text inside the fill region) become "already filled: …".
2. **`ConfirmFields`** (AI, label-free yes/no scoring, the proven ZONES_SCORING technique):
   - "Is «Name des Kindes ____» something the reader must fill in?"
   - Only ambiguous candidates are scored; strong structural evidence (underscores, boxes) passes without a score.
3. **`ClassifyFields`:**
   - The data key comes from the `FormDataKeys` registry (data: given_name, family_name, full_name, birth_date,
     birth_place, address, street, postcode_city, phone, mobile, email, nationality, insurance_no, health_insurer, iban,
     account_holder, school/class, employer, date_today, place_today, signature, free_text, …).
   - The multilingual embedding model ranks the keys for the label (and its section) in ms; the LLM then scores only
     the top 3 + "none".
4. **`AssignRoles`** (AI, scored per SECTION, not per field, so it stays cheap):
   - the role of each section/field comes from the `FormRoles` registry: SUBJECT (the participant/applicant), GUARDIAN,
     PAYER/ACCOUNT_HOLDER, SIGNER, EMERGENCY_CONTACT, OTHER;
   - e.g. "Angaben zum Kind" → SUBJECT, "Erziehungsberechtigte" → GUARDIAN.
5. **`ChooseProfiles`:**
   - The subject is suggested from the profiles plus the form's verified facts (an age range vs birth dates, a quoted
     name, the household). The user confirms it in the one question.
   - The other roles resolve through Phase-2 relationships (GUARDIAN = the subject's parent; PAYER defaults to the
     guardian; SIGNER = the guardian for a minor, the subject for an adult). They are asked only when ambiguous.
6. **`FillValues`** (code only):
   - the facts of the chosen profiles are mapped by data key and formatted for the form's locale (date format,
     address order from AddressFormats);
   - a missing fact → "needs your input"; a checkbox/choice → the user's tick, unless a profile fact answers it
     exactly;
   - a signature → "sign here".
7. **Store + show:** the Fill guide, with Copy / Show on page / input / "Save to profile".

Cost estimate for a 2-page form with ~20 fields: about 20 confirm scores (often fewer) + 20×4 key scores + ~5 section
role scores, at the current ~0.3–0.5 s per short score. That is roughly 40–60 s in the background. Acceptable on
demand. It can be optimized later (roadmap).

## Data (DB v16, additive)
- **`profile_facts`** (profileId, key, value, source USER / CONFIRMED_DOC, sourceDocumentId?, sensitive, updatedAt):
  - an extensible person data store;
  - keys come from `FormDataKeys`;
  - it is filled by the user, by "Save to profile" in the fill guide, and (Phase 2) from confirmed extracted
    identifiers (an insurance number a letter confirmed for Max).
- **`form_fields`** (id, documentId, page, labelText, labelBbox, fillBbox, kind, section, dataKey, role, confidence,
  reviewState, value, valueSource PROFILE / USER / NONE, profileId, updatedAt):
  - one owner: the fill guide;
  - it reuses ReviewState semantics (a confirmed/edited value is never overwritten by a re-run).
- **`form_fills`** (documentId, subjectProfileId, createdAt, status): which profile a form was filled for.
- Depends on Phase 2's profile model (`managed`, `relationship`, `birthDate`, `isSelf`). Phase 2 must come first, or
  its profile part ships together with this.

## UI
- **Extracted tab:**
  - for `form_application`, a card "Help me fill it";
  - for any other family, the overflow item "Fill in this form".
- **"Who is this form for?" sheet:** managed-profile chips (the preselection carries its quoted reason) and
  "+ add person".
- **Fill guide screen:**
  - sections by page;
  - rows with status icons;
  - copy; "Show on page" (PagePreviewDialog with the fill region marked);
  - inline input + "Save to <name>'s profile";
  - sensitive values masked;
  - a progress header ("8 of 11 ready").
- **Profile page:** a "Saved details" list (the facts) with source and edit.

## Phases
- **F1** Phase-2 profile basics (if not done): managed / relationship / birthDate / isSelf + `profile_facts` + the
  profile edit UI for facts.
- **F2** FindFillableFields + ConfirmFields + ClassifyFields + AssignRoles. The domain is JVM-tested with synthetic
  forms (DE/EN/AR: a swim course, a school trip consent, a club membership).
- **F3** ChooseProfiles + FillValues + the form-fill CONVERSATION in the document chat:
  - the state machine and the intent reading;
  - the question writer and answer verifiers;
  - the fill card message with copy and citation chips;
  - answer chips and "Remember for <name>";
  - the entry from the card and from a typed request.
- **F4 (later):**
  - "Filled preview": draw the values into the fill regions of the page images and export a PDF to print or send.
    This is cheap for scans, because we have the page images and the bboxes.
  - Then AcroForm filling for imported digital PDF forms.

## Risks
- **Empty boxes are invisible to OCR.** Mitigation: geometry gaps + glyph shapes. An "Add a field" long-press on the
  page preview later.
- **0.8B label understanding:** embedding pre-ranking + yes/no scoring among 3 keys keeps it to what the model does
  well. Wrong keys show as "needs your input", never as a wrong value from another person's facts:
  - a key below the confidence threshold isn't filled;
  - the role must be confirmed for a GUARDIAN fill.
- **Multiple participants in table rows:** v1 fills the first row for the chosen subject; more rows later.
- **Handwriting on already-filled forms:** detected as "already filled", not overwritten.
