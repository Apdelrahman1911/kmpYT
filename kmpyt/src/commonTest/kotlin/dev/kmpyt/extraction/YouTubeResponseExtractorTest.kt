package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Uses the actual parser/compiler; transport fixtures isolate orchestration from live services. */
class YouTubeResponseExtractorTest {
    private val context = ExtractionContext("https://www.youtube.com/watch?v=ABCDEFGHIJK", "https://owned.test/youtube/capture", 10_000)
    private val binding = YouTubeProgramBinding("ownedSig", "ownedN")
    private val program = """function ownedSig(a){a=a.split("");a.reverse();return a.join("");}function ownedN(a){a=a.split("");a.reverse();return a.join("");}var signatureTimestamp=12345;"""
    private fun client(name: String, endpoint: String = "https://owned.test/youtubei/v1/player") = YouTubeClientContract(name, endpoint,
        buildJsonObject { put("clientName", name); put("clientVersion", "owned-1") })
    private class RecordingTransport(private val reply: (ResearchRequest) -> ResearchResponse) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse { requests += request; return reply(request) }
    }
    private fun initialBody() = """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned title","lengthSeconds":"3"},"playabilityStatus":{"status":"OK"}};</script><script src="/player.js"></script>"""
    private fun cipherBody() = buildJsonObject {
        putJsonObject("videoDetails") { put("videoId", "ABCDEFGHIJK"); put("title", "Owned response title"); put("lengthSeconds", "3") }
        putJsonObject("playabilityStatus") { put("status", "OK") }
        putJsonObject("streamingData") { putJsonArray("formats") { add(buildJsonObject {
            put("itag", 18); put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
            put("width", 160); put("height", 90)
            put("signatureCipher", "url=${"https://owned.test/media.mp4?n=abc&source=owned".encodeURLParameter()}&s=abcdef&sp=sig")
        }) } }
    }.toString()

    @Test fun configuredFallbackExecutesRealRequestObjectsThenParsesAndTransformsSuppliedMedia() = runTest {
        val transport = RecordingTransport { request ->
            when {
                request.method == "GET" -> ResearchResponse(request.url, 200, program)
                (Json.parseToJsonElement(requireNotNull(request.body)) as JsonObject).obj("context")!!.obj("client")!!.text("clientName") == "OWNED_FIRST" -> ResearchResponse(request.url, 503, "temporarily unavailable")
                else -> ResearchResponse(request.url, 200, cipherBody())
            }
        }
        val extractor = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(
            listOf(client("OWNED_FIRST"), client("OWNED_SECOND")), binding))
        val result = extractor.extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
        val media = result.media.single()
        assertEquals("Owned title", media.title)
        assertEquals(3000L, media.durationMs)
        val representation = media.representations.single()
        assertTrue(representation.selectable, representation.unavailableReason)
        val mediaUrl = Url(requireNotNull(representation.url))
        assertEquals("fedcba", mediaUrl.parameters["sig"])
        assertEquals("cba", mediaUrl.parameters["n"])
        assertEquals(160, representation.width)
        assertEquals("avc1.42001E, mp4a.40.2", representation.codecs)
        assertEquals(listOf("POST", "POST", "GET"), transport.requests.map { it.method })
        transport.requests.filter { it.method == "POST" }.forEach { request ->
            val body = Json.parseToJsonElement(requireNotNull(request.body)) as JsonObject
            assertEquals("ABCDEFGHIJK", body.text("videoId"))
            assertFalse(body.containsKey("contentCheckOk"))
            assertFalse(body.containsKey("racyCheckOk"))
        }
        assertTrue(transport.requests.none { it.url.startsWith("https://owned.test/media.mp4") })
        assertTrue(result.diagnostics.any { it.code == "PLAYER_CLIENT_RESOURCES_DISCOVERED" })
    }

    @Test fun directSuppliedResourcesAvoidProgramAndClientRequests() = runTest {
        val transport = RecordingTransport { error("A usable response must not trigger extra requests") }
        val body = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned"},"streamingData":{"formats":[{"itag":18,"url":"https://owned.test/media.mp4","mimeType":"video/mp4"}]}}"""
        val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(client("OWNED")), binding))
            .extract(context, ResearchResponse(context.responseUrl, 200, body))
        assertTrue(result.media.single().representations.single().selectable)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun missingContractsAndSabrOnlyDataDoNotInventPrivateClientsOrMediaUrls() = runTest {
        val transport = RecordingTransport { error("Unconfigured clients must not be requested") }
        val body = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned"},"streamingData":{"serverAbrStreamingUrl":"https://owned.test/sabr?token=not-logged","adaptiveFormats":[{"itag":299,"mimeType":"video/mp4","height":1080}]}}"""
        val result = YouTubeResponseExtractor(transport).extract(context, ResearchResponse(context.responseUrl, 200, body))
        assertNull(result.media.single().representations.single().url)
        assertEquals(TransportKind.SABR, result.media.single().representations.single().transport)
        assertFalse(result.media.single().representations.single().selectable)
        assertTrue(result.diagnostics.any { it.code == "PLAYER_CLIENT_CONTRACT_NOT_CONFIGURED" })
        assertFalse(result.diagnostics.toString().contains("not-logged"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun unsupportedProgramKeepsCipherResourceUnavailableWithoutFakeSuccess() = runTest {
        val transport = RecordingTransport { ResearchResponse(it.url, 200, "function ownedSig(a){while(true){}}") }
        val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(programBinding = binding))
            .extract(context, ResearchResponse(context.responseUrl, 200, "<script>var ytInitialPlayerResponse=${cipherBody()};</script><script src='/player.js'></script>"))
        assertFalse(result.media.single().representations.single().selectable)
        assertEquals(1, transport.requests.size)
        assertTrue(result.diagnostics.any { it.code == "SIGNATURE_CIPHER_UNRESOLVED" })
    }

    @Test fun mismatchFailuresAndAccessLimitsPreserveOriginalMetadataAndStopAtTheRightBoundary() = runTest {
        val cases = listOf(
            ResearchResponse("https://owned.test/youtubei/v1/player", 200, """{"videoDetails":{"videoId":"LMNOPQRSTUV","title":"Wrong video"},"streamingData":{"formats":[{"url":"https://owned.test/wrong.mp4","mimeType":"video/mp4"}]}}"""),
            ResearchResponse("https://owned.test/youtubei/v1/player", 403, "private response"),
            ResearchResponse("https://owned.test/youtubei/v1/player", 429, "rate limited"),
        )
        cases.forEach { reply ->
            val transport = RecordingTransport { reply }
            val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(client("OWNED"))))
                .extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
            assertEquals("Owned title", result.media.single().title)
            assertTrue(result.media.single().representations.isEmpty())
            assertFalse(result.media.any { it.title == "Wrong video" })
            assertEquals(1, transport.requests.size)
        }
        val accessTransport = RecordingTransport { error("Initial access status must stop fallback") }
        val accessResult = YouTubeResponseExtractor(accessTransport, options = YouTubeResponseExtractionOptions(listOf(client("OWNED"))))
            .extract(context, ResearchResponse(context.responseUrl, 403, "denied"))
        assertTrue(accessTransport.requests.isEmpty())
        assertTrue(accessResult.diagnostics.any { it.code == "INITIAL_PLAYER_ACCESS_OR_RATE_LIMIT" })
    }

    @Test fun accountOrEncryptedStatusDoesNotTriggerAlternateClientRequests() = runTest {
        val transport = RecordingTransport { error("Restricted responses must not trigger alternate requests") }
        val restricted = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Private"},"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
        val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(client("OWNED"))))
            .extract(context, ResearchResponse(context.responseUrl, 200, restricted))
        assertTrue(result.diagnostics.any { it.code == "CLIENT_FALLBACK_AUTHORIZATION_REQUIRED" })
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun configurationRejectsRawCredentialsAndFramingAndTransportFailuresAreRedacted() = runTest {
        val transport = RecordingTransport { error("Cookie: private-token https://private.test/?token=private-value") }
        listOf("Cookie", "Authorization", "Host", "Content-Length").forEach { header ->
            assertFailsWith<IllegalArgumentException> { YouTubeResponseExtractor(transport,
                options = YouTubeResponseExtractionOptions(listOf(client("OWNED").copy(headers = mapOf(header to "unsafe"))))) }
        }
        val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(client("OWNED"))))
            .extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
        assertFalse(result.diagnostics.toString().contains("private-token"))
        assertFalse(result.diagnostics.toString().contains("private-value"))
        assertTrue(result.diagnostics.any { it.code == "PLAYER_CLIENT_TRANSPORT_FAILURE" })
        val cancel = RecordingTransport { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { YouTubeResponseExtractor(cancel, options = YouTubeResponseExtractionOptions(listOf(client("OWNED"))))
            .extract(context, ResearchResponse(context.responseUrl, 200, initialBody())) }
    }

    @Test fun fallbackSabrDescriptorsKeepTheIdentityOfTheClientThatReturnedThem() = runTest {
        val first = client("OWNED_FIRST").copy(headers = mapOf("X-Youtube-Client-Name" to "1"))
        val second = client("OWNED_SECOND").copy(clientContext = buildJsonObject {
            put("clientName", "OWNED_SECOND"); put("clientVersion", "owned-2")
        }, headers = mapOf("X-Youtube-Client-Name" to "2", "X-Youtube-Client-Version" to "owned-2"))
        val body = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned"},
          "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}},
          "streamingData":{"serverAbrStreamingUrl":"https://owned.test/sabr","adaptiveFormats":[
          {"itag":134,"lastModified":"123","mimeType":"video/mp4","contentLength":"100","height":360}]}}"""
        val transport = RecordingTransport { request ->
            if (requireNotNull(request.body).contains("OWNED_FIRST")) ResearchResponse(request.url, 503, "unavailable")
            else ResearchResponse(request.url, 200, body)
        }
        val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(first, second)))
            .extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
        val resource = result.media.single().representations.single().sabr!!
        assertEquals(SabrClientInfo(2, "owned-2"), resource.clientInfo)
        assertEquals(2, transport.requests.size)
    }

    @Test fun playerIntegrityRequiresActualScopedIssuerAndIsNotUsedAsAMediaGrant() = runTest {
        val contract = client("OWNED").copy(headers = mapOf("X-Youtube-Client-Name" to "1"),
            playerIntegrity = YouTubePlayerIntegrityContract("owned-issuer", "owned-contract-v1"))
        val unavailable = RecordingTransport { error("An absent required issuer must not trigger a player POST") }
        val absent = YouTubeResponseExtractor(unavailable, options = YouTubeResponseExtractionOptions(listOf(contract)))
            .extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
        assertTrue(absent.diagnostics.any { it.code == "PLAYER_INTEGRITY_ISSUER_UNAVAILABLE" })
        val issued = mutableListOf<YouTubeIntegrityIssueRequest>()
        val issuer = object : YouTubeIntegrityIssuer {
            override val id = "owned-issuer"
            override val capabilities = YouTubeIntegrityCapabilities(setOf(YouTubeIntegrityScope.PLAYER_REQUEST))
            override suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext {
                issued += request
                return YouTubeIssuedIntegrityContext(id, request.scope, request.binding, byteArrayOf(1, 2, 3), 100, 1000)
            }
        }
        val manager = YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(listOf(issuer)), backgroundScope, { 200 })
        val transport = RecordingTransport { request ->
            val json = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
            assertEquals("AQID", json.obj("serviceIntegrityDimensions")!!.text("poToken"))
            ResearchResponse(request.url, 200, """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned"},"streamingData":{"formats":[{"itag":18,"url":"https://owned.test/media.mp4","mimeType":"video/mp4"}]}}""")
        }
        try {
            val result = YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(contract)), integrity = manager)
                .extract(context, ResearchResponse(context.responseUrl, 200, initialBody()))
            assertEquals(YouTubeIntegrityScope.PLAYER_REQUEST, issued.single().scope)
            assertEquals("owned-contract-v1", issued.single().binding.resourceGeneration)
            assertFalse(result.diagnostics.toString().contains("AQID"))
            assertFalse(contract.toString().contains("owned-contract-v1"))
            assertEquals("https://owned.test/media.mp4", result.media.single().representations.single().url)
        } finally { manager.close() }
    }

    @Test fun inconsistentClientVersionsAndDuplicateProfilesAreRejectedBeforeRequests() {
        val transport = RecordingTransport { error("Invalid registry must not make requests") }
        val one = client("OWNED")
        assertFailsWith<IllegalArgumentException> { YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(one, one))) }
        assertFailsWith<IllegalArgumentException> { YouTubeResponseExtractor(transport, options = YouTubeResponseExtractionOptions(listOf(one.copy(headers = mapOf("X-Youtube-Client-Version" to "different"))))) }
    }
}
