# Release checklist: first release, form filling hidden

Written 2026-10-02 from the code and config on branch feat/form-assist (read-only checks plus one `assembleRelease` run). Nothing here was verified on a device.

## Update 2026-10-05 (code only, not device-verified)

- **AAB: done.** `./gradlew :app:bundleRelease` builds `app/build/outputs/bundle/release/app-release.aab` (33.1 MB, unsigned
  until the upload key is set). arm64-v8a only; native libs uncompressed (`useLegacyPackaging = false`); all 13 `.so` files in
  the bundle are 16 KB aligned (`core/ai/local` passes `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, NDK 27 needs it);
  language splits off so the in-app en/de/ar locales work.
- **Target SDK: raised 35 -> 36** (compileSdk too). Play requires API 36 for new apps and updates from 2026-08-31:
  https://support.google.com/googleplay/android-developer/answer/11926878. No manifest change was needed. AGP 8.7.3 compiles
  against 36 with `android.suppressUnsupportedCompileSdk=36`; plan an AGP upgrade later. Smoke the release build on an Android 16 device.
- **First-run model setup: done (blocker "no model on a fresh install" in section a).** New `feature:setup` screen, skip banner on
  Home, start-route logic. Strings in en/de/ar. Not device-verified.

## BLOCKERS (fix before a Play upload)

1. **No release signing config.** `app/build.gradle.kts` has no `signingConfigs`; `assembleRelease` produces `app-release-unsigned.apk` only. Missing: an upload keystore (not created, never commit it), a `signingConfigs.release` reading path/passwords from `local.properties` or env, and Play App Signing enrolment. For an AAB use `:app:bundleRelease` (not run).
2. **The release build has never been run.** R8 compiles (`assembleRelease` passes, 54.7 MB unsigned APK) but a minified build was not installed or exercised. Room, Hilt, kotlinx-serialization, the llama JNI bridge and ONNX Runtime can fail only at runtime under R8. Do one device smoke pass of a release (or release-like) build: scan a document, read it, chat, download the search model, rotate the app lock.
3. **`android:allowBackup="true"`** (AndroidManifest.xml) with no `dataExtractionRules`/`fullBackupContent`. Letters, OCR text and the Room DB would be copied to the user's Google Drive backup. That contradicts the on-device-only statement for the Play listing. Decide: set `allowBackup=false`, or exclude the database, `files/documents/` and model files. Not changed here (product decision).
4. **Play listing/privacy policy not written** (see e): a hosted privacy policy URL is mandatory for an app that handles scanned documents and uses the camera.
5. **Version is still `0.1.0` / versionCode 1** (see c): fine for a first upload, but decide the public name now.

Not blockers but should be fixed soon: the debug-only `ExtractTiming` logs are ungated (f), MigrationTests miss 2->3 and 3->4 and a full-chain test (g), and the UI is mostly English (i).

## Release signing (config in place, no keys in the repo)

`app/build.gradle.kts` defines `signingConfigs.release` from four values, read from Gradle properties (put them in `~/.gradle/gradle.properties`, never in the repo) or from environment variables of the same name: `PAM_UPLOAD_STORE_FILE` (path to the upload keystore), `PAM_UPLOAD_STORE_PASSWORD`, `PAM_UPLOAD_KEY_ALIAS`, `PAM_UPLOAD_KEY_PASSWORD`. If any is missing, release stays unsigned (`app-release-unsigned.apk`). Create the keystore yourself with `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`, keep it off the repo, then `./gradlew :app:bundleRelease`. Version is 1.0.0 (versionCode 1). R8 strips `Log.v/d/i` in release (`app/proguard-rules.pro`); `w`/`e` stay.

## a) Model distribution (can a first user get the chat model?)

**Yes, downloads work in a release build. The old on-screen text was wrong, and it has been corrected in this change.**

- `core/ai/catalog/BundledCatalog.kt` ships five chat models (Qwen3.5 0.8B/2B/4B, Gemma 4 E2B/E4B) with a pinned Hugging Face URL at an immutable revision and a SHA-256 for each. Its KDoc records that these hashes were read from the Hub API and every URL checked.
- Download gating is `AiModelDescriptor.isInstallable` (needs URL and hash) and `ModelDownloadManager.enqueue` (returns false without both). All five entries pass, so the Install button works. `ModelDownloadWorker` verifies the hash before moving the file into place.
- `ModelCatalogRepository` falls back to `BundledCatalog` whenever the remote list is empty. It always is today: `setRemoteDescriptors` has no caller, and `TrustedKeys.keys` in `core/config/ManifestVerifier.kt` is `emptyMap()`, so `ManifestVerifier` rejects every manifest as `UnknownKey`. `HuggingFaceCatalogSource` and `CuratedRepos` also have no caller.
- The embedding model (search by meaning) is pinned separately in `EmbeddingModelRelease` and installs through its own worker.
- What a first user sees: the Models screen with an "Offline catalog" card (text now says the models are built in, download from Hugging Face and are checked against a fixed fingerprint), then picks a model that fits the device and downloads it (UNMETERED by default, metered allowed by a prompt; foreground dataSync service). Before that, chat and reading have no model. Make sure first-run onboarding points to Models.
- What is missing for a signed/remote catalog: (1) generate an Ed25519/ECDSA signing key pair (algorithm per `ManifestVerifier.KEY_ALGORITHM`), keep the private key off the repo; (2) put the base64 X.509 public key in `TrustedKeys.keys` under a key id; (3) host a signed manifest JSON at a stable URL; (4) add the fetch call that verifies it and calls `ModelCatalogRepository.setRemoteDescriptors` (does not exist yet); (5) a manifest sequence/rollback test on device.
- **Minimal path for the first release: ship the bundled catalog as is.** Needs no key. Cost: adding or revoking a model needs an app update. Re-check that the five Hugging Face URLs and hashes still return 200 immediately before release (they are third-party hosted; a deleted repo means no model for new users). Consider mirroring them on your own storage if you want a guarantee.
- The install-failure snackbar in `ModelsViewModel.install` still says "Model downloads require a signed catalog". It is only reachable for a descriptor without URL/hash (none today); reword before adding such entries.

## b) Release build type

- `release { isMinifyEnabled = true; isShrinkResources = true }` with `proguard-android-optimize.txt` and `app/proguard-rules.pro`.
- Rules present: Material, kotlinx-serialization (Companion/serializer keeps for `com.postsaimanager.**`), Room database class, Ktor (broad `-keep class io.ktor.**`), ML Kit, Hilt dontwarn. No rules for: llama JNI (`LlamaNative`), ONNX Runtime, WorkManager workers. The default android rules keep `native` methods. R8 output (`mapping.txt`) shows `LlamaNative` and `ai.onnxruntime.*` not renamed, and `FindClass`/`GetMethodID` do not appear in `core/ai/local/src/main/cpp/*.cpp`, so JNI looks safe on paper. ORT and Room/Hilt/WorkManager ship consumer rules in their AARs. Still unverified at runtime (blocker 2).
- `assembleRelease -Dorg.gradle.workers.max=4`: **BUILD SUCCESSFUL in 1m51s**, output `app/build/outputs/apk/release/app-release-unsigned.apk` (54.7 MB). Lint-vital ran and passed. No signing config, so it is unsigned.
- ABI: release is arm64-v8a only (debug adds x86_64). Say so in the listing (no 32-bit and no x86 devices).

## c) Version

`versionCode = 1`, `versionName = "0.1.0"` in `app/build.gradle.kts`. OK for a first upload; bump per upload. The Settings About "Version" line was hard-coded "1.0.0 (Phase 1)"; it now reads `versionName` from the package.

## d) libaddressinput CC-BY attribution

Done in this change: Settings > About > "Open-source data" (title, subtitle, dialog) shows `settings_address_data_attribution` (same text as `AddressFormats.ATTRIBUTION`). `documentation/THIRD_PARTY.md` updated. English only (the string has no de/ar). Libraries (ONNX Runtime, llama.cpp MIT, ML Kit, etc.) have no in-app licence screen; Apache/MIT libraries do not require one in-app, but llama.cpp (MIT) and the model licences (Apache-2.0, Gemma terms) need a notice: add to the same dialog or the store listing. Gemma has its own terms of use; check them before listing Gemma models.

## e) Privacy statement and Play data-safety notes (draft facts)

- All documents, OCR text, extracted data, chats, profiles and embeddings stay on the device (Room DB and app files). Inference is local (llama.cpp in a separate `:inference` process; ONNX Runtime for embeddings). No analytics, crash reporting or ad SDK found in the dependencies. Data safety form: "no data collected", "no data shared".
- Network use: only model downloads (chat models from huggingface.co; the embedding model from huggingface.co at a pinned revision). The user's documents are never uploaded. Hugging Face sees the device IP and the model file requested.
- Permissions: CAMERA (scan documents; `uses-feature camera required=false`), INTERNET (model downloads), FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC (long model downloads in a foreground notification), POST_NOTIFICATIONS (deadline reminders, download progress; optional). Biometric (app lock) uses the BiometricPrompt library; no extra permission declared in the manifest by us (the library merges its own).
- Security features worth stating: optional app lock with FLAG_SECURE (blocks screenshots and the recents preview), `FileProvider` not exported, only `MainActivity` exported.
- Fix before claiming "on-device only": `allowBackup=true` (blocker 3).
- Form filling hidden in release: do not mention it in the listing.

## f) Debug-only traces

- Gated by `BuildConfig.DEBUG` (`FormFillTraceModule`): `FormFill`, `FormAgent`, `FormOcr` (the OCR trace also needs the `files/debug-trace-docs.txt` allowlist). Release binds the `NONE` objects.
- Gated by `FLAG_DEBUGGABLE`: the per-field reading trace and the content trace in `DocumentProcessingPipeline` (lines ~791, 802).
- **Not gated:** `ExtractTiming` (`TIMING_TAG`) `Log.i` lines and `TAG` info lines in `DocumentProcessingPipeline.kt` (document ids, timings, profile/strategy header, error user messages), plus `RemoteAiEngine` info logs (token counts, speeds). No document text or field values were found in them, but ids and timings reach release logcat. Low risk; cleanest fix is an R8 `-assumenosideeffects class android.util.Log { v; d; i; }` rule in `proguard-rules.pro` (not added: it also removes logs people may want from a field test). `DocumentProcessingPipeline.kt` is owned by the speed workstream, so it was not edited.
- `LlamaNative.crashForTesting()` is a native method that crashes the process on demand; confirm it has no release caller (it is only for the process-isolation test).

## g) Migrations v1 to v17

- `PamMigrations` defines `MIGRATION_1_2` through `MIGRATION_16_17`; `PamDatabase` is `version = 17`; schemas 1.json to 17.json are exported in `core/data/schemas`. `fallbackToDestructiveMigration` was removed.
- `core/data/src/androidTest/.../MigrationTest.kt` covers 1->2 (three tests), 4->5, 5->6, 6->7, 7->8, 8->9, 9->10, 10->11, 11->12, 12->13, 13->14, 14->15, 15->16 and 16->17. Reported passing on the device by the owner: 14->15, 15->16, 16->17 (not re-run here, no device). The others were not re-run in this pass.
- **Gaps:** no dedicated test for 2->3 and 3->4; no single test that opens a v1 database and migrates all the way to v17 with `MigrationTestHelper` plus `runMigrationsAndValidate` over the full chain. A first-release user has no old database, so the chain only matters for developers and testers' installs; the v1..v17 chain test is still recommended before the next schema change.
- Form tables (`form_fills`, `form_fields`, `profile_facts`) stay in the schema with the feature hidden. Do not remove them: the form-assist branch continues from this schema.

## h) Crash-free basics

- StrictMode: only in `PostsAiManagerApp.enableStrictModeInDebug()`, which returns immediately when `!BuildConfig.DEBUG`. Good.
- `:inference` runs in a separate process so a native crash does not take the UI down; `isMainProcess` guards main-only startup (unit tested).
- WorkManager foreground service type is declared in the manifest to avoid the known `foregroundServiceType` crash.
- Not verified: no release smoke run, no ANR/crash test on low-RAM devices. Model fit gating (`ModelFit`) re-measures device RAM before offering a model.

## i) Localisation

- The UI is English. German and Arabic exist only partially: `feature/chat` (41 of 41 strings, de and ar), `app` (8 of 14), `feature/documents` (3 of 180 strings; only the form-fill entries), `feature/profiles` and `feature/settings` have no de/ar files, `feature/models`, `feature/home` and `feature/scanner` have no string resources at all (68 hard-coded `Text("...")` literals remain in feature/app code).
- `locales_config.xml` offers en, de, ar in the system per-app language picker, so choosing German gives a mostly English UI with a few translated strings. Either remove de/ar from `locales_config.xml` for the first release (honest) or state "English UI, documents in any language (German and Arabic tested)" in the listing. Document reading and chat answers follow the document language (AI-driven) and are independent of the UI language.
- With form filling hidden, the de/ar `form_*` and `fill_form_*` strings are unused in release (kept for the other branch).

## j) Known limits for the release notes

- English interface; documents in German, English and Arabic are the tested reading languages.
- A one-time model download is needed (about 0.5 to 3.4 GB for a chat model depending on the device, plus a smaller search model); after that everything works offline. Needs a recent 64-bit ARM phone (arm64-v8a only); small phones get only the smaller models.
- Reading and chat speed depend on the phone (seconds per page on a high-end device, much slower on low RAM). Answers can be wrong: check dates, amounts and references against the original.
- Photos or PDFs only as scans; handwriting and low-quality photos read poorly.
- Chat answers are limited by the model's context; long documents are searched, not read whole.
- Form filling is not in this release.
- No cloud sync, no accounts. Backups: see blocker 3.
- Hugging Face must be reachable for downloads; there is no alternative mirror.

## Done in this change (feat/form-assist)

- `FormFillingFlag` (core:model) bound in `FeatureFlagsModule` (app): ON in debug, OFF in release.
- When OFF: no Extracted-tab card, no overflow "Form filling (beta)" item, no Models-screen "Used for form filling" note, the chat never starts, resumes or routes a form run (the detector is never called, no model call), form chips are ignored. Profiles, Saved details and the profile page are unchanged.
- Settings > About: version from the package, "Open-source data" entry with the CC BY attribution.
- Models screen offline-catalog notice corrected.
- Tests: `FeatureFlagsModuleTest` (debug on, release off); `ChatViewModelFormFillTest` (flag off: no route, plain chat turn; flag off: no start or resume); existing Extracted-tab UI test already covers the card with and without the callback.
