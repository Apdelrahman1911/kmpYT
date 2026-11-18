#!/usr/bin/env bash
# Checksum-pinned wrapper, no global cache or automatic SDK/toolchain installs.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$KMPYT_CI_PROJECT_ROOT"
if [ "${GITHUB_ACTIONS:-}" = true ]; then
    bootstrap="$KMPYT_CI_WORK_ROOT/gradle/bootstrap.log"
    if ! bash ./gradlew --version > "$bootstrap" 2>&1; then
        cat "$bootstrap" >&2
        # Retry a transient distribution download without rerunning build tasks
        # or accepting a wrapper checksum failure.
        grep -q '^Downloading https://' "$bootstrap" &&
            grep -Eq 'SocketException|SocketTimeoutException|HTTP response code: (429|5[0-9]{2})' "$bootstrap" || exit 1
        bash ./gradlew --version
    fi
fi
exec bash ./gradlew --no-daemon --max-workers=1 --no-build-cache \
    '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
    -Pandroid.builder.sdkDownload=false -Porg.gradle.java.installations.auto-download=false \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
