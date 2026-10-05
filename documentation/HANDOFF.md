# HANDOFF: continue PostsAiManager on another computer

Written 2026-10-02. This is the single entry point for a new machine. On the new machine, ask Claude Code to read
`documentation/HANDOFF.md` and `documentation/planning/AGENT-RULES.md` first: Claude Code's memory is per machine, so the
working agreements are written down in section (e) below.

Placeholders: `<repo>` is the clone, `<work-folder>` is the folder next to it that holds plans, data and worktrees
(on the old machine it was named `PostsAiManager-work`).

## (a) Branch map and state

```
main  <-  feat/on-device-ai (PR #1, open)  <-  feat/extraction-v2  <-  feat/form-assist (HEAD, contains everything)
```

| Branch | Contains | DB version | On GitHub? |
|---|---|---|---|
| `main` | the old baseline | - | yes |
| `feat/on-device-ai` | PR #1: llama.cpp chat and extraction on device, embeddings and search, model management, chat with cited sources and the in-place page preview | 13 | yes (`origin/feat/on-device-ai`) |
| `feat/extraction-v2` | extraction v2 (document families and topics, layout conventions, structured address, title and summary, the review state and the Extracted tab, ZONES_SCORING with Qwen3.5-0.8B) plus the submodule-entry fix | 15 | NO, about 145 commits ahead of PR #1 |
| `feat/form-assist` | everything above plus: profiles with saved details (relationship, birth date, sensitive flag, `profile_facts`), form understanding, the on-device tool-calling agent that fills forms in the chat (see `10-on-device-agent.md`), and the release prep (signing config, R8, form filling hidden in release, version 1.0.0) | 17 | NO, 61 commits ahead of extraction-v2 |

Other local branches (`arch/*`, `fa/*`, `p1/*`, `rel/*`) were all merged into `feat/form-assist` or are archived
experiments (p1/vision was dropped on purpose: no vision, OCR stays). They are not needed on the new machine. Nothing has
been pushed by Claude: pushing is always the user's step (the project denies `git push` to agents).

Check the state yourself (in the old repo): `git branch -vv`, `git log origin/feat/extraction-v2..feat/extraction-v2`.

### Push commands (run on the OLD machine, once)

```
git push -u origin feat/extraction-v2 feat/form-assist
```

PR plan, recommended: ONE pull request `feat/form-assist` into `feat/on-device-ai` (the branches are a linear chain, so
it carries extraction-v2 too). Body: `documentation/planning/PR_BODY-extraction-v2.md` as the base, plus a paragraph for
the profiles, the form assist and the release prep. Alternative: two stacked PRs (extraction-v2 into on-device-ai, then
form-assist into extraction-v2), only if you want smaller reviews. Merge PR #1 into `main` first or last, as you prefer;
the chain stays valid either way.

Before a PR, check the submodule entry is intact: `git ls-tree HEAD core/ai/local/src/main/cpp/llama.cpp` must show
mode `160000`. (A symlink once replaced the submodule in a commit; it was repaired.)

## (b) Machine setup from scratch

1. Clone: `git clone --recurse-submodules https://github.com/alyabroudy1/PostsAiManager.git`, then
   `git checkout feat/form-assist` (after you pushed it).
2. Submodule: `git submodule update --init core/ai/local/src/main/cpp/llama.cpp` (pinned to tag `b10299` by the
   superproject commit; `.gitmodules` sets `shallow = true`, url `https://github.com/ggml-org/llama.cpp`). Without it
   `CMakeLists.txt` fails with "llama.cpp submodule is missing" and `:core:ai:local` does not build.
3. Toolchain (from the Gradle files):
   - JDK 17 (all modules use `JavaVersion.VERSION_17`; CI uses JDK 17).
   - Gradle 8.9 (wrapper, downloads itself), AGP 8.7.3, Kotlin 2.1.0.
   - Android SDK platform 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`). AGP 8.7.3 officially supports up to 35;
     `android.suppressUnsupportedCompileSdk=36` in `gradle.properties` silences its warning.
   - NDK `27.0.12077973` (`core/ai/local/build.gradle.kts`) and CMake `3.22.1`; install both in the SDK Manager.
   - Release ABI is arm64-v8a only; debug also builds x86_64.
4. `local.properties` in `<repo>` (git-ignored): `sdk.dir=<path to your Android SDK>`. Android Studio writes it.
5. First build and gates:
   `./gradlew testDebugUnitTest :architecture-test:test :app:assembleDebug`
   (the first run compiles llama.cpp natively and takes several minutes; add `-Dorg.gradle.workers.max=2` on a small
   machine). `:architecture-test` is the Konsist guard (features see only domain/model/designsystem/common).
6. Release signing (nothing secret is in the repo). `app/build.gradle.kts` reads four Gradle properties (or environment
   variables of the same name): `PAM_UPLOAD_STORE_FILE`, `PAM_UPLOAD_STORE_PASSWORD`, `PAM_UPLOAD_KEY_ALIAS`,
   `PAM_UPLOAD_KEY_PASSWORD`. Put them in `~/.gradle/gradle.properties` on the new machine. If any is missing, release
   stays unsigned. The upload keystore is NOT in git and cannot be recreated: copy the existing `.jks` from the old
   machine over a secure channel (encrypted disk or a password manager's file store; never email, chat or git) together
   with its passwords. If it was never created, create it once with
   `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`, back it up
   twice, and keep it out of the repo. Build with `./gradlew :app:bundleRelease`.
7. Device access: `scripts/dev-adb.sh` is a guarded adb wrapper (it finds adb via `PAM_ADB`, `ANDROID_HOME` or the usual SDK
   paths, and enforces the safety rules of `documentation/05-test-harness.md`). Use `./scripts/dev-adb.sh <adb args>`; raw
   `adb` is denied in the project's Claude settings. The phone needs USB debugging on; the app id of the debug build is
   `com.postsaimanager.debug`.

## (c) Models and test data that are NOT in git

**Chat models (GGUF).** They download inside the app from Hugging Face, pinned by revision and SHA-256 in
`core/ai/catalog/src/main/kotlin/com/postsaimanager/core/ai/catalog/BundledCatalog.kt` (read the URLs and hashes there):

| Model | File | Size |
|---|---|---|
| Qwen3.5 0.8B (default reader, ZONES_SCORING, the form agent) | `Qwen3.5-0.8B-Q4_K_M.gguf` | 532,517,120 bytes (about 0.5 GB) |
| Qwen3.5 2B | `Qwen3.5-2B-Q4_K_M.gguf` | 1,280,835,840 bytes (about 1.3 GB) |
| Qwen3.5 4B, Gemma 4 E2B, Gemma 4 E4B | in the same catalog | 2.7 GB, 3.3 GB, 5.2 GB |

**Search (embedding) model.** `distiluse-base-multilingual-cased-v2` (ONNX, from the `Xenova/...` Hugging Face repo at a
pinned revision) plus its vocabulary: model about 258 MB, vocab about 1 MB. Pinned in
`EmbeddingModelRelease` (core/ai/embed). It installs from the Models screen.

**Offline copies for device benchmark runners** (not needed to run the app): in `<work-folder>/data/models7/` there are
`Qwen3.5-0.8B-Q4_K_M.gguf`, `q2b.gguf` (the 2B) and the mmproj files (vision experiments, parked). To avoid re-downloading
them, copy the folder; otherwise the app downloads what it needs.

**Work folder (private, never committed).** `<work-folder>/` holds `plans/` (original design docs; the needed ones are
now copied into `documentation/planning/`), `data/`, `artifacts/` (smoke-test screenshots and results) and `worktrees/`.
Recommendation: copy it via a private drive or an external disk, EXCLUDING `data/testdocs2/web` (public sample letters
with an unclear licence: local only, never committed). The `worktrees/` folder need not be copied: recreate worktrees
from the clone with the recipe in `planning/AGENT-RULES.md`. The phone itself holds real, sensitive documents: nothing
from it belongs in the repo, in docs or in screenshots.

Data in the work folder:
- `data/testdocs/`: invented letters (page images + `manifest.json` with ground truth): invoice, English-ambiguous,
  Arabic RTL, degraded, receipt-noise, tax-long.
- `data/testdocs2/N1..N10-*`: invented German letters (dunning, vehicle, school, family, utilities, bank info, and so
  on) with a manifest. Only the `web/` subfolder is public material: exclude it.
- `data/forms-smoke/`: the invented 2-page swimming-course form for the form-assist smoke test: `page-1.html`,
  `page-2.html` (the sources) and the rendered `page-1.png`, `page-2.png`. To recreate the images, render each HTML file
  with headless Chrome at page size, for example
  `chrome --headless --screenshot=page-1.png --window-size=1240,1754 file://<path>/page-1.html`
  (any Chrome or Chromium binary; adjust the window size to the HTML's page box), then push the PNGs to the phone's
  gallery and scan them in the app.
- `data/fixtures/`: real phone-OCR fixtures (`raw/`, `web/`), local only.

**Benchmark fixtures that ARE in git:** `core/domain/src/test/resources/benchmark/` (manifests, fixtures, recordings,
`baseline.json`, the synthetic set) and `core/domain/src/test/resources/forms/` (form fixtures). The JVM tests and
`BenchmarkGateTest` run from these alone, so the unit tests pass on a fresh clone with no model and no work folder.

## (d) Project status and what is next

**Release v1.0.0** (details: `planning/RELEASE-CHECKLIST.md`). Version is 1.0.0 (versionCode 1), release is arm64-only,
R8 on. Open items:
- App bundle: DONE. `./gradlew :app:bundleRelease` builds `app/build/outputs/bundle/release/app-release.aab` (33.1 MB,
  unsigned until the upload key exists). arm64-v8a only, native libs stored uncompressed (`useLegacyPackaging = false`),
  every `.so` in it has 16 KB segment alignment (checked with `llvm-readelf`; `core/ai/local` now passes
  `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` because NDK 27 does not by default), and language splits are off
  (`bundle { language { enableSplit = false } }`) so the in-app locales keep working.
- Target SDK: DONE, raised 35 -> 36. Google Play requires API 36 for new apps and updates from 2026-08-31
  (https://support.google.com/googleplay/android-developer/answer/11926878). No manifest changes were needed (no locked
  orientation, edge-to-edge already on, the foreground service type is declared, no `onBackPressed`). Not device-verified on Android 16.
- First-run model setup: DONE in code, NOT device-verified. A fresh install (no chat model, setup not skipped) opens
  `feature:setup`: what the app does, the privacy line, one "Download the AI model" action for Qwen3.5-0.8B Q4_K_M
  (`BundledCatalog.firstRunModel`) plus the search model, a progress bar each, an ask before mobile data, cancel, retry
  and an error state. "Skip for now" is stored (`UserPreferences.modelSetupSkipped`) and Home then shows an
  "AI model not installed · Install" banner until a model exists. Wiring: ports `ModelSetupGateway` and `ConnectionMeter`
  and `ObserveSetupNeedUseCase` in `core:domain`, the gateway adapter `CatalogModelSetupGateway` in `app` over the existing
  download machinery, `StartupViewModel` picks the start route once per launch. Device pass still to do: fresh install,
  Wi-Fi download, mobile-data question, cancel and retry, skip and banner, RTL (ar).
- Last code round before 1.0.0 (2026-10-05, JVM-tested, NOT device-verified): unused `firebase-crashlytics`, ML Kit
  `language-id`/`entity-extraction` and the empty `:core:ai:online` module (and its Konsist test) removed;
  "Report this answer" flag on every chat answer and on the AI summary card (`ReportAnswer.kt` in `:core:designsystem`: an
  `ACTION_SENDTO` mailto draft to alyabroudy1@gmail.com, answer text only if the user ticks it, never sent automatically;
  en/de/ar); POST_NOTIFICATIONS requested on the setup screen right before the download (`NotificationPermission.kt`,
  a denial does not block it); the 0.8B is described as the recommended default and the 2B as "better answers, slower"
  (not "used for form filling", the feature is hidden in release); GitHub Pages sources in `docs/` (see
  `store/README.md`). `HuggingFaceCatalogSource` stays but is unreachable. Gate: 2034 JVM tests, 0 failures, 1 skipped;
  `:app:bundleRelease` signed (`jarsigner -verify`: verified), 29.4 MB.
- The upload signing key exists in the user's `~/.gradle/gradle.properties`; enrol in Play App Signing in the Console.
- A release-like smoke was done on the device with a debug-signed build of the release variant (no crash found). It
  must be repeated with the final signed AAB before upload, including: the notification permission prompt on setup,
  the Report answer dialog and e-mail draft (chat and summary card), the Models screen wording.
- `allowBackup` and the locale list stay as the user decided (see the checklist's blockers 3 and "i"); do not change them
  without asking.
- The privacy policy (EN, DE) is written, contact e-mail filled in; it is in `documentation/store/` and copied to `docs/privacy/`
  for GitHub Pages. Still to do by the user: fill the imprint/effective-date PLACEHOLDERs, enable Pages (Settings > Pages >
  main, /docs, after merging; URL `https://alyabroudy1.github.io/PostsAiManager/privacy/`), then the Play Console forms.
- Re-check the five Hugging Face model URLs and hashes right before release.

**Form filling** (design: `planning/FORM-ASSIST.md`, code and tools: `10-on-device-agent.md`):
- Hidden in release by `FormFillingFlag` (ON in debug, OFF in release). With the flag off no card, menu item or chat
  route exists. The form tables stay in the schema; do not remove them.
- State: the agent loop, tools (read_form, fill_from_profile, fill_field, ask_user, remember_detail, show_fill_card and
  others), the grammar-constrained calls, the live card and the "Remember for <person>?" flow work in JVM tests. The
  last device pass was "agent-5" on Qwen3.5-0.8B with an invented form and invented profiles (someone-else role names,
  question wording, the language guard, the card appearing after the first fill). Screenshots of those runs are in
  `<work-folder>/artifacts/smoke-agent5` and `smoke-agent6`. A second device pass for the final round was in progress
  when this was written; check the newest folder in `artifacts/`.
- Latest device result (agent-5): the flow reached who is it for, then `fill_from_profile` (4 of 14 fields), then the
  payer role answered "someone else", then the name question without chips (that was fixed). Also fixed and seen working:
  role wording uses the field label only, German questions pass the language guard, the card appears after the first
  fill, stale chips are disabled.
- ROLE_TYPED bug (agent-5, 2B: after the user typed the payer's name the model kept calling `ask_user` until
  `StepLimit`): FIXED in code (commit d88a7a2, agent-6): that stage exposes only `fill_field` and `skip_field`, the
  suggestion names the exact call, a refused fill repeats it and the second refusal skips the field with a status line
  (`documentation/10-on-device-agent.md` 6d). VERIFIED on the device (agent-6, 2B): after the typed payer name the
  next step was `fill_field` with `tools_now=[fill_field|skip_field]`, the name was filled (5/14) and the run moved on.
- Latest device run (agent-6, 2B, invented swim form; screenshots in `artifacts/smoke-agent7/`) reached 6 of 14,
  then hit `StepLimit`. Open problems, most important first, which are the next round:
  1. **Wrong field meanings from the form understanding** (this is the reading step, not the agent):
     - `fill_from_profile` put Vorname="Test Kind", Nachname="Kind" and Anschrift="12.03.2019" (the birth date);
     - the guardian's typed name went into "Telefon (Notfall)".
     The next step is to capture the real OCR of the test form via the `FormOcr` trace (debug allowlist
     `files/debug-trace-docs.txt`, only for the invented test document) and fix the key/role classification against
     it on the JVM.
  2. **The user-words check rejects verbatim answers:** `fill_field` got "is not what the user wrote" for the exact typed
     "Test Kind" / "Test". That's a bug in `FieldValueGuard`'s user-reply source. The model then re-asked name/birth
     date until `StepLimit`.
  3. **First question:** it was "Welches Formular soll ich ausfüllen?" with no person chips. It should always be "who
     is it for" with the managed people as chips.
  4. **Slowness:** model calls went from 5–8 s to 60–73 s per step mid-run (2B). The cause is unknown (memory
     pressure, or context growth with no rebuild?). Check `ctx_tokens`/`rebuilt` in the FormAgent trace.
  5. An old failed transcript auto-resumed and its first attempt failed. Never auto-resume a FAILED run; offer Start
     over.
  "Remember for <person>" and Saved details are still not verified on the device (the run never reached them).
- Test phone state: the chat model is Qwen3.5-0.8B again; the 2B is installed as the form model.
- Honest risk: a 0.8B model is weak at multi-step tool use. Next steps: finish the device passes, decide whether the 2B
  "thorough" profile is needed, then turn the flag on for a later release (1.1) rather than 1.0.

**Parked quality items:** extraction quality work is parked until after the release (user decision). The ranked ideas
(joint assignment, calibration, learned ranker, margin-gated re-scoring, per-sender memory, LoRA, the 2B reader,
GLiNER2, NuExtract, a VLM fallback) are in `08-extraction-optimization-roadmap.md`. The current best is ZONES_SCORING with
Qwen3.5-0.8B (68% fields, 87% roles, 0% hallucination, about 64 s per letter). Do not start benchmark or timing studies
unless asked.

**Arabic OCR (P7):** ML Kit has no Arabic script, so Arabic documents need their own OCR engine. The research and plan
are in `planning/RESEARCH-ARABIC-OCR.md`; nothing is built yet. v1 scope is German, English and Arabic documents; other
countries come later by data only.

**Phase 2, silent linking** (`planning/PHASE2.md`): people and organisations, who a letter is for, a resolve-parties
step that links silently when certain and asks at most one question per letter in a Review inbox. Partly overlaps with
the profiles work already on `feat/form-assist` (profile relationship, birth date, sensitive flag); read the file
before starting and reconcile with the current schema (v17).

## (e) How to work on this project (working agreements)

Condensed from the project owner's standing preferences. Follow them unless the user changes them.

**Roles and delegation**
- The main session plans, checks diffs and decides; implementation, builds and device work are delegated to Sonnet
  subagents, in phases. Only run commands yourself when the user explicitly asks and an agent cannot get permission.
- Parallel work uses git worktrees under `<work-folder>/worktrees` (never under `/tmp`: a temp folder was wiped once).
- No third-party "Fable" or third-eye reviews any more (the user wants speed). The planner checks diffs itself; the
  agents' tests are the gate.

**Speed rule (build once, test once)**
- Every brief says: do all code fixes first and run only targeted tests while coding; then ONE full build/gate; then ONE
  device pass at the end. No fix, reinstall, retest loops; few screenshots; poll device logs at most every ~15 s; no idle
  sleeps.
- Skip benchmarking and measuring studies; only production-safety checks (such as the migration test) are welcome.

**Product and architecture directives**
- AI-driven, nothing static: the on-device model decides everything that is meaning or content (document type, what a
  value means, party roles, tasks, titles, summaries, questions) in any language. Code only finds value shapes, checks
  checksums and date validity, verifies that AI output exists in the source, stores data and runs the UI. Labels, zones
  and heuristics are hints shown to the model, never the decision. A failed check means "needs review", never a silent
  rule override. No keyword lists, no language-specific regexes deciding meaning, no country or language names in code
  paths (data registries only). Say plainly where something cannot be AI yet.
- Clean architecture first: small single-purpose domain use cases behind ports (Read, Understand, Resolve, Plan, Index),
  no god classes, one owner per concept (one writer per table), additive Room migrations, retire old paths instead of
  duplicating. Features see only core:domain/model/designsystem/common (Konsist enforces it); models are pure Kotlin;
  Android and IO live in core:data or core:ai. Tests for every new pure class; user-facing text in string resources.
- v1 language scope is German, English and Arabic (RTL). Layout conventions, address formats and strings are data: a new
  country must need no code change.
- Extraction quality work is parked until after release; point to `08-extraction-optimization-roadmap.md` instead of
  starting new experiments.

**Git and worktrees**
- The llama.cpp submodule is not checked out in a new worktree. Recipe: create the worktree with
  `git -c submodule.core/ai/local/src/main/cpp/llama.cpp.ignore=all worktree add -b <branch> <work-folder>/worktrees/<name> <base>`,
  `rmdir` the empty `core/ai/local/src/main/cpp/llama.cpp`, symlink it to the main repo's checkout, copy
  `local.properties`. Run EVERY git command in such a worktree with the same `-c ...ignore=all` flag. Remove the symlink
  before `git worktree remove --force`. A rebase or stash can turn the symlink back into an empty directory: re-check.
- NEVER `git add -A`, `git add .` or `git commit -a`. Stage explicit paths only, never the llama.cpp path, and before every
  commit run `git ... diff --cached --summary`: abort on any `mode change` or llama.cpp entry. (A symlink was once
  committed over the submodule.) Before a PR: `git ls-tree HEAD core/ai/local/src/main/cpp/llama.cpp` must show 160000.
- Never push and never merge into other branches from an agent. The user pushes. Commit messages are conventional and end
  with the Claude co-author line.
- Room downgrades crash the app: never install a build from a branch whose `PamDatabase` version is lower than the one on
  the phone. Merge the base branch into feature branches before any device install.

**Editing and permissions**
- Agents edit files only with Edit/Write: no python, heredoc or sed edits. Device commands go one per Bash call through
  `./scripts/dev-adb.sh` (no chaining); background tasks are stopped with TaskStop, not kill.
- The project's `.claude/settings.json` denies `sed -i`, `rm -rf`, `git push`, raw `adb`, `* uninstall *` and similar.
  If a command is denied, do NOT achieve the same effect another way (no python, no other tool): stop and report it.
  Every agent brief must say so. Using a sanctioned tool after a denial (Edit/Write, dev-adb.sh, TaskStop) is fine.

**Device safety and privacy (the phone is shared with real apps and real data)**
- One agent uses the phone at a time; serialise device work (a flag file can signal the hand-off). The USB link drops
  now and then: append results to a file after each case.
- Before EVERY tap, key event or text input, check the focused package is the app (`com.postsaimanager.debug`) with
  `dumpsys window | grep mCurrentFocus`. If not, press HOME, relaunch the app, re-check. Never type text unless the app
  has focus. (A tap once landed inside another messaging app.)
- NEVER `uiautomator dump`, never `logcat` without a tag filter, never read anything from another app. Find buttons from
  screenshots of the app only.
- NEVER modify the app's private files or databases on the device (no `run-as` writes, no sed or echo into app files).
  If a state change cannot be made through the UI, stop and report.
- Screenshots go only in the artifacts folder named in the brief. Delete every screenshot that shows anything but invented
  test data (home list, profiles list, photo picker, other apps).
- The phone holds real, sensitive documents. Never put real personal data in the repo, docs, commits or reports; test
  only with invented letters, forms and profiles. Web sample letters (`data/testdocs2/web`) are never committed. No
  secrets or keys in the repo.

**Agent rules file**: `documentation/planning/AGENT-RULES.md` is the checklist every implementation brief must include
(it was written for Opus/Sonnet agents; some of its steps, like the worktree recipe, refer to `<repo>` and
`<work-folder>`, which you set per machine).
