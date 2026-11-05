package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import dev.kmpyt.testfixtures.startLoopback
import dev.kmpyt.testfixtures.loopbackUrl
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

/** Framing failures must never turn a prefix into a completed media file. */
@RunWith(AndroidJUnit4::class)
class HttpResponseIntegrityTest {
    private val bytes = ByteArray(131_072) { (it % 251).toByte() }

    private fun request(server: MockWebServer, expected: Long? = null): FileTransferRequest {
        val url = server.loopbackUrl("/media").toString()
        return FileTransferRequest(url, PlannedDownload(
            Representation("owned", url, TrackRole.MUXED, mimeType = "video/mp4", sizeBytes = expected),
            inspectionUrl = server.loopbackUrl("/owned").toString(), mediaId = "owned",
        ), totalBytes = expected, expectedBytes = expected)
    }

    private fun chunked(body: ByteArray) = MockResponse().setHeader("Content-Type", "video/mp4")
        .setHeader("ETag", "\"owned\"").setChunkedBody(Buffer().write(body), 8192)

    @Test fun shortChunkedBodyRetainsExpectedSizeAndCannotComplete(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-short-chunked-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            server.enqueue(chunked(bytes.copyOf(100)))
            server.startLoopback()
            val progress = mutableListOf<TransferProgress>()
            expectFailure<RetryableDownloadException> {
                HttpDownloadTransfer(true).fetch(request(server, bytes.size.toLong()), file, { true }) { progress += it }
            }
            assertArrayEquals(bytes.copyOf(100), file.readBytes())
            assertEquals(bytes.size.toLong(), progress.first().total)
            assertEquals(bytes.size.toLong(), progress.last().total)
            assertEquals(100L, progress.last().bytes)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun completeChunkedBodySupportsBothKnownAndUnknownSizes(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-valid-chunked-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            server.enqueue(chunked(bytes)); server.enqueue(chunked(bytes))
            server.startLoopback()
            for (expected in listOf(bytes.size.toLong(), null)) {
                file.writeBytes(byteArrayOf())
                val result = HttpDownloadTransfer(true).fetch(request(server, expected), file, { true }) { }
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(bytes.size.toLong(), result.total)
                assertEquals(bytes.size.toLong(), result.bytes)
            }
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun explicitLengthConflictingWithInspectedSizeFailsBeforeWriting(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-wrong-size-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes.copyOf(100))))
            server.startLoopback()
            expectFailure<PermanentDownloadException> {
                HttpDownloadTransfer(true).fetch(request(server, bytes.size.toLong()), file, { true }) { }
            }
            assertEquals(0L, file.length())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun chunkedBodyCannotExceedInspectedSize(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-long-chunked-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            server.enqueue(chunked(bytes))
            server.startLoopback()
            expectFailure<PermanentDownloadException> {
                HttpDownloadTransfer(true).fetch(request(server, 100L), file, { true }) { }
            }
            assertTrue(file.length() <= 100L)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun http200WithPartialContentRangeCannotBecomeAnUnknownSizeCompleteFile(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-200-range-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-99/1000").setBody(Buffer().write(bytes.copyOf(100))))
            server.startLoopback()
            expectFailure<PermanentDownloadException> {
                HttpDownloadTransfer(true).fetch(request(server), file, { true }) { }
            }
            assertEquals(1, server.requestCount)
            assertEquals(0L, file.length())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun supersededDuringRegistrationDoesNotSendARequestAndClearsTheConnection(): Unit = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("kmpyt-register-cancel-", ".part", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val active = AtomicBoolean(true)
        var cleared = false
        try {
            server.enqueue(chunked(bytes))
            server.startLoopback()
            expectFailure<StaleDownloadException> {
                HttpDownloadTransfer(true, onConnection = {
                    if (it == null) cleared = true else active.set(false)
                }).fetch(request(server), file, { active.get() }) { }
            }
            assertTrue(cleared)
            assertEquals(0, server.requestCount)
            assertEquals(0L, file.length())
        } finally { server.shutdown(); file.delete() }
    }
}

private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    try { block() }
    catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.java.simpleName}")
}