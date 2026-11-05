package dev.kmpyt.internal

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import dev.kmpyt.testfixtures.startLoopback
import dev.kmpyt.testfixtures.loopbackUrl
import okio.Buffer
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileCheckpointIntegrityTest {
    @Test fun interruptedChunkedReplacementDoesNotPersistTheOldEntitySize(): Unit = runBlocking {
        val directory = File.createTempFile("kmpyt-replacement-checkpoint-", ".dir", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val destination = File(directory, "saved.mp4")
        val original = ByteArray(128 * 1024) { (it % 251).toByte() }
        val replacement = ByteArray(64 * 1024) { ((it + 23) % 251).toByte() }
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"old\"")
            .setBody(Buffer().write(original)).throttleBody(4096, 20, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"new\"")
            .setChunkedBody(Buffer().write(replacement), 4096).throttleBody(4096, 20, TimeUnit.MILLISECONDS))
        server.startLoopback()
        val http = HttpClient(Android)
        try {
            val url = server.loopbackUrl("/media").toString()
            val plan = PlannedDownload(Representation("owned", url, TrackRole.MUXED,
                mimeType = "video/mp4", codecs = "avc1.4d401e"),
                inspectionUrl = server.loopbackUrl("/owned").toString(), mediaId = "owned")
            val processor = object : FileMediaProcessor {
                override suspend fun materialize(primary: Representation, audio: Representation?,
                    fetch: suspend (SegmentResource, File) -> Unit, output: File) {
                    fetch(SegmentResource(requireNotNull(primary.url)), output)
                }
            }
            val downloader = FileDownloadCoordinator(processor, http, "Android", allowLocalHttp = true)
            expectFailure<CancellationException> {
                downloader.download(plan, destination.path) {
                    if (it.bytesDownloaded > 0) throw CancellationException("Interrupt original entity")
                }
            }
            val part = File(directory, ".saved.mp4.kmpyt/0-0")
            val oldPrefix = part.length()
            assertTrue(oldPrefix in 1 until original.size.toLong())
            assertNull(server.takeRequest().getHeader("Range"))
            var restarted = false
            expectFailure<CancellationException> {
                downloader.download(plan, destination.path) {
                    if (it.bytesDownloaded == 0L) restarted = true
                    if (restarted && it.bytesDownloaded > 0) throw CancellationException("Interrupt replacement entity")
                }
            }
            assertTrue(restarted)
            assertFalse(destination.exists())
            val newPrefix = part.length().toInt()
            assertTrue(newPrefix in 1 until replacement.size)
            assertArrayEquals(replacement.copyOf(newPrefix), part.readBytes())
            val checkpoint = Json.parseToJsonElement(File(directory, ".saved.mp4.kmpyt/checkpoint.json").readText())
                .jsonObject.getValue("parts").jsonArray.single().jsonObject
            assertNull(checkpoint["total"]?.jsonPrimitive?.longOrNull)
            assertEquals("\"new\"", checkpoint.getValue("etag").jsonPrimitive.content)
            val restartedRequest = server.takeRequest()
            assertEquals("bytes=$oldPrefix-", restartedRequest.getHeader("Range"))
            assertEquals("\"old\"", restartedRequest.getHeader("If-Range"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                .setHeader("ETag", "\"new\"")
                .setHeader("Content-Range", "bytes $newPrefix-${replacement.lastIndex}/${replacement.size}")
                .setBody(Buffer().write(replacement.copyOfRange(newPrefix, replacement.size))))
            val result = downloader.download(plan, destination.path) { }
            assertArrayEquals(replacement, destination.readBytes())
            assertEquals(replacement.size.toLong(), result.bytesWritten)
            val resumedRequest = server.takeRequest()
            assertEquals("bytes=$newPrefix-", resumedRequest.getHeader("Range"))
            assertEquals("\"new\"", resumedRequest.getHeader("If-Range"))
            assertEquals(3, server.requestCount)
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun undersizedCompletedCheckpointIsDiscardedBeforePublishing(): Unit = runBlocking {
        val directory = File.createTempFile("kmpyt-checkpoint-integrity-", ".dir", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val destination = File(directory, "saved.mp4")
        val body = ByteArray(128 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"owned\"")
                .setBody(Buffer().write(body)))
        }
        server.startLoopback()
        val http = HttpClient(Android)
        try {
            val url = server.loopbackUrl("/media").toString()
            val plan = PlannedDownload(Representation("owned", url, TrackRole.MUXED, mimeType = "video/mp4",
                codecs = "avc1.4d401e", sizeBytes = body.size.toLong()),
                inspectionUrl = server.loopbackUrl("/owned").toString(), mediaId = "owned")
            // Publication/caching test: processing returns the verified single local resource.
            val processor = object : FileMediaProcessor {
                override suspend fun materialize(primary: Representation, audio: Representation?,
                    fetch: suspend (SegmentResource, File) -> Unit, output: File) {
                    assertNull(audio)
                    fetch(SegmentResource(requireNotNull(primary.url)), output)
                }
            }
            val downloader = FileDownloadCoordinator(processor, http, "Android", allowLocalHttp = true)
            expectFailure<CancellationException> {
                downloader.download(plan, destination.path) {
                    if (it.bytesDownloaded > 0) throw CancellationException("Authored interruption")
                }
            }
            assertFalse(destination.exists())
            server.takeRequest()
            val checkpoint = File(directory, ".saved.mp4.kmpyt/checkpoint.json")
            val root = Json.parseToJsonElement(checkpoint.readText()).jsonObject
            val staleParts = root.getValue("parts").jsonArray.map { entry -> buildJsonObject {
                entry.jsonObject.forEach { (key, value) -> put(key, value) }
                put("bytes", 100); put("total", 100); put("complete", true)
            } }
            // Reproduce a legacy completed record with self-consistent counts but an incorrect
            // inspected length. Keep the real selection digest so validation is exercised.
            checkpoint.writeText(JsonObject(root + ("parts" to JsonArray(staleParts))).toString())
            File(directory, ".saved.mp4.kmpyt/0-0").writeBytes(body.copyOf(100))
            val result = downloader.download(plan, destination.path) { }
            assertArrayEquals(body, destination.readBytes())
            assertEquals(body.size.toLong(), result.bytesWritten)
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(2, server.requestCount)
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
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