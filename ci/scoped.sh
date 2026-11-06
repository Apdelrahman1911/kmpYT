#!/usr/bin/env bash
# Run a build/test command with project-owned homes, caches and temp files.
# No arguments opens an isolated shell without global startup scripts.
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
cd "$KMPYT_CI_PROJECT_ROOT"
if [ "$#" -eq 0 ]; then
    exec bash --noprofile --norc
fi
exec "$@"
