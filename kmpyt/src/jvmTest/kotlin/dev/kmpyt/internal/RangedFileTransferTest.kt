package dev.kmpyt.internal

import dev.kmpyt.extraction.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import dev.kmpyt.testfixtures.startLoopback
import dev.kmpyt.testfixtures.loopbackUrl
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RangedFileTransferTest {
    private val bytes = ByteArray(100) { it.toByte() }

    private fun plan(server: MockWebServer): PlannedDownload {
        val url = server.loopbackUrl("/track.mp4").toString()
        return PlannedDownload(Representation("owned", url, TrackRole.MUXED, mimeType = "video/mp4",
            segments = listOf(SegmentResource(url, 200, bytes.size.toLong()))),
            inspectionUrl = server.loopbackUrl("/owned").toString(), mediaId = "owned")
    }

    private fun response(body: ByteArray = bytes, range: String = "bytes 200-299/1000") =
        MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
            .setHeader("Content-Range", range).setHeader("ETag", "\"owned\"").setBody(Buffer().write(body))

    @Test fun exactRangeSupportsUnknownObjectSizeAndChunkedBody(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-chunked-", ".part")
        try {
            server.enqueue(response(range = "bytes 200-299/*").setChunkedBody(Buffer().write(bytes), 13))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            val result = RangedFileTransfer(true) { }.fetch(part, PartState(part.key), file, plan, { true }) { _, _ -> }
            assertTrue(result.complete)
            assertContentEquals(bytes, file.readBytes())
            assertEquals("bytes=200-299", server.takeRequest().getHeader("Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun contradictoryOrOverflowingContentRangeTotalsFailBeforeWriting(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-total-", ".part")
        try {
            for (range in listOf("bytes 200-299/250", "bytes 200-299/9223372036854775808")) server.enqueue(response(range = range))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            repeat(2) {
                assertFailsWith<PermanentDownloadException> {
                    RangedFileTransfer(true) { }.fetch(part, PartState(part.key), file, plan, { true }) { _, _ -> }
                }
                assertEquals(0L, file.length())
            }
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun compressedDocumentAndContradictoryLengthResponsesFailBeforeWriting(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-framing-", ".part")
        try {
            server.enqueue(response().setHeader("Content-Encoding", "gzip"))
            server.enqueue(response().setHeader("Content-Type", "application/dash+xml"))
            server.enqueue(response().setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            server.enqueue(response().setHeader("Content-Length", 99))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            repeat(4) {
                assertFailsWith<PermanentDownloadException> {
                    RangedFileTransfer(true) { }.fetch(part, PartState(part.key), file, plan, { true }) { _, _ -> }
                }
                assertEquals(0L, file.length())
            }
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun resumedRangePreservesExactBytesAndSendsStrongValidator(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-resume-", ".part")
        try {
            file.writeBytes(bytes.copyOf(40))
            server.enqueue(response(bytes.copyOfRange(40, 100), "bytes 240-299/1000"))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            val result = RangedFileTransfer(true) { }.fetch(part, PartState(part.key, 40, 100, "\"owned\""), file, plan, { true }) { _, _ -> }
            assertTrue(result.complete)
            assertContentEquals(bytes, file.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=240-299", request.getHeader("Range"))
            assertEquals("\"owned\"", request.getHeader("If-Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun interruptedRangeRetainsObjectSizeAndRejectsAContradictionOnResume(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-object-size-", ".part")
        val active = AtomicBoolean(true)
        var saved: PartState? = null
        try {
            server.enqueue(response().throttleBody(20, 250, TimeUnit.MILLISECONDS))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            assertFailsWith<StaleDownloadException> {
                RangedFileTransfer(true) { }.fetch(part, PartState(part.key), file, plan, { active.get() }) { state, _ ->
                    saved = state
                    if (state.bytes > 0) active.set(false)
                }
            }
            val checkpoint = requireNotNull(saved)
            assertEquals(1000L, checkpoint.objectTotalBytes)
            val prefix = file.readBytes()
            assertTrue(prefix.size in 1 until bytes.size)
            server.enqueue(response(bytes.copyOfRange(prefix.size, 100), "bytes ${200 + prefix.size}-299/500"))
            assertFailsWith<PermanentDownloadException> {
                RangedFileTransfer(true) { }.fetch(part, checkpoint, file, plan, { true }) { _, _ -> }
            }
            assertContentEquals(prefix, file.readBytes())
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun rangeResumePreservesObjectSizeWhenTheNewHeaderOmitsIt(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-unknown-object-", ".part")
        try {
            file.writeBytes(bytes.copyOf(40))
            server.enqueue(response(bytes.copyOfRange(40, 100), "bytes 240-299/*"))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            val result = RangedFileTransfer(true) { }.fetch(part,
                PartState(part.key, 40, 100, "\"owned\"", objectTotalBytes = 1000), file, plan, { true }) { _, _ -> }
            assertContentEquals(bytes, file.readBytes())
            assertEquals(100L, result.total)
            assertEquals(1000L, result.objectTotalBytes)
            assertTrue(result.complete)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun changedValidatorCannotAppendDifferentRepresentation(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-changed-", ".part")
        try {
            file.writeBytes(bytes.copyOf(40))
            server.enqueue(response(bytes.copyOfRange(40, 100), "bytes 240-299/1000").setHeader("ETag", "\"changed\""))
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            assertFailsWith<PermanentDownloadException> {
                RangedFileTransfer(true) { }.fetch(part, PartState(part.key, 40, 100, "\"owned\""), file, plan, { true }) { _, _ -> }
            }
            assertContentEquals(bytes.copyOf(40), file.readBytes())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun weakValidatorRestartsExactRangeWithoutIfRange(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-weak-", ".part")
        try {
            file.writeBytes(ByteArray(40) { 99 })
            server.enqueue(response())
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            val result = RangedFileTransfer(true) { }.fetch(part, PartState(part.key, 40, 100, "W/\"owned\""), file, plan, { true }) { _, _ -> }
            assertTrue(result.complete)
            assertContentEquals(bytes, file.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=200-299", request.getHeader("Range"))
            assertNull(request.getHeader("If-Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun fullPartWithoutCompletedCheckpointRestartsInsteadOfSendingAnEmptyRange(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-uncommitted-", ".part")
        try {
            file.writeBytes(bytes)
            server.enqueue(response())
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            val result = RangedFileTransfer(true) { }.fetch(part,
                PartState(part.key, 100, 100, "\"owned\"", complete = false), file, plan, { true }) { _, _ -> }
            assertTrue(result.complete)
            assertContentEquals(bytes, file.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=200-299", request.getHeader("Range"))
            assertNull(request.getHeader("If-Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun supersededDuringRegistrationCannotStartRangedRequest(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-range-cancel-", ".part")
        val active = AtomicBoolean(true)
        var cleared = false
        try {
            server.enqueue(response())
            server.startLoopback()
            val plan = plan(server)
            val part = TransferPlanning.parts(plan).single()
            assertFailsWith<StaleDownloadException> {
                RangedFileTransfer(true) {
                    if (it == null) cleared = true else active.set(false)
                }.fetch(part, PartState(part.key), file, plan, { active.get() }) { _, _ -> }
            }
            assertTrue(cleared)
            assertEquals(0, server.requestCount)
            assertEquals(0L, file.length())
        } finally { server.shutdown(); file.delete() }
    }
}
