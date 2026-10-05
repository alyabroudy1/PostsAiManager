# Play Store release documents - Posts AI Manager 1.0.0

Drafts, docs only. Nothing here changes the app.

| File | What |
|---|---|
| `PRIVACY_POLICY.md` / `PRIVACY_POLICY.de.md` | Privacy policy (EN, DE), verified against the code. Contact e-mail filled in; the imprint and the effective date are marked `[PLACEHOLDER ...]` |
| `DATA_SAFETY.md` | Item-by-item answers for the Play Data safety form, the evidence behind them, and the backup decision |
| `LISTING.md` | Name ideas, short + full descriptions (EN, DE), release notes, category/tags, content rating guidance, reviewer notes, screenshot plan |
| `../../docs/` | GitHub Pages sources: `docs/index.md`, `docs/privacy/index.md` (EN), `docs/privacy/de/index.md` (DE). Copies of the two policies; keep them in sync when you edit a policy |

Decisions recorded (2026-10-05): contact e-mail is alyabroudy1@gmail.com; the imprint stays a placeholder until you fill it in;
the policy is hosted on this repo's GitHub Pages; backup stays as is (`allowBackup=true`, disclosed in section 6); unused
libraries were removed; each AI answer has a "Report this answer" button (e-mail draft, answer text only if the user ticks it).

## Hosting the privacy policy (GitHub Pages)

After this branch is merged into `main` and pushed:

1. On GitHub open the repository **Settings > Pages**.
2. Under "Build and deployment" choose **Deploy from a branch**, branch **main**, folder **/docs**, then Save.
3. After a minute the pages are live:
   - English: `https://alyabroudy1.github.io/PostsAiManager/privacy/`
   - German: `https://alyabroudy1.github.io/PostsAiManager/privacy/de/`
   - Index: `https://alyabroudy1.github.io/PostsAiManager/`
4. Open both URLs in a private window (no login) and check them. Use the English URL as the Play Console privacy policy URL
   (App content > Privacy policy). The URL pattern is `https://<github user>.github.io/<repository name>/privacy/`; if the
   repository has another name or is under another account, change the two URLs in section 12 of both policies.
5. A private repository needs a paid GitHub plan for Pages, and the pages are public anyway; check the repo's visibility first.

## Your TODO

1. ~~Fill in the placeholders.~~ Done on 2026-10-05: the effective date is 5 October 2026; the provider is "Alyabroudy" + e-mail,
   with no postal address (user decision). Before any commercial use, have it checked whether a German Impressum with an
   address is required (a c/o address service is an option).
2. **Enable GitHub Pages** (above) and test the URLs.
3. **Backup:** unchanged (`allowBackup=true`, documented). If you ever change it, update policy section 6 in both languages and in `docs/privacy`.
4. **Play Console:** App content: Privacy policy, Ads (No), App access (no login), Content rating (LISTING.md), Target audience (not for children), Data safety (DATA_SAFETY.md), Foreground service declaration for `dataSync` (needs a short video), Generative AI / AI-generated content (the in-app "Report this answer" button is the reporting mechanism), News/Government/Financial declarations (answer No / not a financial services app).
5. **Listing:** choose the name, paste descriptions, produce screenshots from invented data (LISTING.md plan), feature graphic 1024x500, icon 512x512.
6. **Target SDK:** the app targets SDK 36, which Play requires for new apps and updates from 2026-08-31; confirm in the Console on your upload date.
7. **Legal read-through** of the policy by someone qualified; model licences (Qwen, Gemma) and ML Kit terms are in `documentation/THIRD_PARTY.md` and may need to be surfaced in-app.

## Resolved questions

- Backup rules: no, stays as is.
- `HuggingFaceCatalogSource` (in `:core:config`) stays in the code but nothing calls it: no screen, use case or Hilt consumer. The app only requests the fixed files in `BundledCatalog` and `EmbeddingModelRelease`. The policy (section 4) says so.
- AI-output reporting: added ("Report this answer", an `ACTION_SENDTO` e-mail draft; the answer text is included only when the user ticks the option, off by default; the app never sends anything itself).
- Unused dependencies `firebase-crashlytics`, ML Kit `language-id`, `entity-extraction` and the empty `:core:ai:online` module: removed.
