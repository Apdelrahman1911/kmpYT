# kmpYT

A standalone Kotlin Multiplatform YouTube library for Android, JVM desktop and iOS, extracted from ClearMedia's independently implemented YouTube engine.

The shared library inspects public videos, discovers formats, searches and browses channels/playlists, loads captions, prepares playback/transfer plans and coordinates compatible video/audio downloads. Platform adapters provide offline JavaScript execution, file I/O and media combining. Application screens, databases, advertisements and account UI are outside the library.

## Targets and runtimes

| Target | Player-program runtime | Media combining |
|---|---|---|
| Android | Ephemeral WebView with network access blocked | Android MediaExtractor/MediaMuxer |
| JVM desktop | Isolated Chromium controlled by Node.js 22 | Caller-installed FFmpeg |
| iOS | Nonpersistent WKWebView with external requests blocked | AVFoundation |

Public player programs are versioned by their actual source URL and reused within a bounded cache. Video metadata and signed resource URLs remain fresh. Available combined transfer sizes are reported before downloading; unknown lengths remain unknown. All three platforms support validated checkpoint resume. There is no fixed 512 MiB library limit.

## Build and integration

Use JDK 17 or newer and the checked-in Gradle wrapper. Android builds require SDK 36; Apple targets require macOS and Xcode. Set the local Android SDK path in `local.properties`, which is not committed.

```shell
./gradlew :kmpyt:jvmTest
./gradlew :kmpyt:assembleRelease
./gradlew :kmpyt:publishToMavenLocal
```

The initial version is `0.1.0`. Artifacts are not published to Maven Central. Build from this repository, use a composite build, or publish locally. macOS can produce `KmpYT.xcframework` with `:kmpyt:assembleKmpYTReleaseXCFramework`.

After publishing locally, add `mavenLocal()` to the consuming build's repositories and use `implementation("io.github.apdelrahman1911.kmpyt:kmpyt:0.1.0")` in `commonMain`. Publish Apple artifacts from macOS. Successful CI jobs also provide the JVM JAR, Android AAR and Apple XCFramework as downloadable build artifacts.

```kotlin
// Android: dev.kmpyt.android.createAndroidYouTubeClient(context)
// iOS: dev.kmpyt.createIosYouTubeClient()
val youtube = dev.kmpyt.jvm.createJvmYouTubeClient(ffmpegPath = "/usr/bin/ffmpeg")
try {
    val report = youtube.inspect(videoUrl)
    val selection = requireNotNull(youtube.select(report, maxHeight = 720))
    val plan = youtube.plan(report, selection)
    youtube.download(plan, destinationPath) { progress ->
        println("${progress.bytesDownloaded} / ${progress.totalBytes ?: "unknown"}")
    }
} finally {
    youtube.close()
}
```

Call these suspending methods from a coroutine. Reuse a client to benefit from its player cache, then close it when its owner ends. Desktop execution requires Node.js 22 and Chrome/Chromium or Edge; set `KMPYT_NODE` and `KMPYT_CHROMIUM` for nonstandard locations. Pass an FFmpeg executable path when combining separate tracks.

Inspect `report.media` to present formats. When a response supplies HLS/DASH catalogues, call `expandFormats(report)` before selecting their tracks; this fetches manifest metadata only. `playbackPlan(report, selection)` provides portable progressive/SABR source contracts. `search`, `channel`, `playlist`, `more` and `captions` expose browsing and subtitle data. Swift callers use `try await` for suspending operations and handle exported errors with `do`/`catch`.

Retry a cancelled or interrupted download with the same selection and destination to resume validated private progress. On iOS, HTTP resume requires a strong response validator; changed or unverifiable resources restart safely. Refreshed signing parameters can retain progress when the selected media, resource scope and validator still match. SABR checkpoints retain byte receipts and start a fresh protocol session; a changed source generation restarts. iOS checkpoints contain hashes and byte counts rather than signed URLs, headers or session tokens. The host app owns background scheduling and must keep the destination directory available.

## Current limitations

Native media combining only accepts compatible codec/container pairs and does not transcode. iOS segmented transfers require static fragmented MP4. Live/encrypted and nested multivariant manifest downloads are unsupported. Android timing around B-frames and AAC priming remains device dependent. The library supplies playback plans and caption cues; the host app provides its player, subtitle controls and account pages.

## Validation

[Cross-platform CI](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/ci.yml) runs shared/JVM tests on Linux, Windows and macOS, Android emulator tests, and iOS simulator/framework checks. [Approved-channel tests](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/live-youtube.yml) are manually triggered and restricted to [@oPiiLz](https://www.youtube.com/@oPiiLz). They upload redacted results rather than downloaded media or signed URLs. The live-test byte budget is a CI resource limit, not a library download limit.

iOS file/protocol tests use the Kotlin simulator runner. WebKit is exercised by unconditional Swift XCTest cases inside the UIKit app in `ci/ios-host/`, linked to the simulator framework. CI generates that project with XcodeGen, publishes its `.xcresult` report, and assembles the release XCFramework after the tests succeed.

Run `./gradlew :kmpyt:connectedDebugAndroidTest` to exercise the native Android runtime, file transfers and media combining on a connected emulator. These tests use authored fixtures and local servers; the live smoke test is skipped by default. To explicitly check one approved-channel video on Android:

```shell
./gradlew :kmpyt:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.kmpyt.android.AndroidApprovedVideoSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.kmpytLiveSmoke=true
```

The smoke test verifies the uploader before transferring media, checks saved audio/video samples and decodes a video frame. Its five-minute/eight-MiB budget applies only to this test. The test application's private `files/kmpyt-live-smoke.json` contains redacted diagnostics; downloaded media is deleted afterward. The 2026-11-05 local Android 15 run stopped at DNS resolution (`TRANSPORT_DNS_FAILURE`), before any media download. Live playback on that emulator remains unverified.

The [2026-11-04 live run](https://github.com/Apdelrahman1911/kmpYT/actions/runs/41902649543) reached the approved channel, but YouTube returned `AUTHORIZATION_REQUIRED` and no formats for its sampled videos. No live media was downloaded. Controlled transfer tests and this upstream access failure are reported separately; a passing offline suite does not establish live YouTube availability.

YouTube's public interfaces can change or refuse resources. A listed format is not a guarantee of server access, device codec support or uninterrupted playback. Account login and bypassing protected playback are not provided. Native playback UIs remain the consuming application's responsibility.

## Source provenance

No code, binaries or assets from the original SnapTube APK are included. Client-profile facts are documented against the upstream yt-dlp version in source; executable extractors and media tools are not bundled. Dependency licensing follows the corresponding published packages.
