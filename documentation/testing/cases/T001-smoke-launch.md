# T001 — Smoke: launch and navigate all tabs

**Goal:** Prove the current `main` build installs, launches, and reaches every top-level
destination without crashing. Closes tasks `6.1.1` and `6.1.2`.

**Why it matters:** The findings report infers compilation success from static reading, and
the APK currently on the device was installed **2026-06-13** — before the last four commits.
Nothing has verified that current `main` runs.

**Preconditions**
- `app/build/outputs/apk/debug/app-debug.apk` exists (built from current `main`)
- Device `R5CW21KC1BM` connected and unlocked
- Animation scales set to 0 (see harness §4.2)

**Teardown**
- Restore animation scales to `1.0`, `1.0`, and *delete* `animator_duration_scale`
- `svc power stayon false`
- Leave the app installed

**Assertion sources** — every expected string is verified in source, not guessed:
- Tab labels: `app/.../navigation/TopLevelDestination.kt:18,24,30,36,42`
- `"Search documents..."`: `feature/documents/.../DocumentsScreen.kt:78`
- `"Search profiles..."`: `feature/profiles/.../ProfilesScreen.kt:77`
- `"Version"`: `feature/settings/.../SettingsScreen.kt:119`

---

## Steps

| # | Command | Expect |
|---|---------|--------|
| 1 | `INSTALL app/build/outputs/apk/debug/app-debug.apk` | `Success` |
| 2 | `LAUNCH` | app starts, logcat cleared |
| 3 | `WAIT 3000` | — |
| 4 | `ASSERT_NO_CRASH` | no `FATAL EXCEPTION` / `AndroidRuntime` / ANR |
| 5 | `ASSERT_TEXT "Home"` | bottom nav rendered |
| 6 | `ASSERT_TEXT "Documents"` | — |
| 7 | `ASSERT_TEXT "Profiles"` | — |
| 8 | `ASSERT_TEXT "Settings"` | — |
| 9 | `ASSERT_TEXT "Parser"` | 5th tab present in current build |
| 10 | `SCREENSHOT 01-home` | — |
| 11 | `TAP_TEXT "Documents"` | — |
| 12 | `WAIT 1500` | — |
| 13 | `ASSERT_TEXT "Search documents..."` | Documents screen loaded |
| 14 | `ASSERT_NO_CRASH` | — |
| 15 | `SCREENSHOT 02-documents` | — |
| 16 | `TAP_TEXT "Profiles"` | — |
| 17 | `WAIT 1500` | — |
| 18 | `ASSERT_TEXT "Search profiles..."` | Profiles screen loaded |
| 19 | `ASSERT_NO_CRASH` | — |
| 20 | `SCREENSHOT 03-profiles` | — |
| 21 | `TAP_TEXT "Settings"` | — |
| 22 | `WAIT 1500` | — |
| 23 | `ASSERT_TEXT "Appearance"` | Settings screen loaded (first section, always visible) |
| 23a | `SCROLL_DOWN` ×3 | reach the About section |
| 23b | `ASSERT_TEXT "Version"` | last row, **below the fold** — see revision note |
| 24 | `ASSERT_NO_CRASH` | — |
| 25 | `SCREENSHOT 04-settings` | — |
| 26 | `TAP_TEXT "Parser"` | — |
| 27 | `WAIT 4000` | ONNX model load is slow and **expected to fail** |
| 28 | `SCREENSHOT 05-parser` | — |
| 29 | `ASSERT_NO_CRASH` | see note below |
| 30 | `TAP_TEXT "Home"` | — |
| 31 | `WAIT 1500` | — |
| 32 | `ASSERT_NO_CRASH` | — |

---

## Notes for the runner

**Step 29 is the interesting one.** `feature:parser` loads a **617-byte placeholder ONNX
model**. It is expected to fail. Two outcomes, both useful:

- Handled error → the screen shows a message. Record the **exact text** in the step note.
  This confirms the finding in `01-findings-report.md §3.2`.
- Unhandled crash → `FAIL`, and capture the full stack trace.

Either way, **record and continue** — do not treat a parser error message as a blocker for
step 30. This is the one step where a visible error is not a failure.

**Step 9** — if `"Parser"` is missing, the installed APK is stale. Report `BLOCKED`, not
`FAIL`: it means step 1 did not actually install the new build.

**Do not improvise.** If a tap does not land, dump, screenshot, and report `FAIL` on that
step. Do not try a different element, do not scroll looking for it, do not restart the app.
