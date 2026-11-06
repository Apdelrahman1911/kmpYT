package dev.kmpyt.internal

import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.extraction.*
import dev.kmpyt.jvm.JvmYouTubeDownloader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** Authored UMP + actual JVM file coordinator, not live-source or native-decoder evidence. */
class SabrFileCheckpointTest {
    @Test fun durableReceiptsContainOnlyHashesAndByteGeometryAndResumeWithAnAnonymousFirstRequest(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt()
            val root = fixture.checkpoint()
            assertEquals(3, root.getValue("schema").jsonPrimitive.int)
            assertTrue(root.keys.all { it in setOf("schema", "planDigest", "parts", "sabrReceipt") })
            val saved = fixture.receipt()
            assertEquals(setOf("sourceDigest", "tracks"), root.getValue("sabrReceipt").jsonObject.keys)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(fixture.payload.copyOf(fixture.cut)).resumeHex(),
                saved.tracks.single().rangeDigests.single())
            fixture.assertPrivateCheckpoint()
            val updates = fixture.resume(expectBuffered = true)
            assertEquals(fixture.cut.toLong(), updates.first().bytesDownloaded)
            assertTrue(updates.all { it.totalBytes == fixture.payload.size.toLong() })
            assertContentEquals(fixture.payload, fixture.output.readBytes())
            assertEquals(listOf("lock"), fixture.working.list()!!.sorted())
        }
    }

    @Test fun sameLengthCorruptionAndTruncationRestartBeforeAcknowledgingOrAppending(): Unit = runBlocking {
        for (truncate in listOf(false, true)) Fixture().use { fixture ->
            fixture.interrupt()
            RandomAccessFile(fixture.part, "rw").use { file ->
                if (truncate) file.setLength(fixture.cut - 1L)
                else { val first = file.read(); file.seek(0); file.write(first xor 1) }
            }
            val updates = fixture.resume(expectBuffered = false)
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun uncommittedCrashTailIsTruncatedButItsVerifiedPrefixStillResumes(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt()
            fixture.part.appendBytes(byteArrayOf(91, 92, 93, 94))
            val updates = fixture.resume(expectBuffered = true, beforeRequest = {
                assertEquals(fixture.cut.toLong(), fixture.part.length())
                assertContentEquals(fixture.payload.copyOf(fixture.cut), fixture.part.readBytes())
            })
            assertEquals(fixture.cut.toLong(), updates.first().bytesDownloaded)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun legacySensitiveCheckpointsArePurgedUnderTheStableLockBeforeCallbacksOrRequests(): Unit = runBlocking {
        for (schema in listOf(1, 2)) Fixture().use { fixture ->
            fixture.interrupt()
            val lockWitness = fixture.lockWitness()
            val root = fixture.checkpoint()
            val legacy = SabrTransferCheckpoint(fixture.plan.mediaId, fixture.format, null, fixture.client,
                listOf(SabrTransferTrackCheckpoint(TrackRole.AUDIO, fixture.format, fixture.payload.size.toLong(),
                    fixture.receipt().tracks.single().coverage, fixture.receipt().tracks.single().completedSegments)),
                nextRequestNumber = 29, playbackCookie = "private-cookie".encodeToByteArray(),
                contexts = listOf(SabrTransferContextCheckpoint(7, "private-context".encodeToByteArray())),
                activeContextTypes = listOf(7), completedRounds = 28,
                resourceGeneration = YouTubeIntegrityBinding.forSabr(fixture.plan.sabrSource(), fixture.client).resourceGeneration,
                activeEndpoint = "https://rr1.googlevideo.com/sabr?sig=private-redirect",
                protocolRedirects = listOf("https://rr1.googlevideo.com/sabr?sig=private-redirect"))
            fixture.checkpointFile.writeText(JsonObject(root - "sabrReceipt" + mapOf(
                "schema" to JsonPrimitive(schema), "sabrCheckpoint" to Json.encodeToJsonElement(SabrTransferCheckpoint.serializer(), legacy),
            )).toString())
            assertTrue("private-redirect" in fixture.checkpointFile.readText())
            var observedCallback = false
            fixture.resume(expectBuffered = false, beforeRequest = {
                assertTrue(observedCallback)
                fixture.assertSameLock(lockWitness)
                fixture.assertPrivateCheckpoint()
            }, progress = {
                observedCallback = true
                fixture.assertPrivateCheckpoint()
            })
            assertTrue(observedCallback)
            fixture.assertSameLock(lockWitness)
            assertEquals(listOf("lock"), fixture.working.list()!!.sorted())
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun exactEndpointOrConfigurationChangeStartsFreshEvenWithIdenticalFormatAndLengths(): Unit = runBlocking {
        for (endpointChanged in listOf(false, true)) Fixture().use { fixture ->
            fixture.interrupt()
            val original = fixture.plan.primary.sabr!!
            val source = if (endpointChanged) original.copy(endpoint = original.endpoint.replace("private-endpoint", "private-new-endpoint"))
                else original.copy(configuration = "BAUG")
            val changed = fixture.plan.copy(primary = fixture.plan.primary.copy(url = source.endpoint, sabr = source))
            val updates = fixture.resume(expectBuffered = false, selected = changed)
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun missingRangeHashesCannotBeSubstitutedByGenericCompletedPartCounters(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt()
            val root = fixture.checkpoint()
            val original = fixture.receipt()
            val receipt = original.copy(tracks = original.tracks.map { it.copy(rangeDigests = emptyList()) })
            fixture.checkpointFile.writeText(JsonObject(root + mapOf(
                "sabrReceipt" to Json.encodeToJsonElement(SabrSavedReceipt.serializer(), receipt),
                "parts" to buildJsonArray { add(buildJsonObject {
                    put("key", "0-0"); put("bytes", fixture.payload.size); put("total", fixture.payload.size); put("complete", true)
                }) },
            )).toString())
            fixture.part.writeBytes(fixture.payload)
            val updates = fixture.resume(expectBuffered = false)
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun sparseCoverageAcknowledgesOnlyHashedRangesNotFileLengthOrUncoveredHoles(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt(offset = fixture.cut, sequence = 1)
            assertEquals(2L * fixture.cut, fixture.part.length())
            assertEquals(listOf(SabrTransferByteRange(fixture.cut.toLong(), 2L * fixture.cut)),
                fixture.receipt().tracks.single().coverage)
            // An unacknowledged sparse hole is not a retained byte receipt and must be overwritten.
            RandomAccessFile(fixture.part, "rw").use { it.write(ByteArray(fixture.cut) { 99 }) }
            val response = fixture.packet(0, fixture.cut, 0) + fixture.packet(fixture.cut, fixture.cut, 1) +
                fixture.packet(2 * fixture.cut, fixture.payload.size - 2 * fixture.cut, 2)
            val updates = fixture.resume(expectBuffered = true, response = response)
            assertEquals(fixture.cut.toLong(), updates.first().bytesDownloaded)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun conflictingReplayCannotOverwriteTheVerifiedPrefixOrPublishAFinalFile(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt()
            val before = fixture.part.readBytes()
            assertFailsWith<PermanentDownloadException> {
                fixture.resume(expectBuffered = true, response = fixture.packet(0, fixture.cut, 0, corrupt = true) +
                    fixture.packet(fixture.cut, fixture.payload.size - fixture.cut, 1))
            }
            assertFalse(fixture.output.exists())
            assertContentEquals(before, fixture.part.readBytes())
            fixture.assertPrivateCheckpoint()
            fixture.resume(expectBuffered = true)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    @Test fun linkedSabrTrackIsRejectedBeforeReceiptHashingCallbacksOrRequests(): Unit = runBlocking {
        Fixture().use { fixture ->
            fixture.interrupt()
            val prefix = fixture.part.readBytes()
            val saved = fixture.checkpointFile.readText()
            val callerFile = File(fixture.directory, "caller-owned-prefix").apply { writeBytes(prefix) }
            check(fixture.part.delete())
            Files.createSymbolicLink(fixture.part.toPath(), callerFile.toPath())
            var callback = false
            var request = false
            try {
                assertFailsWith<IllegalStateException> {
                    fixture.resume(expectBuffered = true, beforeRequest = { request = true }, progress = { callback = true })
                }
                assertFalse(callback)
                assertFalse(request)
                assertTrue(Files.isSymbolicLink(fixture.part.toPath()))
                assertContentEquals(prefix, callerFile.readBytes())
                assertEquals(saved, fixture.checkpointFile.readText())
                assertFalse(fixture.output.exists())
            } finally { Files.delete(fixture.part.toPath()); fixture.part.writeBytes(prefix) }
            fixture.resume(expectBuffered = true)
            assertContentEquals(fixture.payload, fixture.output.readBytes())
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("kmpyt-sabr-receipts-").toFile()
        val output = File(directory, "saved.m4a")
        val working = File(directory, ".saved.m4a.kmpyt")
        val part = File(working, "0-0")
        val checkpointFile = File(working, "checkpoint.json")
        val payload = requireNotNull(javaClass.getResourceAsStream("/research/audio.m4a")).use { it.readBytes() }
        val cut = payload.size / 3
        val format = SabrFormatId(140, 1uL, "authored")
        val client = SabrClientInfo(1, "authored-client")
        private val source = YouTubeSabrResource("AUTHORED001", "https://rr1.googlevideo.com/sabr?sig=private-endpoint",
            "AQID", format, client)
        val plan = PlannedDownload(Representation("140", source.endpoint, TrackRole.AUDIO,
            transport = TransportKind.SABR, mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = payload.size.toLong(), sabr = source),
            inspectionUrl = "https://www.youtube.com/watch?v=${source.videoId}", mediaId = source.videoId)

        suspend fun interrupt(offset: Int = 0, sequence: Int = 0) {
            val policy = YouTubeUmpFraming.encodePart(35,
                YouTubeProtobufWire.encode(listOf(binary(7, "private-cookie".encodeToByteArray()))))
            val context = YouTubeUmpFraming.encodePart(57, YouTubeProtobufWire.encode(listOf(number(1, 7),
                binary(3, "private-context".encodeToByteArray()), number(4, 1), number(5, 0))))
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                assertEquals("1", request.url.parameters["rn"])
                assertAnonymous(request.body as OutgoingContent.ByteArrayContent, expectBuffered = false)
                respond(policy + context + packet(offset, cut, sequence), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            try {
                assertFailsWith<CancellationException> {
                    JvmYouTubeDownloader(http).download(plan, output.path) {
                        if (it.bytesDownloaded > 0 && receipt().tracks.single().completedSegments.isNotEmpty())
                            throw CancellationException("Authored stop after durable MEDIA_END receipt")
                    }
                }
                assertEquals(1, requests)
                assertFalse(output.exists())
                assertEquals(listOf(SabrTransferByteRange(offset.toLong(), offset + cut.toLong())), receipt().tracks.single().coverage)
                assertContentEquals(payload.copyOfRange(offset, offset + cut), part.readBytes().copyOfRange(offset, offset + cut))
            } finally { http.close() }
        }

        suspend fun resume(expectBuffered: Boolean, selected: PlannedDownload = plan,
            response: ByteArray = packet(0, cut, 0) + packet(cut, payload.size - cut, 1),
            beforeRequest: () -> Unit = {}, progress: (YouTubeDownloadProgress) -> Unit = {}): List<YouTubeDownloadProgress> {
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("1", request.url.parameters["rn"])
                assertAnonymous(request.body as OutgoingContent.ByteArrayContent, expectBuffered)
                beforeRequest()
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try {
                val result = JvmYouTubeDownloader(http).download(selected, output.path) { updates += it; progress(it) }
                assertEquals(1, requests)
                assertEquals(payload.size.toLong(), result.bytesWritten)
                assertEquals("audio/mp4", result.mimeType)
                return updates
            } finally { http.close() }
        }

        fun checkpoint() = Json.parseToJsonElement(checkpointFile.readText()).jsonObject
        fun receipt() = Json.decodeFromJsonElement(SabrSavedReceipt.serializer(), checkpoint().getValue("sabrReceipt"))
        // Windows BasicFileAttributes.fileKey() is allowed to be null. A distinct,
        // test-owned hard link gives isSameFile a portable retained-file witness.
        fun lockWitness(): File = File(directory, "retained-lock-witness").also {
            Files.createLink(it.toPath(), File(working, "lock").toPath())
        }
        fun assertSameLock(witness: File) {
            assertTrue(Files.isSameFile(witness.toPath(), File(working, "lock").toPath()),
                "Reset or publication replaced the reusable checkpoint lock.")
        }
        fun assertPrivateCheckpoint() {
            val root = checkpoint()
            assertFalse("sabrCheckpoint" in root)
            val forbidden = setOf("resourceGeneration", "playbackCookie", "contexts", "activeEndpoint", "protocolRedirects",
                "requestHeaders", "endpoint", "configuration", "clientInfo", "nextRequestNumber", "observedFormatIds")
            fun inspect(value: JsonElement) {
                when (value) {
                    is JsonObject -> { assertTrue(value.keys.none { it in forbidden }); value.values.forEach(::inspect) }
                    is JsonArray -> value.forEach(::inspect)
                    else -> Unit
                }
            }
            inspect(root)
            for (secret in listOf("https", "googlevideo", "private-cookie", "private-context", "private-endpoint", "private-redirect", "AQID"))
                assertFalse(secret in root.toString())
        }

        fun packet(offset: Int, length: Int, sequence: Int, corrupt: Boolean = false): ByteArray {
            val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, plan.mediaId.encodeToByteArray()),
                number(6, offset.toLong()), number(8, 0), number(9, sequence.toLong()), number(11, sequence * 300L), number(12, 300),
                binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, length.toLong())))
            val bytes = payload.copyOfRange(offset, offset + length)
            if (corrupt) bytes[0] = (bytes[0].toInt() xor 1).toByte()
            return YouTubeUmpFraming.encodePart(20, header) + YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + bytes) +
                YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
        }

        private fun assertAnonymous(body: OutgoingContent.ByteArrayContent, expectBuffered: Boolean) {
            val fields = YouTubeProtobufWire.decode(body.bytes())
            assertEquals(expectBuffered, fields.any { it.number == 3 }, "Only verified complete segments may be acknowledged.")
            val streamer = YouTubeProtobufWire.decode((fields.single { it.number == 19 }.value as SabrWireValue.Bytes).value)
            assertTrue(streamer.none { it.number in setOf(2, 3, 5, 6) }, "Resume must not replay tokens, cookies or opaque contexts.")
        }
        private fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        private fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        override fun close() { check(directory.deleteRecursively()) }
    }
}
