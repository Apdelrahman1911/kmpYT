#!/usr/bin/env bash
# Checksum-pinned wrapper, no global cache or automatic SDK/toolchain installs.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
cd "$KMPYT_CI_PROJECT_ROOT"
exec bash ./gradlew --no-daemon --max-workers=1 --no-build-cache \
    '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
    -Pandroid.builder.sdkDownload=false -Porg.gradle.java.installations.auto-download=false \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
