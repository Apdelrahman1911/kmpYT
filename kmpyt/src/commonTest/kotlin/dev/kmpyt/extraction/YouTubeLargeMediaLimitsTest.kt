package dev.kmpyt.extraction

import dev.kmpyt.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Large declared offsets use sparse in-memory witnesses; these tests never allocate huge media files. */
class YouTubeLargeMediaLimitsTest {
    private val format = SabrFormatId(137, 1uL, "controlled")
    private val audioFormat = SabrFormatId(140, 1uL, "controlled")
    private val clientInfo = SabrClientInfo(1, "controlled-test")
    private val source = YouTubeSabrSource("ABCDEFGHIJK", "http://localhost/sabr", byteArrayOf(1, 2, 3),
        listOf(YouTubeSabrSourceFormat(format, TrackRole.VIDEO, "video/mp4", 640, 360, null, null, null)), null)

    private class SparseCache(initialLength: Long = 0) : YouTubeSabrPlaybackCache {
        var length = initialLength
        var closed = false
        val writes = linkedMapOf<Long, ByteArray>()
        override suspend fun size() = length
        override suspend fun read(offset: Long, count: Int): ByteArray {
            require(!closed && offset >= 0 && count >= 0 && count <= Long.MAX_VALUE - offset)
            val entry = writes.entries.single { (start, bytes) -> offset >= start && offset - start <= bytes.size && count <= bytes.size - (offset - start) }
            val start = (offset - entry.key).toInt()
            return entry.value.copyOfRange(start, start + count)
        }
        override suspend fun write(offset: Long, bytes: ByteArray) {
            require(!closed && offset >= 0 && bytes.size <= Long.MAX_VALUE - offset)
            writes[offset] = bytes.copyOf()
            length = maxOf(length, offset + bytes.size)
        }
        override suspend fun close() { closed = true }
    }

    private fun checkpoint(expected: Long, covered: Long) = SabrTransferCheckpoint(source.videoId, null, format, clientInfo,
        listOf(SabrTransferTrackCheckpoint(TrackRole.VIDEO, format, expected,
            listOf(SabrTransferByteRange(0, covered)), listOf(SabrTransferSegment(0, covered, 0, 0, 1000, false)))),
        nextRequestNumber = 2, completedRounds = 1,
        resourceGeneration = YouTubeIntegrityBinding.forSabr(source, clientInfo).resourceGeneration)

    private fun packet(offset: Long, payload: ByteArray): ByteArray {
        fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, source.videoId.encodeToByteArray()),
            number(6, offset), number(8, 0), number(9, 1), number(11, 1000), number(12, 1000),
            binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, payload.size.toLong())))
        return YouTubeUmpFraming.encodePart(20, header) + YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + payload) +
            YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
    }

    private fun request(bytes: Long): SourcePlaybackRequest {
        val video = Representation("video", source.endpoint, TrackRole.VIDEO, TransportKind.SABR,
            "video/mp4", sizeBytes = bytes, sabr = YouTubeSabrResource(source.videoId, source.endpoint, "AQID", format, clientInfo))
        return SourcePlaybackRequest(SourcePlaybackMetadata(source.videoId, "Large source", MediaKind.VIDEO),
            PlannedDownload(video, inspectionUrl = "http://localhost/capture", mediaId = source.videoId))
    }

    @Test fun defaultMediaLimitsUseTheFullLongRangeWhileChunkBoundsAndCustomBudgetsRemain() {
        assertEquals(Long.MAX_VALUE, SabrTransferLimits().maxOutputBytes)
        assertEquals(Long.MAX_VALUE, SabrStreamingLimits().maxBytes)
        assertEquals(1234L, SabrTransferLimits(maxOutputBytes = 1234).maxOutputBytes)
        assertEquals(1234L, SabrStreamingLimits(maxBytes = 1234).maxBytes)
        assertFailsWith<IllegalArgumentException> { SabrTransferLimits(maxOutputBytes = 0) }
        assertFailsWith<IllegalArgumentException> { SabrStreamingLimits(maxBytes = -1) }
        assertFailsWith<IllegalArgumentException> { SabrStreamingLimits(maxReadBytes = Int.MAX_VALUE) }
    }

    @Test fun completedLargeCheckpointsResumeWithoutABytePolicyCapOrAnotherHttpRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; error("A complete checkpoint needs no HTTP request") })
        val transfer = YouTubeSabrTransfer(client)
        try {
            for (bytes in listOf(768L * 1024 * 1024, 2L * 1024 * 1024 * 1024 * 1024)) {
                val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to bytes), mapOf(TrackRole.VIDEO to SparseCache(bytes)), {}, checkpoint(bytes, bytes))
                assertEquals(bytes, result.outputBytesByRole[TrackRole.VIDEO])
            }
            assertEquals(0, calls)
        } finally { transfer.close(); client.close() }
    }

    @Test fun resumedSabrTailAtTheLargestRepresentableOffsetCommitsWithoutOverflow() = runTest {
        val tail = byteArrayOf(1, 2, 3, 4, 5)
        val offset = Long.MAX_VALUE - tail.size
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; respond(packet(offset, tail), headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
        } }
        val cache = SparseCache(offset)
        val transfer = YouTubeSabrTransfer(client)
        val progress = mutableListOf<Long>()
        try {
            val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to Long.MAX_VALUE), mapOf(TrackRole.VIDEO to cache), {}, checkpoint(Long.MAX_VALUE, offset),
                onProgress = { progress += it.bytesWritten })
            assertEquals(1, calls)
            assertEquals(Long.MAX_VALUE, result.outputBytesByRole[TrackRole.VIDEO])
            assertEquals(Long.MAX_VALUE, cache.length)
            assertContentEquals(tail, cache.writes.getValue(offset))
            assertTrue(progress.all { it >= 0 })
            assertEquals(Long.MAX_VALUE, progress.last())
        } finally { transfer.close(); client.close() }
    }

    @Test fun sabrRejectsOverflowingTrackTotalsAndExplicitBudgetExcessBeforeHttp() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; error("Invalid media sizes must fail before HTTP") })
        val custom = YouTubeSabrTransfer(client, SabrTransferLimits(maxOutputBytes = 100))
        val unlimited = YouTubeSabrTransfer(client)
        val withAudio = YouTubeSabrSource(source.videoId, source.endpoint, source.ustreamerConfig,
            source.formats + YouTubeSabrSourceFormat(audioFormat, TrackRole.AUDIO, "audio/mp4", null, null, null, null, true), null)
        try {
            assertFailsWith<IllegalArgumentException> {
                custom.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 101L), mapOf(TrackRole.VIDEO to SparseCache()), {})
            }
            assertFailsWith<IllegalArgumentException> {
                unlimited.download(withAudio, YouTubeSabrSelection(audioFormat, format), clientInfo,
                    mapOf(format to Long.MAX_VALUE, audioFormat to 1L),
                    mapOf(TrackRole.VIDEO to SparseCache(), TrackRole.AUDIO to SparseCache()), {})
            }
            assertEquals(0, calls)
        } finally { custom.close(); unlimited.close(); client.close() }
    }

    @Test fun sourcePlaybackReadsVerifiedSparseBytesAboveOneTiBWithoutHugeAllocation() = runTest {
        val offset = 2L * 1024 * 1024 * 1024 * 1024
        val payload = byteArrayOf(7, 8, 9)
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (++calls == 1) respond(packet(offset, payload), headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                else awaitCancellation()
            }
        } }
        val cache = SparseCache()
        val session = YouTubeSabrStreamingSession(client, request(Long.MAX_VALUE), mapOf(TrackRole.VIDEO to cache), this,
            SourcePlaybackAuthorization { _, _ -> })
        try {
            session.start()
            val state = session.state.first { it.bytesWritten == 3L || it.status == SabrStreamingStatus.FAILED }
            assertEquals(3L, state.bytesWritten, "Only received bytes count as playback coverage")
            assertEquals(Long.MAX_VALUE, state.totalBytes)
            assertEquals(offset + payload.size, cache.length)
            assertContentEquals(payload, session.read(TrackRole.VIDEO, offset, payload.size))
            assertFailsWith<IllegalArgumentException> { session.read(TrackRole.VIDEO, offset, session.limits.maxReadBytes + 1) }
        } finally { session.close(); client.close() }
        assertTrue(cache.closed)
    }

    @Test fun streamingAnExplicitlyBudgetedRequestStillChecksTheDeclaredWholeTrack() = runTest {
        val client = HttpClient(MockEngine { error("An over-budget session must not start HTTP") })
        try {
            assertFailsWith<IllegalArgumentException> {
                YouTubeSabrStreamingSession(client, request(101), mapOf(TrackRole.VIDEO to SparseCache()), this,
                    SourcePlaybackAuthorization { _, _ -> }, limits = SabrStreamingLimits(maxBytes = 100))
            }
        } finally { client.close() }
    }
}
