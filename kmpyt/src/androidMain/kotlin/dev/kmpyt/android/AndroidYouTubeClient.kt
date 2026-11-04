package dev.kmpyt.android

import android.content.Context
import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.extraction.KtorResearchTransport
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.internal.FileDownloadCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout

/** A caller may inject an existing Ktor client; its owner remains responsible for closing it. */
class AndroidYouTubeDownloader(private val httpClient: HttpClient, private val allowLocalHttp: Boolean = false) : YouTubeDownloader {
    override suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult =
        FileDownloadCoordinator(AndroidMediaProcessor(), httpClient, "Android", allowLocalHttp)
            .download(plan, destinationPath, onProgress)
}

/** Owns an anonymous HTTP client and an offline WebView runtime until YouTubeClient.close(). */
fun createAndroidYouTubeClient(context: Context): YouTubeClient {
    val http = HttpClient(Android) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        engine { connectTimeout = 15_000; socketTimeout = 20_000 }
    }
    return YouTubeClient(KtorResearchTransport(http, maximumBytes = 8_000_000), AndroidYouTubeJavaScriptRuntime(context),
        AndroidYouTubeDownloader(http), onClose = http::close, platformName = "Android")
}
