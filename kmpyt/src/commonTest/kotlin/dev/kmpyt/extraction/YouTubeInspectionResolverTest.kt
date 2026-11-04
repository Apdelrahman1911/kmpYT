package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Unit fixtures verify parser/orchestration boundaries; they are not live compatibility claims. */
class YouTubeInspectionResolverTest {
    private val canonical = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val thumbnail = "https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg"
    private val oembed = """{"type":"video","provider_name":"YouTube","title":"Owned public title","author_name":"Owner","thumbnail_url":"https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg","width":200,"height":113,"thumbnail_width":480,"thumbnail_height":360,"html":"<iframe></iframe>"}"""

    private class RecordingTransport(val response: (ResearchRequest) -> ResearchResponse) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse {
            requests += request
            return response(request)
        }
    }

    @Test fun anonymousWatchParsingPreservesActualCatalogueWithoutPrivateOrMediaRequest() = runTest {
        val player = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned video","author":"Owner","lengthSeconds":"12","thumbnail":{"thumbnails":[{"url":"$thumbnail"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://video.test/sabr?token=sensitive-test-value","adaptiveFormats":[{"itag":299,"mimeType":"video/mp4; codecs=\"avc1.64002A\"","width":1920,"height":1080,"fps":60,"averageBitrate":4538863,"qualityLabel":"1080p60"},{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","audioSampleRate":"44100","audioChannels":2,"averageBitrate":129475,"audioTrack":{"id":"ar.4","displayName":"Arabic original"}}]}}"""
        val transport = RecordingTransport { ResearchResponse(it.url, 200, "<script>var ytInitialPlayerResponse = $player;</script>") }
        val report = YouTubeInspectionResolver(transport).inspect("https://youtu.be/ABCDEFGHIJK?t=30", 10_000)
        val item = report.media.single()
        assertEquals(canonical, report.input)
        assertEquals("Owned video", item.title)
        assertEquals(12_000L, item.durationMs)
        assertEquals(thumbnail, item.thumbnailUrl)
        assertEquals(2, item.representations.size)
        val video = item.representations[0]
        assertEquals(1080, video.height)
        assertEquals(60, video.frameRate)
        assertEquals("avc1.64002A", video.codecs)
        assertEquals(4_538_863L, video.bitrate)
        assertEquals("1080p60", video.qualityLabel)
        assertEquals(true, video.adaptive)
        assertNull(video.url)
        assertEquals(TransportKind.SABR, video.transport)
        assertFalse(video.selectable)
        val audio = item.representations[1]
        assertEquals("140@ar.4", audio.id)
        assertEquals("ar.4", audio.audioTrackId)
        assertNull(audio.audioLanguage, "A display name is not a supplied language code")
        assertEquals("Arabic original", audio.audioDisplayName)
        assertEquals(44_100, audio.audioSampleRate)
        assertEquals(2, audio.audioChannels)
        assertEquals(TrackRole.AUDIO, audio.role)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.all { it.method == "GET" && it.url == canonical && it.body == null })
        assertTrue(transport.requests.all { it.headers.keys.none { name -> name.lowercase() in setOf("cookie", "authorization") } })
        assertTrue(report.diagnostics.any { it.code == "URLLESS_SABR_ASSOCIATION_INFERRED" && it.message.startsWith("Inferred:") })
        assertTrue(report.diagnostics.any { it.code == "INSPECTION_ONLY" })
        assertFalse(report.diagnostics.toString().contains("sensitive-test-value"))
    }

    @Test fun oembedFallbackSuppliesIdentifyingMetadataWithoutManufacturingGeometryOrDuration() = runTest {
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") ResearchResponse(request.url, 403, "Denied")
            else ResearchResponse(request.url, 200, oembed)
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        val item = report.media.single()
        assertEquals("Owned public title", item.title)
        assertEquals("Owner", item.artist)
        assertEquals(thumbnail, item.thumbnailUrl)
        assertNull(item.durationMs)
        assertTrue(item.representations.isEmpty())
        assertTrue(report.diagnostics.any { it.code == "DURATION_NOT_PROVIDED" })
        assertTrue(report.diagnostics.any { it.code == "FORMAT_CATALOGUE_UNAVAILABLE" })
        assertEquals(listOf("youtube-watch", "youtube-oembed"), transport.requests.map { it.stage })
        assertTrue(transport.requests.all { it.method == "GET" })
    }

    @Test fun supplementMetadataDoesNotDiscardRealFormatDescriptors() = runTest {
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") ResearchResponse(request.url, 200,
                """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","lengthSeconds":"7"},"streamingData":{"formats":[{"itag":18,"url":"https://owned.test/asset.mp4","mimeType":"video/mp4","width":640,"height":360}]}};</script>""")
            else ResearchResponse(request.url, 200, oembed)
        }
        val item = YouTubeInspectionResolver(transport).inspect(canonical).media.single()
        assertEquals("Owned public title", item.title)
        assertEquals(7_000L, item.durationMs)
        assertEquals("18", item.representations.single().id)
        assertEquals(360, item.representations.single().height)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.none { it.url.startsWith("https://owned.test") })
    }

    @Test fun missingResponsesDoNotInventVideoOrQualityList() = runTest {
        val transport = RecordingTransport { ResearchResponse(it.url, 404, "Not found") }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertTrue(report.media.isEmpty())
        assertTrue(report.diagnostics.any { it.code == "METADATA_UNAVAILABLE" })
        assertEquals(2, transport.requests.size)
    }

    @Test fun failuresAreRedactedAndCancellationIsPropagated() = runTest {
        val failing = RecordingTransport { error("https://private.test/?token=secret-value") }
        val report = YouTubeInspectionResolver(failing).inspect(canonical)
        assertTrue(report.media.isEmpty())
        assertFalse(report.diagnostics.toString().contains("secret-value"))
        assertTrue(report.diagnostics.any { it.code == "TRANSPORT_VALIDATION_FAILURE" && it.message.contains("IllegalStateException") })
        val cancelling = RecordingTransport { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { YouTubeInspectionResolver(cancelling).inspect(canonical) }
        assertEquals(1, cancelling.requests.size)
    }

    private class ProbeSocketTimeoutException : Exception("Cookie=private-timeout-value; https://private.test/?token=private-token")

    @Test fun transportDiagnosticsIdentifyTimeoutCauseTypesWithoutMessagesOrPayloads() = runTest {
        val transport = RecordingTransport { throw Exception("private-wrapper-message", ProbeSocketTimeoutException()) }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertTrue(report.media.isEmpty())
        assertTrue(report.diagnostics.any { it.code == "TRANSPORT_TIMEOUT" && it.message.contains("Exception -> ProbeSocketTimeoutException") })
        listOf("private-wrapper-message", "private-timeout-value", "private-token", "https://private.test")
            .forEach { assertFalse(report.diagnostics.toString().contains(it)) }
        assertEquals(2, transport.requests.size)
    }

    @Test fun deceptiveUrlsAreRejectedBeforeNetwork() = runTest {
        val transport = RecordingTransport { error("must not execute") }
        assertFailsWith<IllegalArgumentException> {
            YouTubeInspectionResolver(transport).inspect("https://youtube.com.attacker.test/watch?v=ABCDEFGHIJK")
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun consentAndAccountRedirectsAreNotFollowed() = runTest {
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") ResearchResponse(request.url, 302, "", mapOf("Location" to "https://consent.youtube.com/m?continue=private-test-value"))
            else ResearchResponse(request.url, 200, oembed)
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertEquals("Owned public title", report.media.single().title)
        assertTrue(report.diagnostics.any { it.code == "REDIRECT_NOT_FOLLOWED" })
        assertTrue(transport.requests.none { it.url.contains("consent.") })
        assertFalse(report.diagnostics.toString().contains("private-test-value"))
    }

    @Test fun sameVideoPublicRedirectIsFollowedWithFiniteBudget() = runTest {
        var watches = 0
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") {
                watches++
                if (watches == 1) ResearchResponse(request.url, 302, "", mapOf("location" to "https://m.youtube.com/watch?v=ABCDEFGHIJK&visitorData=sensitive-receipt-test"))
                else ResearchResponse(request.url, 200, """<meta property="og:title" content="Redirected title"><meta property="og:image" content="$thumbnail"><meta name="author" content="Owner"><meta itemprop="duration" content="PT12S">""")
            } else error("No fallback expected")
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertEquals("Redirected title", report.media.single().title)
        assertEquals(12_000L, report.media.single().durationMs)
        assertEquals(2, transport.requests.size)
        assertEquals(canonical, report.input)
        assertTrue(transport.requests.last().url.contains("sensitive-receipt-test"))
        assertFalse(report.requestPlan.toString().contains("sensitive-receipt-test"))
    }

    @Test fun mismatchedVideoRedirectIsRejectedAndRateLimitDoesNotTriggerPrivateFallback() = runTest {
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") ResearchResponse(request.url, 302, "", mapOf("location" to "https://www.youtube.com/watch?v=ZYXWVUTSRQP"))
            else ResearchResponse(request.url, 429, "Too many requests")
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertTrue(report.media.isEmpty())
        assertTrue(report.diagnostics.any { it.code == "PUBLIC_RESPONSE_RATE_LIMITED" })
        assertTrue(transport.requests.none { it.method != "GET" || it.url.contains("youtubei") })
    }

    @Test fun publicRedirectLoopStopsAfterThreeWatchRequests() = runTest {
        val transport = RecordingTransport { request ->
            if (request.stage == "youtube-watch") ResearchResponse(request.url, 302, "", mapOf("Location" to canonical))
            else ResearchResponse(request.url, 200, oembed)
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        assertEquals(3, transport.requests.count { it.stage == "youtube-watch" })
        assertEquals(1, transport.requests.count { it.stage == "youtube-oembed" })
        assertTrue(report.diagnostics.any { it.code == "REDIRECT_NOT_FOLLOWED" })
    }

    @Test fun htmlAndJsonLdUseSuppliedMetadataAndRejectDifferentVideo() {
        val html = """<link rel="canonical" href="$canonical"><meta property="og:title" content="Owned &amp; readable &#x1F60E;"><meta itemprop="duration" content="PT22M6S"><script type="application/ld+json">{"@type":"VideoObject","url":"$canonical","name":"Secondary title","thumbnailUrl":["$thumbnail"],"author":{"name":"Owner"}}</script>"""
        val metadata = assertNotNull(YouTubePublicMetadata.fromHtml(html, canonical))
        assertEquals("Owned & readable 😎", metadata.title)
        assertEquals(1_326_000L, metadata.durationMs)
        assertEquals(thumbnail, metadata.thumbnailUrl)
        assertEquals("Owner", metadata.author)
        assertNull(YouTubePublicMetadata.fromHtml(html.replace(canonical, "https://www.youtube.com/watch?v=ZYXWVUTSRQP"), canonical))
        assertNull(YouTubePublicMetadata.fromHtml("""<script type="application/ld+json">{"@type":"VideoObject","url":"https://www.youtube.com/watch?v=ZYXWVUTSRQP","name":"Different video"}</script>""", canonical))
    }

    @Test fun metadataRejectsMalformedTypesAndUnsafeThumbnailUrls() {
        assertNull(YouTubePublicMetadata.fromOEmbed("""{"type":"video","title":123}"""))
        assertNull(YouTubePublicMetadata.fromOEmbed("""{"type":"photo","title":"Wrong type"}"""))
        assertNull(YouTubePublicMetadata.fromOEmbed("""{"type":"video","provider_name":"Other","title":"Wrong provider"}"""))
        val metadata = assertNotNull(YouTubePublicMetadata.fromOEmbed("""{"type":"video","title":"Actual title","thumbnail_url":"https://user:secret@images.test/a.jpg"}"""))
        assertNull(metadata.thumbnailUrl)
        val duration = YouTubePublicMetadata.fromHtml("""<meta property="og:title" content="Actual title"><meta itemprop="duration" content="NaN">""", canonical)
        assertNull(duration!!.durationMs)
    }

    @Test fun providerDoesNotTraverseLargeUnrelatedPageTrees() {
        val unrelated = "{\"items\":[" + "0,".repeat(40_000) + "0]}"
        val player = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Target player"},"streamingData":{"adaptiveFormats":[{"itag":137,"mimeType":"video/mp4","height":1080}]}}"""
        val page = "<script>var ytInitialData=$unrelated;</script>" + "x".repeat(EmbeddedData.MAX_BODY) + "<script>var ytInitialPlayerResponse = $player;</script>"
        val result = YouTubeProvider().parse(ExtractionContext(canonical, canonical), page)
        assertEquals("Target player", result.media.single().title)
        assertEquals(1080, result.media.single().representations.single().height)
        assertNull(result.media.single().representations.single().url)
        assertEquals(TransportKind.UNRESOLVED, result.media.single().representations.single().transport)
        assertTrue(result.diagnostics.none { it.code == "PARSER_LIMIT_OR_INVALID_DATA" })
    }

    @Test fun actualAudioTrackVariantsAndConfigurationPresenceArePreservedWithoutPayloadLeak() {
        val page = """<script>ytcfg.set({"INNERTUBE_CLIENT_NAME":"WEB","PLAYER_JS_URL":"/s/player/version/player_ias.vflset/en_US/base.js","VISITOR_DATA":"secret-visitor"}); var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK"},"serviceIntegrityDimensions":{"poToken":"secret-integrity"},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"secret-runtime"}}},"streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","audioTrack":{"id":"ar.4","languageCode":"ar"}},{"itag":140,"mimeType":"audio/mp4","audioTrack":{"id":"en.4","languageCode":"en"}}]}};</script>"""
        val provider = YouTubeProvider()
        val result = provider.parse(ExtractionContext(canonical, canonical), page)
        assertEquals(listOf("140@ar.4", "140@en.4"), result.media.single().representations.map { it.id })
        assertEquals(listOf("ar", "en"), result.media.single().representations.map { it.audioLanguage })
        assertEquals("", result.media.single().title)
        listOf("CLIENT_CONFIGURATION_PRESENT", "PLAYER_JAVASCRIPT_DEPENDENCY_PRESENT", "INTEGRITY_CONTEXT_PRESENT", "USTREAMER_CONFIGURATION_PRESENT")
            .forEach { code -> assertTrue(result.diagnostics.any { it.code == code }) }
        assertFalse(result.diagnostics.toString().contains("secret-"))
        assertEquals(listOf("GET"), provider.requestPlan(ExtractionContext(canonical, canonical)).map { it.method })
    }

    @Test fun missingTrackIdsDoNotCollapseDistinctObservedDescriptors() {
        val result = YouTubeProvider().parse(ExtractionContext(canonical, canonical),
            """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","contentLength":"100"},{"itag":140,"mimeType":"audio/mp4","contentLength":"101"}]}}""")
        val formats = result.media.single().representations
        assertEquals(2, formats.size)
        assertEquals(2, formats.map { it.id }.distinct().size)
        assertEquals(listOf(100L, 101L), formats.map { it.sizeBytes })
        assertTrue(formats.all { it.url == null && !it.selectable && it.audioTrackId == null })
    }
}
