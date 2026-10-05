# Google Play Data safety form - recommended answers (Posts AI Manager 1.0.0)

Verified against the code on `feat/form-assist` (e0725e2). The form is your declaration; review the reasoning and decide.

## Facts the answers rest on

| Fact | Evidence |
|---|---|
| Merged release permissions: CAMERA, INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED (the last two via WorkManager), plus a signature-protected `com.postsaimanager.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx, internal) | `app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml` |
| Network: only model downloads. Hosts: `huggingface.co` (model files `BundledCatalog`; embedding model `EmbeddingModelRelease`). A Hugging Face catalog/tree API client (`HuggingFaceCatalogSource`, `huggingface.co/api`) exists in `:core:config` but is not referenced by any UI or use case at this commit | `BundledCatalog.kt`, `EmbeddingModelRelease.kt`, `ModelDownloader.kt` (GET with Range header) |
| No analytics/ads/crash SDK in the app: `firebase-crashlytics` is only declared in the version catalog, never used by any module; no `google-services` plugin or json. `:core:ai:online` (Ktor) exists in settings but no module depends on it (only a Konsist test mentions it) | `libs.versions.toml`, grep of all `build.gradle.kts` |
| On-device: ML Kit text recognition (bundled model, `com.google.mlkit:text-recognition`), ML Kit Document Scanner (Google Play services UI, `play-services-mlkit-document-scanner`), llama.cpp, ONNX Runtime embeddings. `language-id` and `entity-extraction` are declared in `:core:data` but not called in code | `OcrService.kt`, `ScannerScreen.kt` |
| `allowBackup="true"`, no `dataExtractionRules`/`fullBackupContent` | `app/src/main/AndroidManifest.xml` |
| App lock: BiometricPrompt (strong biometric or device credential), optional, timeout setting | `DeviceAuthenticator.kt`, `UserPreferences.kt` |
| Soft delete: Trash, auto-purge after 30 days, permanent delete available | `TrashDocumentUseCases.kt`, `DocumentRepository.deletePermanently` |
| Local DB is plain Room/SQLite (no SQLCipher, no EncryptedFile) | grep |
| Share: user-initiated `ACTION_SEND` (PDF) and `ACTION_VIEW` | `DocumentDetailScreen.kt` |

## Play's definitions that matter

- **Collect** = transmitting data off the user's device to you or a third party. Data processed only on-device and never sent off is **not** collected.
- **Share** = transferring user data to a third party. Excluded: user-initiated transfers (the user chooses Share), service providers processing on your behalf, and anonymised data.
- Data sent by an SDK counts as yours. Fetching a public model file sends no user data.

## Recommended answers

### Section 1 - Data collection and security
- **Does your app collect or share any of the required user data types?** Recommended: **No.**
  - Reason: documents, text, chats, profiles and sensitive details are processed and stored only on the device. The only network traffic is downloading public model files, which carries no user content. (An IP address is visible to the host in any web request; Google's guidance treats this ordinary connection data as not "collected" by an app that does not log or use it itself, and the app has no server of its own. If you want to be most conservative, see the "conservative alternative" below.)
- **Is all of the user data collected by your app encrypted in transit?** Not applicable when nothing is collected; if the form asks anyway: **Yes** (downloads use HTTPS).
- **Do you provide a way for users to request that their data be deleted?** Answer **Yes**-type mechanism only appears when data is collected. Where asked: deletion is in-app (Trash, permanent delete, delete chats and profiles) and by uninstalling; there is no server-side data to delete. Provide the privacy policy URL, which states this.

### Section 2 - Data types (all unchecked under "collected" and "shared")
Go through the list and leave every type unchecked, including: Personal info, Financial info, Health and fitness, Messages, Photos and videos, Audio, Files and docs, Calendar, Contacts, App activity, Web browsing, App info and performance (no crash logs or diagnostics are sent), Device or other IDs, Location.

Items users may expect to be flagged, with the reason they are not:
| Type | Why not "collected" |
|---|---|
| Photos/videos, Files and docs | Scans stay on-device; never transmitted |
| Personal info, Financial info (names, addresses, amounts) | Extracted and stored locally only |
| Messages (chat) | Chat is with a local AI model; stored locally |
| Device or other IDs | No advertising ID or identifier is read or sent |
| App info and performance | No crash reporting or analytics |

### Section 3 - Other declarations
- **Data safety "security practices":** "Data is encrypted in transit" = **Yes** (HTTPS for downloads). "Users can request data deletion" = see above; "Committed to Play Families Policy" = not applicable (not targeted at children). "Independent security review" = **No**.
- **App access:** none needed; the app has no login. (Mention if a reviewer needs an AI model: the first launch requires a model download; see LISTING.md "Notes for reviewers".)
- **Ads:** declare **No ads**.
- **Target audience:** 18+ (or 16+), **not** designed for children.
- **Permissions declaration:**
  - `FOREGROUND_SERVICE_DATA_SYNC`: Play requires a foreground-service declaration. Use: "Downloads large on-device AI model files (0.5 to 5 GB) initiated by the user; the service shows progress and must keep running when the screen is off." Provide a short video of starting a model download with the notification visible.
  - `CAMERA`, `POST_NOTIFICATIONS`, `INTERNET`: not restricted permissions; no declaration form.

## The backup aspect (decision point)

`allowBackup="true"` means Android Auto Backup (Google account, when the user has it on, up to 25 MB of app data per app for key-value/auto backup; also device-to-device transfer) may copy the app's database and files, including sensitive extracted details. Per Play guidance, backups made by the OS under the user's own account are the user's own storage; Google states that **data transferred to the user's own backup by the OS is not considered collection by the developer**, and no developer has access to it. Therefore the recommended Data safety answer remains **not collected / not shared**, and the privacy policy discloses the backup openly (section 6).

Residual risk: the sensitive data (identity, health or financial details a user chose to store) can end up in a Google backup. Options, in increasing order of protection (code changes, outside this docs task):
1. Keep as is (current decision) and disclose (done).
2. Add `android:dataExtractionRules` / `fullBackupContent` to exclude the database and page images, keeping only settings.
3. Set `allowBackup="false"`.

If you pick option 2 or 3, update PRIVACY_POLICY section 6 (EN and DE) and this file.

### Conservative alternative
If you prefer to declare conservatively, you can mark nothing as collected but still state in the Play listing's "Privacy" text (and the policy) that Android backup may copy data. A Data safety declaration that over-reports collection is allowed but would misstate that you receive data; not recommended.

## Re-check before each release
Re-run the dependency grep (no new analytics/ads/crash/online-AI module), the merged-manifest permission list, and the network host list. Any new network call that carries user content changes every answer above.
