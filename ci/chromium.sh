#!/usr/bin/env bash
# Keep POSIX browser scratch/keychain integration scoped without weakening its
# actual renderer sandbox or the library's offline/network-denial policy.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$SCRIPT_DIR/env.sh"
: "${KMPYT_CI_BROWSER:?Existing browser must be selected by ci/select-tools.sh}"
[ -x "$KMPYT_CI_BROWSER" ] || exit 1
args=(--disable-dev-shm-usage --password-store=basic "--crash-dumps-dir=$KMPYT_CI_WORK_ROOT/tmp")
if [ "$(uname -s)" = Darwin ]; then args+=(--use-mock-keychain); fi
exec "$KMPYT_CI_BROWSER" "${args[@]}" "$@"
