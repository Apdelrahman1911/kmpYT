package dev.kmpyt.jvm

import dev.kmpyt.MediaKind
import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.*
import dev.kmpyt.testfixtures.loopbackUrl
import dev.kmpyt.testfixtures.startLoopback
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real loopback sockets, protocol ingestion, private files and authored CC0 audio, not live YouTube. */
class JvmYouTubeSabrPlaybackAdapterTest {
    @Test fun sourcePostBecomesAnExactSeekableNativeGetResourceAndClosesItsPrivateCache(): Unit = runBlocking {
        Fixture().use { fixture ->
            val resource = fixture.open()
            try {
                val endpoint = resource.endpoints.single()
                assertEquals(TrackRole.AUDIO, endpoint.role)
                assertEquals(fixture.body.size.toLong(), endpoint.lengthBytes)
                val head = fixture.get(endpoint.url, method = "HEAD")
                assertEquals(200, head.status); assertEquals(fixture.body.size.toString(), head.headers["Content-Length"])
                assertTrue(head.body.isEmpty())
                val middle = fixture.get(endpoint.url, "bytes=17-116")
                assertEquals(206, middle.status); assertContentEquals(fixture.body.copyOfRange(17, 117), middle.body)
                assertEquals("bytes 17-116/${fixture.body.size}", middle.headers["Content-Range"])
                val tail = fixture.get(endpoint.url, "bytes=-31")
                assertContentEquals(fixture.body.takeLast(31).toByteArray(), tail.body)
                assertContentEquals(fixture.body, fixture.get(endpoint.url).body)
                val request = assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertTrue(fixture.authorizations >= 2)
                val token = URI(endpoint.url).path.split('/')[1]
                assertTrue(fixture.directory.listFiles()!!.isNotEmpty())
                assertTrue(fixture.directory.listFiles()!!.none { token in it.name })
                assertFalse(token in resource.toString())
                assertEquals(1, fixture.server.requestCount)
            } finally { resource.close(); resource.close() }
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun aRealNativeFfmpegDecoderCanReadAndSeekTheOwnedEndpoint(): Unit = runBlocking {
        val ffmpeg = System.getenv("KMPYT_FFMPEG")?.let(::File)
        assumeTrue("An explicit FFmpeg executable is needed for native loopback decoder verification", ffmpeg?.isFile == true)
        Fixture().use { fixture ->
            val resource = fixture.open()
            try {
                val process = ProcessBuilder(ffmpeg!!.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-ss", "0.05", "-i", resource.endpoints.single().url, "-map", "0:a:0", "-f", "null", "-")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                process.outputStream.close()
                // Drain bounded native diagnostics without recording the bearer
                // endpoint in files, assertions or test logs.
                val drain = async(Dispatchers.IO) {
                    process.errorStream.use { input ->
                        val buffer = ByteArray(4096)
                        var count = 0
                        while (true) {
                            val next = input.read(buffer)
                            if (next < 0) break
                            count += next
                            if (count > 65_536) { process.destroyForcibly(); break }
                        }
                    }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { process.waitFor(20, TimeUnit.SECONDS) }, "Native authored-audio decode timed out")
                    assertEquals(0, process.exitValue(), "Native decoder rejected the owned authored-audio endpoint")
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                    drain.await()
                }
            } finally { resource.close() }
        }
    }

    @Test fun missingCapabilityHostAliasesAndBodiesCannotAccessPrivateTracks(): Unit = runBlocking {
        Fixture().use { fixture ->
            val resource = fixture.open()
            try {
                val endpoint = URI(resource.endpoints.single().url)
                suspend fun raw(target: String = endpoint.rawPath, host: String = "127.0.0.1:${endpoint.port}", extra: String = "") =
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", endpoint.port).use { socket ->
                            socket.soTimeout = 5_000
                            socket.getOutputStream().write(("GET $target HTTP/1.1\r\nHost: $host\r\n$extra\r\n").encodeToByteArray())
                            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
                        }
                    }
                assertEquals(404, raw("/audio"))
                assertEquals(404, raw(host = "localhost:${endpoint.port}"))
                assertEquals(404, raw(endpoint.rawPath + "?file=../other"))
                assertEquals(403, raw(extra = "Origin: https://example.test\r\n"))
                assertEquals(400, raw(extra = "Content-Length: 1\r\n"))
                assertEquals(416, fixture.get(endpoint.toString(), "bytes=0-1,4-5").status)
            } finally { resource.close() }
        }
    }

    @Test fun authorizationDenialAndByteBudgetFailureCreateNoFilesAndMakeNoMediaRequest(): Unit = runBlocking {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.adapter.open(fixture.request, SourcePlaybackAuthorization { _, _ -> error("Authored denial") })
            }
            val tiny = createJvmYouTubeSabrPlaybackAdapter(fixture.http, fixture.directory.path, YouTubeNativePlaybackLimits(maxCacheBytes = 1))
            assertFailsWith<IllegalArgumentException> { tiny.open(fixture.request, fixture.authorization) }
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun closeCancelsPendingIngestionAndAWaitingNativeReaderWithoutLeavingStorage(): Unit = runBlocking {
        Fixture(noResponse = true).use { fixture ->
            val resource = fixture.open()
            assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
            val pending = async(Dispatchers.IO) { runCatching { fixture.get(resource.endpoints.single().url) } }
            delay(100)
            withTimeout(3_000) { resource.close() }
            withTimeout(3_000) { pending.await() }
            resource.close()
            assertTrue(fixture.directory.listFiles()!!.isEmpty())
        }
    }

    private class Fixture(noResponse: Boolean = false) : AutoCloseable {
        val body = requireNotNull(javaClass.getResourceAsStream("/research/audio.m4a")).use { it.readBytes() }
        val directory = Files.createTempDirectory("kmpyt-owned-playback-").toFile()
        val server = MockWebServer().apply { startLoopback() }
        val http = HttpClient(CIO) { followRedirects = false; expectSuccess = false }
        val endpoint = server.loopbackUrl("/sabr").toString()
        val format = SabrFormatId(140, 1uL, "authored")
        val request = SourcePlaybackRequest(SourcePlaybackMetadata("ABCDEFGHIJK", "Authored CC0 audio", MediaKind.AUDIO),
            PlannedDownload(Representation("140", endpoint, TrackRole.AUDIO, TransportKind.SABR, "audio/mp4",
                codecs = "mp4a.40.2", sizeBytes = body.size.toLong(),
                sabr = YouTubeSabrResource("ABCDEFGHIJK", endpoint, "AQID", format, SabrClientInfo(1, "authored-test"))),
                inspectionUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK", mediaId = "ABCDEFGHIJK"))
        val adapter = createJvmYouTubeSabrPlaybackAdapter(http, directory.path)
        var authorizations = 0
        val authorization = SourcePlaybackAuthorization { selected, url ->
            require(selected === request && url.substringBefore('?') == endpoint)
            authorizations++
        }
        init {
            server.enqueue(if (noResponse) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else
                MockResponse().setHeader("Content-Type", "application/vnd.yt-ump").setBody(Buffer().write(packet(body))))
        }
        suspend fun open(): YouTubePlaybackResource = adapter.open(request, authorization)
        data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)
        fun get(url: String, range: String? = null, method: String = "GET"): Response {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            range?.let { connection.setRequestProperty("Range", it) }
            return try {
                val status = connection.responseCode
                val headers = listOf("Content-Length", "Content-Range").mapNotNull { name ->
                    connection.getHeaderField(name)?.let { name to it }
                }.toMap()
                val bytes = if (status < 400 && method != "HEAD") connection.inputStream.use { it.readBytes() } else ByteArray(0)
                Response(status, headers, bytes)
            } finally { connection.disconnect() }
        }
        private fun packet(content: ByteArray): ByteArray {
            fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
            fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
            val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, "ABCDEFGHIJK".encodeToByteArray()),
                number(6, 0), number(7, 0), number(8, 0), number(9, 0), number(11, 0), number(12, 100),
                binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, content.size.toLong())))
            return YouTubeUmpFraming.encodePart(20, header) + YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + content) +
                YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
        }
        override fun close() { http.close(); server.shutdown(); directory.deleteRecursively() }
    }
}
