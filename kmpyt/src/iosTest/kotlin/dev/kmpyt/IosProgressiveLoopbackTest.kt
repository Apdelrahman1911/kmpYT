package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*

/** Authored bytes and MockEngine only. */
class IosProgressiveLoopbackTest {
    private val bytes = ByteArray(3000) { (it % 251).toByte() }
    private val url = "https://r1---fixture.googlevideo.com/videoplayback?id=authored"
    private fun request() = SourcePlaybackRequest(
        SourcePlaybackMetadata("AUTHORED001", "Authored", MediaKind.AUDIO, 1_000, null, null),
        PlannedDownload(Representation("140", url, TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2",
            sizeBytes = bytes.size.toLong()), inspectionUrl = "https://www.youtube.com/watch?v=AUTHORED001", mediaId = "AUTHORED001"))

    // Prefetched windows run concurrently with the player reads and the assertions.
    private class RequestLog<T> {
        private val lock = Mutex()
        private val entries = mutableListOf<T>()

        suspend fun record(value: T) = lock.withLock { entries.add(value) }

        suspend fun snapshot(): List<T> = lock.withLock { entries.toList() }
    }

    private fun engine(ranges: RequestLog<String?>, status: HttpStatusCode = HttpStatusCode.PartialContent) = MockEngine { call ->
        val range = call.headers[HttpHeaders.Range]
        ranges.record(range)
        val (first, last) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range!!)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
        respond(if (status == HttpStatusCode.PartialContent) bytes.copyOfRange(first, last + 1) else ByteArray(0), status, Headers.build {
            append(HttpHeaders.ContentType, "audio/mp4")
            if (status == HttpStatusCode.PartialContent) append(HttpHeaders.ContentRange, "bytes $first-$last/${bytes.size}")
        })
    }

    @Test fun readsAreServedFromExactBoundedRangeWindows() = iosTest {
        val ranges = RequestLog<String?>()
        val http = HttpClient(engine(ranges))
        val authorized = RequestLog<String>()
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, resource -> authorized.record(resource) }, windowBytes = 1000)
        try {
            assertContentEquals(bytes.copyOfRange(0, 400), source.read(TrackRole.AUDIO, 0, 400))
            assertContentEquals(bytes.copyOfRange(400, 900), source.read(TrackRole.AUDIO, 400, 500))
            assertContentEquals(bytes.copyOfRange(2500, 3000), source.read(TrackRole.AUDIO, 2500, 800))
            // Await the remaining prefetched window before comparing the two logs.
            assertContentEquals(bytes.copyOfRange(1000, 1001), source.read(TrackRole.AUDIO, 1000, 1))
            val requested = ranges.snapshot()
            assertEquals("bytes=0-999", requested.first())
            assertTrue(requested.contains("bytes=2000-2999"))
            assertTrue(requested.all { r -> Regex("bytes=(\\d+)-(\\d+)").matchEntire(r!!)!!.destructured.let { (a, b) -> b.toLong() - a.toLong() < 1000 } })
            assertEquals(requested.size, authorized.snapshot().size)
            assertContentEquals(bytes.copyOfRange(100, 300), source.read(TrackRole.AUDIO, 100, 200))
            assertEquals(requested, ranges.snapshot(), "a cached read should not request the range again")
            assertNull(source.failureCode)
        } finally { source.close(); http.close() }
    }

    @Test fun readsAcrossWindowBoundariesNeverRefetchOverlappingBytes() = iosTest {
        // AVPlayer's reads straddle window boundaries; each window must be fetched exactly once.
        val ranges = RequestLog<String?>()
        val http = HttpClient(engine(ranges))
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, _ -> }, windowBytes = 1000)
        try {
            val first = source.read(TrackRole.AUDIO, 900, 300)
            assertContentEquals(bytes.copyOfRange(900, 1000), first) // short read up to the boundary
            assertContentEquals(bytes.copyOfRange(1000, 1200), source.read(TrackRole.AUDIO, 1000, 200))
            assertContentEquals(bytes.copyOfRange(2950, 3000), source.read(TrackRole.AUDIO, 2950, 400))
            assertContentEquals(bytes.copyOfRange(10, 20), source.read(TrackRole.AUDIO, 10, 10))
            val requested = ranges.snapshot()
            assertEquals(setOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2999"), requested.toSet())
            assertEquals(requested.size, requested.distinct().size, "a window was fetched twice: $requested")
        } finally { source.close(); http.close() }
    }

    @Test fun aFragmentedObjectIsIndexedFromItsFirstWindowAndAnythingElseIsNot() = iosTest {
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        fun box(type: String, vararg body: ByteArray) = body.fold(ByteArray(0)) { a, b -> a + b }.let { u32(8L + it.size) + type.encodeToByteArray() + it }
        val head = box("ftyp", "dash".encodeToByteArray(), u32(0)) + box("moov", box("mvex", box("trex", ByteArray(24)))) +
            box("sidx", u32(0), u32(1), u32(1000), u32(0), u32(0), byteArrayOf(0, 0, 0, 2), u32(1200), u32(5000), u32(0), u32(800), u32(5000), u32(0))
        val fragmented = head + ByteArray(2000) { 7 }
        val ranges = RequestLog<String?>()
        val http = HttpClient(MockEngine { call ->
            val range = call.headers[HttpHeaders.Range]; ranges.record(range)
            val (first, last) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range!!)!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
            respond(fragmented.copyOfRange(first, last + 1), HttpStatusCode.PartialContent, Headers.build {
                append(HttpHeaders.ContentType, "audio/mp4"); append(HttpHeaders.ContentRange, "bytes $first-$last/${fragmented.size}")
            })
        })
        val sized = request().let { it.copy(plan = it.plan.copy(primary = it.plan.primary.copy(sizeBytes = fragmented.size.toLong()))) }
        val source = ProgressiveRangePlaybackBytes(http, sized, SourcePlaybackAuthorization { _, _ -> }, windowBytes = 1000)
        try {
            source.start()
            val index = assertNotNull(source.segmentIndex)
            assertEquals(listOf(head.size.toLong(), head.size + 1200L), index.segments.map { it.offset })
            assertEquals(listOf<String?>("bytes=0-999"), ranges.snapshot()) // Only the first window, which the player reads first anyway.
            assertContentEquals(fragmented.copyOfRange(0, 100), source.read(TrackRole.AUDIO, 0, 100))
            assertEquals(1, ranges.snapshot().count { it == "bytes=0-999" })
        } finally { source.close(); http.close() }

        val plainHttp = HttpClient(engine(RequestLog()))
        val plain = ProgressiveRangePlaybackBytes(plainHttp, request(), SourcePlaybackAuthorization { _, _ -> }, windowBytes = 1000)
        try { plain.start(); assertNull(plain.segmentIndex) } finally { plain.close(); plainHttp.close() }
    }

    @Test fun aRefusedRangeFailsClosedWithAStableCode() = iosTest {
        val http = HttpClient(engine(RequestLog(), HttpStatusCode.Forbidden))
        val source = ProgressiveRangePlaybackBytes(http, request(), SourcePlaybackAuthorization { _, _ -> })
        try {
            assertFails { source.read(TrackRole.AUDIO, 0, 100) }
            assertEquals("PROGRESSIVE_RESOURCE_NOT_AUTHORIZED", source.failureCode)
        } finally { source.close(); http.close() }
    }

    @Test fun aValidatedRedirectTargetIsReusedForLaterWindows() = iosTest {
        val hosts = RequestLog<String>()
        // Like the real iOS client: redirects reach the source, which validates each destination.
        val http = HttpClient(MockEngine { call ->
            hosts.record(call.url.host)
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
            assertEquals(1, hosts.snapshot().count { it == "r1---fixture.googlevideo.com" })
        } finally { source.close(); http.close() }
    }
}
