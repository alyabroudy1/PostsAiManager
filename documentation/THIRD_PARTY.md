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
