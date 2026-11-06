package dev.kmpyt

import dev.kmpyt.extraction.KtorResearchTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

/** Owns its Darwin client and offline WebKit sessions; call the returned client's suspend close(). */
@Throws(Exception::class)
fun createIosYouTubeClient(): YouTubeClient {
    val http = HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 45_000
        }
    }
    return YouTubeClient(
        transport = KtorResearchTransport(http, maximumBytes = 8_000_000),
        playerRuntime = IosYouTubeJavaScriptRuntime(),
        downloader = IosYouTubeDownloader(http),
        onClose = { http.close() },
        platformName = "iOS",
        playbackAdapter = createIosYouTubeSabrPlaybackAdapter(http),
    )
}
