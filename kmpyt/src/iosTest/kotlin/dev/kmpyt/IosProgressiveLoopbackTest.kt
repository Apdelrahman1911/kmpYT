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
            assertEquals("bytes=0-999", ranges.first())
            assertTrue(ranges.contains("bytes=2500-2999"))
            // Every request is an exact bounded window, each authorized; re-reads come from memory.
            assertTrue(ranges.all { r -> Regex("bytes=(\\d+)-(\\d+)").matchEntire(r!!)!!.destructured.let { (a, b) -> b.toLong() - a.toLong() < 1000 } })
            assertEquals(ranges.size, authorized.size)
            assertContentEquals(bytes.copyOfRange(100, 300), source.read(TrackRole.AUDIO, 100, 200))
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

    @Test fun aValidatedRedirectTargetIsReusedForLaterWindows() = iosTest {
        val hosts = mutableListOf<String>()
        // Like the real iOS client: redirects reach the source, which validates each destination.
        val http = HttpClient(MockEngine { call ->
            hosts += call.url.host
            if (call.url.host == "r1---fixture.googlevideo.com") respond(ByteArray(0), HttpStatusCode.Found,
                Headers.build { append(HttpHeaders.Location, "https://r2---fixture.googlevideo.com/videoplayback?id=authored") })
            else {
                val (first, last) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(call.headers[HttpHeaders.Range]!!)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
                respond(bytes.copyOfRange(first, last + 1), HttpStatusCode.PartialContent, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mp4"); append(HttpHeaders.ContentRange, "bytes $first-$last/${bytes.size}")
                })
            }
        }) { followRedirects = false }
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, _ -> }, windowBytes = 1000, cachedWindows = 1)
        try {
            assertContentEquals(bytes.copyOfRange(0, 10), source.read(TrackRole.AUDIO, 0, 10))
            assertContentEquals(bytes.copyOfRange(2000, 2010), source.read(TrackRole.AUDIO, 2000, 10))
            assertEquals(1, hosts.count { it == "r1---fixture.googlevideo.com" })
        } finally { source.close(); http.close() }
    }
}
