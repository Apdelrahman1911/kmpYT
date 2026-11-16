package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*

/** Authored bytes and MockEngine only. */
class IosProgressiveLoopbackTest {
    private val bytes = ByteArray(3000) { (it % 251).toByte() }
    private val url = "https://r1---fixture.googlevideo.com/videoplayback?id=authored"
    private fun request() = SourcePlaybackRequest(
        SourcePlaybackMetadata("AUTHORED001", "Authored", MediaKind.AUDIO, 1_000, null, null),
        PlannedDownload(Representation("140", url, TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2",
            sizeBytes = bytes.size.toLong()), inspectionUrl = "https://www.youtube.com/watch?v=AUTHORED001", mediaId = "AUTHORED001"))

    private fun engine(ranges: MutableList<String?>, status: HttpStatusCode = HttpStatusCode.PartialContent) = MockEngine { call ->
        val range = call.headers[HttpHeaders.Range]
        ranges += range
        val (first, last) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range!!)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
        respond(if (status == HttpStatusCode.PartialContent) bytes.copyOfRange(first, last + 1) else ByteArray(0), status, Headers.build {
            append(HttpHeaders.ContentType, "audio/mp4")
            if (status == HttpStatusCode.PartialContent) append(HttpHeaders.ContentRange, "bytes $first-$last/${bytes.size}")
        })
    }

    @Test fun readsAreServedFromExactBoundedRangeWindows() = iosTest {
        val ranges = mutableListOf<String?>()
        val http = HttpClient(engine(ranges))
        val authorized = mutableListOf<String>()
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, resource -> authorized += resource }, windowBytes = 1000)
        try {
            assertContentEquals(bytes.copyOfRange(0, 400), source.read(TrackRole.AUDIO, 0, 400))
            assertContentEquals(bytes.copyOfRange(400, 900), source.read(TrackRole.AUDIO, 400, 500))
            assertContentEquals(bytes.copyOfRange(2500, 3000), source.read(TrackRole.AUDIO, 2500, 800))
            assertEquals(listOf<String?>("bytes=0-999", "bytes=2500-2999"), ranges)
            assertEquals(2, authorized.size)
            assertNull(source.failureCode)
        } finally { source.close(); http.close() }
    }

    @Test fun aRefusedRangeFailsClosedWithAStableCode() = iosTest {
        val http = HttpClient(engine(mutableListOf(), HttpStatusCode.Forbidden))
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, _ -> })
        try {
            assertFails { source.read(TrackRole.AUDIO, 0, 100) }
            assertEquals("PROGRESSIVE_RESOURCE_NOT_AUTHORIZED", source.failureCode)
        } finally { source.close(); http.close() }
    }
}
