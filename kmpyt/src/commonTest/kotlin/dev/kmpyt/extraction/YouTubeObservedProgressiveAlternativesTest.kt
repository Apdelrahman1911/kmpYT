package dev.kmpyt.extraction

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Authored player responses exercise metadata-only enrichment without contacting media servers. */
class YouTubeObservedProgressiveAlternativesTest {
    private val canonical = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val context = ExtractionContext(canonical, canonical)
    private val config = """{"INNERTUBE_CLIENT_NAME":"WEB","INNERTUBE_CLIENT_VERSION":"owned-web-1","INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_API_KEY":"fixture-private-key","STS":12345,"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"owned-web-1","visitorData":"fixture-private-visitor"}}}"""
    private val video = """{"itag":137,"lastModified":"123","mimeType":"video/mp4; codecs=\"avc1.640028\"","contentLength":"100","width":1920,"height":1080}"""
    private val audio = """{"itag":140,"lastModified":"124","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","contentLength":"40","audioTrack":{"id":"en.1","languageCode":"en","audioIsDefault":true}}"""
    private fun player(formats: List<String> = listOf(video, audio), id: String = "ABCDEFGHIJK", endpoint: String = "https://owned.test/initial-sabr") =
        """{"videoDetails":{"videoId":"$id","title":"Owned video"},"playabilityStatus":{"status":"OK"},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}},"streamingData":{"serverAbrStreamingUrl":"$endpoint","adaptiveFormats":[${formats.joinToString(",")}]}}"""
    private fun page(player: String = player()) = "<script>ytcfg.set($config);var ytInitialPlayerResponse=$player;</script>"
    private fun progressive(format: String, file: String) = format.dropLast(1) + ",\"url\":\"https://owned.test/$file\"}"

    private suspend fun extract(body: String = page(), enabled: Boolean = true, publicClients: Boolean = false,
        reply: (ResearchRequest) -> ResearchResponse): Pair<ProviderResult, List<ResearchRequest>> {
        val requests = mutableListOf<ResearchRequest>()
        val actualClient = assertNotNull(YouTubeObservedWebClient.discover(context, body))
        val unusedClient = YouTubeClientContract("unused owned fallback", "https://other.test/player",
            buildJsonObject { put("clientName", "OWNED_OTHER") })
        val extractor = YouTubeResponseExtractor(ResearchTransport { request -> requests += request; reply(request) },
            options = YouTubeResponseExtractionOptions(clients = if (publicClients) YouTubePublicPlayerClients.fromObservedWeb(actualClient) + actualClient
                else listOf(actualClient, unusedClient), requestObservedProgressiveAlternatives = enabled,
                requestPublicProgressiveAlternatives = publicClients))
        return try { extractor.extract(context, ResearchResponse(canonical, 200, body)) to requests }
        finally { extractor.close() }
    }

    @Test fun requestsOnlyObservedWebMetadataAndRetainsIndependentSabrAndProgressiveTracks() = runTest {
        val (result, requests) = extract { request ->
            val json = Json.parseToJsonElement(assertNotNull(request.body)).jsonObject
            assertEquals("ABCDEFGHIJK", json.text("videoId"))
            assertEquals("WEB", json.obj("context")!!.obj("client")!!.text("clientName"))
            assertEquals("fixture-private-visitor", json.obj("context")!!.obj("client")!!.text("visitorData"))
            assertFalse(json.containsKey("serviceIntegrityDimensions"))
            assertFalse(json.containsKey("contentCheckOk"))
            ResearchResponse(request.url, 200, player(listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))))
        }
        assertEquals(1, requests.size)
        assertEquals("POST", requests.single().method)
        assertTrue(requests.single().url.startsWith("https://www.youtube.com/youtubei/v1/player?"))
        val formats = result.media.single().representations
        assertEquals(listOf("137#progressive", "140@en.1#progressive", "137", "140@en.1"), formats.map { it.id })
        assertTrue(formats.all { it.selectable })
        assertEquals(listOf(TransportKind.PROGRESSIVE, TransportKind.PROGRESSIVE, TransportKind.SABR, TransportKind.SABR), formats.map { it.transport })
        assertEquals("en.1", formats[1].audioTrackId)
        assertEquals("en", formats[1].audioLanguage)
        assertEquals(true, formats[1].audioIsDefault)
        assertTrue(formats.take(2).all { it.sabr == null })
        assertTrue(formats.takeLast(2).all { it.sabr?.endpoint == "https://owned.test/initial-sabr" })
        assertTrue(result.diagnostics.any { it.code == "OBSERVED_WEB_PROGRESSIVE_RESOURCES" })
        assertFalse(result.diagnostics.toString().contains("fixture-private"))
    }

    @Test fun partialProgressiveResponseKeepsBothOriginalSabrTracksSelectable() = runTest {
        val (result, requests) = extract { ResearchResponse(it.url, 200, player(listOf(progressive(audio, "audio.m4a")))) }
        val formats = result.media.single().representations
        assertEquals(1, requests.size)
        assertEquals(listOf("140@en.1#progressive", "137", "140@en.1"), formats.map { it.id })
        assertTrue(formats.filter { it.transport == TransportKind.SABR }.all { it.selectable })
    }

    @Test fun unusableProgressiveAndRefreshedSabrDescriptorsDoNotEraseOriginalResources() = runTest {
        val invalidVideo = progressive(video, "video.mp4?n=unresolved")
        val (result, requests) = extract {
            ResearchResponse(it.url, 200, player(listOf(invalidVideo, audio), endpoint = "https://owned.test/new-sabr"))
        }
        assertEquals(1, requests.size)
        assertEquals(listOf("137", "140@en.1"), result.media.single().representations.map { it.id })
        assertTrue(result.media.single().representations.all { it.selectable && it.sabr?.endpoint == "https://owned.test/initial-sabr" })
        assertTrue(result.diagnostics.any { it.code == "OBSERVED_WEB_PROGRESSIVE_UNAVAILABLE" })
    }

    @Test fun noAlternativeOrDeniedMetadataStopsAfterOneRequestWithoutTryingOtherIdentities() = runTest {
        for (status in listOf(200, 403, 429, 503)) {
            val (result, requests) = extract { ResearchResponse(it.url, status, if (status == 200) player() else "Unavailable") }
            assertEquals(1, requests.size)
            assertEquals(listOf("137", "140@en.1"), result.media.single().representations.map { it.id })
            assertTrue(result.media.single().representations.all { it.selectable })
        }
    }

    @Test fun anotherVideoCannotAddProgressiveResourcesToThisCatalogue() = runTest {
        val (result, requests) = extract {
            ResearchResponse(it.url, 200, player(listOf(progressive(video, "other.mp4")), id = "ZYXWVUTSRQP"))
        }
        assertEquals(1, requests.size)
        assertTrue(result.media.single().representations.all { it.transport == TransportKind.SABR })
        assertTrue(result.diagnostics.any { it.code == "PLAYER_CLIENT_VIDEO_MISMATCH" })
    }

    @Test fun existingProgressiveOrDisabledOptionAvoidsAdditionalRequest() = runTest {
        val (_, disabled) = extract(enabled = false) { error("No optional request expected") }
        assertTrue(disabled.isEmpty())
        val (result, supplied) = extract(body = page(player(listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))))) {
            error("Existing progressive media does not need enrichment")
        }
        assertTrue(supplied.isEmpty())
        assertTrue(result.media.single().representations.any { it.transport == TransportKind.PROGRESSIVE })
    }

    @Test fun pinnedPublicTvProfileUsesActualVisitorAndStopsAfterReceivingProgressiveMediaMetadata() = runTest {
        val (result, requests) = extract(publicClients = true) { request ->
            val json = Json.parseToJsonElement(assertNotNull(request.body)).jsonObject
            val client = json.obj("context")!!.obj("client")!!
            assertEquals("TVHTML5", client.text("clientName"))
            assertEquals("fixture-private-visitor", client.text("visitorData"))
            assertEquals(client.text("visitorData"), request.headers["X-Goog-Visitor-Id"])
            assertEquals(client.text("clientVersion"), request.headers["X-Youtube-Client-Version"])
            assertEquals(12345L, json.obj("playbackContext")!!.obj("contentPlaybackContext")!!.number("signatureTimestamp"))
            assertFalse(json.containsKey("serviceIntegrityDimensions"))
            assertFalse(json.containsKey("contentCheckOk"))
            assertFalse(json.containsKey("racyCheckOk"))
            assertFalse(request.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
            ResearchResponse(request.url, 200, player(listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))))
        }
        assertEquals(1, requests.size)
        assertTrue(requests.all { it.method == "POST" && it.url.contains("/youtubei/v1/player?") })
        assertEquals(2, result.media.single().representations.count { it.transport == TransportKind.PROGRESSIVE })
        assertEquals(2, result.media.single().representations.count { it.transport == TransportKind.SABR })
        assertTrue(result.diagnostics.any { it.code == "PUBLIC_PLAYER_PROGRESSIVE_RESOURCES" })
        assertFalse(result.diagnostics.toString().contains("fixture-private"))
    }

    @Test fun publicMetadataServerFailureCanUseNextConfiguredProfileButAccountAndRateDenialsStop() = runTest {
        val (_, recovery) = extract(publicClients = true) { request ->
            if (request.headers["X-Youtube-Client-Name"] == "7") ResearchResponse(request.url, 503, "Unavailable")
            else ResearchResponse(request.url, 200, player(listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))))
        }
        assertEquals(listOf("7", "101"), recovery.map { it.headers["X-Youtube-Client-Name"] })
        for (status in listOf(200, 403, 429)) {
            val (result, requests) = extract(publicClients = true) {
                ResearchResponse(it.url, status, """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Account required"}}""")
            }
            assertEquals(1, requests.size)
            assertTrue(result.diagnostics.any { it.code in setOf("CLIENT_FALLBACK_AUTHORIZATION_REQUIRED", "PLAYER_CLIENT_ACCESS_OR_RATE_LIMIT") })
            assertTrue(result.media.single().representations.none { it.transport == TransportKind.PROGRESSIVE })
        }
    }

    @Test fun publicProfilesRequireRealPageVisitorContextAndCannotBeAttributedToAnUnobservedClient() {
        val observed = assertNotNull(YouTubeObservedWebClient.discover(context, page()))
        val profiles = YouTubePublicPlayerClients.fromObservedWeb(observed)
        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.provenance == YouTubeClientProvenance.PINNED_PUBLIC_CLIENT })
        val noVisitor = observed.copy(clientContext = JsonObject(observed.clientContext.filterKeys { it != "visitorData" }))
        assertTrue(YouTubePublicPlayerClients.fromObservedWeb(noVisitor).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            YouTubePublicPlayerClients.fromObservedWeb(observed.copy(provenance = YouTubeClientProvenance.EXPLICIT_AUTHORIZED_CONTRACT))
        }
    }

    @Test fun partialInitialProgressiveTrackStillLooksForItsOtherTrackWithoutDuplicatingTheExistingResource() = runTest {
        for (initial in listOf(listOf(video, progressive(audio, "audio.m4a")), listOf(progressive(video, "video.mp4"), audio))) {
            val (result, requests) = extract(body = page(player(initial)), publicClients = true) {
                ResearchResponse(it.url, 200, player(listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))))
            }
            assertEquals(1, requests.size)
            val formats = result.media.single().representations
            assertEquals(2, formats.count { it.transport == TransportKind.PROGRESSIVE })
            assertEquals(1, formats.count { it.transport == TransportKind.SABR })
            assertEquals(2, formats.filter { it.transport == TransportKind.PROGRESSIVE }.map { it.url }.distinct().size)
        }
    }

    @Test fun partialFirstPublicResponseContinuesUntilAnotherResponseCompletesTheProgressivePair() = runTest {
        val (result, requests) = extract(publicClients = true) { request ->
            val formats = if (request.headers["X-Youtube-Client-Name"] == "7") listOf(progressive(audio, "audio.m4a"))
                else listOf(progressive(video, "video.mp4"), progressive(audio, "audio.m4a"))
            ResearchResponse(request.url, 200, player(formats))
        }
        assertEquals(listOf("7", "101"), requests.map { it.headers["X-Youtube-Client-Name"] })
        assertEquals(2, result.media.single().representations.count { it.transport == TransportKind.PROGRESSIVE })
        assertEquals(2, result.media.single().representations.count { it.transport == TransportKind.SABR })
    }

    @Test fun declaredAudioOnlyProgressiveSourceDoesNotNeedAnAdditionalVideoLookup() = runTest {
        val (result, requests) = extract(body = page(player(listOf(progressive(audio, "audio.m4a")))), publicClients = true) {
            error("The declared source contains only audio and already has its complete resource")
        }
        assertTrue(requests.isEmpty())
        assertEquals(TrackRole.AUDIO, result.media.single().representations.single().role)
    }

    @Test fun emptyInitialCatalogueDoesNotMistakeFirstAudioOnlyFallbackForACompleteVideo() = runTest {
        val (result, requests) = extract(body = page(player(emptyList())), publicClients = true) { request ->
            val formats = if (request.headers["X-Youtube-Client-Name"] == "7") listOf(progressive(audio, "audio.m4a"))
                else listOf(progressive(video, "video.mp4"))
            ResearchResponse(request.url, 200, player(formats))
        }
        assertEquals(listOf("7", "101"), requests.map { it.headers["X-Youtube-Client-Name"] })
        val formats = result.media.single().representations
        assertEquals(setOf(TrackRole.VIDEO, TrackRole.AUDIO), formats.map { it.role }.toSet())
        assertTrue(formats.all { it.selectable && it.transport == TransportKind.PROGRESSIVE })
    }
}
