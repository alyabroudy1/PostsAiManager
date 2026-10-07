# Third-party data and attributions

This file records data (not libraries) the app ships under a licence that asks for attribution. Libraries are declared in
`gradle/libs.versions.toml`.

## Model licences (downloaded by the user, not shipped in the APK)

- **Qwen 3.5** (`BundledCatalog`): Apache License 2.0.
- **Gemma 4 E2B / E4B** (`BundledCatalog`, Google's QAT Q4_0 GGUF builds): Apache License 2.0, <https://ai.google.dev/gemma/docs/gemma_4_license>
  (the Hugging Face cards of `google/gemma-4-E2B-it-qat-q4_0-gguf` and `google/gemma-4-E4B-it-qat-q4_0-gguf` say `license: apache-2.0`).
  Shown as "Apache-2.0" in Settings > Models (`AiModelDescriptor.license`). Earlier Gemma generations (the Gemma 3 entries in
  `CuratedRepos`) remain under the Gemma Terms of Use.

- **Gemma 4 E2B / E4B, LiteRT-LM builds** (`BundledCatalog`, `litert-community/gemma-4-E2B-it-litert-lm` and `…E4B…`, pinned to the
  Gallery allowlist's commit): Apache License 2.0 (the Hugging Face cards say `license: apache-2.0`).

## Google AI Edge Gallery and LiteRT-LM (Apache License 2.0)

- **What:** the Gemma 4 chat engine. `:core:ai:litert` runs the LiteRT-LM runtime (`com.google.ai.edge.litertlm:litertlm-android`,
  <https://github.com/google-ai-edge/LiteRT-LM>, Copyright Google LLC) and contains code adapted from the Google AI Edge Gallery
  (<https://github.com/google-ai-edge/gallery>, v1.0.20, Copyright Google LLC):
  `LlmModelHelper.kt` (from `runtime/LlmModelHelper.kt`) and `LlmChatModelHelper.kt` (from `ui/llmchat/LlmChatModelHelper.kt`).
- **Licence:** Apache License 2.0, <https://www.apache.org/licenses/LICENSE-2.0>. The Google copyright and licence headers are kept in
  the adapted files, and each carries a "Modified by PostsAiManager" note saying what was removed (Firebase, the metrics tracker,
  benchmarking, speculative decoding, image and audio input, the Gallery's Model/Task types) and what was added (the GPU-to-CPU
  fallback, the instance handle).
- **Shown in the app:** Settings > About > "Open-source software and data" (`settings_litert_attribution`).

## Google libaddressinput address metadata (CC BY 4.0)

- **What:** `core/domain/src/main/resources/address/formats.json`, the per-country address formats (postcode pattern, the order of
  the address parts, the parts a country requires) used to verify an address read from a letter
  ([07-document-pipeline.md](07-document-pipeline.md), section 12.3). Countries: DE, GB, US, AE, SA, EG.
- **Source:** the Google libaddressinput address metadata, <https://github.com/google/libaddressinput>.
- **Licence:** Creative Commons Attribution 4.0 International (CC BY 4.0), <https://creativecommons.org/licenses/by/4.0/>.
- **Changes:** adapted. Only the postcode patterns, the order of the parts, the required parts and the countries' names (local and
  English, added to verify a country line) of the countries above are kept, in the app's own JSON shape.
- **Where the attribution lives:**
  - in the data file itself (`source` field of `formats.json`);
  - `AddressFormats.ATTRIBUTION` in `:core:domain` (pinned by `AddressFormatsTest`);
  - the string resource `settings_address_data_attribution` in `feature/settings/src/main/res/values/strings.xml`, the same text, for
    a Settings > About screen.
- **Shown in the app:** Settings > About > "Open-source data" opens a dialog with `settings_address_data_attribution`.

## Google AI Edge Gallery: Agent Skills (Apache License 2.0)

- **What:** code and skill text adapted from the Gallery, <https://github.com/google-ai-edge/gallery>, Copyright 2026 Google LLC
  (v1.0.20, `Android/src/app/src/main/java/com/google/ai/edge/gallery/`). The design is the Gallery's: a skill is a folder with a
  `SKILL.md`; the model calls `load_skill`, then `run_intent`; intents are executed by the app. See
  [agent-skills.md](agent-skills.md).
- **Files adapted (each keeps Google's licence header and a "Modified by PostsAiManager" line):**
  - `core/domain/.../skills/SkillParser.kt` and `Skill.kt`, from `skills/SkillManager.kt` (`convertSkillMdToProto`,
    `matchesSkillName`, `getSelectedSkillsNamesAndDescriptions`) and `skills/SkillExtensions.kt`;
  - `core/domain/.../skills/AgentActionParser.kt` and `AgentIntentSpecs.kt`, and `app/.../agent/AndroidAgentActionExecutor.kt`, from
    `intents/IntentHandler.kt` (`send_email`, `create_calendar_event`, `schedule_notification`, `get_current_date_and_time`);
  - `core/data/.../skills/AssetSkillCatalog.kt`, from `SkillManager.loadBuiltInSkills`;
  - `core/ai/litert/.../tools/LoadSkillTool.kt`, `RunIntentTool.kt` and `AgentToolCalls.kt`, from `tools/LoadSkillTool.kt` and
    `tools/RunIntentTool.kt` (the tools' names, descriptions and parameters are the Gallery's; `run_intent` proposes instead of
    running); `ToolContext.kt`, from the role of `agent/ToolExecutionContext` and its `actionChannel`; `LiteRtToolKit.kt`, from
    `tools/ToolsProvider.kt` (`getLiteRtToolProviders`) and the skills part of the system prompt of
    `customtasks/agentchat/AgentChatTaskModule.kt`; the `ConversationConfig(tools = ...)` and constrained-decoding setting in
    `LlmChatModelHelper.kt`, from `ui/llmchat/LlmChatModelHelper.kt` and `agent/DefaultAgentRuntimeExecutor.kt`;
  - `app/src/main/assets/skills/send-email/SKILL.md`, from `skills/built-in/send-email/SKILL.md`; the `create-calendar-event` and
    `schedule-reminder` skills follow the Gallery's SKILL.md style and intent contracts.
- **Changes:** pure Kotlin instead of protos, Hilt and Moshi; bundled skills only (no URLs, no remote lists, no JavaScript skills);
  the e-mail intent is `ACTION_SENDTO` with `mailto:` and every action waits for the user's confirmation; reminders use the app's own
  scheduler.
- **Where the attribution lives:** the headers of the files above; the string resource `settings_skills_attribution` in
  `feature/settings/src/main/res/values/` (en, de, ar).
- **Shown in the app:** Settings > About > "Open-source data" shows it under the address data text.

## Google AI Edge Gallery: chat pieces (Apache License 2.0)

Phase 3 of `plans/11-gemma4-litertlm.md`. Source: the Gallery v1.0.20, `Android/src/app/src/main/java/com/google/ai/edge/gallery/`
(Copyright Google LLC). Each adapted file keeps Google's licence header and carries a "Modified by PostsAiManager" note. Details of
how they work: [agent-skills.md](agent-skills.md), "Chat pieces".

| Gallery file | Our file | Adapted / left out |
|---|---|---|
| `ui/common/chat/MessageBodyThinking.kt` | `feature/chat/.../MessageBodyThinking.kt` | string resources, the app's right-to-left aware `MarkdownText`, "Thought for N s" title; starts collapsed; no long-press copy |
| `ui/common/chat/MessageBodyCollapsableProgressPanel.kt` | `feature/chat/.../MessageBodyCollapsableProgressPanel.kt` | shows the stored `ToolStep`s of a reply instead of a live `ChatMessageCollapsableProgressPanel`; no spinner, no logs viewer |
| `ui/common/chat/MessageBodyImage.kt` | `feature/chat/.../MessageBodyImage.kt` | files drawn by Coil instead of decoded Bitmaps; same single and grid layout |
| `ui/common/chat/MessageBodyWebview.kt` | `feature/chat/.../MessageBodyWebview.kt` | the offline sandbox instead of `GalleryWebView`; same "full screen" sheet |
| `ui/common/GalleryWebView.kt` (`BaseGalleryWebViewClient`) | `feature/chat/.../skills/SkillSandbox.kt` | **not network, not files**: every request answered from the bundled skill folder or blocked; no `allowFileAccess`, no DOM storage, no camera or microphone prompts, a Content-Security-Policy on every answer |
| `customtasks/agentchat/AgentChatScreen.kt` (`CallJsToolAction` branch, `ChatWebViewJavascriptInterface`) | `feature/chat/.../skills/WebViewJsSkillExecutor.kt` | an off-screen WebView per call instead of the screen's visible one; no secret |
| `tools/RunJsTool.kt`, `tools/ToolAction.kt` (`CallJsSkillResult`) | `core/ai/litert/.../tools/RunJsTool.kt`, `AgentToolCalls.runJs`; `core/domain/.../skills/JsSkills.kt` | Moshi becomes kotlinx.serialization; no skill secrets, no `image` result, no remote skill URLs; the script runs in the app process and the tool waits for its answer over AIDL |
| `skills/SkillExtensions.kt` (`getJsSkillUrl`, `getJsSkillWebviewUrl`) | `JsSkillPaths` in `JsSkills.kt` | skill-relative paths only; an absolute webview address is refused |
| `skills/built-in/calculate-hash/` (SKILL.md, scripts/index.html, scripts/index.js) | `app/src/main/assets/skills/calculate-hash/` | adopted unchanged (the simplest built-in skill that needs no network and no camera) |
| `ui/llmchat/LlmChatModelHelper.kt` (image part: `visionBackend`, `Content.ImageBytes` before the text) | `core/ai/litert/.../LlmChatModelHelper.kt` | the vision encoder is started on demand; audio still not taken |
| `common/Utils.kt` (`decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize`) and the picker flow of `ui/common/chat/MessageInputText.kt` | `core/data/.../util/FileChatImageStore.kt`, `feature/chat/.../ChatInputBar.kt` | pictures are decoded to at most 1024 px, turned upright, kept as PNG in the app's private `chat-attachments` folder; the system photo picker |
| The Gallery's reset session (`ChatViewModel` clear history + `LlmChatViewModel.resetSession`) | `StartNewChatUseCase`, `ChatViewModel.newChat` | the document's conversation is deleted (not archived) and the model's conversation dropped |

Not taken: their whole `ChatView`/`ChatViewModel`/model manager, MCP, Firebase, remote skill URLs, benchmark screens, audio.
