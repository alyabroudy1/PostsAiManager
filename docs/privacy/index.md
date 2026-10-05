---
title: Privacy Policy - Posts AI Manager
---

[Deutsch](de/)

# Privacy Policy - Posts AI Manager

**Effective date:** 5 October 2026
**App:** Posts AI Manager (package `com.postsaimanager`), version 1.0.0
**Contact:** alyabroudy1@gmail.com
**Provider (imprint):** **[PLACEHOLDER: provider name and postal address]**

Posts AI Manager helps you scan and understand your paper mail on your own phone. It is built to work offline. This policy explains in plain language what the app does with your information.

## 1. The short version

- Your documents, photos, text, chats and personal details are processed **on your device only**. The app has no server and we never receive them.
- No account, no sign-up, no advertising, no analytics, no crash-reporting service, and we never sell or share your data.
- The app uses the internet **only to download AI model files** (from Hugging Face). Your documents are never sent in these requests.
- An optional app lock protects the app with your fingerprint, face, PIN, pattern or password.
- You can delete any document, chat or profile in the app, and uninstalling the app removes its data from your phone.
- Android's own device backup may copy app data to your Google account if you have that turned on (see section 6).

## 2. What the app processes and stores on your device

All of the following stays in the app's private storage on your phone:

- **Documents:** the scanned pages (images) and any PDF you create.
- **Recognised text:** text read from your pages by on-device text recognition (OCR).
- **Extracted information:** values the on-device AI reads from a document, such as sender, recipient, dates, reference numbers, amounts, and a short summary, plus your decision on each (Confirm, Edit, Ignore).
- **Chats:** your questions to the document assistant, its answers, and which passages it cited.
- **Profiles and saved details:** names and details you create for people or households, including details you or the app save from documents. These can be sensitive (for example addresses, dates of birth, identification or insurance numbers) if you choose to save them.
- **Settings:** language, app lock choice and similar preferences.
- **AI model files** you downloaded.

The app does not read your contacts, location, microphone, SMS, call log, or other apps' data. The app only reads photos or files that you choose to scan.

## 3. How your information is processed (on-device)

- **Scanning:** pages are captured with the document scanner provided by Google Play services. The scanning happens on your device, and the resulting images are handed to our app.
- **Text recognition:** performed on-device by Google ML Kit text recognition, using a model bundled in the app. No page image or text is sent to Google or to us.
- **AI extraction, summaries and chat:** performed by AI models that run on your phone (via llama.cpp, and an embedding model run with ONNX Runtime for search). The prompts and your documents never leave the device.

Because a small on-device model is used, results can be wrong or incomplete. Always check important values (amounts, dates, reference numbers) against the original letter.

## 4. Internet use

The app declares the INTERNET permission for one purpose: **downloading AI model files**.

- **Where to:** `huggingface.co` (Hugging Face, Inc.), over HTTPS. The model files named in the app's built-in list are downloaded from Hugging Face repositories. A small text-embedding model is also downloaded from Hugging Face.
- **What is sent:** a standard HTTPS request for a file (including your device's IP address and standard technical headers, such as the HTTP user agent, which any web request carries; and, when a download is resumed, a byte-range header). **No document, text, chat, profile or personal data is sent.** The app does not send any identifier of you.
- **What Hugging Face may do:** Hugging Face, as the host, receives the request and may log it under its own privacy policy (https://huggingface.co/privacy). We do not control that.
- **Integrity:** downloads are checked against a stored checksum before use.
- After the models are downloaded, the app works fully offline.
- Google Play services may, on its own and under Google's privacy policy, download or update components such as the document scanner. That is a Google system service, not something this app sends your documents to.

The app contains no advertising, analytics, tracking or crash-reporting libraries. We do not contact any other server. The app has no screen for browsing or adding model sources: it only requests the fixed model files named above.

## 5. Permissions and why

| Permission | Why |
|---|---|
| Camera | To photograph letters. Optional: you can import images instead. |
| Internet / network state | To download AI model files and to check that a connection exists before doing so. |
| Foreground service (data sync) | To keep a large model download running with a visible notification while the screen is off. |
| Notifications | To show download progress and completion. On Android 13 and newer the app asks right before the first model download, with a one-line explanation. Optional: the download works if you decline. |
| Wake lock, run at start-up | Declared by Android's background-work library (WorkManager) used for downloads and document processing, so unfinished work can resume. |

The app does not request storage, contacts, location or microphone permissions.

## 6. Android backup (please read)

The app allows Android's standard backup (`allowBackup`). This means that, **if you have backup turned on for your phone** (for example "Backup by Google One"), Android may include the app's data in your Google account backup and restore it when you set up a new phone. Depending on your Android version and device, this can include the app database (documents, extracted fields, chats, profiles and saved details) and files. The backup is made and stored by Google, under Google's terms, not by us, and we cannot access it.

To prevent this:
- Turn off device backup: **Settings > Google > All services > Backup** (names vary by device), or
- Turn it off for the whole phone, or delete existing backups there.

Downloaded AI models are large; Android may or may not include them in a backup, and they can be downloaded again.

## 7. Sharing

We do not share your information with anyone. Data leaves the app only when **you** do it, for example by using "Share as PDF" or "Open" on a document, which hands the file to the app you choose. What that app does with it is governed by its own policy.

**Reporting an AI answer.** Every AI answer in the chat, and the AI summary of a document, has a "Report this answer" button. It opens a draft in your e-mail app, addressed to alyabroudy1@gmail.com, with a short question about what was wrong. Nothing is sent by the app: you read the draft and send it yourself, or close it. The text of the answer is added to the draft only if you tick "Include the answer text (may contain personal data)" in the confirmation dialog; it is off by default. If you send the e-mail, we receive what is in it (your address and your text) and use it only to look into the problem and improve the app. Your e-mail provider handles the message under its own policy.

## 8. Retention and deletion

- Data stays on your device until you delete it. There is no time limit set by us, and no copy on our side.
- **Deleting a document** moves it to the **Trash**. Trashed documents are kept for 30 days, can be restored, and are then deleted permanently. You can also delete from the Trash immediately.
- Chats, profiles and saved details can be deleted in the app.
- **Uninstalling** the app deletes its stored data from your phone. Copies in an Android/Google backup (section 6) are managed in your Google account.
- Since we hold no data about you, there is nothing for us to delete on request. Use the in-app deletion or uninstall. If you have questions, write to alyabroudy1@gmail.com.

## 9. Security

- Data is kept in the app's private storage, which other apps cannot normally read.
- **App lock (optional):** in Settings you can require your fingerprint, face, PIN, pattern or password to open the app, with a choice of how long it may stay in the background before locking again. The app lock relies on your phone's own screen lock and biometrics; we never see or store them.
- Model downloads use HTTPS and are verified with a checksum.
- Note: the app's local database is not separately encrypted by the app; protection relies on Android's device encryption and your screen lock. Keep your phone locked and updated.
- No system is perfectly secure and we cannot guarantee absolute security.

## 10. Children

The app is not directed to children and is not intended for people under 16 (or the minimum age in your country). We do not knowingly collect any data from children, and we collect no data from anyone.

## 11. Your rights

Because we do not collect or hold your personal data, there is generally no data for us to access, correct or export. You control your data directly in the app. If you are in the EU/EEA/UK and believe we hold anything about you (for example because you contacted us), you have the rights of access, rectification, erasure, restriction, portability and objection, and the right to complain to your data protection authority. Contact: alyabroudy1@gmail.com.

## 12. Changes

If this policy changes, the new version will be published at https://alyabroudy1.github.io/PostsAiManager/privacy/ with a new effective date, and in the app update's release notes if the change is significant.

## 13. Contact

alyabroudy1@gmail.com  
**[PLACEHOLDER: provider name and postal address]**
