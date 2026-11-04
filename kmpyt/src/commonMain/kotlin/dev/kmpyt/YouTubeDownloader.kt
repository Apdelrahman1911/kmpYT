package dev.kmpyt

import dev.kmpyt.extraction.PlannedDownload

data class YouTubeDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
)

data class YouTubeDownloadResult(val destinationPath: String, val bytesWritten: Long, val mimeType: String)

/** Native adapters own streaming, files and muxing; coroutine cancellation must stop the transfer. */
fun interface YouTubeDownloader {
    @Throws(Exception::class)
    suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult
}
