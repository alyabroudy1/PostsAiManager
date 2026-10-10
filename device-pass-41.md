# Device pass 41 (debug app, feat/agent-chat with chat-images, app-language, drive-backup)

Screenshots: /Users/mohammad/AndroidStudioProjects/PostsAiManager-work/artifacts/store-screens/

The debug app held NO documents or profiles (Home: "No documents yet"), so checks 5 and 6 and the Documents list and letter detail screens could not run.

1. Arabic: PASS for Settings and AI models. Switches at once, mirrored (back arrow, switches, sliders, nav order). Home, Documents, letter detail and chat sheet: not run (no data).
2. Deutsch: PASS for Home and Settings (Sie form, "Noch keine Dokumente"). Letter detail: not run.
3. Restart: PASS, German kept after force-stop and relaunch.
4. English: PASS.
5. Reprocess jc-2 in German: NOT RUN (no letters).
6. Chat attach menu, camera and gallery: NOT RUN (no Stadtwerke letter).
7. Crashes: PASS, AndroidRuntime:E empty.
8. Backup section: PASS. Settings shows the Backup section with "Connect Google Drive", mirrored in Arabic. Connect not tapped.

## Issues
English text left in every language (model config controls): CPU threads, Context window, Accelerator, Temperature, Top-K, Top-P, Flash attention, Thinking dialog (Thinking, OFF, LOW, HIGH), and the Accelerator value "CPU". The "Applies on next model load" hint is translated.
Clipped: German bottom nav label "Einstellungen" wraps to "Einstellunge / n".
Not translated: Home title "Posts AI Manager" stays English in German and Arabic.
RTL: none found. Arabic mixes Latin names (Gemma, Qwen, JSON) as expected.
