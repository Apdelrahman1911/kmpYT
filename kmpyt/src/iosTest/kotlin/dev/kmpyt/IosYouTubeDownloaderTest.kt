@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*
import kotlin.test.*

class IosYouTubeDownloaderTest {
    @Test fun downloadsExactAvcAndAacThenAvFoundationExportsBothTracks() = iosTest {
        inIosTestDirectory { directory ->
            val video = IosAuthoredMedia.video
            val audio = IosAuthoredMedia.audio
            val requests = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                requests += request.url.encodedPath
                val bytes = if (request.url.encodedPath == "/video") video else audio
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                    HttpHeaders.ContentType to listOf("video/mp4")))
            }) { followRedirects = false }
            try {
                val updates = mutableListOf<YouTubeDownloadProgress>()
                val output = "$directory/combined.mp4"
                val result = IosYouTubeDownloader(http).download(plan(video.size.toLong(), audio.size.toLong()), output) { updates += it }
                assertEquals(output, result.destinationPath)
                assertTrue(result.bytesWritten > 0)
                assertEquals("video/mp4", result.mimeType)
                assertEquals(listOf("/video", "/audio"), requests)
                assertEquals(YouTubeDownloadProgress(0, (video.size + audio.size).toLong()), updates.first())
                assertEquals((video.size + audio.size).toLong(), updates.last().bytesDownloaded)
                val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(output), options = null)
                assertEquals(1, asset.tracksWithMediaType(AVMediaTypeVideo).size)
                assertEquals(1, asset.tracksWithMediaType(AVMediaTypeAudio).size)
                assertTrue(CMTimeGetSeconds(asset.duration) in 0.5..1.0)
                assertEquals(listOf("combined.mp4"), children(directory))
            } finally { http.close() }
        }
    }

    @Test fun nativeFileSupportsSparseOffsetsAbove512MiBAndChecksOverflow() = iosTest {
        inIosTestDirectory { directory ->
            val file = IosMediaFile("$directory/sparse")
            try {
                val offset = 512L * 1024 * 1024 + 17
                file.write(offset, byteArrayOf(3, 7))
                assertEquals(offset + 2, file.size())
                assertContentEquals(byteArrayOf(3, 7), file.read(offset, 2))
                assertFailsWith<IllegalArgumentException> { file.write(Long.MAX_VALUE, byteArrayOf(1)) }
                assertFailsWith<IllegalArgumentException> { checkedIosSum(listOf(Long.MAX_VALUE, 1)) }
                assertFailsWith<IosMediaStorageException> { requireIosStorage(directory, Long.MAX_VALUE) }
            } finally { file.close() }
        }
    }

    @Test fun truncatedResponseAndCancellationDoNotPublishOrLeavePrivateFiles() = iosTest {
        inIosTestDirectory { directory ->
            val bytes = IosAuthoredMedia.video
            val http = HttpClient(MockEngine {
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (bytes.size + 10).toString()))
            }) { followRedirects = false }
            try {
                assertFailsWith<IllegalStateException> {
                    IosYouTubeDownloader(http).download(plan(bytes.size.toLong(), null), "$directory/truncated.mp4") {}
                }
                assertTrue(children(directory).isEmpty())
            } finally { http.close() }
            val cancellingHttp = HttpClient(MockEngine {
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
            }) { followRedirects = false }
            try {
                assertFailsWith<CancellationException> {
                    IosYouTubeDownloader(cancellingHttp).download(plan(bytes.size.toLong(), null), "$directory/cancelled.mp4") {
                        if (it.bytesDownloaded > 0) throw CancellationException("test cancellation")
                    }
                }
                assertTrue(children(directory).isEmpty())
            } finally { cancellingHttp.close() }
        }
    }

    @Test fun refusesUnsupportedMuxBeforeRequestAndDoesNotOverwriteCallerFile() = iosTest {
        inIosTestDirectory { directory ->
            var requests = 0
            val http = HttpClient(MockEngine { requests++; respond("unexpected") })
            try {
                val chosen = plan(12, 14)
                assertFailsWith<IllegalStateException> {
                    IosYouTubeDownloader(http).download(chosen.copy(primary = chosen.primary.copy(codecs = "vp09", mimeType = "video/webm")),
                        "$directory/unsupported.mp4") {}
                }
                val caller = IosMediaFile("$directory/existing.mp4")
                caller.write(0, byteArrayOf(8, 9)); caller.close()
                assertFailsWith<IllegalArgumentException> {
                    IosYouTubeDownloader(http).download(chosen, "$directory/existing.mp4") {}
                }
                assertEquals(0, requests)
                assertEquals(listOf("existing.mp4"), children(directory))
                assertEquals(2L, (NSFileManager.defaultManager.attributesOfItemAtPath("$directory/existing.mp4", null)?.get(NSFileSize) as NSNumber).longLongValue)
            } finally { http.close() }
        }
    }

    private fun plan(videoBytes: Long, audioBytes: Long?): PlannedDownload = PlannedDownload(
        primary = Representation("136", "https://fixture.invalid/video", TrackRole.VIDEO, mimeType = "video/mp4",
            codecs = "avc1.42c00a", sizeBytes = videoBytes, width = 32, height = 32),
        audio = audioBytes?.let { Representation("140", "https://fixture.invalid/audio", TrackRole.AUDIO,
            mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = it) },
        inspectionUrl = "https://fixture.invalid/watch", mediaId = "AUTHORED001")

    private fun children(directory: String): List<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
        .orEmpty().map { it as String }.sorted()
}
