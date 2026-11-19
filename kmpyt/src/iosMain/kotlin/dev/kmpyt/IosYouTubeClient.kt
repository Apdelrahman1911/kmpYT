package dev.kmpyt

import dev.kmpyt.extraction.KtorResearchTransport
import dev.kmpyt.extraction.ResearchTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/** Owns its Darwin client and offline WebKit sessions; call the returned client's suspend close(). */
@Throws(Exception::class)
fun createIosYouTubeClient(): YouTubeClient = createIosYouTubeClient(null)

@Throws(Exception::class)
fun createIosYouTubeClient(observer: ((IosDownloadStageEvent) -> Unit)?): YouTubeClient =
    createIosYouTubeClient(observer, null)

@Throws(Exception::class)
fun createIosYouTubeClient(
    observer: ((IosDownloadStageEvent) -> Unit)?,
    researchObserver: ((IosResearchEvent) -> Unit)?,
    diagnosticsEnabled: () -> Boolean = { true },
): YouTubeClient {
    val http = HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        engine {
            // Anonymous like every other platform: never send or keep YouTube cookies. A stored
            // preference cookie otherwise overrides the neutral request language (translated titles).
            configureSession {
                setHTTPCookieStorage(null)
                setHTTPShouldSetCookies(false)
                setHTTPCookieAcceptPolicy(platform.Foundation.NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever)
                setURLCache(null)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 45_000
        }
    }
    return YouTubeClient(
        transport = observeIosResearch(KtorResearchTransport(http, maximumBytes = 8_000_000), researchObserver, diagnosticsEnabled),
        playerRuntime = IosYouTubeJavaScriptRuntime(),
        downloader = IosYouTubeDownloader(http, false, observer),
        onClose = { http.close() },
        platformName = "iOS",
        playbackAdapter = createIosYouTubeSabrPlaybackAdapter(http),
        programStore = IosYouTubePlayerProgramStore(iosPlayerProgramDirectory()),
    )
}

/** The application's private Caches subfolder for exact player programs. */
private fun iosPlayerProgramDirectory(): String {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: NSTemporaryDirectory()
    return caches.trimEnd('/') + "/kmpyt-player-programs"
}
