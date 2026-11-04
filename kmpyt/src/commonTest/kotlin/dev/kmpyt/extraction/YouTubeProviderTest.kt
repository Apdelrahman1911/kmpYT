package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class YouTubeProviderTest {
    private val provider = YouTubeProvider()
    private val context = ExtractionContext("https://www.youtube.com/watch?v=ABCDEFGHIJK", "http://127.0.0.1:8123/captures/watch", 1_000_000)

    @Test fun uploaderChannelIdentityComesOnlyFromValidSourceMetadata() {
        val channel = "UCabcdefghijklmnopqrstuv"
        fun body(field: String) = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owner",$field"author":"@oPiiLz"},"playabilityStatus":{"status":"OK"}}"""
        assertEquals(channel, provider.parse(context, body("\"channelId\":\"$channel\"," )).media.single().uploaderChannelId)
        assertNull(provider.parse(context, body("")).media.single().uploaderChannelId)
        assertNull(provider.parse(context, body("\"channelId\":\"@oPiiLz\"," )).media.single().uploaderChannelId)
    }

    @Test fun normalizesVideoUrlFormsWithoutAcceptingDeceptiveHosts() {
        listOf("https://youtu.be/ABCDEFGHIJK?t=9", "https://m.youtube.com/shorts/ABCDEFGHIJK", "https://www.youtube-nocookie.com/embed/ABCDEFGHIJK", "https://youtube.com/live/ABCDEFGHIJK")
            .forEach { assertEquals("https://www.youtube.com/watch?v=ABCDEFGHIJK", provider.normalize(it)) }
        assertNull(provider.normalize("https://youtube.com.evil.example/watch?v=ABCDEFGHIJK"))
        assertNull(provider.normalize("https://user:secret@youtube.com/watch?v=ABCDEFGHIJK"))
        assertNull(provider.normalize("https://youtube.com/playlist?list=ABCDEFGHIJK"))
        assertNull(provider.normalize("javascript:alert(1)"))
    }

    @Test fun readsAssignedPlayerAfterUnrelatedScriptObjectAndPreservesTracks() {
        val player = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"A brace } in title","author":"Artist","lengthSeconds":"12","thumbnail":{"thumbnails":[{"url":"/small.jpg"},{"url":"/large.jpg"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":18,"url":"/muxed.mp4?expire=2000","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","contentLength":"90","width":640,"height":360}],"adaptiveFormats":[{"itag":137,"url":"/video.mp4","mimeType":"video/mp4; codecs=\"avc1.640028\"","height":1080,"width":1920,"averageBitrate":2200000},{"itag":140,"url":"/audio.m4a","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"approxDurationMs":"12020"}]}}"""
        val result = provider.parse(context, "<script>var unrelated={\"x\":1}; var ytInitialPlayerResponse = $player;</script>")
        val item = result.media.single()
        assertEquals("A brace } in title", item.title)
        assertEquals("Artist", item.artist)
        assertEquals(12_000L, item.durationMs)
        assertEquals("http://127.0.0.1:8123/large.jpg", item.thumbnailUrl)
        assertEquals(listOf(TrackRole.MUXED, TrackRole.VIDEO, TrackRole.AUDIO), item.representations.map { it.role })
        assertEquals(90L, item.representations[0].sizeBytes)
        assertEquals("avc1.42001E, mp4a.40.2", item.representations[0].codecs)
        assertEquals(2_000_000L, item.representations[0].expiresAtEpochMs)
        assertEquals(1080, item.representations[1].height)
        assertEquals(2_200_000L, item.representations[1].bitrate)
        assertEquals(12_020L, item.representations[2].durationMs)
        assertTrue(item.representations.all { it.selectable })
    }

    @Test fun retainsCipherSourceButBlocksItWithoutSignatureGeneration() {
        val body = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Cipher"},"streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","signatureCipher":"url=https%3A%2F%2Fcapture.example%2Faudio.m4a&s=unresolved-test-value&sp=sig"}]}}"""
        val result = provider.parse(context, body)
        val format = result.media.single().representations.single()
        assertEquals("https://capture.example/audio.m4a", format.url)
        assertFalse(format.selectable)
        assertTrue(format.unavailableReason!!.contains("player-JavaScript"))
        assertTrue(result.diagnostics.any { it.code == "SIGNATURE_CIPHER_UNRESOLVED" })
        assertFalse(Url(format.requireResourceUrl()).parameters.contains("sig"))
    }

    @Test fun retainsCipherDescriptorWithoutInventingSourceUrl() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"formats":[{"itag":18,"cipher":"s=test-value"}]}}""")
        val representation = result.media.single().representations.single()
        assertNull(representation.url)
        assertEquals(TransportKind.UNRESOLVED, representation.transport)
        assertFalse(representation.selectable)
        assertFailsWith<IllegalArgumentException> { representation.requireResourceUrl() }
        assertTrue(result.diagnostics.any { it.code == "FORMAT_URL_MISSING_OR_INVALID" })
    }

    @Test fun identifiesUnresolvedThrottlingParameter() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"formats":[{"itag":18,"url":"/muxed.mp4?n=unresolved-test-value","mimeType":"video/mp4"}]}}""")
        assertFalse(result.media.single().representations.single().selectable)
        assertTrue(result.diagnostics.any { it.code == "THROTTLING_PARAMETER_UNRESOLVED" })
    }

    @Test fun assemblesRealCipherAndNResultsWithoutRewritingUnrelatedSignedQueryBytes() {
        val source = """function sig(a){a=a.split("");a.reverse();return a.join("");}function throttle(a){a=a.split("");a.splice(0,1);return a.join("");}"""
        val transforms = assertNotNull(BoundedYouTubePlayerProgramCompiler().compile(source, YouTubeProgramBinding("sig", "throttle")).transforms)
        val base = "https://capture.example/audio.m4a?token=a%2Fb%2bc&n=0next&other=x+y"
        val cipher = "url=${base.encodeURLParameter()}&s=abcdef&sp=sig"
        val result = YouTubeProvider(transforms).parse(context, playerWithFormat(buildJsonObject {
            put("itag", 140); put("signatureCipher", cipher); put("mimeType", "audio/mp4")
        }))
        val representation = result.media.single().representations.single()
        assertEquals("https://capture.example/audio.m4a?token=a%2Fb%2bc&n=next&other=x+y&sig=fedcba", representation.url)
        assertTrue(representation.selectable)
        assertEquals(TrackRole.AUDIO, representation.role)
        assertTrue(result.diagnostics.any { it.code == "SIGNATURE_CIPHER_RESOLVED" })
        assertTrue(result.diagnostics.any { it.code == "THROTTLING_PARAMETER_RESOLVED" })
        assertTrue(result.diagnostics.none { it.message.contains("abcdef") || it.message.contains("a%2Fb") })
    }

    @Test fun honorsAnExplicitSuppliedSignatureContractWithoutClaimingJsDeciphering() {
        val cipher = "url=${"https://capture.example/video.mp4?existing=a%2Bb".encodeURLParameter()}&sig=actual-signature&sp=signature"
        val result = provider.parse(context, playerWithFormat(buildJsonObject { put("cipher", cipher); put("mimeType", "video/mp4") }))
        val representation = result.media.single().representations.single()
        assertEquals("https://capture.example/video.mp4?existing=a%2Bb&signature=actual-signature", representation.url)
        assertTrue(representation.selectable)
        assertTrue(result.diagnostics.any { it.code == "SOURCE_SIGNATURE_ASSEMBLED" })
        assertTrue(result.diagnostics.none { it.code == "SIGNATURE_CIPHER_RESOLVED" })
    }

    @Test fun rejectsAmbiguousOrUnsafeCipherContractsEvenWhenTransformIsAvailable() {
        val transforms = assertNotNull(BoundedYouTubePlayerProgramCompiler().compile("""function sig(a){a=a.split("");a.reverse();return a.join("");}""", YouTubeProgramBinding(signatureFunction = "sig")).transforms)
        val base = "url=${"https://capture.example/video.mp4".encodeURLParameter()}"
        listOf("$base&s=abc&sp=expire", "$base&s=abc&sp=n", "$base&s=abc&sig=provided", "$base&s=abc&s=def", "$base&sig=one&signature=two", "$base&s=abc&sp=bad%26field").forEach { cipher ->
            val result = YouTubeProvider(transforms).parse(context, playerWithFormat(buildJsonObject { put("signatureCipher", cipher) }))
            assertFalse(result.media.single().representations.single().selectable)
            assertTrue(result.diagnostics.any { it.code == "SIGNATURE_CIPHER_UNRESOLVED" })
        }
    }

    @Test fun transformExecutionFailureNeverMakesAFormatOrManifestAvailable() {
        val failing = object : YouTubeUrlTransforms {
            override val signatureAvailable = true
            override val throttlingAvailable = true
            override val signatureTimestamp: Long? = null
            override fun signature(input: String): String = ""
            override fun throttling(input: String): String = error("Deliberate controlled failure")
        }
        val cipher = "url=${"https://capture.example/video.mp4".encodeURLParameter()}&s=abc"
        val result = YouTubeProvider(failing).parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"formats":[{"signatureCipher":${JsonPrimitive(cipher)}}],"hlsManifestUrl":"/master.m3u8?n=actual-value","dashManifestUrl":"/manifest.mpd?n=actual-value"}}""")
        assertEquals(3, result.media.single().representations.size)
        assertTrue(result.media.single().representations.none { it.selectable })
        assertTrue(result.media.single().representations.drop(1).all { it.unavailableReason!!.contains("unresolved") })
    }

    @Test fun refusesDuplicateNFieldsAndStillKeepsRestrictionAndDrmBoundaries() {
        val transform = object : YouTubeUrlTransforms {
            override val signatureAvailable = true
            override val throttlingAvailable = true
            override val signatureTimestamp: Long? = null
            override fun signature(input: String) = input.reversed()
            override fun throttling(input: String) = input.reversed()
        }
        val duplicate = YouTubeProvider(transform).parse(context, playerWithFormat(buildJsonObject { put("url", "https://capture.example/video.mp4?n=a&n=b") }))
        assertFalse(duplicate.media.single().representations.single().selectable)
        val restricted = YouTubeProvider(transform).parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"playabilityStatus":{"status":"LOGIN_REQUIRED"},"streamingData":{"formats":[{"url":"/media.mp4?n=abc"}]}}""")
        assertFalse(restricted.media.single().representations.single().selectable)
        assertTrue(restricted.diagnostics.none { it.code == "THROTTLING_PARAMETER_RESOLVED" })
        val drm = YouTubeProvider(transform).parse(context, playerWithFormat(buildJsonObject { put("url", "/media.mp4?n=abc"); put("isDrm", true) }))
        assertFalse(drm.media.single().representations.single().selectable)
    }

    private fun playerWithFormat(format: JsonObject): String = buildJsonObject {
        putJsonObject("videoDetails") { put("videoId", "ABCDEFGHIJK") }
        putJsonObject("streamingData") { putJsonArray("formats") { add(format) } }
    }.toString()

    @Test fun preservesHlsAndDashPointersButNeverTreatsSabrAsAFile() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"hlsManifestUrl":"/master.m3u8","dashManifestUrl":"/manifest.mpd","serverAbrStreamingUrl":"/sabr"},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"test-configuration"}}}}""")
        assertEquals(listOf(TransportKind.HLS, TransportKind.DASH), result.media.single().representations.map { it.transport })
        assertTrue(result.diagnostics.any { it.code == "SABR_UMP_UNSUPPORTED" })
        assertTrue(result.media.single().representations.none { it.url?.endsWith("/sabr") == true })
    }

    @Test fun blocksLoginRestrictedFormatsWhileKeepingMetadata() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Restricted"},"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"},"streamingData":{"formats":[{"itag":18,"url":"/video.mp4","mimeType":"video/mp4"}]}}""")
        assertEquals("Restricted", result.media.single().title)
        assertFalse(result.media.single().representations.single().selectable)
        assertTrue(result.diagnostics.any { it.code == "AUTHORIZATION_REQUIRED" })
    }

    @Test fun blocksDeclaredDrm() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"adaptiveFormats":[{"itag":137,"url":"/encrypted.mp4","mimeType":"video/mp4","drmFamilies":["WIDEVINE"]}]}}""")
        assertFalse(result.media.single().representations.single().selectable)
        assertTrue(result.diagnostics.any { it.code == "ENCRYPTED_FORMAT_UNSUPPORTED" })
    }

    @Test fun blocksResponseLevelLicenseDeclarations() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"licenseInfos":[{"drmFamily":"WIDEVINE"}],"formats":[{"itag":18,"url":"/encrypted.mp4","mimeType":"video/mp4"}],"dashManifestUrl":"/encrypted.mpd"}}""")
        assertEquals(2, result.media.single().representations.size)
        assertTrue(result.media.single().representations.none { it.selectable })
    }

    @Test fun blocksExpiredUrlsAndUsesObservedRelativeExpiry() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"expiresInSeconds":"60","formats":[{"itag":18,"url":"/expired.mp4?expire=900","mimeType":"video/mp4"},{"itag":22,"url":"/fresh.mp4","mimeType":"video/mp4"}]}}""")
        val formats = result.media.single().representations
        assertFalse(formats[0].selectable)
        assertEquals(900_000L, formats[0].expiresAtEpochMs)
        assertTrue(formats[1].selectable)
        assertEquals(1_060_000L, formats[1].expiresAtEpochMs)
    }

    @Test fun rejectsMismatchedVideoId() {
        val result = provider.parse(context, """{"videoDetails":{"videoId":"ZYXWVUTSRQP"},"streamingData":{"formats":[{"url":"/other.mp4"}]}}""")
        assertTrue(result.media.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "VIDEO_ID_MISMATCH" })
    }

    @Test fun readsStringEncodedPlayerResponse() {
        val encoded = buildJsonObject {
            putJsonObject("args") { put("player_response", """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Encoded"},"streamingData":{"formats":[{"itag":18,"url":"/video.mp4","mimeType":"video/mp4"}]}}""") }
        }.toString()
        assertEquals("Encoded", provider.parse(context, encoded).media.single().title)
    }

    @Test fun malformedAndOversizedCapturesProduceDiagnostics() {
        assertTrue(provider.parse(context, "<html>no player</html>").diagnostics.any { it.code == "PLAYER_DATA_NOT_FOUND" })
        val result = provider.parse(context, "x".repeat(EmbeddedData.MAX_BODY + 1))
        assertTrue(result.media.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "PARSER_LIMIT_OR_INVALID_DATA" })
    }

    @Test fun requestPlanSerializesValidRequestsOnlyToAuthorizedResponseOrigin() = runTest {
        val seen = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host)
            assertEquals(8123, request.url.port)
            seen += request.method.value
            if (request.method == HttpMethod.Post) {
                assertEquals("/youtubei/v1/player", request.url.encodedPath)
                val json = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals("ABCDEFGHIJK", json.text("videoId"))
                assertTrue(json.obj("context")!!.obj("client")!!.isEmpty())
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
            }
            respond("{\"accepted\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val plans = provider.requestPlan(context)
            assertEquals(2, plans.size)
            plans.forEach { plan ->
                val response = client.request(plan.url) {
                    method = HttpMethod.parse(plan.method)
                    plan.headers.forEach { (name, value) -> header(name, value) }
                    plan.body?.let { setBody(it) }
                }
                assertEquals("{\"accepted\":true}", response.bodyAsText())
            }
            assertEquals(listOf("GET", "POST"), seen)
        } finally { client.close() }
        assertTrue(provider.requestPlan(context.copy(responseUrl = "file:///capture.html")).isEmpty())
        assertTrue(provider.requestPlan(context.copy(responseUrl = "https://user:secret@capture.example/page")).isEmpty())
    }
}
