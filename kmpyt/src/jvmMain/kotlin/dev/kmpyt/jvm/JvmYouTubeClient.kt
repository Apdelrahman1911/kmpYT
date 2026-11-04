package dev.kmpyt.jvm

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.extraction.KtorResearchTransport
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.internal.FileDownloadCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.HttpTimeout

class JvmYouTubeDownloader(private val httpClient: HttpClient,
    private val ffmpegPath: String? = System.getenv("KMPYT_FFMPEG"),
    private val allowLocalHttp: Boolean = false) : YouTubeDownloader {
    override suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult {
        val processor = JvmMediaProcessor(ffmpegPath)
        processor.preflight(plan)
        return FileDownloadCoordinator(processor, httpClient, "Desktop", allowLocalHttp)
            .download(plan, destinationPath, onProgress)
    }
}

/** Node/Chromium and optional FFmpeg are supplied by the host; no executables are bundled. */
fun createJvmYouTubeClient(ffmpegPath: String? = System.getenv("KMPYT_FFMPEG")): YouTubeClient {
    val http = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        install(ContentEncoding) { gzip(); deflate() }
    }
    val runtime = DesktopYouTubeJavaScriptRuntime()
    return YouTubeClient(KtorResearchTransport(http, maximumBytes = 8_000_000), runtime, JvmYouTubeDownloader(http, ffmpegPath),
        onClose = { try { runtime.close() } finally { http.close() } }, platformName = "JVM")
}
