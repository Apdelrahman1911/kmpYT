# CI tools and test environments

The cross-platform workflow runs offline regression fixtures on standard public
GitHub-hosted runners. The manual live workflow is limited to the configured
approved channel. Keep real media, credentials, and signed URLs out of CI logs
and artifacts.

- `ci/scoped.sh` puts Gradle, Konan, language-tool caches, Android preferences,
  and Java/Node scratch space under `.work`. It checks for paths escaping the
  checkout before it creates them.
- JVM jobs use the installed JDK 17, Node.js 22 or later, Chromium, and pinned or
  installed FFmpeg. See [FFmpeg in CI](FFMPEG.md).
- Android jobs install the requested emulator and image into a job-owned SDK,
  then run instrumentation on API 29 or 35. The installed SDK used to compile
  the library stays read-only. Each job owns its ADB server and AVD. If KVM
  access needs an adjustment, the job restores the original device ownership.
- Apple jobs use a disposable runner's CoreSimulator service and create, boot,
  and delete only their own simulator. Both Kotlin/Native tests and the UIKit
  WebKit harness run on that device. The UIKit host uses the installed matching
  iOS 26.2 SDK and runtime. XcodeGen is checksum-pinned under the job workspace. The Apple Silicon job assembles the
  release XCFramework for all three Apple targets.
- `ci/native-boundary.sh` refuses to start these hosted device services on a
  local machine or a shared runner. Developers can use their existing Android
  and Xcode tools to run the Gradle tasks on their own devices.

Zero test results and failed preflights fail the relevant hosted check. Hosted
results describe the emulator or simulator and do not establish physical-device
behavior or continued availability of an external media source.
