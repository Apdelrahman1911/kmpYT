# kmpYT

[![Cross-platform CI](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.apdelrahman1911.kmpyt/kmpyt)](https://central.sonatype.com/artifact/io.github.apdelrahman1911.kmpyt/kmpyt)
[![MIT License](https://img.shields.io/github/license/Apdelrahman1911/kmpYT)](LICENSE)

kmpYT is a Kotlin Multiplatform library for integrating public YouTube content
into Android, desktop JVM and iOS apps. It inspects media, selects formats, and
builds playback and download plans. It also provides search, channel and playlist
browsing, and caption cues. Your app supplies its own media player, UI, and storage
and access policies.

## Supported platforms

| Platform | Maven variant | Runtime requirements |
| --- | --- | --- |
| Android | `kmpyt-android` | API 23 or later, Android WebView, Internet permission. Compatible audio/video track combinations use platform media APIs. |
| Desktop JVM | `kmpyt-jvm` | Java 17, Node.js 22 or later, and Chrome, Chromium or Edge. Install FFmpeg if you combine separate audio and video streams. CI uses x64 Windows, Linux and macOS. |
| iOS | `kmpyt-iosarm64`, `kmpyt-iossimulatorarm64`, `kmpyt-iosx64` | UIKit, WKWebView and AVFoundation. Build with macOS and Xcode. The CI host declares iOS 15 and runs on the installed simulator runtimes. |

Desktop CI runs the real browser and FFmpeg regression tests. Hosted mobile
checks use Android API 29 and 35 and Apple Silicon and Intel iOS simulators.
See the [CI results](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/ci.yml)
for the tested revision and actual results. Hosted checks leave physical-device
behavior and arbitrary live-source availability unverified.

## Install

Use `google()` and `mavenCentral()` for dependency resolution. Use the root
multiplatform artifact in `commonMain`. Gradle selects the target variant.
The library is built with Kotlin 2.2.21 and targets JVM 17.

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.apdelrahman1911.kmpyt:kmpyt:0.1.2")
    }
}
```

The current release is `0.1.2`. See the [version on Maven Central](https://central.sonatype.com/artifact/io.github.apdelrahman1911.kmpyt/kmpyt/0.1.2)
for coordinates and dependencies.

## Quick start

Use the factory for your target.

| Target | Import and invocation |
| --- | --- |
| Android | `dev.kmpyt.android.createAndroidYouTubeClient(context)` |
| Desktop JVM | `dev.kmpyt.jvm.createJvmYouTubeClient()` |
| iOS | `dev.kmpyt.createIosYouTubeClient()` |

This JVM example prepares and saves a compatible selection. Pass an absolute
destination in app-owned storage and a URL you have permission to download.

```kotlin
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.jvm.createJvmYouTubeClient

suspend fun saveVideo(
    videoUrl: String,
    destinationPath: String,
    ffmpegPath: String?,
): YouTubeDownloadResult {
    val client = createJvmYouTubeClient(ffmpegPath = ffmpegPath)
    try {
        val report = client.inspect(videoUrl)
        val selection = client.select(report, maxHeight = 720)
            ?: error("No supported format is available")
        val plan = client.plan(report, selection)
        return client.download(plan, destinationPath) { progress ->
            println("${progress.bytesDownloaded} bytes downloaded")
        }
    } finally {
        client.close()
    }
}
```

Reuse a client across operations to share its bounded player-program cache.
Close it when its owner ends, outside progress callbacks. Let coroutine
cancellation propagate. Handle unavailable formats and network failures at
the app boundary. Never log signed media URLs.

On desktop, set `KMPYT_NODE`, `KMPYT_CHROMIUM`, or `KMPYT_FFMPEG` if the
executables are outside their usual locations. An app that manages its own
runtime can pass explicit executables and a private temporary directory through
`DesktopYouTubeRuntimeOptions`.

## API guide

| Task | API |
| --- | --- |
| Inspect a video or audio-only catalogue | `inspect(url)`, `inspectAudio(url)` |
| Load available manifest tracks and select quality | `expandFormats(report)`, then `select(report, maxHeight = 720)` |
| Prepare a progressive source for a player | `preparePlayback(report, selection)` waits for the source's declared availability |
| Browse or fetch caption cues | `search`, `channel`, `playlist`, `more`, `captions` |
| Save media or retry an expired selection | `plan`, `download`, `refresh`. Retry the same selection and destination to reuse compatible checkpoint progress. |

Keep transfer-state directories private. Retry with the same destination to
reuse checked progress, and keep an app-owned record if jobs must survive
app restarts. For source selection, standalone audio, native playback resources
and their cleanup rules, see the [integration notes](docs/integration.md).

Media combining requires compatible tracks. The library does not transcode.
Live or encrypted manifest downloads and nested multivariant manifests are
outside the supported download scope. iOS segmented transfers are limited to
static fragmented MP4.

## Build and contribute

Use JDK 17 and the checked-in Gradle wrapper. Android source builds need SDK 36.
Linking Apple frameworks and running simulator checks require macOS and Xcode.

```sh
./gradlew :kmpyt:jvmTest
./gradlew :kmpyt:assembleRelease
# macOS with Xcode
./gradlew :kmpyt:assembleKmpYTReleaseXCFramework
```

- [GitHub releases](https://github.com/Apdelrahman1911/kmpYT/releases) and [Maven Central](https://central.sonatype.com/artifact/io.github.apdelrahman1911.kmpyt/kmpyt)
- [CI workflow](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/ci.yml) and [tag-based release workflow](https://github.com/Apdelrahman1911/kmpYT/actions/workflows/release.yml)
- [Issue tracker](https://github.com/Apdelrahman1911/kmpYT/issues). Include the library version, target, runtime versions and a reproducible case. Redact private URLs, credentials and media before sharing.

**Maintainer:** Abdelrahman Fahmy. kmpYT is licensed under the [MIT License](LICENSE).
The project originated in ClearMedia’s independently implemented YouTube engine.
See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency and source
attribution.
