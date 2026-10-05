# 10: The on-device tool-calling agent

Status: implemented on branch `fa/agent` (2026-10-01), JVM-tested; the first device pass is still to come. The form-filling
chat ("Form filling (beta)") runs on it. The framework is generic and reusable for other features.

The idea, as in modern assistants with function calling (Gemini, Claude tool use, Google AI Edge's FunctionGemma): **the AI
manages the interaction, code provides tools**. The model reads the conversation and picks one tool call per step; code
validates the arguments, runs the tool, stores the result and renders it. There is no question order, no intent classifier
and no template question in the main path. Code decides only what must be true: a value comes from a stored detail, the
user's own words or a printed option (see `FieldValueGuard`).

## 1. Layout

| Where | What |
|---|---|
| `core/domain/agent/` | the framework: `AgentTool`, `ToolRegistry`, `AgentLoop`, `ToolCallFormat` (+ `QwenToolCallFormat`, `HermesToolCallFormat`), the GBNF builder, `AgentModel` (+ `EngineAgentModel`), `AgentProfile` |
| `core/domain/form/agent/` | the form feature: the tools, `FormAgentSpec` (system prompt, state summary), `FormAgentTranscript` (storage), `FormReader`, `FieldValueGuard`, `FormFillAgent` (entry points) |
| `core/domain/form/fill/` | what the tools reuse: `FillProgress`, `FormMask`, `FormMessageCodec` (how messages are stored and rendered), `FillRequestDetector` (the typed-request gate) |
| `feature/chat` | renders the calls that show something: `FormMessageItem` (question + chips, card, page chip, status line) |

`FormFillAgent` is the only class the chat talks to (`start`, `resume`, `route`, `chip`).

## 2. The loop

`AgentLoop.run(spec, transcript)`, once per user turn:

1. Read the stored entries of the run (user texts, calls, results). The newest entry is what the model answers; the ones
   before it are the history a rebuilt session starts from.
2. `AgentModel.openSession(conversationId, system, history)`: a no-op while the engine's KV-cached chat session holds it.
   The system prompt is the spec's instructions plus the format's tool description. Thinking is off for every step
   (`thinkingEnabled = false`, the chat's forced-close mode), sampling is low (`AgentProfile`).
3. `AgentModel.step(message, request)`: one reply, **constrained by a GBNF grammar built from the registered tool schemas**,
   so it is always exactly one valid call (name enum + the arguments of that tool). `AiRequest.grammar` is the engine's
   existing chat grammar path.
4. `format.parse(reply)` gives name and arguments. A reply that is not a call is discarded (`discardPendingReply`) and asked
   for again, at most `maxInvalidRetries` times.
5. `ArgumentValidator` checks the arguments against the tool's JSON schema (missing, unknown, wrong type, not in the enum);
   an error result goes back to the model as the next message, which then corrects itself.
6. **Repeat detector**: the same call with the same arguments twice in one turn is answered `already done: ...`, not run.
7. The tool runs. An exception becomes an error result. After two failed steps in a row the result carries the spec's hint
   (for the form: the next open field). Every result also carries the spec's one-line **state summary**.
8. The call and its result are stored (`AgentTranscript.record`). A **turn-ending tool** (`endsTurn`, such as `ask_user` and
   `finish`) stores only the call and returns: the user's reply comes back as the next user message.
9. At most `maxStepsPerTurn` (6) steps per turn, then `AgentOutcome.StepLimit`.

**Context size.** A 0.8B model has a 4,096-token window. The standing prompt (instructions + 11 tool schemas) is kept under
about 5,500 characters (a test pins it). `AgentProfile.conversationRoom(systemChars)` is what the conversation may grow to
beyond it; past that the session is reset and rebuilt from `AgentHistory`: whole recent turns within a budget, older ones
replaced by the spec's state summary. The state lives in storage, not in the history, so nothing is lost by the cut.

**Stop, leave, crash.** Cancelling the run (Stop, leaving the chat) pauses it: a status line with a Continue chip. What the
steps did is stored, so continuing (or opening the chat after a crash) re-reads the entries and goes on from the newest one.
A conversation whose last stored step is a turn-ending call is left alone: opening a chat costs no model call.

## 3. The format port

`ToolCallFormat` is how a model family speaks tools: `describeTools`, `grammar`, `parse`, `renderCall`, `renderResult`.
Which one a model uses is data of its profile: `ModelProfile.agent: AgentProfile(format = ToolFormatId.QWEN, ...)`.

- **`QwenToolCallFormat`** (the default, 0.8B and the other Qwen3.5) is the model's NATIVE template, read from llama.cpp's
  `Qwen3.5-4B.jinja` (llama.cpp parses it as "Qwen3-Coder XML"). Tools are JSON objects in a `<tools>` block of the system
  turn; a call is
  ```
  <tool_call>
  <function=fill_field>
  <parameter=field_id>
  f3
  </parameter>
  </function>
  </tool_call>
  ```
  (a string value raw, any other type as JSON); a result is a user turn `<tool_response>...</tool_response>`. The template does
  not describe tools when the on-device engine renders a chat without a tools list (the known llama.cpp limitation), so
  `describeTools` writes that section into the system prompt itself, as the template would.
- **`HermesToolCallFormat`**: the JSON convention of Hermes, Qwen2.5 and Qwen3, `<tool_call>{"name": ..., "arguments": {...}}</tool_call>`.
  Not the Qwen3.5 template, but the one the brief named; kept as the second implementation of the port and for a model whose
  template speaks it.

The grammar of both is generated from the same tool schemas (`Gbnf`): root, one rule per tool, one per argument (an enum is
its literals), the JSON value rules. Golden tests pin the output. A string value in the Qwen grammar excludes `<`, so a value
can never close its own tag.

## 4. The form tools

Each tool is small and typed. Fields are `f1`, `f2`... (page and reading order) and people `p1`, `p2`... (Me first): a small
model garbles a UUID. A secret stored value (IBAN, insurance number, health notes) is only ever the token `***<key>` (for
example `***iban`) in what the model sees; `fill_field` resolves it.

| Tool | Does |
|---|---|
| `read_form()` | the understanding pipeline (`UnderstandFormUseCase`), stored per document by the OCR it read; one line per field `id\|label\|page\|section\|kind\|options\|key\|role\|status`; on a fresh reading the likely subject with its quoted reason |
| `list_people()` | managed profiles: person_id, name, relationship, age |
| `get_person_details(person_id)` | stored details by key, secrets as tokens |
| `fill_from_profile(person_id, role)` | fills every field of a role from that person (`FillValues`: key and role known, a value stored, dates and IBANs written as the form does). Extra to the brief: filling 10 fields one call at a time is too many steps for a 0.8B model; the AI still decides WHO has each role |
| `fill_field(field_id, value, source, person_id?)` | one value, checked by `FieldValueGuard` |
| `ask_user(question, chips[])` | ends the turn; the chat shows the question with the chips as answers |
| `remember_detail(person_id, key, value, user_agreed)` | to the profile (column or fact) |
| `skip_field(field_id)` | leaves a field to write by hand. Extra: without it an unanswerable field stays "open" forever |
| `show_fill_card()` | the live card in the chat |
| `show_on_page(field_id)` | a chip that opens the page with the field marked |
| `finish(summary)` | ends the run: the summary is the last message, the fill is marked done |

### Never invent a value (by construction)

`fill_field` writes only what `FieldValueGuard` can prove, and then runs the shape checks of `AnswerVerifiers`:

- **profile**: the value equals a stored detail of that person (its text, its date or IBAN as the form writes it, the composed
  address, a part of the full name) or its `***key` token; a secret goes only into a field that asks for that very key; a
  field with printed options takes only a stored value that is one of them.
- **user**: the value is contained, case and accent folded, in something the user wrote in this run. Free text for a field
  with options, or for a tick box, is refused ("use source option").
- **option**: one of the printed options; for a tick box without options `yes` or `no`, after the user has spoken.
- a signature is never filled.

`FormToolGuardTest` runs random strings through all three sources against every field and asserts that no field holds a value.

`remember_detail` is guarded the same way: the model must say the user agreed (`user_agreed`), the previous step of the run
must be an `ask_user` and the current turn must begin with the user's reply (so it cannot ask and remember in one breath),
and the value must be what the user wrote or entered, never the model's.

## 5. Storage and what the user sees

No schema change. A step is a `MessageEntity` with its tool columns: a call is role `TOOL_CALL` (`toolCallId`, `toolName`,
`toolArgs`; the message content is the question of `ask_user` or the closing message of `finish`), its result role
`TOOL_RESULT` (same `toolCallId`, `toolResult`). Status lines (progress, paused, no model) are `TOOL_RESULT` with tool name
`form` and a `FormMessage` of codes. The plain chat only sends `USER` and `ASSISTANT` to the model, so none of it becomes
history of a normal question.

The chat renders (`FormMessageCodec.parse`): `ask_user` as a question with answer chips (a tap sends the chip text as the
user's message), `show_fill_card` as the live card, `show_on_page` as a page chip, `finish` as the closing message.
Every other step is protocol: `FormMessageCodec.isAgentStep` hides it, so the user never sees tool JSON.

A run begins at the newest beta-notice line. A typed request ("fülle das aus") is recognised by `FillRequestDetector` (the
embedding model, then one yes/no score; this is the only place a message is read for an intent). While a run goes, every
typed message and tapped chip is the user's next message to the agent. After `finish` the plain chat answers again.

## 6. Adding a tool

1. Implement `AgentTool`: a `name`, a short `description`, `parameters = ToolParams.schema(ToolParams.string(...), ...)` (leave
   a self-evident argument's description empty: the schema is part of the standing prompt), `endsTurn` if it hands back to the
   user, and `execute(args, context)`. Check inputs in the tool and return `ToolResult.error("why")` for anything wrong; the
   model sees the reason. `AgentContext` says what the user wrote (`userReplies`), what this turn did (`turnCalls`) and the
   call that ended the previous turn (`previousTurnEnd`).
2. Register it in the feature's `ToolRegistry` (for the form: `FormAgentTools.specFor`).
3. If the chat should show something for its call, add it to `FormMessageCodec.parse`; otherwise it is protocol.
4. Test it with the scripted model (`ScriptedAgentModel` in the tests of `core/domain/agent`).

A new feature is an `AgentSpec` (conversation id, tools, system prompt, state summary, stuck hint), an `AgentTranscript` over
its storage and the same loop.

## 6a. Guidance for a small model (added after the first device pass)

The first pass showed a 0.8B model asking "who is the form for?" again after every answer. What changed (the AI still decides; code
only tells it where things stand and refuses what is plainly wrong):

- **STATE block.** Every tool result ends with `state`: the form's language, the person chosen per role, filled/open counts, the
  first open fields (id, label, section) and `suggested next: ...`, a call computed from the stored state (`FormGuidance`), for
  example `fill_from_profile(person_id=p2, role=subject)` once a person was picked and the subject role has nobody.
- **The reply is a result.** A user message right after a turn-ending call reaches the model as the tool result of that call
  (`AgentSpec.replyResult`): `{"ok":true,"answer":"Test Kind","matched_chip":"Test Kind","matched_person":"p2","state":"..."}`.
  Stored as before (a USER message); only the rendering changed. In a rebuilt history the state is dropped from older replies.
- **Repeat block.** `ask_user` refuses a question the user already answered in this run (word overlap of the questions, or the
  same chips with a similar question): `already answered: <answer>. Use it: suggested next: ...`.
- **Language.** The system prompt names the form's language (the document's stored `language`) and every state line repeats it.
  `ask_user` refuses a question in another script than the form's language or what the user wrote (`WritingScript`: Unicode scripts
  plus a small language-to-script registry). Two languages of one script (English, German) cannot be told apart by code.
- **Version.** The beta-notice line that starts a run carries `FormAgentSpec.VERSION`. A newest run without it (the earlier
  code-driven chat) or with another version starts a fresh run; the old messages stay visible but never reach the model. A stopped
  run offers Continue / Start over, a finished one Start over (the chat does not silently restart or continue).
- **Trace.** Debug builds log one line per step with tag `FormAgent` (`AgentTrace`): turn, step, tool, argument names, validation,
  outcome, model and tool milliseconds, a rough context size, whether the session was rebuilt, and the question text of an
  `ask_user` with its chips, and the filled/open counts after every tool. No field values, no answers.
- **Question guards (agent-3).** `QuestionGuard` sends an error result with a hint for an `ask_user` that (1) is only a label or
  section title of the form (punctuation aside), (2) offers the subject as the guardian, or a minor subject as payer or signer, or
  (3) is about an open field but whose chips are neither its printed options nor a stored value of its key. Texts are only compared
  with the form and the people, never judged by meaning.
- **Role wording.** `RoleWording` names a role in the form's own words (the section heading when the whole section has the role,
  plus the first field's label: "Zahlung per Lastschrift (Kontoinhaber)"), else `FormWording` (string resources de/en/ar, read in
  the form's language). The STATE block and `list_people` use these names; the English enum word appears only inside a call. A role
  without a candidate person suggests asking about it with the other people and "Someone else" as chips.
- **Form model.** `ModelProfiles.FORM_AGENT_MODELS` (Qwen3.5-2B) is the one setting: `ActiveModelProvider.formModelPath/Id/Config`
  return the first installed of them, else the chat model. The engine loads it for the run; the next chat message loads the chat
  model again (`engine.load` swaps models and drops the session). A side-loaded GGUF is tied to its catalog descriptor by SHA-256 and
  size (`CatalogMatcher`, at import and as a migration of earlier imports on startup), so a hand-copied 2B is the form model too.

## 6b. Dynamic tool exposure (agent-4)

A small model does better with fewer choices. At every step the loop asks the spec `allowedTools(entries)`; the grammar is generated
from just that subset (cached per subset within a run), each result ends with `tools_now` naming them, and a call to another tool is
refused. The system prompt still describes every tool once (it is KV-cached; rebuilding it per step would throw the cache away).

`FormToolExposure` reads the stage from the stored fill and the run (never from the meaning of a text); `ToolPolicy` is the data
table stage to tools (`FormStage`):

| Stage | Tools |
|---|---|
| NOT_READ | read_form |
| SUBJECT_UNKNOWN | list_people, ask_user |
| ROLE_READY (a person is guessed for the first role without one: a child's guardian) | fill_from_profile, ask_user |
| ROLE_ANSWERED (the user named the person by chip or typed the exact name) | fill_from_profile |
| ROLE_NEEDS_PERSON | ask_user, skip_field |
| ROLE_TYPED (the user typed who has the role; the role has an open name field) | fill_field, skip_field |
| OPEN_FIELDS | ask_user, fill_field, skip_field, show_on_page |
| NOTHING_OPEN | show_fill_card, show_on_page, finish |

A typed reply registers like a chip tap: the folded text equals exactly one managed person's name (else the one person whose name
has all its words) and the stage is ROLE_ANSWERED. A reply that fits several people is not registered; its result carries
`candidates`. The stage is computed in one place (`FormGuidance.stage`), and the "suggested next" text is checked against the
policy for that stage (`ToolPolicy.named`): a suggestion that names a tool the stage does not expose is replaced by "call one of: ...",
so guidance and exposure cannot contradict. Chips are labels: `p1` becomes the person's name (`AgentTool.normalize`), another id is
refused. A question of three or more words in the form's script that shares no word with the form's labels is refused once ("write it
in German"); repeated unchanged it goes through. The trace line carries `tools_now=[...]`.

`remember_detail` is added in the last two open stages when the turn began with the user's answer to a question and the fill holds
something the user typed. `get_person_details` is exposed in no stage (fill_from_profile moves stored values). A role the user
answered by hand (a field holds what they typed, or was skipped) is settled, so its other fields are asked like any field and the
same role question is never repeated; a typed answer to a role question is suggested as `fill_field(..., source=user)` for the role's
name fields. Start over resets the engine session. The trace line of an error step ends with `reason="..."`, the first 60 characters
with quoted values replaced.

## 6c. Someone else, wording, language guard, card (agent-5)

- **ROLE_NAME_NEEDED** (tools ask_user, skip_field): the user chose "someone else" (or "Me" when no profile of the user exists) for a
  role. The reply result carries `note`: "The user will give the name of the <role> person. Ask for their name now (no chips)."
  `ask_user` refuses a question that repeats the role question (word overlap, or the same chips / the someone-else chip) with that hint;
  a new question passes. The third refusal in a turn adds the question to ask in the form's language (`FormWording.personNameQuestion`,
  string resources). The typed reply then fills the role's name fields (ROLE_TYPED). The chip "Me" in the form's language
  (`FormWording.me`) names the profile of the user when there is one.
- **Role name**: a section heading names the role only when it is at most three words and does not start in lower case; otherwise the
  role field's label alone ("Kontoinhaber/in").
- **Language guard**: a question is refused only when it is plainly English on a non-English form: at least half of its words are in
  `EnglishFunctionWords` (a small negative-signal list) and none is in the form's vocabulary (labels, headings, options and the stored
  OCR words, loaded once per run).
- **Card**: the first successful `fill_from_profile` / `fill_field` of a run posts the live card even if the model never calls
  `show_fill_card`.
- **Chips**: only the newest question / status line with chips is live (the UI); a Continue only acts on a stopped run. Typed text
  after a run stopped or failed (fill not DONE) is kept and answered with the paused line and Continue / Start over.

## 6d. ROLE_TYPED (agent-6)

After the user typed who has a role, only `fill_field` and `skip_field` are exposed, so the grammar forces a fill or a skip (the 2B
used to call `ask_user` until `StepLimit`). The suggestion names the exact call for the role's first open name field:
`fill_field(field_id=<that field>, value=<typed text>, source=user)`. A role with several name fields is filled one at a time: once
the first holds the user's text the role is settled and the others are asked like any field. A role with no open name field is not
ROLE_TYPED (its fields are asked like any field). A refused `fill_field` in this stage (a wrong field_id, a value the guard
rejects) repeats the exact correct call in its error; the second refusal in the turn skips that field, the result carries
`skipped=<label>` and the chat shows the status line `FormText.FIELD_LEFT_TO_USER`, so the run never dead-ends. A typed reply is used
once: after a successful `fill_from_profile`, `fill_field` or `skip_field` it no longer decides the stage of the next role.

## 7. Known risks (0.8B)

Small models are weak at multi-step tool use. The mitigations are in the loop (grammar, limits, hints, a compact context), but
the open questions are for the device pass: whether the model asks the right thing at the right time, picks the right role for
`fill_from_profile`, writes questions in the user's language, and stays inside the 4,096-token window. Qwen3.5-2B is the
opt-in "thorough" profile if the 0.8B proves too weak (set `ModelProfile.agent` and the download). FunctionGemma 270M is noted
as a later "action model".
