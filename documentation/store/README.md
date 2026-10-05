# Play Store release documents - Posts AI Manager 1.0.0

Drafts, docs only. Nothing here changes the app.

| File | What |
|---|---|
| `PRIVACY_POLICY.md` / `PRIVACY_POLICY.de.md` | Privacy policy (EN, DE), verified against the code; placeholders in `<angle brackets>` |
| `DATA_SAFETY.md` | Item-by-item answers for the Play Data safety form, the evidence behind them, and the backup decision |
| `LISTING.md` | Name ideas, short + full descriptions (EN, DE), release notes, category/tags, content rating guidance, reviewer notes, screenshot plan |

## Your TODO

1. **Fill in placeholders:** `<contact email>`, `<effective date>` / `<Datum des Inkrafttretens>`, provider name and postal address (a German Impressum is usually required for a public app; have it checked), `<privacy policy URL>`. Remove the HTML comment checklists before hosting.
2. **Host the privacy policy** at a stable public URL (e.g. GitHub Pages: publish `PRIVACY_POLICY.md` and the `.de` version as pages; Play needs a URL that opens without login, not a PDF download). Put the URL into the policy, the Play Console (App content > Privacy policy) and, if you like, into Settings of the app.
3. **Decide the backup question** (DATA_SAFETY.md): keep `allowBackup=true` (documented) or exclude sensitive data via backup rules. If you change it, update policy section 6 in both languages.
4. **Play Console:** App content: Privacy policy, Ads (No), App access (no login), Content rating (LISTING.md), Target audience (not for children), Data safety (DATA_SAFETY.md), Foreground service declaration for `dataSync` (needs a short video), News/Government/Financial declarations (answer No / not a financial services app).
5. **Listing:** choose the name, paste descriptions, produce screenshots from invented data (LISTING.md plan), feature graphic 1024x500, icon 512x512.
6. **Check target SDK:** the app targets SDK 35; Play raises the minimum target level every year, so confirm what the Console requires on your upload date.
7. **Legal read-through** of the policy by someone qualified; model licences (Qwen, Gemma) and ML Kit terms are in `documentation/THIRD_PARTY.md` and may need to be surfaced in-app.

## Open questions

- Should the app ship backup rules (exclude database and page images) before release?
- Is the Hugging Face catalog browser (`HuggingFaceCatalogSource`) meant to ship? It is unreferenced now; if shipped it adds `huggingface.co/api` requests (still no user data) and the policy wording should mention user-chosen repositories.
- Is an in-app way to report AI output or send feedback wanted (Play's generative AI expectations)?
- Unused declared dependencies (`firebase-crashlytics`, `mlkit language-id`, `entity-extraction`, `:core:ai:online`): recommend removing them before release so the policy claim "no analytics, crash reporting or other network" stays obviously true.
