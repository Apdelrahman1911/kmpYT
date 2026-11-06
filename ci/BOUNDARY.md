# Scoped CI and remaining required gates

This hygiene change removes known unauthorized system setup and artifact/cache uploads. It does **not** claim successful native verification, weaken branch protections, or turn unexecuted tests green.

## Scope

- Literal standard public-repository runners only: Ubuntu24.04, Windows2022, macOS15 Intel. No paid/larger runners, artifact uploads or remote caches.
- Branch updates with an open PR run the complete matrix through `pull_request` only, not a duplicate `push` matrix. Push CI is restricted to `main`; all PRs and manual dispatch still run every existing job/status name with the same failure gates. This conserves free runner capacity without skipping required PR tests, overriding protected checks or reclassifying native failures.
- `ci/scoped.sh` confines homes, temp, Gradle/Konan, Android, XDG, Java preferences, JNA and language-tool caches to `.work`, rejecting escaping symlinks before creating directories. Java/Node temporary directories agree.
- Existing JDK17, Node22+, Chromium, SDK and Xcode are read-only inputs. An existing FFmpeg is preferred. When absent, `ci/ffmpeg-tool.py` builds official pinned FFmpeg8.0.1 source on Linux/macOS using existing C/make tools, or extracts the SHA256-pinned Gyan8.0.1 essentials Windows test tool. All downloads, build products, provenance and complete upstream notices stay under `.work/tools/ffmpeg-ci`; no global installation occurs. See `ci/FFMPEG.md`. Missing prerequisites and failed real-media preflights fail, never silently skip. No apt/brew/choco, setup caches, SDK auto-download, sysctl/udev, xcode-select, global Git configuration, sandbox weakening or unrelated cleanup.
- The POSIX Chromium wrapper redirects shared-memory scratch to the scoped temp directory and disables system password/keychain integration for the isolated test profile. It does not add `--no-sandbox`, remove the existing network-denial policy or change AppArmor. Browser/platform behavior still needs actual runner verification; scoped settings are not a claim of completed runtime testing.
- SHA-pinned checkout5.0.1 disables persisted credentials, global safe-directory configuration and submodule helper use. Its transient checkout credential stays repository-local. Upgrades require another credential-path audit.
- GitHub necessarily provisions the runner, retrieves action code, creates step/control files and records logs before disposing of the machine. These are service-controlled orchestration, **not permission for our commands** to write system/global files. The explicitly requested job-summary file is reporting only. No blanket exception applies to host services started by our tools.

## Private-by-default JavaScript startup diagnostics

The selected Node executable comes from `process.execPath`, not a Windows PATH
shim. CI records only the actual Node/browser basenames, byte lengths, SHA256s
and validated Node version. It does **not** launch a GUI browser with `--version`
(which is not a reliable read-only operation on Windows), dump the environment,
or print absolute tool paths. These identities describe existing tools, not a
new browser version pin or native containment proof.

JUnit summaries count a fixed allowlist of authored `OFFLINE_*` stage codes from
failed testcases. Raw exception bodies, Chromium stderr, URLs, source variables
and unknown codes remain private. A 30-second test failure interval alone does
not establish its cause; the actual safe code and subsequent executed tests
are needed. No timeout, browser sandbox or offline-network policy is relaxed.

## Honest failed native preflights

Original JVM test/JAR, API29/35 instrumentation+AAR/lint, native iOS tests, UIKit-hosted WebKit tests and release XCFramework intent remains visible in the workflow. No continue-on-error, skip-ci, synthetic native success or protected-check override is added. Independent compile/package steps provide only that narrower evidence.

`ci/native-boundary.sh` returns nonzero **before native runtime launch** until an independently reviewed harness replaces the unsafe defaults. This is a failed required gate, not a passing replacement check or an opt-in boolean bypass.

Android still needs an audited owned AVD/image launcher, private adb server/port, owned process cleanup and proven backing for hard-coded scratch paths. Existing KVM permissions may be inspected but no udev/sysctl change is allowed. API29/35 tests remain unexecuted until verified.

For Apple, `simctl --set <repo>` scopes a device set but does not prove launchd-managed CoreSimulatorService log/cache containment. Changing HOME/CFFIXED_USER_HOME in one process does not establish the service's inherited state. Kotlin and Xcode destination discovery also must use the same owned set. No simulator operation starts before the gate. Supported containment evidence or a separately owner-authorized boundary change is needed.

## License-only publication

The lead inspected the public repository on 2026-11-06: main protection was absent, required-check enforcement was off/empty and no rulesets existed. Those settings were **not changed**. Recheck if repository policy changes; never assume a protected check is exempt or manufacture its status. The independent license/provenance job verifies the audited nonbehavioral grant. Remaining failed native containment checks do not prove a Kotlin source regression and must be disclosed, not called green or used as product completion evidence.
