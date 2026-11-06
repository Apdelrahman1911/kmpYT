#!/usr/bin/env bash
# Source inside ci/scoped.sh; prefer existing read-only tools. Missing FFmpeg
# is prepared reproducibly inside this repository, never installed globally.
set -euo pipefail
: "${KMPYT_CI_SCOPED_ENV:?Use ci/scoped.sh first}"
case "$(uname -m)" in
    arm64|aarch64) candidates=("${JAVA_HOME_17_ARM64:-}" "${JAVA_HOME_17_arm64:-}" "${JAVA_HOME:-}") ;;
    *) candidates=("${JAVA_HOME_17_X64:-}" "${JAVA_HOME:-}") ;;
esac
for candidate in "${candidates[@]}"; do
    [ -n "$candidate" ] || continue
    if command -v cygpath >/dev/null 2>&1; then candidate="$(cygpath -m "$candidate")"; fi
    if [ -x "$candidate/bin/java" ] || [ -f "$candidate/bin/java.exe" ]; then
        export JAVA_HOME="$candidate"
        java_bin="$candidate/bin"
        if command -v cygpath >/dev/null 2>&1; then java_bin="$(cygpath -u "$java_bin")"; fi
        export PATH="$java_bin:$PATH"
        break
    fi
done
version="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')"
[ "$version" = 17 ] || { printf 'Existing JDK17 required; observed %s. No install attempted.\n' "$version" >&2; return 1; }
if [ "$(uname -s)" = Darwin ]; then
    export DEVELOPER_DIR=/Applications/Xcode_26.0.1.app/Contents/Developer
    [ -d "$DEVELOPER_DIR" ] || { printf '%s\n' 'Pinned existing Xcode missing; no xcode-select/install attempted.' >&2; return 1; }
fi
if [ "${1:-}" = jvm ]; then
    # The executable itself, not a possible PATH shim/cmd launcher on Windows.
    export KMPYT_NODE="$(node -p 'process.execPath')"
    node -e 'if (+process.versions.node.split(".")[0] < 22) process.exit(1)'
    KMPYT_FFMPEG="$(command -v ffmpeg || true)"
    if [ -z "$KMPYT_FFMPEG" ]; then
        KMPYT_FFMPEG="$(python3 -B "$KMPYT_CI_PROJECT_ROOT/ci/ffmpeg-tool.py")"
    fi
    export KMPYT_FFMPEG
    [ -f "$KMPYT_FFMPEG" ] || { printf '%s\n' 'FFmpeg preparation failed; skipped media tests are not success.' >&2; return 1; }
    if command -v cygpath >/dev/null 2>&1; then KMPYT_FFMPEG="$(cygpath -m "$KMPYT_FFMPEG")"; fi
    python3 -B "$KMPYT_CI_PROJECT_ROOT/ci/ffmpeg-tool.py" --verify "$KMPYT_FFMPEG"
    KMPYT_CHROMIUM="$(python3 -B - <<'PY'
import os,shutil
from pathlib import Path
paths=[os.environ.get('KMPYT_CHROMIUM')]+[shutil.which(x) for x in ('google-chrome','google-chrome-stable','chromium','chromium-browser','chrome','msedge')]
paths+=['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome','C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']
for value in paths:
    if value and Path(value).is_file():
        print(Path(value).resolve()); break
else:
    raise SystemExit('Existing Chromium/Chrome/Edge required; no install attempted')
PY
)"
    export KMPYT_CHROMIUM
    if command -v cygpath >/dev/null 2>&1; then
        export KMPYT_NODE="$(cygpath -m "$KMPYT_NODE")"
        export KMPYT_FFMPEG="$(cygpath -m "$KMPYT_FFMPEG")"
        export KMPYT_CHROMIUM="$(cygpath -m "$KMPYT_CHROMIUM")"
    fi
    python3 -B "$KMPYT_CI_PROJECT_ROOT/ci/js-tool-provenance.py" \
        --node "$KMPYT_NODE" --node-version "$(node -p 'process.version')" --browser "$KMPYT_CHROMIUM"
    if ! command -v cygpath >/dev/null 2>&1; then
        export KMPYT_CI_BROWSER="$KMPYT_CHROMIUM"
        export KMPYT_CHROMIUM="$KMPYT_CI_PROJECT_ROOT/ci/chromium.sh"
    fi
fi
