package dev.kmpyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.testfixtures.loopbackUrl
import dev.kmpyt.testfixtures.startLoopback
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** Mirrors the real JVM transfer regression on Android's native HTTP and file adapters. */
@RunWith(AndroidJUnit4::class)
class AndroidRefreshedResumeTest {
    @Test fun refreshedSignatureResumesExactAuthoredAacBytes(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val body = context.assets.open("research/audio.m4a").use { it.readBytes() }
        val directory = File(context.cacheDir, "kmpyt-refreshed-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val output = File(directory, "saved.m4a")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                return MockResponse().setResponseCode(if (start == 0) 200 else 206)
                    .setHeader("Content-Type", "audio/mp4").setHeader("ETag", "\"authored-audio\"")
                    .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${body.lastIndex}/${body.size}") }
                    .setBody(Buffer().write(body, start, body.size - start)).throttleBody(4096, 20, TimeUnit.MILLISECONDS)
            }
        }
        server.startLoopback()
        val http = HttpClient(Android)
        try {
            fun plan(signature: String, expiry: Long) = PlannedDownload(
                Representation("140", server.loopbackUrl("/videoplayback?id=authored&itag=140&sig=$signature&expire=$expiry").toString(),
                    TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = body.size.toLong(), expiresAtEpochMs = expiry * 1000),
                inspectionUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK", mediaId = "ABCDEFGHIJK",
            )
            var cancelled = false
            try {
                AndroidYouTubeDownloader(http, true).download(plan("first", 4_000_000_000), output.path) {
                    if (it.bytesDownloaded > 0) throw CancellationException("Authored interruption")
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            val prefix = File(directory, ".saved.m4a.kmpyt/0-0").length()
            assertTrue(prefix in 1 until body.size.toLong())
            assertFalse(output.exists())
            server.takeRequest(5, TimeUnit.SECONDS)
            val result = AndroidYouTubeDownloader(http, true).download(plan("second", 4_000_003_600), output.path) { }
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("bytes=$prefix-", request!!.getHeader("Range"))
            assertEquals("\"authored-audio\"", request.getHeader("If-Range"))
            assertArrayEquals(body, output.readBytes())
            assertEquals(body.size.toLong(), result.bytesWritten)
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }
}
