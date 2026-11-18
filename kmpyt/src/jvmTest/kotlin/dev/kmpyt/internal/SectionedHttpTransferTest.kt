package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.testfixtures.loopbackUrl
import dev.kmpyt.testfixtures.startLoopback
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Authored loopback bytes only; exercises bounded parallel sections with exact receipts. */
class SectionedHttpTransferTest {
    private val bytes = ByteArray(300 * 1024 + 17) { (it * 31 % 251).toByte() }
    private val section = TransferSectioning(connections = 3, sectionBytes = 64 * 1024, loopbackFixtures = true)

    private fun sha(data: ByteArray, length: Int = data.size): String =
        MessageDigest.getInstance("SHA-256").apply { update(data, 0, length) }.digest().resumeHex()

    private fun task(server: MockWebServer): FileTransferRequest {
        val url = server.loopbackUrl("/media.m4a").toString()
        return FileTransferRequest(url, PlannedDownload(Representation("owned", url, TrackRole.AUDIO, mimeType = "audio/mp4"),
            inspectionUrl = server.loopbackUrl("/page").toString(), mediaId = "owned"),
            expectedBytes = bytes.size.toLong(), requireStrongResume = true)
    }

    /** Honors `Range` exactly; [override] can replace the response for a given request number. */
    private fun rangeServer(etag: (Int) -> String = { "\"v1\"" }, throttled: Boolean = false, override: (Int, RecordedRequest) -> MockResponse? = { _, _ -> null }):
        Pair<MockWebServer, MutableList<String?>> {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val count = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val number = count.getAndIncrement()
                ranges += request.getHeader("Range")
                override(number, request)?.let { return it }
                val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range") ?: "")
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                        .setHeader("Content-Type", "audio/mp4").setHeader("ETag", etag(number))
                val first = match.groupValues[1].toInt()
                val last = match.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
                return MockResponse().setResponseCode(206).setBody(Buffer().write(bytes.copyOfRange(first, last + 1)))
                    .setHeader("Content-Type", "audio/mp4").setHeader("ETag", etag(number))
                    .setHeader("Content-Range", "bytes $first-$last/${bytes.size}")
                    .apply { if (throttled) throttleBody(16 * 1024, 60, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
        }
        server.startLoopback()
        return server to ranges
    }

    @Test fun parallelSectionsWriteExactBytesWithBoundedRangesAndAPrefixReceipt() : Unit = runBlocking {
        val (server, ranges) = rangeServer()
        val file = File.createTempFile("kmpyt-sections-", ".part")
        try {
            val progress = Collections.synchronizedList(mutableListOf<TransferProgress>())
            val result = HttpDownloadTransfer(true, sectioning = section).fetch(task(server), file, { true }) { progress += it }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), result.bytes)
            assertEquals(sha(bytes), result.prefixDigest)
            assertEquals(5, ranges.size)
            ranges.forEach { range ->
                val (a, b) = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range!!)).destructured
                assertTrue("section $range exceeds the bound", b.toLong() - a.toLong() + 1 <= 64 * 1024)
            }
            assertEquals(listOf(0L, 65_536L, 131_072L, 196_608L, 262_144L),
                ranges.map { it!!.removePrefix("bytes=").substringBefore('-').toLong() }.sorted())
            // Every reported prefix is monotonic and its receipt describes exactly those bytes.
            progress.zipWithNext().forEach { (a, b) -> assertTrue(b.bytes >= a.bytes) }
            progress.filter { it.prefixDigest != null && it.bytes > 0 }.forEach {
                assertEquals(sha(bytes, it.bytes.toInt()), it.prefixDigest)
            }
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun singleConnectionSectionsAreSequentialAndExact() : Unit = runBlocking {
        val (server, ranges) = rangeServer()
        val file = File.createTempFile("kmpyt-sections-", ".part")
        try {
            val result = HttpDownloadTransfer(true, sectioning = section.copy(connections = 1))
                .fetch(task(server), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(sha(bytes), result.prefixDigest)
            assertEquals(listOf("bytes=0-65535", "bytes=65536-131071", "bytes=131072-196607", "bytes=196608-262143",
                "bytes=262144-${bytes.size - 1}"), ranges.toList())
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun aServerThatIgnoresSectionRangesFallsBackToOneVerifiedRequest() : Unit = runBlocking {
        val (server, ranges) = rangeServer(override = { number, _ ->
            // Requests 1..3 are concurrent sections; answer them with the whole object.
            if (number in 1..3) MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                .setHeader("Content-Type", "audio/mp4").setHeader("ETag", "\"v1\"") else null
        })
        val file = File.createTempFile("kmpyt-sections-", ".part")
        try {
            val result = HttpDownloadTransfer(true, sectioning = section).fetch(task(server), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(sha(bytes), result.prefixDigest)
            // The fallback request runs to the object end, not another section.
            assertTrue(ranges.toList().toString(), ranges.last()!!.endsWith("-${bytes.size - 1}"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun aChangedRepresentationFailsAndKeepsOnlyTheReportedPrefix() : Unit = runBlocking {
        val (server, _) = rangeServer(etag = { if (it == 0) "\"v1\"" else "\"v2\"" })
        val file = File.createTempFile("kmpyt-sections-", ".part")
        try {
            var last: TransferProgress? = null
            try {
                HttpDownloadTransfer(true, sectioning = section).fetch(task(server), file, { true }) { last = it }
                fail("A changed representation must not be stitched into one file")
            } catch (expected: PermanentDownloadException) { }
            val reported = last?.bytes ?: 0
            assertEquals(reported, file.length())
            last?.prefixDigest?.let { assertEquals(sha(bytes, reported.toInt()), it) }
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun anInterruptedSectionedTransferResumesFromItsCommittedReceipt() : Unit = runBlocking {
        val (server, _) = rangeServer(throttled = true)
        val file = File.createTempFile("kmpyt-sections-", ".part")
        val active = AtomicBoolean(true)
        try {
            var last: TransferProgress? = null
            try {
                HttpDownloadTransfer(true, sectioning = section.copy(connections = 1)).fetch(task(server), file, { active.get() }) {
                    last = it
                    if (it.bytes >= 70_000) active.set(false)
                }
                fail("The transfer should have been superseded")
            } catch (expected: StaleDownloadException) { }
            val checkpoint = assertNotNullAndGet(last)
            assertTrue(checkpoint.bytes in 70_000 until bytes.size)
            assertEquals(checkpoint.bytes, file.length())
            assertEquals(sha(bytes, checkpoint.bytes.toInt()), checkpoint.prefixDigest)
            val result = HttpDownloadTransfer(true, sectioning = section).fetch(task(server).copy(
                etag = checkpoint.etag, totalBytes = checkpoint.total, resumeResponseScope = checkpoint.responseScope),
                file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(sha(bytes), result.prefixDigest)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun nonStrictTransfersAreNeverSectioned() : Unit = runBlocking {
        val (server, ranges) = rangeServer()
        val file = File.createTempFile("kmpyt-sections-", ".part")
        try {
            HttpDownloadTransfer(true, sectioning = section).fetch(task(server).copy(requireStrongResume = false), file, { true }) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf("bytes=0-${bytes.size - 1}"), ranges.toList())
        } finally { server.shutdown(); file.delete() }
    }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
