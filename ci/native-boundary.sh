#!/usr/bin/env bash
# A failed required check, NOT a skip or a successful native test. No bypass.
set -euo pipefail
case "${1:-}" in
    android) printf '%s\n' 'BLOCKED: native Android harness needs verified owned AVD/image state, private adb ownership and scratch containment. No emulator/adb/udev/sysctl operation started.' >&2 ;;
    ios) printf '%s\n' 'BLOCKED: simctl --set/HOME do not prove launchd-managed CoreSimulatorService containment or Xcode/Kotlin discovery of an owned device set. No simulator operation started.' >&2 ;;
    *) printf '%s\n' 'Expected android or ios.' >&2; exit 2 ;;
esac
printf '%s\n' 'Required native tests remain unexecuted; this job must fail. See ci/BOUNDARY.md. Replace this gate only with an independently reviewed containment harness; do not bypass branch protections.' >&2
exit 78
