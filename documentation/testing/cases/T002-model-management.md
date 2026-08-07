# T002 — Model management screen

**Goal:** Prove the first AI-facing feature renders on a real device: Settings → AI models
shows the device memory card, the offline-catalog notice, and the bundled models with
correct install-blocking.

**Why it matters:** `ModelFit` decides what a user is offered. On this device the numbers
are unusual — 11.3 GB total RAM but ~2.5 GB available — so the screen is also a live check
that capability is read from `ActivityManager.MemoryInfo` rather than `MemTotal`.

**Preconditions**
- `app/build/outputs/apk/debug/app-debug.apk` built from current `main`
- Device `R5CW21KC1BM` connected and unlocked
- Animation scales set to 0

**Teardown** — restore animation scales (`1.0`, `1.0`, delete `animator_duration_scale`).

**Assertion sources** (verified in source, not guessed)
- `"AI models"` — `SettingsScreen.kt`, AI section row
- `"AI Models"` — `ModelsScreen.kt` top bar title
- `"This device"` — `ModelsScreen.kt` `DeviceCard`
- `"Offline catalog"` — `ModelsScreen.kt` `OfflineCatalogNotice`
- `"Available"` — `ModelsScreen.kt` section header
- `"Qwen2.5 1.5B Instruct"`, `"Gemma 2 2B Instruct"` — `BundledCatalog.kt`

---

## Steps

| # | Command | Expect |
|---|---------|--------|
| 1 | `INSTALL app-debug.apk` | `Success` |
| 2 | `LAUNCH` | app starts, logcat cleared |
| 3 | `WAIT 3000` | — |
| 4 | `ASSERT_NO_CRASH` | — |
| 5 | `TAP_TEXT "Settings"` | — |
| 6 | `WAIT 1500` | — |
| 7 | `ASSERT_TEXT "AI models"` | **no scrolling** — the AI section is third in Settings, above the fold. Verified against `dump-step05` on a Galaxy S23 Ultra. |
| 8 | `ASSERT_TEXT "AI"` | section header |
| 9 | `TAP_TEXT "AI models"` | — |
| 10 | `WAIT 2500` | — |
| 11 | `ASSERT_NO_CRASH` | screen composed without error |
| 12 | `ASSERT_TEXT "AI Models"` | top bar |
| 13 | `ASSERT_TEXT "This device"` | device capability card |
| 14 | `ASSERT_TEXT "Offline catalog"` | correct — no signing key yet (7.4.6) |
| 15 | `ASSERT_TEXT "Available"` | catalog section |
| 16 | `ASSERT_TEXT "Qwen2.5 1.5B Instruct"` | bundled catalog rendered |
| 17 | `SCREENSHOT 01-models` | — |
| 18 | dump → report every `text=` value | **the key observation** |
| 19 | `TAP_TEXT "Install"` | should be **disabled** — see note |
| 20 | `WAIT 1500` | — |
| 21 | `ASSERT_NO_CRASH` | — |
| 22 | `SCREENSHOT 02-after-install-tap` | — |

---

## Notes for the runner

**Step 18 is the most valuable output.** Report the device card's memory line verbatim — it
shows real `availMem` vs `totalMem` from the device.

**Step 19:** every bundled model has no download URL or hash, so `ModelFit` returns
`NotInstallable` and the Install button is rendered **disabled**. Tapping it should do
nothing at all. That is a **pass**, not a failure. If a download *starts*, report `FAIL` —
it would mean an unverified model was accepted.

**Do not improvise.** If an element is not found, dump, screenshot, report `FAIL` on that
step, and stop.

**Revision note.** The first run of this case scrolled twice before looking for
`"AI models"` and scrolled straight past it — the AI section sits third in Settings, so it
is on screen immediately. The mirror image of T001, which failed by *not* scrolling to
reach `"Version"` at the bottom. Both were defective tests, not defective app behaviour;
both were caught because the runner refuses to go hunting for a missing element.
