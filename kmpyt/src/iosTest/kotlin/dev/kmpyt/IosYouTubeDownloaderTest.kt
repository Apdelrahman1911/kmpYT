@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.*
import kotlin.test.*

class IosYouTubeDownloaderTest {
    @Test fun passthroughMuxPreservesAuthoredRelativeAudioDelay() = iosTest {
        inIosTestDirectory { directory ->
            suspend fun store(name: String, bytes: ByteArray): String {
                val file = IosMediaFile("$directory/$name")
                try { file.write(0, bytes); file.flush() } finally { file.close() }
                return file.path
            }
            val video = store("video.mp4", IosAuthoredMedia.video)
            val audio = store("audio.m4a", IosAuthoredMedia.delayedAudio)
            fun start(path: String, type: String): Double {
                val track = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), options = null)
                    .tracksWithMediaType(type).first() as AVAssetTrack
                return CMTimeGetSeconds(track.timeRange.useContents { start.readValue() })
            }
            val originalGap = start(audio, requireNotNull(AVMediaTypeAudio)) - start(video, requireNotNull(AVMediaTypeVideo))
            assertTrue(originalGap > 0.15, "The authored fixture must contain a real audio timeline delay: $originalGap")
            val output = "$directory/combined.mp4"
            IosMediaMuxer.mux(video, TrackRole.VIDEO, audio, output)
            val actualGap = start(output, requireNotNull(AVMediaTypeAudio)) - start(output, requireNotNull(AVMediaTypeVideo))
            assertEquals(originalGap, actualGap, 0.005, "MP4 muxing must retain the relative A/V start offset.")
        }
    }

    @Test fun manifestResponsesAndContradictoryRangeTotalsAreRejectedBeforePublication() = iosTest {
        inIosTestDirectory { directory ->
            for (type in listOf("application/xml", "application/vnd.apple.mpegurl", "audio/x-mpegurl")) {
                val http = HttpClient(MockEngine {
                    respond(IosAuthoredMedia.video, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type))
                }) { followRedirects = false }
                try {
                    assertFailsWith<IllegalStateException> {
                        IosYouTubeDownloader(http).download(plan(IosAuthoredMedia.video.size.toLong(), null), "$directory/rejected.mp4") {}
                    }
                    assertTrue(children(directory).isEmpty())
                } finally { http.close() }
            }
            val bytes = IosAuthoredMedia.video
            val http = HttpClient(MockEngine {
                respond(bytes, HttpStatusCode.PartialContent, headersOf(
                    HttpHeaders.ContentType to listOf("video/mp4"),
                    HttpHeaders.ContentRange to listOf("bytes 0-${bytes.size - 1}/${bytes.size - 1}")))
            }) { followRedirects = false }
            try {
                val selected = plan(bytes.size.toLong(), null)
                val range = SegmentResource(selected.primary.url!!, rangeStart = 0, rangeLength = bytes.size.toLong())
                assertFailsWith<IllegalStateException> {
                    IosYouTubeDownloader(http).download(selected.copy(primary = selected.primary.copy(segments = listOf(range))),
                        "$directory/invalid-range.mp4") {}
                }
                assertTrue(children(directory).isEmpty())
            } finally { http.close() }
        }
    }

    @Test fun actualUmpFramesCommitThroughNativeSabrSinkBeforePublication() = iosTest {
        inIosTestDirectory { directory ->
            val bytes = IosAuthoredMedia.video
            val id = SabrFormatId(136, 1uL, "authored")
            fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
            fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
            val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, "AUTHORED001".encodeToByteArray()),
                number(6, 0), number(8, 0), number(9, 0), number(11, 0), number(12, 600),
                binary(13, YouTubeSabrProtocol.encodeFormatId(id)), number(14, bytes.size.toLong())))
            val response = YouTubeUmpFraming.encodePart(20, header) +
                YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + bytes) + YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Post, request.method)
                respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            }) { followRedirects = false }
            try {
                val original = plan(bytes.size.toLong(), null)
                val source = YouTubeSabrResource(original.mediaId, "https://fixture.invalid/sabr", "AQID", id, SabrClientInfo(1, "authored"))
                val selected = original.copy(primary = original.primary.copy(url = source.endpoint,
                    transport = TransportKind.SABR, sabr = source))
                val updates = mutableListOf<YouTubeDownloadProgress>()
                val result = IosYouTubeDownloader(http).download(selected, "$directory/sabr.mp4") { updates += it }
                assertEquals(1, requests)
                assertEquals(bytes.size.toLong(), result.bytesWritten)
                assertEquals(bytes.size.toLong(), updates.first().totalBytes)
                assertEquals(0L, updates.first().bytesDownloaded)
                val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(result.destinationPath), options = null)
                assertEquals(1, asset.tracksWithMediaType(AVMediaTypeVideo).size)
                assertEquals(listOf("sabr.mp4"), children(directory))
            } finally { http.close() }
        }
    }

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
