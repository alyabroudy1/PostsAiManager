# Agent-Driven Device Test Harness

A protocol for having a **cheap model** (Haiku-class) execute scripted UI tests on a real
device via `adb`, and report structured results.

> **This is not a replacement for instrumented tests.** Espresso / Compose UI tests belong
> in CI and cover deterministic assertions. This harness covers what those handle badly:
> smoke checks, process-death scenarios, resource exhaustion, permission paths, and triage.

---

## 1. Why not screenshots

The obvious design — screenshot the device, ask a model what it sees — is the wrong one for
a cheap model. Vision is expensive, non-deterministic, and a small model will confidently
report what it *expected* to see.

**`adb shell uiautomator dump` returns the whole view hierarchy as XML**, with every node's
`text`, `content-desc`, `resource-id`, and pixel `bounds`. That reduces the model's job to
two operations it cannot get wrong:

| Need | Operation | Skill required |
|---|---|---|
| Assert | Is this string in the XML? | String match |
| Act | Tap the centre of `bounds="[x1,y1][x2,y2]"` | Arithmetic |

Screenshots are captured **only as failure artifacts**, for a human or a stronger model to
review afterwards. They are never the pass/fail signal.

`adb logcat` supplies the second signal — crashes and exceptions — and is also pure text.

---

## 2. Division of labour

| Concern | Espresso / Compose | This harness |
|---|---|---|
| Deterministic assertions in CI | ✅ | — |
| Build → install → launch → navigate smoke | overkill | ✅ |
| Kill mid-download, verify resume (task 7.6.9) | awkward | ✅ |
| No model installed → all non-AI features work (7.10.3) | tedious | ✅ |
| Low memory / OOM behaviour | very hard | ✅ |
| Denied permissions, first-run onboarding | painful | ✅ |
| Triage: what broke, with logs | — | ✅ |

---

## 3. Designing around a cheap model

Reliability comes from removing the opportunity to drift:

1. **One test case per agent run.** Short and fixed.
2. **Every step declares its expected observation.** No inference.
3. **The agent never chooses the next action.** The script is fixed. Deviation is a `FAIL`,
   never an invitation to improvise or "try something else".
4. **Structured JSON output**, not prose.
5. **Observe and report — never diagnose.** Root-causing is the caller's job.

Rule 3 is the important one. A cheap model asked to *achieve a goal* will wander; a cheap
model asked to *follow twelve numbered steps and report* is reliable.

---

## 4. Environment

| Item | Value |
|---|---|
| ADB | `C:/Users/test/AppData/Local/Android/Sdk/platform-tools/adb.exe` |
| Device serial | `R5CW21KC1BM` — **always pass `-s`**, an emulator is also attached |
| Device | Samsung SM-S918B (Galaxy S23 Ultra) |
| OS | Android 16, API 36 |
| ABI | `arm64-v8a` (list: `arm64-v8a, armeabi-v7a, armeabi`) |
| RAM | 11.3 GB total — **2.5 GB available**, see §8 |
| Free storage | 209 GB |
| Package | `com.postsaimanager.debug` |

### 4.0 Unattended runs — invoke the wrapper by its literal path

Permission rules match the **literal command string**. Aliasing the wrapper defeats them:

```bash
A="./scripts/dev-adb.sh"
$A shell input tap 100 200      # ← starts with "$A"; the rule never matches, so it prompts
./scripts/dev-adb.sh shell input tap 100 200   # ← matches Bash(./scripts/dev-adb.sh *)
```

Earlier case files used the `$A` alias and prompted on every step, which defeated the point
of routing everything through one guarded wrapper. **Runner prompts must spell the path out
in full, every time.** Verbosity is the price of an unattended run.

The rest of the harness vocabulary (`sleep`, `grep`, `cat`, `mkdir -p`, …) is allowlisted in
`.claude/settings.json`.

> Claude Code loads `.claude/settings.json` at session start and only watches directories
> that already contained one. If the file was created mid-session, **restart** before
> expecting it to take effect.

### 4.1 Windows / Git Bash gotchas

These will bite on every run if not handled:

```bash
# 1. Absolute paths in `adb shell` get mangled by MSYS path conversion.
#    /sdcard/x.xml becomes C:/Program Files/Git/sdcard/x.xml
export MSYS_NO_PATHCONV=1

# 2. Binary output must use exec-out, NOT shell — `shell` corrupts bytes with CRLF.
"$ADB" $D exec-out screencap -p > shot.png     # correct
"$ADB" $D shell screencap -p > shot.png        # CORRUPT on Windows

# 3. Two devices are attached. Every command needs -s.
D="-s R5CW21KC1BM"
```

### 4.2 Determinism setup and teardown

Animations make UI automation flaky. Set them to zero before a run and restore after —
**this modifies the user's device settings**, so the teardown is not optional.

```bash
# setup (record originals first)
"$ADB" $D shell settings put global window_animation_scale 0
"$ADB" $D shell settings put global transition_animation_scale 0
"$ADB" $D shell settings put global animator_duration_scale 0
"$ADB" $D shell svc power stayon usb

# teardown — original values on this device were 1.0, 1.0, null
"$ADB" $D shell settings put global window_animation_scale 1.0
"$ADB" $D shell settings put global transition_animation_scale 1.0
"$ADB" $D shell settings delete global animator_duration_scale
"$ADB" $D shell svc power stayon false
```

---

## 5. Safety constraints

This is the user's **personal phone**. The agent prompt must forbid, explicitly:

- Uninstalling or `pm clear`-ing any package other than `com.postsaimanager.debug`
- Navigating outside the app under test (no wandering via HOME into other apps)
- Reading, copying, or reporting the contents of any file outside the app's own storage
- `adb root`, `adb disable-verity`, factory reset, any `settings put` beyond the three
  animation scales above
- Sending any device data anywhere; logcat is filtered to the app's PID and read-only
- Modifying the repository — the agent is **read-only on the filesystem** except for its
  own artifact directory

---

## 6. Command vocabulary

Test cases are written in this vocabulary; the agent translates each to `adb`.

| Command | Meaning |
|---|---|
| `LAUNCH` | `am start -n <pkg>/<activity>`; clears logcat first |
| `TAP_TEXT "<s>"` | Dump, find node with `text` or `content-desc` = `<s>`, tap centre of bounds |
| `TAP_ID "<id>"` | Same, matching `resource-id` |
| `TYPE "<s>"` | `input text` (escape spaces as `%s`) |
| `BACK` / `HOME` | `input keyevent 4` / `3` |
| `WAIT <ms>` | Sleep |
| `ASSERT_TEXT "<s>"` | Dump; PASS if `<s>` present |
| `ASSERT_NO_TEXT "<s>"` | Dump; PASS if absent |
| `ASSERT_NO_CRASH` | PASS if logcat since `LAUNCH` has no `FATAL EXCEPTION` / `AndroidRuntime` / `ANR` |
| `SCREENSHOT <name>` | `exec-out screencap` to the artifact dir |
| `SCROLL_DOWN` | `input swipe <w/2> <h*0.6> <w/2> <h*0.25> 300` |
| `KILL` | `am force-stop <pkg>` — for process-death tests |

**`SCROLL_DOWN` exists because T001 found its absence.** The first run failed asserting
`"Version"` on the Settings screen. `Version` is real — `SettingsScreen.kt:121` — but it is
the last row of the last section, below the fold. The runner correctly refused to go
looking for it. That was a **defective test, not a defective app**, and exactly the class of
mistake the "never improvise" rule is designed to surface rather than paper over.

Assertions on off-screen content must scroll explicitly.

**Rule: assert before you scroll.** Both scroll bugs so far were written blind —
T001 failed by not scrolling to `"Version"` at the bottom of Settings; T002 failed by
scrolling twice past `"AI models"` near the top. Neither was an app defect.

When writing a case, check a dump of the screen first and record what is actually visible.
A scroll step should exist only when a dump proves the target is off-screen — and the case
should say which dump justified it, so the next person can re-check when the layout moves.

Dump idiom:

```bash
"$ADB" $D shell uiautomator dump /sdcard/ui.xml >/dev/null
"$ADB" $D shell cat /sdcard/ui.xml
```

### 6.1 Compose limitation — action required

`uiautomator` sees Compose nodes' `text` and `content-desc`, but **`resource-id` is empty
unless the app opts in**. Without it, `TAP_ID` is unusable and icon-only buttons (which have
no text) can only be reached by `content-desc`, if one was set.

One line at the root of `PamApp` fixes this permanently:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
Modifier.semantics { testTagsAsResourceId = true }
```

Every `Modifier.testTag("…")` then surfaces as a `resource-id`. **Tracked as task 6.8.1** —
worth doing early, because every test written before it is fragile by construction.

---

## 7. Test case format

```markdown
# T00N — <title>

**Goal:** one sentence
**Preconditions:** …
**Teardown:** …

| # | Command | Expect |
|---|---------|--------|
| 1 | `LAUNCH` | app starts |
| 2 | `ASSERT_TEXT "Home"` | Home tab visible |
```

## 8. Result contract

The agent returns **only** this JSON:

```json
{
  "case": "T001",
  "verdict": "PASS | FAIL | BLOCKED",
  "device": "R5CW21KC1BM",
  "steps": [
    { "n": 1, "cmd": "LAUNCH", "result": "PASS", "note": "" },
    { "n": 2, "cmd": "ASSERT_TEXT \"Home\"", "result": "FAIL",
      "note": "dump contained 'Documents','Profiles' but not 'Home'" }
  ],
  "crashes": ["java.lang.IllegalStateException at …"],
  "artifacts": ["…/T001/step2-dump.xml", "…/T001/step2.png"],
  "duration_s": 34
}
```

`BLOCKED` = could not start (device offline, app not installed, build missing).
**`BLOCKED` is not `FAIL`** — the distinction matters for triage.

On any `FAIL`: capture a dump **and** a screenshot, then **stop**. Do not continue, do not
retry, do not improvise a workaround.

---

## 9. Device-tier finding

Recorded here because it changes the architecture, not just the tests.

```
MemTotal:      11,309,732 kB  (11.3 GB)
MemAvailable:   2,582,248 kB  ( 2.5 GB)
```

A flagship with 11 GB of RAM had **2.5 GB actually available**. A 3–4 B model at Q4
quantisation needs ~2.5 GB resident — meaning this device is at its practical limit *right
now*, despite being Tier 3 by the `MemTotal` rule in
[02-architecture.md §6.11](02-architecture.md).

**Correction:** tiering must read `ActivityManager.MemoryInfo` (`availMem`, `totalMem`,
`lowMemory`, `threshold`) and re-check **at load time**, not once at startup. A model that
fits at 09:00 may not fit at 17:00.

This also means the pre-load RAM check (task 7.3.5) is not a nicety — on real devices it is
the difference between an actionable error and an OOM kill. Tracked as task **7.10.6**.
