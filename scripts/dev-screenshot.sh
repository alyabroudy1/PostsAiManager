#!/usr/bin/env bash
# dev-screenshot.sh: a privacy-safe screenshot for the device test harness.
#
# It takes a screenshot ONLY if the focused window belongs to one of our packages (com.postsaimanager or
# com.postsaimanager.debug). The focus check and the capture run in ONE device command, so another app
# cannot slip in between them. If anything else is in front, nothing is captured and the script exits 3.
#
# It writes ONLY under the artifacts screenshot folder, so a single permission rule,
# Bash(./scripts/dev-screenshot.sh *), is enough.
#
# Usage:  ./scripts/dev-screenshot.sh <name>.png [release|debug]
#         (default: release = com.postsaimanager; "debug" allows com.postsaimanager.debug)

set -euo pipefail

NAME="${1:-}"
WHICH="${2:-release}"
[ -n "$NAME" ] || { echo "dev-screenshot: usage: $0 <name>.png [release|debug]" >&2; exit 2; }
case "$NAME" in
  */*|*..*) echo "dev-screenshot: a plain file name only, no paths" >&2; exit 2 ;;
  *.png) ;;
  *) echo "dev-screenshot: the name must end in .png" >&2; exit 2 ;;
esac
case "$WHICH" in
  release) PKG_RE='mCurrentFocus.*com\.postsaimanager/'; APP_RE='mFocusedApp=.* com\.postsaimanager/' ;;
  debug)   PKG_RE='mCurrentFocus.*com\.postsaimanager\.debug/'; APP_RE='mFocusedApp=.* com\.postsaimanager\.debug/' ;;
  *) echo "dev-screenshot: the second argument must be release or debug" >&2; exit 2 ;;
esac
# A menu or dropdown opened by our app is a window without a package name ("Pop-up Window"). It counts as
# ours only while our activity is the focused app, so another app's screen is still never captured.
POPUP_RE='mCurrentFocus=Window{[^ ]* u[0-9]* Pop-up Window}'

OUT_DIR="${PAM_SCREENSHOT_DIR:-$HOME/AndroidStudioProjects/PostsAiManager-work/artifacts/store-screens}"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$NAME"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# One device command: the capture runs only if our app holds the focus (or our app's own pop-up menu does).
"$SCRIPT_DIR/dev-adb.sh" exec-out "w=\$(dumpsys window 2>/dev/null); { echo \"\$w\" | grep -q '$PKG_RE' || { echo \"\$w\" | grep -qi '$POPUP_RE' && echo \"\$w\" | grep -q '$APP_RE'; }; } && screencap -p" > "$TMP" || true

if [ -s "$TMP" ]; then
  mv "$TMP" "$OUT"
  trap - EXIT
  echo "dev-screenshot: saved $OUT"
else
  echo "dev-screenshot: NOT captured: our app ($WHICH) is not in front" >&2
  exit 3
fi
