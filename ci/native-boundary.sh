#!/usr/bin/env bash
# Native services may use their normal host directories on disposable, single-job
# GitHub-hosted VMs. This runner is deliberately unavailable on shared hosts.
set -euo pipefail

if [ "${GITHUB_ACTIONS:-}" != true ] || [ "${RUNNER_ENVIRONMENT:-}" != github-hosted ]; then
    printf '%s\n' 'The CI device controller is for disposable GitHub-hosted runners only.' >&2
    exit 78
fi
: "${KMPYT_CI_SCOPED_ENV:?Run through ci/scoped.sh}"

if [ "${1:-}" = android ]; then
    api=${KMPYT_ANDROID_TEST_API:?Set the Android test API}
    [[ "$api" == 29 || "$api" == 35 ]]
    installed_sdk=${ANDROID_HOME:?Android SDK required}
    sdk_tools="$installed_sdk/cmdline-tools/latest/bin"
    emulator_sdk="$KMPYT_CI_WORK_ROOT/android/sdk"
    mkdir -p "$emulator_sdk/licenses"
    cp "$installed_sdk"/licenses/* "$emulator_sdk/licenses/"
    image="system-images;android-$api;google_apis;x86_64"
    "$sdk_tools/sdkmanager" --sdk_root="$emulator_sdk" emulator "$image"
    # avdmanager locates packages from its own SDK, so run the command-line
    # tools from the same scoped SDK as the newly installed system image.
    mkdir -p "$emulator_sdk/cmdline-tools/latest"
    cp -RL "$sdk_tools/.."/* "$emulator_sdk/cmdline-tools/latest/"
    printf 'no\n' | ANDROID_HOME="$emulator_sdk" ANDROID_SDK_ROOT="$emulator_sdk" \
        "$emulator_sdk/cmdline-tools/latest/bin/avdmanager" create avd --name "kmpyt-ci-$api" --package "$image"
    export PATH="$installed_sdk/platform-tools:$PATH"
    # The default ADB socket starts a local server. Supplying a TCP host to
    # ADB_SERVER_SOCKET makes adb connect as a client and refuse to start it.
    unset ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT
    python3 -B - <<'PY'
import socket
with socket.socket() as port:
    port.bind(("127.0.0.1", 5037))
PY
    kvm_owner=''
    if ! [ -r /dev/kvm ] || ! [ -w /dev/kvm ]; then
        kvm_owner=$(stat -c '%u:%g' /dev/kvm)
        sudo chown "$USER" /dev/kvm
    fi
    emulator_pid=''
    emulator_log="$KMPYT_CI_WORK_ROOT/android/emulator-$api.log"
    cleanup() {
        if [ "$?" -ne 0 ] && [ -f "$emulator_log" ]; then tail -80 "$emulator_log" >&2; fi
        if [ -n "$emulator_pid" ]; then
            kill "$emulator_pid" 2>/dev/null || true
            wait "$emulator_pid" 2>/dev/null || true
        fi
        adb kill-server >/dev/null 2>&1 || true
        if [ -n "$kvm_owner" ]; then sudo chown "$kvm_owner" /dev/kvm; fi
    }
    trap cleanup EXIT
    adb start-server
    ANDROID_HOME="$emulator_sdk" ANDROID_SDK_ROOT="$emulator_sdk" "$emulator_sdk/emulator/emulator" "@kmpyt-ci-$api" -port 5554 \
        -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
        > "$emulator_log" 2>&1 &
    emulator_pid=$!
    export ANDROID_SERIAL=emulator-5554
    timeout 180 adb wait-for-device
    for ((attempt=0; attempt<90; attempt++)); do
        if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
        sleep 2
    done
    test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
    test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "$api"
    adb shell input keyevent 82
    ci/gradle.sh :kmpyt:connectedDebugAndroidTest --stacktrace
    python3 -B ci/summary.py --require-executed
elif [ "${1:-}" = ios ]; then
    case "$(uname -m)" in
        arm64) target=IosSimulatorArm64; framework_target=iosSimulatorArm64 ;;
        x86_64) target=IosX64; framework_target=iosX64 ;;
        *) exit 2 ;;
    esac
    device_spec=$(xcrun simctl list -j devices available | python3 -B -c '
import json, sys
runtimes = json.load(sys.stdin)["devices"]
versions = {name: tuple(map(int, name.split(".iOS-")[-1].split("-"))) for name in runtimes if ".iOS-" in name}
for runtime in sorted(versions, key=versions.__getitem__, reverse=True):
    for device in runtimes[runtime]:
        if device["isAvailable"] and device["name"].startswith("iPhone") and device.get("deviceTypeIdentifier"):
            print(device["deviceTypeIdentifier"], runtime)
            sys.exit(0)
raise SystemExit("No installed, available iPhone simulator runtime")
')
    read -r device_type runtime <<< "$device_spec"
    simulator=$(xcrun simctl create "kmpYT CI ${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" "$device_type" "$runtime")
    trap 'xcrun simctl shutdown "$simulator" 2>/dev/null || true; xcrun simctl delete "$simulator"' EXIT
    python3 -B - "$simulator" <<'PY'
import subprocess, sys
subprocess.run(['xcrun', 'simctl', 'bootstatus', sys.argv[1], '-b'], timeout=360, check=True)
PY
    ci/gradle.sh ":kmpyt:linkDebugFramework$target" ":kmpyt:${framework_target}Test" "-PiosSimulatorDevice=$simulator" --stacktrace
    python3 -B ci/summary.py --require-executed
    export KMPYT_FRAMEWORK="$KMPYT_CI_PROJECT_ROOT/kmpyt/build/bin/$framework_target/debugFramework/KmpYT.framework"
    xcodegen generate --spec ci/ios-host/project.yml --project ci/ios-host
    result="$KMPYT_CI_WORK_ROOT/xcode/results/ios-host.xcresult"
    xcodebuild -project ci/ios-host/KmpYTHost.xcodeproj -scheme KmpYTHost -configuration Debug \
        -destination "id=$simulator" -derivedDataPath "$KMPYT_CI_WORK_ROOT/xcode/DerivedData" \
        -resultBundlePath "$result" -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
    xcrun xcresulttool get test-results summary --path "$result" | python3 -B -c '
import json, sys
result = json.load(sys.stdin)
assert result["passedTests"] >= 5 and result["failedTests"] == 0, "UIKit-hosted WebKit tests did not all pass"
print("{} UIKit-hosted WebKit tests passed".format(result["passedTests"]))
'
else
    printf '%s\n' 'Expected android or ios.' >&2
    exit 2
fi
