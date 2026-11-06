# kmpYT

Licensed under the [MIT License](LICENSE). Retain its copyright and permission
notice when redistributing. See [third-party notices and the ownership audit](THIRD_PARTY_NOTICES.md)
for dependency, fixture, runtime and GPLv3 compatibility details.

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

For application-owned runtime provisioning, pass `runtimeOptions = DesktopYouTubeRuntimeOptions(
nodeExecutable = absoluteNodePath, chromiumExecutable = absoluteChromiumPath,
temporaryDirectory = absolutePrivateTempPath)` to `createJvmYouTubeClient`. Explicit executable
paths must already exist and be executable; the private temporary directory must exist and be
writable. Invalid explicit paths fail rather than silently using another installation. The
default factory/constructor and environment lookups remain available. Only child-process
`TMPDIR`/`TMP`/`TEMP` change, matching the Java helper/profile directory; no application-wide
environment or JVM property is modified. Chromium's sandbox and offline network restrictions
remain required; these options do not add a no-sandbox mode or install runtime dependencies.

Inspect `report.media` to present formats. When a response supplies HLS/DASH catalogues, call `expandFormats(report)` before selecting their tracks; this fetches manifest metadata only. `playbackPlan(report, selection)` provides portable progressive/SABR source contracts. `search`, `channel`, `playlist`, `more` and `captions` expose browsing and subtitle data. Swift callers use `try await` for suspending operations and handle exported errors with `do`/`catch`.

Search submits its **complete query verbatim**, including surrounding whitespace, up to 16,384
well-formed UTF-16 code units. Oversized/invalid queries and titles are rejected, not shortened.
`YouTubeBrowsePage.query` records that submitted query; `firstEntry` records the leading content
entry **before** unavailable/unsupported filtering in the UI-oriented `items` list. Check its
typed `issue` and nullable kind/identity; never substitute `items.first()` when the first result
matters. Private videos, unknown cards, advertisements and unrecognized shelves cannot silently
promote a later video. A navigable album/playlist/channel shelf is a collection result, not one
of its child tracks. Channels/playlists may be browsed, but are not single-media playback or
download representations. Browse receipts preserve complete titles and stable public identities;
they do not certify playback authorization or recording/album equivalence.

### Owned native SABR playback resources

SABR source URLs are POST protocol endpoints, not ordinary MP4/M4A URLs. For an actual
selected static SABR `SourcePlaybackRequest`, native client factories expose
`supportsSabrPlayback` and `suspend openPlayback(request, authorization)`. The caller must
provide its real `SourcePlaybackAuthorization`; no implicit authorization/integrity issuer
is installed. The existing `YouTubeSabrStreamingSession` is the sole media ingestion engine.

```kotlin
val resource = youtube.openPlayback(selectedSabrRequest, applicationAuthorization)
try {
    // Give the native decoder the exact endpoint for the selected AUDIO/VIDEO role.
    // Never pass it the original SABR endpoint or persist/log the capability URL.
    nativePlayer.prepare(resource.endpoints)
    // Keep resource alive throughout playback, seeks and native callbacks.
} finally {
    nativePlayer.release() // Stop native access before releasing its private cache.
    resource.close()
}
```

The owned endpoint binds only numeric IPv4 loopback, uses a random 256-bit capability route,
and supports exact GET/HEAD/single byte ranges with no-store responses. It serves only verified
cached coverage, not sparse-file length, and rechecks the caller policy on actual media requests.
It has bounded headers, connections, chunks and timeouts. `YouTubeNativePlaybackLimits` defaults
to a 1-GiB static cache budget and four connections per resource; the client permits at most
eight active/opening resources. Seeking waits for whole-track ingestion to reach the requested
bytes; there is no invented live window or server-time-seek protocol. Loopback remains **remote
source playback**, not an offline download.

`resource.close()` cancels/joins owned readers and ingestion, closes listeners and removes its
private cache. Client close also disposes any remaining resources before its transport. Unknown
native release stays a failure on repeated close, never a successful deletion authorization.
Custom hosts can inject `createJvmYouTubeSabrPlaybackAdapter`,
`createAndroidYouTubeSabrPlaybackAdapter` or `createIosYouTubeSabrPlaybackAdapter`; they retain
ownership of the HTTP client passed to those adapter factories. Mobile hosts must explicitly
permit only the required local native transport according to their platform security policy.

The 2026-11-06 focused JVM run passed 103 tests with explicit FFmpeg, including real loopback
POST-to-range byte integrity and authored AAC decode/seek to a null sink. Android/iOS adapters
and the Android resume mirror are authored, not yet platform-compiled/executed for this change.
These tests do not establish authorized live YouTube playback, audible output or app completion.

Retry a cancelled or interrupted download with the same selection and destination to resume
validated private progress. JVM/Android schema-2 checkpoints additionally permit **standalone
progressive** signing-URL refresh only when the selected media/format, exact origin/path/content
query, strong quoted ETag, actual redirected response scope and committed-prefix SHA-256 still
match. Uncommitted tails are truncated; retained complete parts are revalidated too. Query
order, duplicate/empty pairs and raw encoding remain identity-bearing. Only documented exact
lowercase signing keys on Googlevideo (or explicit authored loopback tests) are excluded.
Changed/unverifiable identity restarts; legacy schema-1 checkpoints require the exact old plan.
SABR and multi-resource plans retain their existing exact-plan/generation bindings rather than
borrowing this progressive identity rule. iOS has a separate checkpoint implementation; these
new JVM/Android prefix/scope guarantees are not a claim of iOS parity.

JVM/Android checkpoint roots must remain caller-private. Symlinks, special files and oversized
trees fail before reset/cleanup. After success, media/checkpoint data is purged **while holding
the OS lock**; the empty reusable `.<destination-name>.kmpyt/lock` inode and its parent remain.
Do not unlink/recreate that lock while any process may reuse the destination: doing so can let
two processes lock different inodes for one path. A host may remove a retired private generation
only under a stronger external lifetime fence proving that no current or future writer can
reuse it. The host owns background scheduling and persistent job state. Checkpoints contain
hashes/receipts, not signed URLs or an application library database.

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

The [2026-11-05 live run](https://github.com/Apdelrahman1911/kmpYT/actions/runs/41980615183) reached the approved channel, but YouTube again returned `AUTHORIZATION_REQUIRED` and no formats for the selected video. No live media was downloaded. Controlled transfer tests and this upstream access failure are reported separately; a passing offline suite does not establish live YouTube availability.

YouTube's public interfaces can change or refuse resources. A listed format is not a guarantee of server access, device codec support or uninterrupted playback. Account login and bypassing protected playback are not provided. Native playback UIs remain the consuming application's responsibility.

## Source provenance

No code, binaries or assets from the original SnapTube APK are included. Client-profile facts are documented against the upstream yt-dlp version in source; executable extractors and media tools are not bundled. Dependency licensing follows the corresponding published packages.
