#!/usr/bin/env bash
# Source only inside a build subprocess. Prefer ci/scoped.sh or ci/gradle.sh
# so the caller's HOME and existing gh authentication remain unchanged.

_kmpyt_ci_prepare_environment() {
    local root python native_root native_home native_tmp native_prefs native_daemon
    root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)" || return 1
    if command -v python3 >/dev/null 2>&1; then
        python=python3
    elif command -v python >/dev/null 2>&1; then
        python=python
    else
        printf '%s\n' 'kmpYT requires an existing Python 3 installation; no global install will be attempted.' >&2
        return 1
    fi

    # Validate every destination before mkdir: a pre-existing escaping symlink
    # must not turn a project-local-looking path into an out-of-scope write.
    "$python" -B - "$root" <<'PY'
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve(strict=True)
names = (
    'home', 'tmp', 'gradle', 'konan', 'kotlin-daemon', 'cache', 'config',
    'data', 'state', 'runtime', 'android/user', 'android/avd',
    'android/emulator', 'preferences/user', 'preferences/system',
    'xcode/DerivedData', 'xcode/results', 'xcode/module-cache',
)
paths = [root / '.work' / name for name in names]
for path in paths:
    resolved = path.resolve(strict=False)
    if os.path.commonpath((str(root), str(resolved))) != str(root):
        raise SystemExit('Refusing an out-of-repository tool directory: ' + str(path))
old_mask = os.umask(0o077)
try:
    for path in paths:
        path.mkdir(parents=True, exist_ok=True)
    if os.name != 'nt':
        (root / '.work' / 'runtime').chmod(0o700)
finally:
    os.umask(old_mask)
PY
    if [ "$?" -ne 0 ]; then return 1; fi

    native_root="$root"
    if command -v cygpath >/dev/null 2>&1; then
        native_root="$(cygpath -m "$root")" || return 1
    fi
    native_home="$native_root/.work/home"
    native_tmp="$native_root/.work/tmp"
    native_prefs="$native_root/.work/preferences"
    native_daemon="$native_root/.work/kotlin-daemon"

    export KMPYT_CI_PROJECT_ROOT="$root"
    export KMPYT_CI_WORK_ROOT="$root/.work"
    export KMPYT_CI_SCOPED_ENV=1
    export HOME="$native_home"
    export USERPROFILE="$native_home"
    export GRADLE_USER_HOME="$native_root/.work/gradle"
    export KONAN_DATA_DIR="$native_root/.work/konan"
    export TMPDIR="$native_tmp" TMP="$native_tmp" TEMP="$native_tmp"
    export XDG_CACHE_HOME="$native_root/.work/cache"
    export XDG_CONFIG_HOME="$native_root/.work/config"
    export XDG_DATA_HOME="$native_root/.work/data"
    export XDG_STATE_HOME="$native_root/.work/state"
    export XDG_RUNTIME_DIR="$native_root/.work/runtime"
    export APPDATA="$native_root/.work/config"
    export LOCALAPPDATA="$native_root/.work/data"
    export CFFIXED_USER_HOME="$native_home"
    export ANDROID_USER_HOME="$native_root/.work/android/user"
    export ANDROID_AVD_HOME="$native_root/.work/android/avd"
    export ANDROID_EMULATOR_HOME="$native_root/.work/android/emulator"
    # AGP rejects simultaneous deprecated/new preference overrides even when
    # both are repository-owned. ANDROID_HOME is the read-only SDK, not prefs.
    unset ANDROID_PREFS_ROOT ANDROID_SDK_HOME
    export CLANG_MODULE_CACHE_PATH="$native_root/.work/xcode/module-cache"
    export SWIFT_MODULECACHE_PATH="$native_root/.work/xcode/module-cache"
    export PYTHONDONTWRITEBYTECODE=1
    export PYTHONPYCACHEPREFIX="$native_root/.work/cache/python"
    export npm_config_cache="$native_root/.work/cache/npm"
    export npm_config_userconfig="$native_root/.work/config/npmrc"
    export npm_config_prefix="$native_root/.work/data/npm"
    export PIP_CACHE_DIR="$native_root/.work/cache/pip"
    export XAUTHORITY="$native_root/.work/runtime/Xauthority"
    export GIT_CONFIG_NOSYSTEM=1
    export GIT_CONFIG_GLOBAL="$native_root/.work/config/gitconfig"
    export GIT_TERMINAL_PROMPT=0

    # kmpYT checks that Java's real temporary directory equals Node's
    # os.tmpdir(). Keep ALL four settings identical, including on Windows.
    # Do not carry arbitrary inherited Java options (or secrets) into logs.
    unset _JAVA_OPTIONS JDK_JAVA_OPTIONS
    export JAVA_TOOL_OPTIONS="-XX:-UsePerfData -Duser.home=\"$native_home\" -Djava.io.tmpdir=\"$native_tmp\" -Djava.util.prefs.userRoot=\"$native_prefs/user\" -Djava.util.prefs.systemRoot=\"$native_prefs/system\" -Dkotlin.daemon.runFilesPath=\"$native_daemon\" -Djna.tmpdir=\"$native_tmp\""
    export JAVA_OPTS=""
    export GRADLE_OPTS="-Dorg.gradle.java.installations.auto-download=false"

    # Installed SDK/JDK locations remain read-only; no SDK manager, package
    # manager, gh login, global git config, or download runs from this script.
    return 0
}

_kmpyt_ci_prepare_environment
_kmpyt_ci_env_status=$?
unset -f _kmpyt_ci_prepare_environment
if [ "$_kmpyt_ci_env_status" -ne 0 ]; then
    return "$_kmpyt_ci_env_status" 2>/dev/null || exit "$_kmpyt_ci_env_status"
fi
unset _kmpyt_ci_env_status
