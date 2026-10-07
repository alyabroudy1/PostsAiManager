# Agent Skills (phase 2a built, phase 2b to connect)

Plan: `plans/11-gemma4-litertlm.md` (work folder). The design is adopted from Google AI Edge Gallery (Apache 2.0, see
[THIRD_PARTY.md](THIRD_PARTY.md)): a skill is DATA (a folder with a `SKILL.md`); the model sees only the names and descriptions,
calls `load_skill` to read one, then `run_intent` to act. The model decides the content (who to write to, what to say, when);
code verifies the values against the letter and the user confirms on a card before anything fires.

Everything below exists and is tested WITHOUT the LiteRT-LM library. Phase 2b adds the library and connects it.

## The pieces (all on-device, nothing fetched)

| Piece | Where | Role |
|---|---|---|
| `Skill`, `SkillCatalog` (port), `SkillParser`, `SkillNames`, `SkillPrompt` | `core/domain/.../skills/Skill.kt`, `SkillParser.kt` | the SKILL.md model and parsing |
| `AssetSkillCatalog` | `core/data/.../skills/` | the catalog: `app/src/main/assets/skills/<name>/SKILL.md` |
| `AgentAction` (sealed), `AgentActionParser`, `AgentIntent` | `core/domain/.../skills/` | what a `run_intent` call means |
| `ActionGrounding`, `GroundingSources`, `FieldCheck` | `core/domain/.../skills/ActionGrounding.kt` | code verifies the model's values |
| `ProposeActionUseCase`, `LoadGroundingSourcesUseCase` | `core/domain/.../skills/ProposeActionUseCase.kt` | step 1: check, build the proposal. Runs nothing |
| `ConfirmActionUseCase` | `core/domain/.../skills/ConfirmActionUseCase.kt` | step 2: only after Open; the only caller of the executor |
| `AgentActionExecutor` (port) | `core/domain/.../skills/AgentActionExecutor.kt` | does the action, main process |
| `AndroidAgentActionExecutor` | `app/.../agent/` | mail and calendar intents; reminder through `ReminderScheduler` |
| `ReminderScheduler` (port), `WorkManagerReminderScheduler`, `ReminderWorker` | domain port, `core/data` | the app's one reminder owner |
| `AgentIntentSpecs`, `IntentSpec` | `core/domain/.../skills/AgentIntentSpecs.kt` | the Android intents as plain data (pure, tested) |
| `ActionForm` | `core/domain/.../skills/ActionForm.kt` | action <-> the card's editable text fields |
| `ActionCardsViewModel`, `ActionCard`, `DebugActionMenu` | `feature/chat` | the confirm card in the chat |

## Skills shipped (`app/src/main/assets/skills`)

`send-email`, `create-calendar-event`, `schedule-reminder` (Gallery style, from its intents) and our letter skills
`draft-reply-to-letter`, `add-deadline-to-calendar`, `remind-me-before-deadline`. The skill folder name equals its `name`.
`BundledSkillsTest` checks that every skill parses and that every intent and parameter it names is one the parser reads.

## Actions and the `run_intent` contract

`run_intent(intent: String, parameters: String /* JSON */)`, parsed by `AgentActionParser.parse(intent, parameters, chatDocumentId)`:

| intent | parameters | becomes |
|---|---|---|
| `send_email` | `extra_email`, `extra_subject`, `extra_text` | `AgentAction.SendEmail` (opens the composer, `ACTION_SENDTO` + `mailto:`) |
| `create_calendar_event` | `title`, `description`, `begin_time`, `end_time?` (`yyyy-MM-ddTHH:mm:ss`) | `CreateCalendarEvent` (`ACTION_INSERT`) |
| `schedule_notification` | `message`, `year`, `month`, `day`, `hour`, `minute`, `document_id?` | `ScheduleReminder` (our scheduler) |
| `get_current_date_and_time` | none | `GetDateTime` (no card; the executor answers `2026-10-07T14:30:00 Wednesday`) |

`ActionParse.Rejected(reason)` carries a sentence for the model so it can fix the call. `send_sms` and `read_calendar_events` are
refused: later options, they need sensitive permissions (SMS, READ_CALENDAR).

## Grounding (code verifies, the AI decides)

`ActionGrounding.check(action, sources, now)` gives a `FieldCheck` per field. A failing value is flagged on the card ("Not found in
the letter, check"), never changed.

- e-mail `to`: well-formed (`AnswerVerifiers.verifyEmail`) and occurs, as a whole address, in the letter OCR, the verified
  extracted fields, the profile (e-mail and non-secret details) or the user's own words;
- dates (event start/end, reminder time): real, and found in the letter, the verified fields or the user's words (the existing
  `CandidateExtractor` reads every written form). A reminder may also lie before the latest known date (the point of "remind me
  before the deadline") or be today; a reminder in the past is `INVALID`; an event end before its start is `INVALID`;
- figures and references written in subject, body, title, description, reminder text: every token with 2+ digits (amount,
  reference, time, date) must occur in the letter, the verified fields, the profile or the user's words, as a whole value (so `123`
  is not found inside `123,45`); a date written in text counts if it is a known date or today;
- comparing folds case, accents and Arabic spelling and digit variants (`QuoteVerifier.fold`, `OcrText.normalizeChars`). No word of
  any language decides anything.

The card shows the user's edits as "Edited by you": a value the user typed is theirs.

## Reminders: one owner

The app had no reminder scheduler (the Settings switch "Deadline reminders" only stores a preference; the `reminders` table has no
DAO and no worker), so `ReminderScheduler` is the one owner, backed by WorkManager, a channel `deadline_reminders`, and a
notification that opens the letter through `NotificationIntents`/`NotificationRoute.Document` (so through the app lock). With the app
lock on, the notification text is a fixed sentence, like the processing notification. When the deadline-reminder feature is built,
it must use `ReminderScheduler` too. No DB change was made.

## What phase 2b must connect

1. **Add the LiteRT-LM dependency** (phase 1 does this) and port the Gallery tools into `core/ai/litert` (copy with the licence
   header and a "Modified by PostsAiManager" line):
   - `LoadSkillTool` (`@Tool loadSkill(skillName)`): calls `SkillCatalog.load(name)` and returns
     `mapOf("skill_name" to name, "skill_instructions" to skill.content())`, or "Skill not found";
   - `RunIntentTool` (`@Tool runIntent(intent, parameters)`): `AgentActionParser.parse(intent, parameters, chatDocumentId)`; on
     `Rejected` return `mapOf("error" to reason, "status" to "failed")`; on `Parsed` EMIT the action on the action channel and return
     `mapOf("status" to "waiting for the user's confirmation on the card")`. It must not run the action. For
     `get_current_date_and_time` return `ActionDateTime.forModel(LocalDateTime.now())` directly.
   - Register both in `ConversationConfig`; put `SkillCatalog.namesAndDescriptions()` in the system prompt (the Gallery's wording:
     names and descriptions of the skills, then "call load_skill first").
2. **The action channel over AIDL** from `:inference` (where the tools run, in-process JNI like llama.cpp) to the main process:
   an AIDL callback carrying a parcel with the intent name, the parameters JSON and the chat's document id (the `AgentAction` is
   rebuilt in the main process with `AgentActionParser`, which is pure; no new Parcelable per action type is needed). The service
   returns once the card is shown; the user's Open/Cancel does not flow back to the model in v1 (or, if wanted, as the tool result of
   a later turn).
3. **Main process side:** collect the channel in `ActionCardsViewModel.propose(action, userMessages)` (already public; the debug menu
   calls it today). `ProposeActionUseCase` runs the grounding with `documentId` taken from the chat's `SavedStateHandle` and the
   user's messages of the conversation. Cards are not stored (no DB change); if they should survive process death, that is a storage
   decision for 2b (preferences or a file; the DB is frozen at v20).
4. Stream the skill-progress lines ("Loading skill ...") into the chat if wanted (Gallery's `SkillProgressToolAction`); not built.

## Not built (listed for later)

- `send_sms` (needs `SENDTO smsto:`, no permission but sensitive) and `read_calendar_events` (needs `READ_CALENDAR` at run time);
- JavaScript skills, `run_js`, the offline WebView, `run_mcp`, skills from URLs or a remote list (against the on-device-only rule);
- user-imported skill folders (SAF): the Gallery's `addSkillFromLocalImport` is tied to its protos, DataStore selection state and
  Firebase logging, and does not port cleanly; the catalog port (`SkillCatalog`) is where a second source plugs in later (phase 2b+);
- per-skill enable/disable settings.
