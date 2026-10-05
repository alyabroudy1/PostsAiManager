# Rules for every implementation agent (read fully before starting)

1. Read documentation/planning/ARCHITECTURE-FINAL.md first: the USER DECISIONS
   header, then your phase. Directives: the AI decides meaning, code only verifies; nothing static (no keyword lists, no
   language-specific regexes deciding meaning, no country or language names in code paths; data registries only); clean
   architecture (small classes behind ports, one owner per concept); v1 scope DE/EN/AR, but extensible by data alone.
2. WORKTREE SETUP (from the main repo `<repo>`; `<work-folder>` is the folder next to it, see HANDOFF.md):
   - `git -c submodule.core/ai/local/src/main/cpp/llama.cpp.ignore=all worktree add -b <branch> <work-folder>/worktrees/<name> feat/extraction-v2`
   - In the new worktree, the directory core/ai/local/src/main/cpp/llama.cpp is empty: remove that empty directory with
     `rmdir` and replace it with a symlink to <repo>/core/ai/local/src/main/cpp/llama.cpp.
   - Copy local.properties from the main repo.
   - EVERY git command in the worktree: `git -c submodule.core/ai/local/src/main/cpp/llama.cpp.ignore=all …`.
   - Never commit the symlink. Check `git status` before each commit.
   - NEVER `git add -A`, `git add .` or `git commit -a`: stage explicit paths only. Before EVERY commit, run
     `git -c submodule.core/ai/local/src/main/cpp/llama.cpp.ignore=all diff --cached --summary` and abort if it shows
     `mode change` or any `core/ai/local/src/main/cpp/llama.cpp` entry. (A wave-1 commit once replaced the submodule with
     the symlink.)
3. File edits ONLY with the Edit/Write tools. No python, heredoc, sed or awk edits. If a command is denied, do NOT achieve
   the same effect another way; stop and report it.
4. NO DEVICE. Do not run dev-adb.sh, adb or installs. JVM tests only. Another agent owns the phone.
5. Do NOT edit these files (the speed workstream owns them until it merges): ZoneScoringInterpreter.kt, PromptSession.kt,
   ZonePrompt.kt, ExtractionV2Pipeline.kt, DocumentProcessingPipeline.kt, and anything under core/ai/local. If your phase
   seems to need them, leave a TODO(P4) note in your report instead.
6. Build lightly (several agents share the Mac): run only the module tasks you need, e.g.
   `./gradlew :core:domain:testDebugUnitTest --tests '…' -Dorg.gradle.workers.max=2` (check the actual task names).
   Run the full module test task + BenchmarkGateTest + Konsist once before the final commit.
7. Commit on your branch with a clear conventional message (small commits are fine), ending with
   `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Never push. Never merge into other branches.
8. Match the surrounding code's style, KDoc density and naming. Tests for every new pure class. All user-facing text in
   string resources. Never attribute statements to the user.
9d. NEVER modify the app's private files or databases on the device (no `run-as` writes, no sed/echo into app files).
    If the app needs a state change you can't make through its UI, STOP and report. (An agent once patched
    installed.json via run-as sed.)
9c. SCREENSHOTS: save device screenshots ONLY in the artifacts folder named in your brief, never in /tmp. Before you
    finish, delete every screenshot showing anything besides the invented test data (home list, profiles list, photo
    picker, other apps).
9b. DEVICE PRIVACY: NEVER `uiautomator dump`, never `logcat` without a tag filter, never read anything from another app.
    Find buttons from screenshots of OUR app only, taken right after a focus check. (A UI dump once captured a private
    chat from another app.)
9a. SPEED (user, 2026-10-01): do ALL code fixes first and run only targeted tests while coding. Then ONE full
    build/gate, then ONE device pass at the end, with no fix→reinstall loops and few screenshots. While waiting on the
    phone's AI, poll logs at most every ~15 s; no idle sleeps.
9. Don't stall: if a Gradle run takes >10 min, check it with short polls. If blocked, report what you have.
10. Final report: branch + commit hashes, files changed, test results (counts), deviations from the plan with reasons,
    and TODO(P4) items.
