package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real binary Ktor transport and protocol code; responses isolate controlled protocol cases. */
class YouTubeInitialSabrProbeTest {
    // Ktor's engine dispatcher uses real time. Leaving the outer probe deadline
    // on runTest's virtual scheduler lets it advance 30 seconds while that engine
    // is still returning its response, producing a false timeout in milliseconds.
    private fun transportTest(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }
    private val context = ExtractionContext("https://www.youtube.com/watch?v=ABCDEFGHIJK", "https://owned.test/player", 1_000)
    private val clientInfo = SabrClientInfo(1, "observed-owned-client")
    private val selection = YouTubeSabrSelection(SabrFormatId(140, 300uL, "a"), SabrFormatId(135, 401uL, "low"))
    private fun source(endpoint: String = "https://owned.test/sabr?n=original-n&token=private-value", id: String = "ABCDEFGHIJK", config: String = "AQID") = buildJsonObject {
        putJsonObject("videoDetails") { put("videoId", id) }
        putJsonObject("playabilityStatus") { put("status", "OK") }
        putJsonObject("streamingData") {
            put("serverAbrStreamingUrl", endpoint)
            putJsonArray("adaptiveFormats") {
                add(format(140, "audio/mp4", 300, "a"))
                add(format(137, "video/mp4", 400, "high", 720))
                add(format(135, "video/mp4", 401, "low", 360))
            }
        }
        putJsonObject("playerConfig") { putJsonObject("mediaCommonConfig") { putJsonObject("mediaUstreamerRequestConfig") { put("videoPlaybackUstreamerConfig", config) } } }
    }.toString()
    private fun format(itag: Int, mime: String, modified: Long, xtags: String, height: Int? = null) = buildJsonObject {
        put("itag", itag); put("mimeType", mime); put("lastModified", modified.toString()); put("xtags", xtags)
        height?.let { put("height", it); put("width", it * 16 / 9) }
    }
    private fun response(body: String = source()) = ResearchResponse(context.responseUrl, 200, body)
    private fun probe(client: HttpClient, limits: YouTubeSabrProbeLimits = YouTubeSabrProbeLimits()) = YouTubeInitialSabrProbe(client, YouTubeSabrProbeAuthorization("ABCDEFGHIJK"), limits)
    private fun binary() =
        YouTubeUmpFraming.encodePart(20, YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(3uL)),
            SabrWireField(2, SabrWireValue.Bytes("ABCDEFGHIJK".encodeToByteArray())),
            SabrWireField(13, SabrWireValue.Bytes(YouTubeSabrProtocol.encodeFormatId(selection.video!!)))))) +
        YouTubeUmpFraming.encodePart(21, byteArrayOf(3, 1, 2, 3, 4)) +
        YouTubeUmpFraming.encodePart(22, byteArrayOf(3)) +
        YouTubeUmpFraming.encodePart(58, byteArrayOf(8, 2, 16, 1)) +
        YouTubeUmpFraming.encodePart(44, byteArrayOf(10, 6) + "secret".encodeToByteArray() + byteArrayOf(16, 7))

    @Test fun sourceDiscoveryUsesActualFormatIdentityConfigurationAndAvailableResolution() {
        val client = HttpClient(MockEngine { error("Discovery must not contact HTTP") })
        val probe = probe(client)
        try {
            val found = assertNotNull(probe.discover(context, response()).source)
            assertContentEquals(byteArrayOf(1, 2, 3), found.ustreamerConfig)
            assertEquals(3, found.formats.size)
            assertEquals(selection, found.select(360))
            assertTrue(found.endpoint.contains("n=original-n"))
            assertFalse(found.toString().contains("private-value"))
            assertTrue(probe.discover(context, response()).diagnostics.any { it.code == "SABR_ENDPOINT_N_UNRESOLVED" })
            assertNull(probe.discover(context, response(source(id = "LMNOPQRSTUV"))).source)
            assertNull(probe.discover(context, response(source(config = "!!!"))).source)
            assertNull(probe.discover(context, response(source(endpoint = "https://foreign.test/sabr"))).source)
            val html = "<script>var ytInitialPlayerResponse=${source()};</script>" + " ".repeat(2_000_001)
            assertEquals(3, probe.discover(context, response(html)).source?.formats?.size)
        } finally { probe.close(); client.close() }
    }

    @Test fun binaryPostUsesActualSelectedIdsClientInfoAndUntouchedEndpointThenReportsTypedParts() = transportTest {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("original-n", request.url.parameters["n"])
            assertEquals("application/vnd.yt-ump", request.headers[HttpHeaders.Accept])
            assertEquals("application/x-protobuf", request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString())
            val fields = YouTubeProtobufWire.decode((request.body as OutgoingContent.ByteArrayContent).bytes())
            assertContentEquals(byteArrayOf(1, 2, 3), (fields.first { it.number == 5 }.value as SabrWireValue.Bytes).value)
            assertFalse(fields.any { it.number == 2 || it.number == 3 }) // No received format/ranges yet.
            val streamer = YouTubeProtobufWire.decode((fields.first { it.number == 19 }.value as SabrWireValue.Bytes).value)
            assertEquals(listOf(1), streamer.map { it.number }) // Actual client, no PoToken/cookie.
            val state = YouTubeProtobufWire.decode((fields.first { it.number == 1 }.value as SabrWireValue.Bytes).value)
            assertEquals(listOf(21, 22, 28, 34, 35, 40), state.map { it.number })
            assertEquals(0uL, (state[1].value as SabrWireValue.Varint).value) // Our fixed viewport.
            assertEquals(0uL, (state[2].value as SabrWireValue.Varint).value) // Initial playback time.
            assertEquals(1uL, (state[3].value as SabrWireValue.Varint).value) // Visible initial state.
            assertEquals(1f.toBits().toUInt(), (state[4].value as SabrWireValue.Fixed32).bits)
            assertEquals(0uL, (state.last().value as SabrWireValue.Varint).value)
            respond(binary(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/vnd.yt-ump"), "Set-Cookie" to listOf("secret-cookie")))
        }) { followRedirects = true }
        val probe = probe(client)
        try {
            val result = probe.inspect(context, response(), clientInfo, selection)
            assertEquals(1, requests)
            assertEquals(200, result.httpStatus, result.diagnostics.toString())
            assertTrue(result.completeFraming, result.diagnostics.toString())
            assertFalse(result.diagnostics.any { it.code == "SABR_PROBE_TIMEOUT" }, result.diagnostics.toString())
            assertEquals(binary().size, result.receivedBytes)
            assertEquals(listOf(20, 21, 22, 58, 44), result.parts.map { it.type })
            assertEquals(true, result.parts.first().header?.formatMatches)
            assertEquals(4, result.parts[1].mediaBytes)
            assertEquals(2, result.parts[3].protection?.status)
            assertEquals(7, result.parts[4].error?.code)
            assertEquals("[redacted]", result.responseHeaders["set-cookie"])
            assertFalse(result.diagnostics.toString().contains("private-value"))
            assertFalse(result.diagnostics.toString().contains("secret"))
        } finally { probe.close(); client.close() }
    }

    @Test fun wrongAuthorizationAbsentSelectionExpiredEndpointAndRawAccountsDoNotReachHttp() = transportTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("No HTTP expected") })
        val probe = probe(client)
        val different = YouTubeInitialSabrProbe(client, YouTubeSabrProbeAuthorization("LMNOPQRSTUV"))
        try {
            assertTrue(different.inspect(context, response(), clientInfo, selection).diagnostics.any { it.code == "SABR_VIDEO_NOT_AUTHORIZED_FOR_PROBE" })
            assertTrue(probe.inspect(context, response(), clientInfo, selection.copy(video = SabrFormatId(135, 402uL, "low"))).diagnostics.any { it.code == "SABR_SELECTION_NOT_IN_SOURCE" })
            assertTrue(probe.inspect(context, response(source(endpoint = "https://owned.test/sabr?expire=0")), clientInfo, selection).diagnostics.any { it.code == "SABR_SOURCE_URL_EXPIRED" })
            assertFailsWith<IllegalArgumentException> { probe.inspect(context, response(), clientInfo, selection, mapOf("Cookie" to "secret")) }
            assertFailsWith<IllegalArgumentException> { probe.inspect(context, response(), clientInfo, selection, mapOf("X-YouTube-Client-Name" to "spoofed")) }
            assertEquals(0, requests)
        } finally { probe.close(); different.close(); client.close() }
    }

    @Test fun nonSuccessRedirectAndContentTypeFailuresNeverCauseFallbackOrBodyParsing() = transportTest {
        listOf(HttpStatusCode.Found, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.OK).forEach { status ->
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                respond("secret-body", status, headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.Location to listOf("https://private.test/?token=secret")))
            }) { followRedirects = true }
            val probe = probe(client)
            try {
                val result = probe.inspect(context, response(), clientInfo, selection)
                assertEquals(1, requests)
                assertFalse(result.completeFraming)
                assertTrue(result.parts.isEmpty())
                assertEquals(0, result.receivedBytes)
                assertFalse(result.diagnostics.toString().contains("secret"))
            } finally { probe.close(); client.close() }
        }
    }

    @Test fun oversizedAndTruncatedBinaryResponsesRemainIncompleteAndRedacted() = transportTest {
        listOf(ByteArray(100), byteArrayOf(20, 4, 1, 2)).forEach { bytes ->
            val client = HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) })
            val probe = probe(client, YouTubeSabrProbeLimits(64, 32, 8))
            try {
                val result = probe.inspect(context, response(), clientInfo, selection)
                assertFalse(result.completeFraming)
                assertTrue(result.diagnostics.any { it.code == "SABR_TRANSPORT_OR_FRAMING_FAILURE" }, result.diagnostics.toString())
            } finally { probe.close(); client.close() }
        }
    }

    @Test fun transportMessagesAreHiddenAndCallerCancellationIsPreserved() = transportTest {
        val client = HttpClient(MockEngine { error("Cookie=secret https://private.test/?token=secret") })
        val probe = probe(client)
        try {
            val result = probe.inspect(context, response(), clientInfo, selection)
            assertFalse(result.diagnostics.toString().contains("secret"))
            assertTrue(result.diagnostics.any { it.code == "SABR_TRANSPORT_OR_FRAMING_FAILURE" }, result.diagnostics.toString())
        } finally { probe.close(); client.close() }
        val cancelling = HttpClient(MockEngine { throw CancellationException("cancel") })
        val cancelledProbe = probe(cancelling)
        try { assertFailsWith<CancellationException> { cancelledProbe.inspect(context, response(), clientInfo, selection) } }
        finally { cancelledProbe.close(); cancelling.close() }
    }

    @Test fun malformedInt32SpsErrorAndHeaderFieldsCannotTruncateIntoValidValues() = transportTest {
        val overflow = SabrWireValue.Varint(4_294_967_298uL)
        val cases = listOf(58 to listOf(SabrWireField(1, overflow)), 44 to listOf(SabrWireField(2, overflow)),
            20 to listOf(SabrWireField(3, SabrWireValue.Varint(4_294_967_431uL))))
        cases.forEach { (type, fields) ->
            val bytes = YouTubeUmpFraming.encodePart(type, YouTubeProtobufWire.encode(fields))
            val client = HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) })
            val probe = probe(client)
            try {
                val result = probe.inspect(context, response(), clientInfo, selection)
                assertEquals(200, result.httpStatus, result.diagnostics.toString())
                assertFalse(result.completeFraming)
                assertTrue(result.parts.isEmpty())
                assertTrue(result.diagnostics.any { it.code == "SABR_TRANSPORT_OR_FRAMING_FAILURE" }, result.diagnostics.toString())
                assertFalse(result.diagnostics.any { it.code == "SABR_STREAM_PROTECTION_STATUS" })
            } finally { probe.close(); client.close() }
        }
    }

    @Test fun headerFormatIdentityRequiresActualCompleteFieldsAndRejectsNestedTopConflicts() = transportTest {
        fun int(number: Int, value: ULong) = SabrWireField(number, SabrWireValue.Varint(value))
        fun text(number: Int, value: String) = SabrWireField(number, SabrWireValue.Bytes(value.encodeToByteArray()))
        val nested = SabrWireField(13, SabrWireValue.Bytes(YouTubeSabrProtocol.encodeFormatId(selection.video!!)))
        val incomplete = SabrWireField(13, SabrWireValue.Bytes(YouTubeSabrProtocol.encodeFormatId(SabrFormatId(135, 401uL))))
        val cases = listOf(
            listOf(int(3, 135uL)) to null,
            listOf(incomplete) to null,
            listOf(int(3, 135uL), int(4, 401uL), text(5, "low")) to true,
            listOf(nested, int(3, 137uL)) to false,
            listOf(nested, int(4, 999uL)) to false,
            listOf(nested, text(5, "different")) to false,
            listOf(nested, int(3, 135uL), int(4, 401uL), text(5, "low")) to true,
        )
        cases.forEach { (fields, expected) ->
            val bytes = YouTubeUmpFraming.encodePart(20, YouTubeProtobufWire.encode(fields))
            val client = HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) })
            val probe = probe(client)
            try {
                val result = probe.inspect(context, response(), clientInfo, selection)
                assertTrue(result.completeFraming, result.diagnostics.toString())
                assertEquals(expected, result.parts.single().header?.formatMatches)
            } finally { probe.close(); client.close() }
        }
    }

    @Test fun genuineDeadlineStillCancelsRealDelayedTransportWithoutRetry() = transportTest {
        var attempts = 0
        val client = HttpClient(MockEngine { attempts++; delay(5_000); respond(binary(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump")) })
        val probe = probe(client, YouTubeSabrProbeLimits(timeoutMs = 500))
        try {
            val result = probe.inspect(context, response(), clientInfo, selection)
            assertEquals(1, attempts)
            assertNull(result.httpStatus)
            assertFalse(result.completeFraming)
            assertTrue(result.diagnostics.any { it.code == "SABR_PROBE_TIMEOUT" }, result.diagnostics.toString())
        } finally { probe.close(); client.close() }
    }
}
