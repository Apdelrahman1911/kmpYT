package dev.kmpyt.internal



import dev.kmpyt.extraction.*

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback HTTP and file transfers; no internet services or mock UI completion. */

class HttpDownloadTransferTest {
    private val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }

    private fun partial(): File = File.createTempFile("kmpyt-transfer-", ".part")

    private fun task(server: MockWebServer): FileTransferRequest { val url = server.url("/media.mp4").toString(); return FileTransferRequest(url, PlannedDownload(Representation("owned", url, TrackRole.MUXED, mimeType = "video/mp4"), inspectionUrl = server.url("/page").toString(), mediaId = "owned")) }

    private fun response(body: ByteArray, status: Int = 200, range: String? = null, etag: String = "\"a\"") =
        MockResponse().setResponseCode(status).setBody(Buffer().write(body))
            .setHeader("Content-Type", "video/mp4").setHeader("ETag", etag)
            .apply { range?.let { setHeader("Content-Range", it) } }

    @Test fun resumesBeyond512MiBWithoutAllocatingTheWholeMediaInMemory() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        val offset = 600L * 1024 * 1024
        val tail = ByteArray(64) { it.toByte() }
        val total = offset + tail.size
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(offset) }
            server.enqueue(response(tail, 206, "bytes $offset-${total - 1}/$total"))
            server.start()
            val result = HttpDownloadTransfer(true).fetch(task(server).copy(etag = "\"a\"", totalBytes = total), file, { true }) { }
            assertEquals(total, result.bytes)
            assertEquals(total, file.length())
            val actual = ByteArray(tail.size)
            java.io.RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(actual) }
            assertArrayEquals(tail, actual)
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun downloadPersistsExactBytesAndProgress() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            server.enqueue(response(bytes))
            server.start()
            val progress = mutableListOf<TransferProgress>()
            val result = HttpDownloadTransfer(true).fetch(task(server), file, { true }) { progress += it }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), result.bytes)
            assertEquals(bytes.size.toLong(), progress.last().bytes)
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun pauseAndResumeUsesRangeAndIfRangeWithoutDuplicateBytes() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        val active = AtomicBoolean(true)
        var last: TransferProgress? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                return if (offset == 0) response(bytes).throttleBody(32 * 1024, 5, TimeUnit.MILLISECONDS)
                else response(bytes.copyOfRange(offset, bytes.size), 206, "bytes $offset-${bytes.lastIndex}/${bytes.size}")
            }
        }
        try {
            server.start()
            val initial = task(server)
            try {
                HttpDownloadTransfer(true).fetch(initial, file, { active.get() }) {
                    last = it
                    if (it.bytes > 0) active.set(false)
                }
                error("Transfer should stop when its operation is superseded")
            } catch (_: StaleDownloadException) { }
            assertTrue(file.length() in 1 until bytes.size.toLong())
            val offset = file.length()
            active.set(true)
            HttpDownloadTransfer(true).fetch(initial.copy(etag = last?.etag), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            server.takeRequest()
            val resume = server.takeRequest()
            assertEquals("bytes=$offset-", resume.getHeader("Range"))
            assertEquals("\"a\"", resume.getHeader("If-Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun changedServerFileReturns200AndReplacesExistingPartial() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        val replacement = ByteArray(bytes.size) { ((it + 17) % 251).toByte() }
        try {
            server.enqueue(response(replacement, etag = "\"b\""))
            server.start()
            file.writeBytes(bytes.copyOfRange(0, 100_000))
            HttpDownloadTransfer(true).fetch(task(server).copy(etag = "\"a\""), file, { true }) { }
            assertArrayEquals(replacement, file.readBytes())
            assertEquals(replacement.size.toLong(), file.length())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun truncatedConnectionCanResumeFromActualSavedLength() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        var last: TransferProgress? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == null) return response(bytes).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                val offset = range.removePrefix("bytes=").substringBefore('-').toInt()
                return response(bytes.copyOfRange(offset, bytes.size), 206, "bytes $offset-${bytes.lastIndex}/${bytes.size}")
            }
        }
        try {
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(task(server), file, { true }) { last = it }
                error("A truncated response must not complete")
            } catch (_: java.io.IOException) { }
            assertTrue(file.length() in 1 until bytes.size.toLong())
            HttpDownloadTransfer(true).fetch(task(server).copy(etag = last?.etag), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun wrongContentRangeIsRejectedBeforeAppending() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            val original = file.readBytes()
            server.enqueue(response(bytes.copyOfRange(0, 100), 206, "bytes 0-99/${bytes.size}"))
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(task(server).copy(etag = "\"a\""), file, { true }) { }
                error("A wrong-offset response must fail")
            } catch (_: PermanentDownloadException) { }
            assertArrayEquals(original, file.readBytes())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun unknownRangeTotalPreservesKnownSizeAndCannotCompleteASubset() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        val progress = mutableListOf<TransferProgress>()
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            server.enqueue(response(bytes.copyOfRange(100, 200), 206, "bytes 100-199/*"))
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(
                    task(server).copy(etag = "\"a\"", totalBytes = bytes.size.toLong()),
                    file, { true },
                ) { progress += it }
                error("An incomplete range must not complete the known larger file")
            } catch (_: RetryableDownloadException) { }
            assertArrayEquals(bytes.copyOfRange(0, 200), file.readBytes())
            assertEquals(bytes.size.toLong(), progress.last().total)
            assertEquals(200L, progress.last().bytes)
            assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun unknownRangeTotalWithoutKnownSizeRestartsAsAFullDownload() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            server.enqueue(response(bytes.copyOfRange(100, 200), 206, "bytes 100-199/*"))
            server.enqueue(response(bytes))
            server.start()
            val result = HttpDownloadTransfer(true).fetch(task(server).copy(etag = "\"a\""), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), result.total)
            assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))
            val full = server.takeRequest()
            assertEquals(null, full.getHeader("Range"))
            assertEquals(null, full.getHeader("If-Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun repeatedUnknownRangeTotalsCannotBecomeACompletedFile() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            server.enqueue(response(bytes.copyOfRange(100, 200), 206, "bytes 100-199/*"))
            server.enqueue(response(bytes.copyOfRange(0, 200), 206, "bytes 0-199/*"))
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(task(server).copy(etag = "\"a\""), file, { true }) { }
                error("Repeated ambiguous partial responses must fail")
            } catch (_: PermanentDownloadException) { }
            assertEquals(0L, file.length())
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun rangeNotSatisfiableCannotCompleteAShorterThanKnownPartial() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            server.enqueue(MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "bytes */100").setHeader("ETag", "\"a\""))
            server.enqueue(response(bytes))
            server.start()
            val result = HttpDownloadTransfer(true).fetch(
                task(server).copy(etag = "\"a\"", totalBytes = bytes.size.toLong()),
                file, { true },
            ) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), result.total)
            assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))
            assertEquals(null, server.takeRequest().getHeader("Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun contradictoryExplicitRangeTotalRestartsWithoutMixingFileVersions() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        val replacement = ByteArray(300) { ((it + 17) % 251).toByte() }
        try {
            file.writeBytes(bytes.copyOfRange(0, 100))
            server.enqueue(response(replacement.copyOfRange(100, 300), 206, "bytes 100-299/300"))
            server.enqueue(response(replacement))
            server.start()
            val result = HttpDownloadTransfer(true).fetch(
                task(server).copy(etag = "\"a\"", totalBytes = bytes.size.toLong()),
                file, { true },
            ) { }
            assertArrayEquals(replacement, file.readBytes())
            assertEquals(replacement.size.toLong(), result.total)
            assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))
            assertEquals(null, server.takeRequest().getHeader("Range"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun productionTransportRejectsCleartextBeforeMakingRequest() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            server.start()
            try {
                HttpDownloadTransfer(false).fetch(task(server), file, { true }) { }
                error("Release transport must require HTTPS")
            } catch (_: PermanentDownloadException) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun anonymousRedirectCanTransferActualBytesFromAnotherOrigin() = runBlocking {
        val server = MockWebServer(); val target = MockWebServer(); val file = partial()
        try {
            server.start(); target.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",target.url("/cdn/media.mp4")))
            target.enqueue(response(bytes))
            val outcome = HttpDownloadTransfer(true,requestHeaders=mapOf("X-Owned-Header" to "source-only-test"))
                .fetch(task(server),file,{true}) { }
            assertArrayEquals(bytes,file.readBytes())
            assertEquals(bytes.size.toLong(),outcome.bytes)
            assertEquals(1,server.requestCount)
            assertEquals(1,target.requestCount)
            assertEquals("source-only-test",server.takeRequest().getHeader("X-Owned-Header"))
            assertEquals(null,target.takeRequest().getHeader("X-Owned-Header"))
        } finally { server.shutdown(); target.shutdown(); file.delete() }
    }

    @Test fun protectedSessionHeadersCannotFollowAnActualCrossOriginRedirect() = runBlocking {
        val server = MockWebServer(); val target = MockWebServer(); val file = partial()
        val header = "Bearer controlled-session-test"
        try {
            server.start(); target.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",target.url("/cdn/media.mp4")))
            target.enqueue(response(bytes))
            try {
                HttpDownloadTransfer(true,requestHeaders=mapOf("Authorization" to header),sessionOrigin=server.url("/").toString())
                    .fetch(task(server),file,{true}) { }
                error("Protected session headers must remain on their stored origin")
            } catch (_: IllegalArgumentException) { }
            assertEquals(header,server.takeRequest().getHeader("Authorization"))
            assertEquals(0,target.requestCount)
            assertEquals(0L,file.length())
        } finally { server.shutdown(); target.shutdown(); file.delete() }
    }

    @Test fun nonMediaResponseCannotBecomeACompletedMediaFile() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody("not media"))
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(task(server), file, { true }) { }
                error("A nonmedia response must fail")
            } catch (_: PermanentDownloadException) { }
            assertEquals(0L, file.length())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun emptyResponseCannotBeReportedAsSuccessfulMedia() = runBlocking {
        val server = MockWebServer()
        val file = partial()
        try {
            server.enqueue(response(byteArrayOf()))
            server.start()
            try {
                HttpDownloadTransfer(true).fetch(task(server), file, { true }) { }
                error("Empty media must fail")
            } catch (_: PermanentDownloadException) { }
            assertEquals(0L, file.length())
        } finally { server.shutdown(); file.delete() }
    }
}
