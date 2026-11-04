package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Isolated invariants complement actual HTTP/file integration tests in composeApp. */
class YouTubeSabrTransferTest {
    private val format = SabrFormatId(137, ULong.MAX_VALUE, "owned")
    private val clientInfo = SabrClientInfo(1, "controlled")
    private val source = YouTubeSabrSource("ABCDEFGHIJK", "http://localhost/sabr", byteArrayOf(1),
        listOf(YouTubeSabrSourceFormat(format, TrackRole.VIDEO, "video/mp4", 160, 90, null, null, null)), null)
    private class Sink(private val length: Long = 0) : YouTubeSabrTrackSink {
        override suspend fun size() = length
        override suspend fun read(offset: Long, count: Int) = error("No read is permitted before validation")
        override suspend fun write(offset: Long, bytes: ByteArray) = error("No write is permitted before validation")
    }
    private fun checkpoint(coverage: List<SabrTransferByteRange> = emptyList()) =
        SabrTransferCheckpoint(source.videoId, null, format, clientInfo,
            listOf(SabrTransferTrackCheckpoint(TrackRole.VIDEO, format, 100, coverage)),
            resourceGeneration = YouTubeIntegrityBinding.forSabr(source, clientInfo).resourceGeneration)

    private fun redirect(target: String) = YouTubeUmpFraming.encodePart(43,
        YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Bytes(target.encodeToByteArray())))))

    private fun metadata(id: SabrFormatId, videoId: String = source.videoId, mime: String = "video/mp4",
        extra: List<SabrWireField> = emptyList()) = YouTubeUmpFraming.encodePart(42,
        YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Bytes(videoId.encodeToByteArray())),
            SabrWireField(2, SabrWireValue.Bytes(YouTubeSabrProtocol.encodeFormatId(id))),
            SabrWireField(5, SabrWireValue.Bytes(mime.encodeToByteArray()))) + extra))

    private fun packetParts(id: SabrFormatId, payload: ByteArray, headerId: Int = 1,
        videoId: String = source.videoId, length: Long = payload.size.toLong(), offset: Long = 0,
        sequence: Long = 0, compression: Long? = null, topItag: Long? = null): List<ByteArray> {
        fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        val header = YouTubeProtobufWire.encode(listOf(number(1, headerId.toLong()), binary(2, videoId.encodeToByteArray()),
            number(6, offset), number(8, 0), number(9, sequence), number(11, 0), number(12, 100),
            binary(13, YouTubeSabrProtocol.encodeFormatId(id)), number(14, length)) +
            listOfNotNull(compression?.let { number(7, it) }, topItag?.let { number(3, it) }))
        return listOf(YouTubeUmpFraming.encodePart(20, header), YouTubeUmpFraming.encodePart(21, byteArrayOf(headerId.toByte()) + payload),
            YouTubeUmpFraming.encodePart(22, byteArrayOf(headerId.toByte())))
    }

    private fun completePacket(id: SabrFormatId, payload: ByteArray) = packetParts(id, payload).fold(byteArrayOf()) { a, b -> a + b }

    @Test fun validUnselectedMetadataIsInformationalAndSelectedMediaStillCompletesExactly() = runTest {
        val extraFormat = SabrFormatId(251, 1uL, "unselected")
        val payload = ByteArray(100) { it.toByte() }; val output = ByteArray(100); var size = 0L; var requests = 0
        val selectedParts = packetParts(format, payload)
        val extraPayload = ByteArray(63) { (it + 77).toByte() }
        val extraParts = packetParts(extraFormat, extraPayload, headerId = 2)
        val sink = object : YouTubeSabrTrackSink {
            override suspend fun size() = size
            override suspend fun read(offset: Long, count: Int) = output.copyOfRange(offset.toInt(), offset.toInt() + count)
            override suspend fun write(offset: Long, bytes: ByteArray) { bytes.copyInto(output, offset.toInt()); size = maxOf(size, offset + bytes.size) }
        }
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requests++
                respond(metadata(extraFormat, mime = "audio/webm") + metadata(format) +
                    selectedParts[0] + extraParts[0] + selectedParts[1] + extraParts[1] + extraParts[2] + selectedParts[2],
                    headers = headersOf("Content-Type", "application/vnd.yt-ump"))
            }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {})
            assertEquals(1, requests); assertContentEquals(payload, output)
            assertEquals(listOf(format), result.checkpoint.receivedFormatIds)
            assertEquals(listOf(extraFormat, format), result.checkpoint.observedFormatIds)
            assertEquals(listOf(format), result.checkpoint.tracks.map { it.formatId })
            assertEquals(100L, result.outputBytesByRole.getValue(TrackRole.VIDEO))
            val receipt = result.responseDiagnostics.single()
            assertEquals(100L, receipt.mediaPayloadBytes); assertEquals(100L, receipt.committedBytes)
            assertEquals(63L, receipt.ignoredMediaPayloadBytes); assertEquals(1, receipt.ignoredMediaPacketCount)
        } finally { transfer.close(); client.close() }
    }

    @Test fun registeredUnselectedMediaIsDiscardedWithoutOutputAndNeverBypassesEmptyMandatoryProtection() = runTest {
        val extraFormat = SabrFormatId(136, 1uL, "unselected")
        val protection = YouTubeUmpFraming.encodePart(58, YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(3uL)))))
        for ((reply, expectedCode) in listOf(
            metadata(extraFormat) + completePacket(extraFormat, ByteArray(100)) to "SABR_EMPTY_CONTINUATION",
            metadata(extraFormat) + completePacket(extraFormat, ByteArray(100)) + protection to "SABR_PROTECTION_CONTEXT_UNAVAILABLE")) {
            var writes = 0; var requests = 0
            val sink = object : YouTubeSabrTrackSink {
                override suspend fun size() = 0L
                override suspend fun read(offset: Long, count: Int) = error("No committed media to read")
                override suspend fun write(offset: Long, bytes: ByteArray) { writes++ }
            }
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { requests++; respond(reply, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
            } }
            val transfer = YouTubeSabrTransfer(client)
            try {
                val failure = assertFailsWith<SabrTransferException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {}) }
                assertEquals(expectedCode, failure.code); assertEquals(1, requests); assertEquals(0, writes)
                assertTrue(failure.checkpoint.receivedFormatIds.isEmpty()); assertTrue(failure.checkpoint.tracks.single().coverage.isEmpty())
                val receipt = failure.responseDiagnostics.single()
                assertTrue(receipt.completeFraming); assertEquals(0L, receipt.mediaPayloadBytes); assertEquals(0L, receipt.committedBytes)
                assertEquals(100L, receipt.ignoredMediaPayloadBytes); assertEquals(1, receipt.ignoredMediaPacketCount)
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun unknownForeignInconsistentOrIncompleteExtraPacketsPreventAllSelectedPublication() = runTest {
        val extraFormat = SabrFormatId(251, 1uL, "unselected")
        val selected = completePacket(format, ByteArray(100) { it.toByte() })
        val extraPayload = ByteArray(63) { 77 }
        fun join(parts: List<ByteArray>) = parts.fold(byteArrayOf()) { a, b -> a + b }
        for ((extra, code) in listOf(
            completePacket(extraFormat.copy(lastModified = 2uL), extraPayload) to "SABR_MEDIA_FORMAT_ID_MISMATCH",
            join(packetParts(extraFormat, extraPayload, topItag = 250)) to "SABR_MEDIA_FORMAT_ID_CONFLICT",
            join(packetParts(extraFormat, extraPayload, videoId = "ZYXWVUTSRQP")) to "SABR_MEDIA_VIDEO_ID_MISMATCH",
            join(packetParts(extraFormat, extraPayload, length = 64)) to "SABR_PACKET_LENGTH_INCOMPLETE",
            join(packetParts(extraFormat, extraPayload).take(2)) to "SABR_NETWORK_RECOVERY_EXHAUSTED",
            join(packetParts(extraFormat, extraPayload, offset = Long.MAX_VALUE)) to "SABR_IGNORED_PACKET_RANGE_INVALID",
            join(packetParts(extraFormat, extraPayload, length = SabrUmpLimits().maxTotalBytes + 1)) to "SABR_IGNORED_PACKET_RANGE_INVALID",
            join(packetParts(extraFormat, extraPayload, sequence = -1)) to "SABR_SEGMENT_SEQUENCE_INVALID",
            join(packetParts(extraFormat, extraPayload, compression = 2)) to "SABR_MEDIA_COMPRESSION_UNSUPPORTED")) {
            var requests = 0; var writes = 0
            val sink = object : YouTubeSabrTrackSink {
                override suspend fun size() = 0L
                override suspend fun read(offset: Long, count: Int) = error("No classified bytes to read")
                override suspend fun write(offset: Long, bytes: ByteArray) { writes++ }
            }
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { requests++; respond(metadata(extraFormat) + selected + extra, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
            } }
            val transfer = YouTubeSabrTransfer(client, SabrTransferLimits(maxRetries = 0))
            try {
                val failure = assertFailsWith<SabrTransferException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {}) }
                assertEquals(code, failure.code); assertEquals(1, requests); assertEquals(0, writes)
                assertTrue(failure.checkpoint.observedFormatIds.isEmpty()) // Unclassified registry never persists.
                assertTrue(failure.checkpoint.receivedFormatIds.isEmpty()); assertTrue(failure.checkpoint.tracks.single().coverage.isEmpty())
                assertEquals(0L, failure.responseDiagnostics.single().committedBytes)
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun resumedBoundMetadataRegistryAllowsExactDiscardButNeverChangesSelectedRequestAcknowledgments() = runTest {
        val extraFormat = SabrFormatId(251, 1uL, "unselected")
        val payload = ByteArray(100) { it.toByte() }; val output = ByteArray(100); var size = 0L
        val saved = checkpoint().copy(observedFormatIds = listOf(extraFormat), completedRounds = 1, nextRequestNumber = 2)
        val sink = object : YouTubeSabrTrackSink {
            override suspend fun size() = size
            override suspend fun read(offset: Long, count: Int) = output.copyOfRange(offset.toInt(), offset.toInt() + count)
            override suspend fun write(offset: Long, bytes: ByteArray) { bytes.copyInto(output, offset.toInt()); size = maxOf(size, offset + bytes.size) }
        }
        var requests = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests++
                val fields = YouTubeProtobufWire.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                assertTrue(fields.none { it.number == 2 }) // The discard registry is not the generic selected-format ACK.
                assertTrue(fields.none { it.number == 16 }) // Still video-only selection.
                val selectedId = YouTubeSabrProtocol.decodeFormatId((fields.single { it.number == 17 }.value as SabrWireValue.Bytes).value)
                assertEquals(format, selectedId)
                respond(completePacket(extraFormat, ByteArray(63)) + completePacket(format, payload),
                    headers = headersOf("Content-Type", "application/vnd.yt-ump"))
            }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo, mapOf(format to 100L),
                mapOf(TrackRole.VIDEO to sink), {}, saved)
            assertEquals(1, requests); assertContentEquals(payload, output)
            assertEquals(listOf(extraFormat), result.checkpoint.observedFormatIds)
            assertEquals(listOf(format), result.checkpoint.receivedFormatIds)
            assertEquals(saved.resourceGeneration, result.checkpoint.resourceGeneration)
            assertEquals(63L, result.responseDiagnostics.single().ignoredMediaPayloadBytes)
        } finally { transfer.close(); client.close() }
    }

    @Test fun observedMetadataRegistryIsBoundUniqueAndCappedBeforeAnyResumeHttp() = runTest {
        val extraFormat = SabrFormatId(251, 1uL, "unselected")
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Invalid registry must not reach HTTP") })
        val transfer = YouTubeSabrTransfer(client)
        try {
            for (saved in listOf(
                checkpoint().copy(observedFormatIds = listOf(extraFormat, extraFormat)),
                checkpoint().copy(observedFormatIds = (1..513).map { SabrFormatId(it, 1uL, "unselected") }),
                checkpoint().copy(observedFormatIds = listOf(extraFormat), resourceGeneration = "different-extraction-generation"))) {
                assertFailsWith<IllegalArgumentException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {}, saved) }
            }
            val legacy = assertFailsWith<SabrTransferException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {}, checkpoint().copy(observedFormatIds = listOf(extraFormat), resourceGeneration = null)) }
            assertEquals("SABR_CHECKPOINT_RESOURCE_BINDING_MISSING", legacy.code); assertEquals(0, requests)
        } finally { transfer.close(); client.close() }
    }

    @Test fun unselectedMetadataStillValidatesVideoWireSchemaAndSelectedMetadataKeepsMimeValidation() = runTest {
        val extraFormat = SabrFormatId(136, 1uL, "unselected")
        val malformedRange = YouTubeProtobufWire.encode(listOf(SabrWireField(3, SabrWireValue.Varint(0x1_0000_0002uL))))
        val missingId = YouTubeUmpFraming.encodePart(42, YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Bytes(source.videoId.encodeToByteArray())))))
        val incompleteId = YouTubeUmpFraming.encodePart(42, YouTubeProtobufWire.encode(listOf(SabrWireField(2, SabrWireValue.Bytes(byteArrayOf())))))
        for ((reply, expectedCode) in listOf(
            metadata(extraFormat, videoId = "ZYXWVUTSRQP") to "SABR_MALFORMED_PROTOCOL",
            metadata(extraFormat, extra = listOf(SabrWireField(3, SabrWireValue.Bytes(byteArrayOf(1))))) to "SABR_MALFORMED_PROTOCOL",
            metadata(extraFormat, extra = listOf(SabrWireField(6, SabrWireValue.Bytes(malformedRange)))) to "SABR_MALFORMED_PROTOCOL",
            missingId to "SABR_FORMAT_INITIALIZATION_ID_MISSING", incompleteId to "SABR_FORMAT_INITIALIZATION_ID_MISSING",
            metadata(format, mime = "video/webm") to "SABR_FORMAT_MIME_CONFLICT")) {
            var requests = 0
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { requests++; respond(reply, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
            } }
            val transfer = YouTubeSabrTransfer(client)
            try {
                val failure = assertFailsWith<SabrTransferException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {}) }
                assertEquals(expectedCode, failure.code); assertEquals(1, requests)
                assertTrue(failure.checkpoint.receivedFormatIds.isEmpty()); assertTrue(failure.checkpoint.tracks.single().coverage.isEmpty())
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun explicitlyExpiredRedirectStopsBeforeNextPostWithoutExtendingOrReplacingSourceGeneration() = runTest {
        val futureSource = YouTubeSabrSource(source.videoId, source.endpoint, source.ustreamerConfig, source.formats, 100_000)
        for (expiry in listOf("1", "20", "invalid", "-1", Long.MAX_VALUE.toString())) {
            val target = "http://localhost/redirected?expire=$expiry"
            var requests = 0
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    requests++
                    if (requests == 1) respond(redirect(target), headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                    else throw IllegalArgumentException("Stop the isolated test once a valid/unknown expiry permits the second POST")
                }
            } }
            val transfer = YouTubeSabrTransfer(client)
            try {
                val failure = assertFailsWith<SabrTransferException> {
                    transfer.download(futureSource, YouTubeSabrSelection(video = format), clientInfo,
                        mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {}, nowEpochMs = { 2_000 })
                }
                assertEquals(if (expiry == "1") "SABR_REDIRECT_URL_EXPIRED" else "SABR_HTTP_REQUEST_FAILED", failure.code)
                assertEquals(if (expiry == "1") 1 else 2, requests)
                assertEquals(target, failure.checkpoint.activeEndpoint)
                assertEquals(YouTubeIntegrityBinding.forSabr(futureSource, clientInfo).resourceGeneration, failure.checkpoint.resourceGeneration)
                assertEquals(100_000L, futureSource.expiresAtEpochMs)
                assertTrue(failure.checkpoint.tracks.single().coverage.isEmpty())
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun cookieIsUsedForNextRequestButNotReplayedAfterACompleteReplyOmitsIt() = runTest {
        val payload = ByteArray(100) { it.toByte() }
        val cookie = YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(21uL))))
        val policy = YouTubeUmpFraming.encodePart(35, YouTubeProtobufWire.encode(listOf(SabrWireField(7, SabrWireValue.Bytes(cookie)))))
        val policyWithoutCookie = YouTubeUmpFraming.encodePart(35, byteArrayOf())
        fun packet(offset: Int, length: Int, sequence: Int): ByteArray {
            fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
            fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
            val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, source.videoId.encodeToByteArray()),
                number(6, offset.toLong()), number(8, 0), number(9, sequence.toLong()), number(11, sequence * 100L), number(12, 100),
                binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, length.toLong())))
            return YouTubeUmpFraming.encodePart(20, header) +
                YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + payload.copyOfRange(offset, offset + length)) +
                YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
        }
        var requests = 0; var size = 0L; val written = ByteArray(100)
        val sink = object : YouTubeSabrTrackSink {
            override suspend fun size() = size
            override suspend fun read(offset: Long, count: Int) = written.copyOfRange(offset.toInt(), offset.toInt() + count)
            override suspend fun write(offset: Long, bytes: ByteArray) { bytes.copyInto(written, offset.toInt()); size = maxOf(size, offset + bytes.size) }
        }
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests++
                val fields = YouTubeProtobufWire.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                val streamer = YouTubeProtobufWire.decode((fields.single { it.number == 19 }.value as SabrWireValue.Bytes).value)
                val supplied = (streamer.singleOrNull { it.number == 3 }?.value as? SabrWireValue.Bytes)?.value
                if (requests == 2) assertContentEquals(cookie, supplied) else assertNull(supplied)
                val response = when (requests) {
                    1 -> packet(0, 40, 0) + policy
                    // A later actual policy also replaces an earlier cookie
                    // within this complete response when field7 is omitted.
                    2 -> packet(40, 30, 1) + policy + policyWithoutCookie
                    else -> packet(70, 30, 2)
                }
                respond(response, headers = headersOf("Content-Type", "application/vnd.yt-ump"))
            }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {})
            assertEquals(3, requests); assertContentEquals(payload, written); assertNull(result.checkpoint.playbackCookie)
        } finally { transfer.close(); client.close() }
    }

    @Test fun emptyMandatoryProtectionBeforeRedirectRequiresRealIssuerAndNeverFollowsTheTarget() = runTest {
        val target = "http://localhost/redirected?private=server-value"
        val protection = YouTubeUmpFraming.encodePart(58,
            YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(3uL)))))
        var requests = 0
        val checked = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { requests++; respond(redirect(target) + protection, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val failure = assertFailsWith<SabrTransferException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), { checked += it })
            }
            assertEquals("SABR_PROTECTION_CONTEXT_UNAVAILABLE", failure.code); assertEquals(1, requests)
            assertEquals(3, failure.protectionStatus); assertTrue(failure.responseDiagnostics.single().completeFraming)
            assertEquals(0L, failure.responseDiagnostics.single().committedBytes)
            assertNull(failure.checkpoint.activeEndpoint); assertTrue(failure.checkpoint.protocolRedirects.isEmpty())
            assertFalse(checked.any { it.contains("/redirected") }); assertFalse(failure.toString().contains("server-value"))
        } finally { transfer.close(); client.close() }
    }

    @Test fun acceptedRedirectCheckpointResumesAtTheServerEndpointWithSameGenerationCounterAndCookie() = runTest {
        val target = "http://localhost/redirected?private=server-value"
        val cookie = YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(21uL))))
        val policy = YouTubeUmpFraming.encodePart(35, YouTubeProtobufWire.encode(listOf(SabrWireField(7, SabrWireValue.Bytes(cookie)))))
        val payload = ByteArray(100) { it.toByte() }
        fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, source.videoId.encodeToByteArray()),
            number(6, 0), number(8, 0), number(9, 0), number(11, 0), number(12, 100),
            binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, 100)))
        val media = YouTubeUmpFraming.encodePart(20, header) + YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + payload) +
            YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
        var requests = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests++
                assertEquals(requests.toString(), request.url.parameters["rn"])
                if (requests == 1) {
                    assertEquals("/sabr", request.url.encodedPath)
                    respond(redirect(target) + policy, headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                } else {
                    assertEquals("/redirected", request.url.encodedPath)
                    val fields = YouTubeProtobufWire.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
                    assertContentEquals(source.ustreamerConfig, (fields.single { it.number == 5 }.value as SabrWireValue.Bytes).value)
                    val streamer = YouTubeProtobufWire.decode((fields.single { it.number == 19 }.value as SabrWireValue.Bytes).value)
                    assertContentEquals(cookie, (streamer.single { it.number == 3 }.value as SabrWireValue.Bytes).value)
                    assertTrue(streamer.none { it.number == 2 }) // No context is fabricated for continuation.
                    respond(media, headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                }
            }
        } }
        val transfer = YouTubeSabrTransfer(client)
        var saved: SabrTransferCheckpoint? = null
        var size = 0L; val written = ByteArray(100)
        val sink = object : YouTubeSabrTrackSink {
            override suspend fun size() = size
            override suspend fun read(offset: Long, count: Int) = written.copyOfRange(offset.toInt(), offset.toInt() + count)
            override suspend fun write(offset: Long, bytes: ByteArray) { bytes.copyInto(written, offset.toInt()); size = offset + bytes.size }
        }
        try {
            assertFailsWith<CancellationException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {}, onCheckpoint = {
                        if (it.activeEndpoint != null) { saved = it; throw CancellationException("Pause after classified redirect") }
                    })
            }
            val checkpoint = assertNotNull(saved)
            assertEquals(target, checkpoint.activeEndpoint); assertEquals(listOf(target), checkpoint.protocolRedirects)
            assertEquals(2, checkpoint.nextRequestNumber)
            assertEquals(YouTubeIntegrityBinding.forSabr(source, clientInfo).resourceGeneration, checkpoint.resourceGeneration)
            assertFalse(checkpoint.toString().contains(target)); assertFalse(checkpoint.toString().contains("server-value"))
            val result = transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {}, checkpoint)
            assertContentEquals(payload, written); assertEquals(2, requests)
            assertEquals(checkpoint.resourceGeneration, result.checkpoint.resourceGeneration)
            assertEquals(target, result.checkpoint.activeEndpoint); assertEquals(2, result.rounds)
            assertNull(result.checkpoint.playbackCookie)
        } finally { transfer.close(); client.close() }
    }

    @Test fun protocolRedirectCyclesLimitsConflictsAndDeniedDestinationsNeverOpenAnUnacceptedTarget() = runTest {
        val first = "http://localhost/first"
        val second = "http://localhost/second"
        val cases = listOf("SABR_PROTOCOL_REDIRECT_LOOP", "SABR_PROTOCOL_REDIRECT_LIMIT",
            "SABR_CONFLICTING_PROTOCOL_REDIRECTS", "SABR_RESOURCE_NOT_AUTHORIZED")
        for (code in cases) {
            var requests = 0
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    requests++
                    val response = when (code) {
                        "SABR_PROTOCOL_REDIRECT_LOOP" -> redirect(source.endpoint)
                        "SABR_PROTOCOL_REDIRECT_LIMIT" -> redirect(if (requests == 1) first else second)
                        "SABR_CONFLICTING_PROTOCOL_REDIRECTS" -> redirect(first) + redirect(second)
                        else -> redirect(first)
                    }
                    respond(response, headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                }
            } }
            val transfer = YouTubeSabrTransfer(client, SabrTransferLimits(maxProtocolRedirects = 1))
            try {
                val failure = assertFailsWith<SabrTransferException> {
                    transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                        mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), { url ->
                            if (code == "SABR_RESOURCE_NOT_AUTHORIZED") require(!url.contains("/first"))
                        })
                }
                assertEquals(code, failure.code)
                assertEquals(if (code == "SABR_PROTOCOL_REDIRECT_LIMIT") 2 else 1, requests)
                assertTrue(failure.checkpoint.tracks.single().coverage.isEmpty())
                assertFalse(failure.responseDiagnostics.toString().contains(first))
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun resumedRedirectHistoryRejectsInvalidIdentityCredentialsCyclesAndTlsDowngradeBeforeHttp() = runTest {
        val target = "http://localhost/redirected"
        val secureSource = YouTubeSabrSource(source.videoId, "https://localhost/sabr", source.ustreamerConfig, source.formats, null)
        val candidates = listOf(
            Triple(source, checkpoint().copy(activeEndpoint = target), "SABR_REDIRECT_CHECKPOINT_INVALID"),
            Triple(source, checkpoint().copy(activeEndpoint = target, protocolRedirects = listOf(target)), "SABR_REDIRECT_CHECKPOINT_INVALID"),
            Triple(source, checkpoint().copy(activeEndpoint = target, protocolRedirects = listOf("http://localhost/other"), completedRounds = 1, nextRequestNumber = 2), "SABR_REDIRECT_CHECKPOINT_INVALID"),
            Triple(source, checkpoint().copy(activeEndpoint = target, protocolRedirects = listOf(target, target), completedRounds = 2, nextRequestNumber = 3), "SABR_PROTOCOL_REDIRECT_LOOP"),
            Triple(source, checkpoint().copy(activeEndpoint = "http://user:secret@localhost/redirected", protocolRedirects = listOf("http://user:secret@localhost/redirected"), completedRounds = 1, nextRequestNumber = 2), "SABR_REDIRECT_INVALID"),
            Triple(secureSource, checkpoint().copy(activeEndpoint = target, protocolRedirects = listOf(target), completedRounds = 1, nextRequestNumber = 2,
                resourceGeneration = YouTubeIntegrityBinding.forSabr(secureSource, clientInfo).resourceGeneration), "SABR_REDIRECT_TLS_DOWNGRADE"))
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Invalid saved migration must not open HTTP") })
        val transfer = YouTubeSabrTransfer(client)
        try {
            for ((original, saved, code) in candidates) {
                val failure = assertFailsWith<SabrTransferException> {
                    transfer.download(original, YouTubeSabrSelection(video = format), clientInfo,
                        mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {}, saved)
                }
                assertEquals(code, failure.code); assertFalse(failure.toString().contains("secret"))
            }
            assertEquals(0, requests)
        } finally { transfer.close(); client.close() }
    }

    @Test fun requestPipelineIllegalArgumentIsNotReportedAsMalformedUmpAndRetainsOnlySafeFrames() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requests++
                throw IllegalArgumentException("bad URI https://localhost/sabr?signature=private-token")
            }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val failure = assertFailsWith<SabrTransferException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {})
            }
            assertEquals("SABR_HTTP_REQUEST_FAILED", failure.code); assertEquals(1, requests)
            assertNull(failure.cause)
            val receipt = failure.responseDiagnostics.single()
            assertNull(receipt.httpStatus); assertEquals(0L, receipt.receivedBytes); assertTrue(receipt.mediaHeaders.isEmpty())
            assertEquals(SabrTransferFailureStage.HTTP_REQUEST, receipt.failureStage)
            assertEquals("IllegalArgumentException", receipt.transportFailureType)
            // Kotlin/Native uses #method/#internal symbols, and optimized runtimes may
            // strip frames. Unrecognized formats must be omitted rather than exposed.
            assertTrue(receipt.failureFrames.size <= 8)
            assertTrue(receipt.failureFrames.all { it.matches(Regex("[A-Za-z0-9_.$<>]+")) && '.' in it })
            assertFalse(receipt.toString().contains("private-token")); assertFalse(receipt.toString().contains("bad URI"))
            assertFalse(receipt.toString().contains("https://")); assertFalse(receipt.toString().contains(".kt:"))
        } finally { transfer.close(); client.close() }
    }

    @Test fun invalidReceivedProtobufStillReportsMalformedUmpWithTheActualParserStage() = runTest {
        val invalidHeader = YouTubeUmpFraming.encodePart(20, byteArrayOf(0)) // Illegal protobuf field number zero.
        var requests = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { requests++; respond(invalidHeader, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
        } }
        val transfer = YouTubeSabrTransfer(client)
        try {
            val failure = assertFailsWith<SabrTransferException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink()), {})
            }
            assertEquals("SABR_MALFORMED_PROTOCOL", failure.code); assertEquals(1, requests)
            val receipt = failure.responseDiagnostics.single()
            assertEquals(200, receipt.httpStatus); assertEquals(invalidHeader.size.toLong(), receipt.receivedBytes)
            assertEquals(SabrTransferFailureStage.UMP_PARSE, receipt.failureStage)
            assertEquals("IllegalArgumentException", receipt.transportFailureType)
            assertEquals(0L, receipt.committedBytes); assertFalse(receipt.completeFraming)
        } finally { transfer.close(); client.close() }
    }

    @Test fun successfulReceiptHasNoFailureStageAndFailingObserverIsRecordedWithoutRecursiveCallback() = runTest {
        val payload = ByteArray(100) { it.toByte() }
        fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, source.videoId.encodeToByteArray()),
            number(6, 0), number(8, 0), number(9, 0), number(11, 0), number(12, 100),
            binary(13, YouTubeSabrProtocol.encodeFormatId(format)), number(14, payload.size.toLong())))
        val response = YouTubeUmpFraming.encodePart(20, header) +
            YouTubeUmpFraming.encodePart(21, byteArrayOf(1) + payload) + YouTubeUmpFraming.encodePart(22, byteArrayOf(1))
        for (failObserver in listOf(false, true)) {
            val written = ByteArray(100); var size = 0L; var observations = 0
            val sink = object : YouTubeSabrTrackSink {
                override suspend fun size() = size
                override suspend fun read(offset: Long, count: Int) = written.copyOfRange(offset.toInt(), offset.toInt() + count)
                override suspend fun write(offset: Long, bytes: ByteArray) {
                    bytes.copyInto(written, offset.toInt()); size = maxOf(size, offset + bytes.size)
                }
            }
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond(response, headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
            } }
            val transfer = YouTubeSabrTransfer(client)
            try {
                val outcome = runCatching { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to sink), {}, onResponse = {
                        observations++
                        if (failObserver) throw IllegalArgumentException("observer-private-token")
                    }) }
                assertContentEquals(payload, written); assertEquals(1, observations)
                if (failObserver) {
                    val failure = outcome.exceptionOrNull() as SabrTransferException
                    assertEquals("SABR_TRANSFER_FAILED", failure.code)
                    val receipt = failure.responseDiagnostics.single()
                    assertEquals(SabrTransferFailureStage.CALLBACK, receipt.failureStage)
                    assertEquals("IllegalArgumentException", receipt.transportFailureType)
                    assertFalse(receipt.toString().contains("observer-private-token"))
                } else {
                    val receipt = outcome.getOrThrow().responseDiagnostics.single()
                    assertNull(receipt.failureStage); assertNull(receipt.transportFailureType); assertTrue(receipt.failureFrames.isEmpty())
                }
            } finally { transfer.close(); client.close() }
        }
    }

    @Test fun byteAndSegmentRangesRejectOverflowAndInvalidBounds() {
        assertFailsWith<IllegalArgumentException> { SabrTransferByteRange(-1, 2) }
        assertFailsWith<IllegalArgumentException> { SabrTransferByteRange(1, 1) }
        assertFailsWith<IllegalArgumentException> { SabrTransferSegment(Long.MAX_VALUE, 1, 0, 0, 0, false) }
        assertFailsWith<IllegalArgumentException> { SabrTransferSegment(0, 1, -1, 0, 0, false) }
        assertFailsWith<IllegalArgumentException> { SabrTransferSegment(0, 1, 0, Long.MAX_VALUE, 1, false) }
    }

    @Test fun checkpointSerializesUnsignedSourceIdentityAndRedactsOpaqueServerState() {
        val original = checkpoint().copy(playbackCookie = "private-cookie".encodeToByteArray(),
            contexts = listOf(SabrTransferContextCheckpoint(7, "private-context".encodeToByteArray())),
            activeContextTypes = listOf(7))
        val restored = Json.decodeFromString<SabrTransferCheckpoint>(Json.encodeToString(original))
        assertEquals(ULong.MAX_VALUE, restored.selectedVideo!!.lastModified)
        assertEquals(original.tracks, restored.tracks)
        assertContentEquals(original.playbackCookie, restored.playbackCookie)
        assertFalse(original.toString().contains("private-cookie"))
        assertFalse(original.contexts.toString().contains("private-context"))
        assertFalse(SabrTransferException("SABR_SERVER_ERROR", original).toString().contains("private-cookie"))
    }

    @Test fun mismatchedOrMalformedCheckpointNeverReachesHttp() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("HTTP must not be reached") })
        val transfer = YouTubeSabrTransfer(client, SabrTransferLimits(maxContextBytes = 4))
        try {
            val candidates = listOf(checkpoint().copy(videoId = "ZYXWVUTSRQP"),
                checkpoint().copy(clientInfo = SabrClientInfo(2, "different")),
                checkpoint().copy(nextRequestNumber = 0),
                checkpoint().copy(completedRounds = 1),
                checkpoint().copy(completedRounds = Int.MAX_VALUE),
                checkpoint().copy(contexts = listOf(SabrTransferContextCheckpoint(7, ByteArray(5)))),
                checkpoint().copy(contexts = listOf(SabrTransferContextCheckpoint(7, ByteArray(3))), playbackCookie = ByteArray(2)),
                checkpoint(listOf(SabrTransferByteRange(50, 100), SabrTransferByteRange(0, 50))),
                checkpoint(listOf(SabrTransferByteRange(0, 101))),
                checkpoint().copy(tracks = checkpoint().tracks.map { it.copy(completedSegments =
                    listOf(SabrTransferSegment(0, 100, 0, 0, 500, false))) }))
            for (candidate in candidates) assertFailsWith<IllegalArgumentException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink(101)), {}, candidate)
            }
            assertEquals(0, requests)
        } finally { transfer.close(); client.close() }
    }

    @Test fun truncatedCheckpointFileIsRejectedBeforeHttpOrWriting() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("HTTP must not be reached") })
        val transfer = YouTubeSabrTransfer(client)
        try {
            val failure = assertFailsWith<SabrTransferException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink(20)), {},
                    checkpoint(listOf(SabrTransferByteRange(0, 50))))
            }
            assertEquals("SABR_CHECKPOINT_FILE_TRUNCATED", failure.code); assertEquals(0, requests)
        } finally { transfer.close(); client.close() }
    }

    @Test fun sourceSelectionLengthAndRoleValidationHappensBeforeHttp() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("HTTP must not be reached") })
        val transfer = YouTubeSabrTransfer(client, SabrTransferLimits(maxOutputBytes = 100))
        try {
            assertFailsWith<IllegalArgumentException> { transfer.download(source, YouTubeSabrSelection(video = SabrFormatId(138)), clientInfo,
                mapOf(SabrFormatId(138) to 20L), mapOf(TrackRole.VIDEO to Sink()), {}) }
            assertFailsWith<IllegalArgumentException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 101L), mapOf(TrackRole.VIDEO to Sink()), {}) }
            assertFailsWith<IllegalArgumentException> { transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                mapOf(format to 20L), mapOf(TrackRole.AUDIO to Sink()), {}) }
            assertEquals(0, requests)
        } finally { transfer.close(); client.close() }
    }

    @Test fun changedEndpointConfigurationAndUnboundLegacyStateNeverReachHttp() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Generation mismatch must stop before HTTP") })
        val transfer = YouTubeSabrTransfer(client)
        try {
            val saved = checkpoint(listOf(SabrTransferByteRange(0, 50)))
            val changedEndpoint = YouTubeSabrSource(source.videoId, "http://localhost/different-sabr", source.ustreamerConfig, source.formats, null)
            val changedConfig = YouTubeSabrSource(source.videoId, source.endpoint, byteArrayOf(2), source.formats, null)
            for (changed in listOf(changedEndpoint, changedConfig)) assertFailsWith<IllegalArgumentException> {
                transfer.download(changed, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink(50)), {}, saved)
            }
            val failure = assertFailsWith<SabrTransferException> {
                transfer.download(source, YouTubeSabrSelection(video = format), clientInfo,
                    mapOf(format to 100L), mapOf(TrackRole.VIDEO to Sink(50)), {}, saved.copy(resourceGeneration = null))
            }
            assertEquals("SABR_CHECKPOINT_RESOURCE_BINDING_MISSING", failure.code)
            assertEquals(0, requests)
            assertFalse(failure.toString().contains("localhost"))
        } finally { transfer.close(); client.close() }
    }
}
