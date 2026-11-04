package dev.kmpyt.jvm

import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JvmYouTubeDownloaderTest {
    private fun media(name: String) = requireNotNull(javaClass.getResourceAsStream("/research/$name")).use { it.readBytes() }
    private fun plan(server: MockWebServer, audio: Boolean = false): PlannedDownload {
        val video = Representation("video", server.url("/video").toString(), if (audio) TrackRole.VIDEO else TrackRole.MUXED,
            mimeType = "video/mp4", codecs = "avc1.4d401e", sizeBytes = media("video.mp4").size.toLong())
        val sound = if (audio) Representation("audio", server.url("/audio").toString(), TrackRole.AUDIO,
            mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = media("audio.m4a").size.toLong()) else null
        return PlannedDownload(video, sound, inspectionUrl = server.url("/owned").toString(), mediaId = "owned")
    }

    @Test fun independentDownloaderInstancesCannotMixTheSameCanonicalDestination(): Unit = runBlocking {
        val body = media("video.mp4")
        val directory = Files.createTempDirectory("kmpyt-exclusive-test").toFile()
        val output = File(directory, "saved.mp4")
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"owned\"")
            .setBody(Buffer().write(body)))
        server.start()
        val http = HttpClient(CIO)
        val firstBytes = CompletableDeferred<Unit>()
        val continueFirst = CompletableDeferred<Unit>()
        try {
            val selected = plan(server)
            val first = async {
                JvmYouTubeDownloader(http, allowLocalHttp = true).download(selected, output.path) {
                    if (it.bytesDownloaded > 0 && !firstBytes.isCompleted) {
                        firstBytes.complete(Unit)
                        continueFirst.await()
                    }
                }
            }
            try {
                withTimeout(10_000) { firstBytes.await() }
                val alias = File(directory, "unused/../saved.mp4")
                val failure = assertFailsWith<IllegalStateException> {
                    JvmYouTubeDownloader(http, allowLocalHttp = true).download(selected, alias.path) { }
                }
                assertTrue(failure.message.orEmpty().contains("Another download"))
                assertEquals(1, server.requestCount)
            } finally { continueFirst.complete(Unit) }
            first.await()
            assertContentEquals(body, output.readBytes())
        } finally { continueFirst.complete(Unit); http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun cancellationLeavesPrivateCheckpointAndNextCallResumesExactFile(): Unit = runBlocking {
        val body = media("video.mp4")
        val directory = Files.createTempDirectory("kmpyt-download-test").toFile()
        val destination = File(directory, "saved.mp4")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                return MockResponse().setResponseCode(if (offset == 0) 200 else 206)
                    .setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"owned\"")
                    .apply { if (offset > 0) setHeader("Content-Range", "bytes $offset-${body.lastIndex}/${body.size}") }
                    .setBody(Buffer().write(body, offset, body.size - offset))
                    .throttleBody(4096, 10, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        val http = HttpClient(CIO)
        try {
            val selected = plan(server)
            val downloader = JvmYouTubeDownloader(http, allowLocalHttp = true)
            assertFailsWith<CancellationException> {
                downloader.download(selected, destination.path) { if (it.bytesDownloaded > 0) throw CancellationException("Authored cancellation") }
            }
            assertFalse(destination.exists())
            val checkpoint = File(directory, ".saved.mp4.kmpyt/checkpoint.json")
            assertTrue(checkpoint.isFile)
            assertFalse(checkpoint.readText().contains(server.url("/").toString()))
            val progress = mutableListOf<YouTubeDownloadProgress>()
            val result = downloader.download(selected, destination.path) { progress += it }
            assertContentEquals(body, destination.readBytes())
            assertEquals(body.size.toLong(), result.bytesWritten)
            assertEquals(body.size.toLong(), progress.last().totalBytes)
            server.takeRequest()
            val resumed = server.takeRequest()
            assertTrue(resumed.getHeader("Range")!!.startsWith("bytes="))
            assertEquals("\"owned\"", resumed.getHeader("If-Range"))
            assertFalse(checkpoint.exists())
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun actualSeparateVideoAndAudioAreDownloadedAndCombinedByCallerFfmpeg(): Unit = runBlocking {
        val ffmpeg = System.getenv("KMPYT_FFMPEG")
        assumeTrue("CI supplies FFmpeg explicitly", ffmpeg != null && File(ffmpeg).isFile)
        val video = media("video.mp4"); val audio = media("audio.m4a")
        val directory = Files.createTempDirectory("kmpyt-mux-test").toFile()
        val output = File(directory, "saved.mp4")
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(video)))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(audio)))
        server.start()
        val http = HttpClient(CIO)
        try {
            val progress = mutableListOf<YouTubeDownloadProgress>()
            val result = JvmYouTubeDownloader(http, ffmpeg, true).download(plan(server, true), output.path) { progress += it }
            assertEquals("video/mp4", result.mimeType)
            assertTrue(output.length() > maxOf(video.size, audio.size))
            assertEquals((video.size + audio.size).toLong(), progress.first().totalBytes)
            assertEquals((video.size + audio.size).toLong(), progress.last().bytesDownloaded)
            val check = ProcessBuilder(ffmpeg!!, "-v", "error", "-i", output.path, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")
                .redirectError(File(directory, "decode.log")).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try { assertTrue(check.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, check.exitValue()) }
            finally { if (check.isAlive) check.destroyForcibly() }
            assertEquals(2, server.requestCount)
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun missingMuxerFailsBeforeDownloadingEitherTrack(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val http = HttpClient(CIO)
        val directory = Files.createTempDirectory("kmpyt-preflight-test").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                JvmYouTubeDownloader(http, null, true).download(plan(server, true), File(directory, "saved.mp4").path) { }
            }
            assertEquals(0, server.requestCount)
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }
}
