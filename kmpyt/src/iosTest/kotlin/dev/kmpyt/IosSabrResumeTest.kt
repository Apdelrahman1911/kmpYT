@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import platform.Foundation.*
import kotlin.test.*

class IosSabrResumeTest {
    private val payload get() = IosAuthoredMedia.video
    private val cut get() = payload.size / 3
    private val format = SabrFormatId(136, 1uL, "authored")

    @Test fun interruptedUmpBytesResumeAcrossInstancesWithoutPersistingOrReplayingSessionSecrets() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val text = read("${working(directory)}/checkpoint.json").decodeToString()
            listOf("https", "fixture.invalid", "AQID", "private-cookie", "private-context", "resourceGeneration",
                "playbackCookie", "activeEndpoint", "contexts").forEach { assertFalse(it in text) }
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                assertEquals("1", request.url.parameters["rn"])
                val fields = requestFields(request.body as OutgoingContent.ByteArrayContent)
                assertTrue(fields.any { it.number == 3 }, "Committed segments must produce a buffered-range receipt.")
                assertAnonymous(fields)
                // Repeated authorized bytes are compared against the fsynced prefix before new bytes commit.
                respond(packet(0, cut, 0) + packet(cut, payload.size - cut, 1),
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            }) { followRedirects = false }
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try {
                val result = IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it }
                assertEquals(1, requests)
                assertEquals(cut.toLong(), updates.first().bytesDownloaded)
                assertTrue(updates.all { it.totalBytes == payload.size.toLong() })
                assertContentEquals(payload, read(result.destinationPath))
                assertIosResumeResidue(directory, listOf("output.mp4"))
            } finally { http.close() }
        }
    }

    @Test fun changedSabrEndpointOrConfigCannotReusePriorGenerationCoverage() = iosTest {
        for (endpointChanged in listOf(true, false)) inIosTestDirectory { directory ->
            val initial = plan()
            interrupt(directory, initial)
            val source = requireNotNull(initial.primary.sabr)
            val nextSource = if (endpointChanged) source.copy(endpoint = "https://fixture.invalid/new-sabr")
                else source.copy(configuration = "BAUG")
            val selected = initial.copy(primary = initial.primary.copy(url = nextSource.endpoint, sabr = nextSource))
            val http = HttpClient(MockEngine { request ->
                val fields = requestFields(request.body as OutgoingContent.ByteArrayContent)
                assertTrue(fields.none { it.number == 3 }, "A changed actual source must begin without old buffered ranges.")
                assertAnonymous(fields)
                respond(packet(0, payload.size, 0), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try {
                val result = IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it }
                assertEquals(0L, updates.first().bytesDownloaded)
                assertContentEquals(payload, read(result.destinationPath))
            } finally { http.close() }
        }
    }

    @Test fun conflictingSabrReplayDoesNotOverwriteSavedPrefixOrCommitLaterBytes() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val original = read("${working(directory)}/sabr-0.mp4")
            val http = HttpClient(MockEngine {
                respond(packet(0, cut, 0, corrupt = true) + packet(cut, payload.size - cut, 1),
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            try {
                assertFailsWith<SabrTransferException> { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {} }
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/output.mp4"))
                assertContentEquals(original, read("${working(directory)}/sabr-0.mp4"))
                val store = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(selected))
                try {
                    store.load(selected, TransferPlanning.parts(selected))
                    assertEquals(listOf(SabrTransferByteRange(0, cut.toLong())), store.sabr!!.tracks.single().coverage)
                } finally { store.close() }
            } finally { http.close() }
        }
    }

    @Test fun changedLocalSabrBytesAndMissingRangeHashesRestartWithoutBufferedAcknowledgements() = iosTest {
        for (missingHash in listOf(false, true)) inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            if (missingHash) {
                val path = "${working(directory)}/checkpoint.json"
                val root = Json.parseToJsonElement(read(path).decodeToString()).jsonObject
                val receipt = root.getValue("sabr").jsonObject
                val track = receipt.getValue("tracks").jsonArray.single().jsonObject
                val missing = JsonObject(root + ("sabr" to JsonObject(receipt +
                    ("tracks" to JsonArray(listOf(JsonObject(track - "rangeDigests")))))))
                val file = IosMediaFile(path, existing = true)
                try { file.truncate(0); file.write(0, missing.toString().encodeToByteArray()); file.flush() } finally { file.close() }
            } else {
                val file = IosMediaFile("${working(directory)}/sabr-0.mp4", existing = true)
                try {
                    val first = file.read(0, 1).single()
                    file.write(0, byteArrayOf((first.toInt() xor 1).toByte())); file.flush()
                    assertEquals(cut.toLong(), file.length())
                } finally { file.close() }
            }
            val http = HttpClient(MockEngine { request ->
                assertEquals("1", request.url.parameters["rn"])
                val fields = requestFields(request.body as OutgoingContent.ByteArrayContent)
                assertTrue(fields.none { it.number == 3 })
                assertAnonymous(fields)
                respond(packet(0, payload.size, 0), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it } }
            finally { http.close() }
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(payload, read("$directory/output.mp4"))
            assertIosResumeResidue(directory, listOf("output.mp4"))
        }
    }

    @Test fun uncommittedSabrTailIsTruncatedBeforeTheNextRequestButVerifiedCoverageSurvives() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val file = IosMediaFile("${working(directory)}/sabr-0.mp4", existing = true)
            try { file.write(cut.toLong(), byteArrayOf(91, 92, 93)); file.flush() } finally { file.close() }
            val http = HttpClient(MockEngine { request ->
                val fields = requestFields(request.body as OutgoingContent.ByteArrayContent)
                assertTrue(fields.any { it.number == 3 }); assertAnonymous(fields)
                val retained = IosMediaFile("${working(directory)}/sabr-0.mp4", existing = true)
                try { assertEquals(cut.toLong(), retained.length()) } finally { retained.close() }
                respond(packet(cut, payload.size - cut, 1), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {} }
            finally { http.close() }
            assertContentEquals(payload, read("$directory/output.mp4"))
        }
    }

    @Test fun nativeSparseSabrReceiptDoesNotAcknowledgeHolesOrUseFileLengthAsProgress() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected, offset = cut, sequence = 1)
            val file = IosMediaFile("${working(directory)}/sabr-0.mp4", existing = true)
            try {
                assertEquals(2L * cut, file.length())
                file.write(0, ByteArray(cut) { 99 }); file.flush()
            } finally { file.close() }
            val http = HttpClient(MockEngine { request ->
                val fields = requestFields(request.body as OutgoingContent.ByteArrayContent)
                assertTrue(fields.any { it.number == 3 }); assertAnonymous(fields)
                respond(packet(0, cut, 0) + packet(cut, cut, 1) + packet(2 * cut, payload.size - 2 * cut, 2),
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it } }
            finally { http.close() }
            assertEquals(cut.toLong(), updates.first().bytesDownloaded)
            assertContentEquals(payload, read("$directory/output.mp4"))
        }
    }

    private suspend fun interrupt(directory: String, selected: PlannedDownload, offset: Int = 0, sequence: Int = 0) {
        val policy = YouTubeUmpFraming.encodePart(35, YouTubeProtobufWire.encode(listOf(binary(7, "private-cookie".encodeToByteArray()))))
        val context = YouTubeUmpFraming.encodePart(57, YouTubeProtobufWire.encode(listOf(number(1, 7),
            binary(3, "private-context".encodeToByteArray()), number(4, 1), number(5, 0))))
        val http = HttpClient(MockEngine {
            respond(policy + context + packet(offset, cut, sequence), headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"))
        })
        try {
            assertFailsWith<CancellationException> {
                IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {
                    if (it.bytesDownloaded > 0) {
                        val saved = Json.parseToJsonElement(read("${working(directory)}/checkpoint.json").decodeToString()).jsonObject
                        val track = saved.getValue("sabr").jsonObject.getValue("tracks").jsonArray.single().jsonObject
                        if (track.getValue("completedSegments").jsonArray.isNotEmpty())
                            throw CancellationException("after durable UMP response and MEDIA_END receipt")
                    }
                }
            }
            assertContentEquals(payload.copyOfRange(offset, offset + cut),
                read("${working(directory)}/sabr-0.mp4").copyOfRange(offset, offset + cut))
        } finally { http.close() }
    }

    private fun plan(): PlannedDownload {
        val source = YouTubeSabrResource("AUTHORED001", "https://fixture.invalid/sabr", "AQID", format, SabrClientInfo(1, "authored"))
        return PlannedDownload(Representation("136", source.endpoint, TrackRole.VIDEO, mimeType = "video/mp4",
            codecs = "avc1.42c00a", sizeBytes = payload.size.toLong(), width = 32, height = 32,
            transport = TransportKind.SABR, sabr = source), inspectionUrl = "https://fixture.invalid/watch", mediaId = source.videoId)
    }

    private fun packet(offset: Int, length: Int, sequence: Int, corrupt: Boolean = false): ByteArray {
        val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, "AUTHORED001".encodeToByteArray()),
            number(6, offset.toLong()), number(8, 0), number(9, sequence.toLong()), number(11, sequence * 300L), number(12, 300),
            binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, length.toLong())))
        val bytes = payload.copyOfRange(offset, offset + length)
        if (corrupt) bytes[0] = (bytes[0].toInt() xor 1).toByte()
        return YouTubeUmpFraming.encodePart(20, header) + YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + bytes) +
            YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
    }

    private fun requestFields(body: OutgoingContent.ByteArrayContent) = YouTubeProtobufWire.decode(body.bytes())
    private fun assertAnonymous(fields: List<SabrWireField>) {
        val streamer = YouTubeProtobufWire.decode((fields.single { it.number == 19 }.value as SabrWireValue.Bytes).value)
        assertTrue(streamer.none { it.number in setOf(2, 3, 5, 6) }, "A new call must not restore tokens, cookies, or context bytes.")
    }
    private fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
    private fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
    private fun children(directory: String) = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
        .orEmpty().map { it as String }.sorted()
    private fun working(directory: String) = "$directory/${children(directory).single { it.startsWith(".kmpyt-") }}"
    private suspend fun read(path: String): ByteArray {
        val file = IosMediaFile(path, existing = true)
        return try { file.read(0, file.length().toInt()) } finally { file.close() }
    }
}
