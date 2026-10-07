# Third-party data and attributions

This file records data (not libraries) the app ships under a licence that asks for attribution. Libraries are declared in
`gradle/libs.versions.toml`.

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
  - `app/src/main/assets/skills/send-email/SKILL.md`, from `skills/built-in/send-email/SKILL.md`; the `create-calendar-event` and
    `schedule-reminder` skills follow the Gallery's SKILL.md style and intent contracts.
- **Changes:** pure Kotlin instead of protos, Hilt and Moshi; bundled skills only (no URLs, no remote lists, no JavaScript skills);
  the e-mail intent is `ACTION_SENDTO` with `mailto:` and every action waits for the user's confirmation; reminders use the app's own
  scheduler.
- **Where the attribution lives:** the headers of the files above; the string resource `settings_skills_attribution` in
  `feature/settings/src/main/res/values/` (en, de, ar).
- **Shown in the app:** Settings > About > "Open-source data" shows it under the address data text.
