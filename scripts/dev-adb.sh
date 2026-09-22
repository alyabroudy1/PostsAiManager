#!/usr/bin/env bash
# dev-adb.sh — guarded adb wrapper for the device test harness.
#
# Two jobs:
#   1. Give every adb invocation a STABLE LITERAL PREFIX so a single Claude Code
#      permission rule — Bash(./scripts/dev-adb.sh *) — covers the whole harness.
#      (A "$ADB" shell variable cannot be matched by prefix-based permission rules.)
#   2. ENFORCE the safety constraints in documentation/05-test-harness.md §5.
#      Pattern matching on command strings is not a real guard; this is.
#
# Usage:  ./scripts/dev-adb.sh shell dumpsys battery
#         PAM_DEVICE=emulator-5554 ./scripts/dev-adb.sh devices
#
# See documentation/05-test-harness.md

set -euo pipefail

# Resolve adb: explicit PAM_ADB wins; otherwise fall back to the common SDK
# locations for the host OS (macOS/Linux first, then the Windows Git Bash path).
if [ -z "${PAM_ADB:-}" ]; then
  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "/c/Users/test/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then PAM_ADB="$candidate"; break; fi
  done
fi
ADB="${PAM_ADB:-}"
DEVICE="${PAM_DEVICE:-R5CW21KC1BM}"
PKG="com.postsaimanager.debug"

# Git Bash mangles absolute paths passed to `adb shell` (/sdcard/x -> C:/Program Files/Git/sdcard/x)
export MSYS_NO_PATHCONV=1

[ -x "$ADB" ] || { echo "dev-adb: adb not found at $ADB" >&2; exit 127; }
[ $# -gt 0 ]  || { echo "dev-adb: no arguments" >&2; exit 2; }

die() { echo "dev-adb: BLOCKED — $1" >&2; exit 13; }

# ── Safety guard ────────────────────────────────────────────────────────────
# This runs against a real personal device. Refuse anything destructive or
# out-of-scope, regardless of what the caller intended.
ARGS="$*"

case "${1:-}" in
  root|unroot|disable-verity|remount|reboot|sideload|restore|bugreport)
    die "'$1' is not permitted by the harness" ;;
esac

# uninstall / pm clear wipe app data (multi-GB downloaded models, documents).
# Restricted to our own debug package AND require an explicit opt-in, because
# an automated agent once wiped the developer's models with `pm clear`.
case "$ARGS" in
  *uninstall*)
    case "$ARGS" in
      *"$PKG"*) : ;;
      *) die "uninstall is restricted to $PKG" ;;
    esac
    [ "${PAM_ALLOW_WIPE:-}" = "1" ] || die "uninstall wipes app data; re-run with PAM_ALLOW_WIPE=1 if you really mean it" ;;
esac

case "$ARGS" in
  *"pm clear"*)
    case "$ARGS" in
      *"$PKG"*) : ;;
      *) die "pm clear is restricted to $PKG" ;;
    esac
    [ "${PAM_ALLOW_WIPE:-}" = "1" ] || die "pm clear wipes app data; re-run with PAM_ALLOW_WIPE=1 if you really mean it" ;;
esac

# Only the three animation scales may be written. Everything else is read-only.
case "$ARGS" in
  *"settings put"*|*"settings delete"*)
    case "$ARGS" in
      *window_animation_scale*|*transition_animation_scale*|*animator_duration_scale*) : ;;
      *) die "only the three animation scales may be modified" ;;
    esac ;;
esac

case "$ARGS" in
  *"rm -rf"*|*"--wipe-data"*|*"master-clear"*|*"FACTORY_RESET"*)
    die "destructive command refused" ;;
esac

# Screenshots and other binary payloads MUST use exec-out; `shell` corrupts
# bytes with CRLF translation on Windows.
case "$ARGS" in
  "shell screencap"*|"shell "*screencap*)
    die "use 'exec-out screencap -p' — 'shell' corrupts PNG bytes on Windows" ;;
esac

exec "$ADB" -s "$DEVICE" "$@"
