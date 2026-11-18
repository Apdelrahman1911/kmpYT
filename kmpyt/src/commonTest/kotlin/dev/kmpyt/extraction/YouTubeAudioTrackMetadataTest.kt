package dev.kmpyt.extraction

import dev.kmpyt.YouTubeClient
import dev.kmpyt.youtube.YouTubeFormatSelection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class YouTubeAudioTrackMetadataTest {
    private val videoId = "ABCDEFGHIJK"
    private val page = "https://www.youtube.com/watch?v=$videoId"
    private val context = ExtractionContext(page, page)
    private val english = audioFormat("en.dub", "English (US)", default = true, autoDubbed = true)
    private val arabic = audioFormat("ar.source", "Arabic original", default = false)

    @Test fun preservesDefaultAutoDubAndSeparateSourceOriginalWithoutGuessingLanguage() {
        val media = report(player(english, arabic)).media.single()
        val dubbed = media.representations[0]
        val original = media.representations[1]
        assertEquals(videoId, media.id)
        assertEquals("140@en.dub", dubbed.id)
        assertEquals("en.dub", dubbed.audioTrackId)
        assertEquals(true, dubbed.audioIsDefault)
        assertEquals(true, dubbed.audioIsAutoDubbed)
        assertEquals(AudioTrackProvenance.AUTO_DUBBED, dubbed.audioProvenance)
        assertEquals("140@ar.source", original.id)
        assertEquals("ar.source", original.audioTrackId)
        assertEquals(false, original.audioIsDefault)
        assertNull(original.audioIsAutoDubbed)
        assertNull(original.audioLanguage)
        assertEquals("Arabic original", original.audioDisplayName)
        assertEquals(AudioTrackProvenance.ORIGINAL, original.audioProvenance)
        assertEquals(listOf(original), YouTubeFormatSelection.originalAudioTracks(media))
    }

    @Test fun originalMarkerRequiresAnExplicitLabelBoundaryAndDoesNotInferFromAutoDubbedFalse() {
        for (label in listOf("Arabic original", "French (Original)", " original ")) {
            val track = report(player(audioFormat("source", label, autoDubbed = false))).media.single().representations.single()
            assertEquals(AudioTrackProvenance.ORIGINAL, track.audioProvenance)
        }
        for (label in listOf("Arabic", "English", "Unoriginal", "Originally Arabic", "Original soundtrack remix", "Arabic (original")) {
            val track = report(player(audioFormat("ar.4", label, autoDubbed = false))).media.single().representations.single()
            assertEquals(AudioTrackProvenance.UNKNOWN, track.audioProvenance)
            assertNull(track.audioLanguage)
        }
    }

    @Test fun contradictorySourceMarkersStayConflictingInsteadOfBeingPromotedToOriginal() {
        val conflicting = audioFormat("conflicting", "English original", default = true, autoDubbed = true)
        val media = report(player(conflicting, arabic)).media.single()
        assertEquals(AudioTrackProvenance.CONFLICTING, media.representations.first().audioProvenance)
        assertEquals(true, media.representations.first().audioIsAutoDubbed)
        assertEquals(listOf("140@ar.source"), YouTubeFormatSelection.originalAudioTracks(media).map { it.id })
        assertEquals(YouTubeFormatSelection("140@ar.source"),
            YouTubeFormatSelection.choose(media, "iOS", audioOnly = true))
    }

    @Test fun malformedOrMissingAutoDubbedFieldsRemainUnknown() {
        val malformed = JsonObject(english + ("audioTrack" to JsonObject(english.getValue("audioTrack").jsonObject +
            ("isAutoDubbed" to buildJsonObject { put("invalid", true) }))))
        val track = report(player(malformed)).media.single().representations.single()
        assertNull(track.audioIsAutoDubbed)
        assertEquals(AudioTrackProvenance.UNKNOWN, track.audioProvenance)
        val absent = report(player(audioFormat("unknown", "Arabic"))).media.single().representations.single()
        assertNull(absent.audioIsAutoDubbed)
        assertEquals(AudioTrackProvenance.UNKNOWN, absent.audioProvenance)
    }

    @Test fun serializationPreservesRawProvenanceWhileLegacyMissingFieldsRemainUnknown() {
        for (track in report(player(english, arabic)).media.single().representations) {
            val encoded = Json.encodeToString(Representation.serializer(), track)
            val decoded = Json.decodeFromString(Representation.serializer(), encoded)
            assertEquals(track, decoded)
            assertEquals(track.audioProvenance, decoded.audioProvenance)
            assertFalse("audioProvenance" in encoded)
        }
        val legacy = report(player(audioFormat("legacy", "Arabic"))).media.single().representations.single()
        val encoded = Json.encodeToString(Representation.serializer(), legacy)
        assertFalse("audioIsAutoDubbed" in encoded)
        assertEquals(AudioTrackProvenance.UNKNOWN,
            Json.decodeFromString(Representation.serializer(), encoded).audioProvenance)
    }

    @Test fun sourceRestrictionsRemainAuthoritativeForOriginalTracks() {
        val restricted = report(player(english, arabic, status = "LOGIN_REQUIRED"))
        val media = restricted.media.single()
        assertEquals(AudioTrackProvenance.ORIGINAL, media.representations.last().audioProvenance)
        assertTrue(media.representations.none { it.selectable })
        assertNull(YouTubeFormatSelection.choose(media, "iOS", audioOnly = true))
        assertTrue(restricted.diagnostics.any { it.code == "AUTHORIZATION_REQUIRED" })
    }

    @Test fun automaticPlaybackAndDownloadUseTheSameOriginalButExplicitDubSelectionIsUnchanged() = runTest {
        val inspected = report(player(english, arabic))
        for (platform in listOf("Android", "iOS", "JVM")) {
            val client = YouTubeClient(ResearchTransport { error("Selection and direct plans must not fetch resources") },
                platformName = platform)
            try {
                val selection = assertNotNull(client.select(inspected, audioOnly = true))
                val playback = client.playbackPlan(inspected, selection)
                val download = client.plan(inspected, selection)
                assertEquals("140@ar.source", selection.primaryId)
                assertEquals(download.primary, playback.plan.primary)
                assertEquals(AudioTrackProvenance.ORIGINAL, download.primary.audioProvenance)
                assertEquals(videoId, download.mediaId)
                assertEquals(videoId, playback.metadata.id)
                val explicit = YouTubeFormatSelection("140@en.dub")
                assertEquals(explicit.primaryId, client.playbackPlan(inspected, explicit).plan.primary.id)
                assertEquals(explicit.primaryId, client.plan(inspected, explicit).primary.id)
            } finally { client.close() }
        }
    }

    @Test fun selectedManifestResolutionPreservesTheActualAudioTrackAndAutoDubFlag() = runTest {
        val inspected = report(player(english))
        val media = inspected.media.single()
        val selected = media.representations.single().copy(transport = TransportKind.HLS,
            url = "https://media.example/selected-audio.m3u8")
        val actual = inspected.copy(media = listOf(media.copy(representations = listOf(selected))))
        val client = YouTubeClient(ResearchTransport { request ->
            assertEquals("selected-manifest", request.stage)
            ResearchResponse(request.url, 200, "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.m4a\n#EXT-X-ENDLIST\n")
        })
        try {
            val planned = client.plan(actual, YouTubeFormatSelection(selected.id))
            assertEquals(selected.id, planned.primary.id)
            assertEquals(selected.audioTrackId, planned.primary.audioTrackId)
            assertEquals(selected.audioDisplayName, planned.primary.audioDisplayName)
            assertEquals(true, planned.primary.audioIsAutoDubbed)
            assertEquals(AudioTrackProvenance.AUTO_DUBBED, planned.primary.audioProvenance)
            assertEquals(1, planned.primary.segments.size)
            assertEquals(videoId, planned.mediaId)
        } finally { client.close() }
    }

    @Test fun renewalRetainsSelectedTrackWhenDefaultChangesAndFailsRatherThanReplacingLostOriginal() = runTest {
        val inspected = report(player(english, arabic))
        val renewedUrl = "https://media.example/renewed-source.m4a"
        val renewedOriginal = JsonObject(arabic + ("url" to JsonPrimitive(renewedUrl)))
        var responseBody = player(english, renewedOriginal)
        val client = YouTubeClient(ResearchTransport { request ->
            when (request.stage) {
                "youtube-watch" -> ResearchResponse(request.url, 200, responseBody)
                "youtube-oembed" -> ResearchResponse(request.url, 404, "")
                else -> error("Unexpected renewal request stage: ${request.stage}")
            }
        })
        try {
            val selection = assertNotNull(client.select(inspected, audioOnly = true))
            val selected = client.plan(inspected, selection)
            val refreshed = client.refresh(selected)
            assertEquals(selected.primary.id, refreshed.primary.id)
            assertEquals("ar.source", refreshed.primary.audioTrackId)
            assertEquals(renewedUrl, refreshed.primary.url)
            responseBody = player(english, audioFormat("ar.source", "Arabic original", default = true))
            assertEquals(selected.primary.id, client.refresh(selected).primary.id)
            for (replacement in listOf(player(english), player(english, audioFormat("ar.source", "Arabic")),
                player(english, audioFormat("ar.source", "Arabic original", autoDubbed = true)))) {
                responseBody = replacement
                assertFailsWith<IllegalArgumentException> { client.refresh(selected) }
            }
        } finally { client.close() }
    }

    @Test fun progressiveAliasesAndSabrRecoveryRequireUnchangedTrackProvenanceNotJustTheSameItag() {
        val original = report(player(arabic)).media.single().representations.single()
        val aliased = original.copy(id = "${original.id}#progressive-2")
        assertEquals(aliased.id, refreshedProgressiveRepresentation(aliased,
            listOf(original.copy(audioIsDefault = true))).id)
        val sabr = original.copy(transport = TransportKind.SABR)
        assertTrue(SourcePlaybackRequest.sameProgressiveFormat(sabr, aliased.copy(audioIsDefault = true)))
        for (changed in listOf(original.copy(audioDisplayName = "Arabic"), original.copy(audioIsAutoDubbed = true),
            original.copy(audioTrackId = "en.dub"), original.copy(audioLanguage = "en"))) {
            assertFailsWith<IllegalArgumentException> { refreshedProgressiveRepresentation(aliased, listOf(changed)) }
            assertFalse(SourcePlaybackRequest.sameProgressiveFormat(sabr, changed))
        }
    }

    private fun report(body: String): ExtractionReport {
        val parsed = YouTubeProvider().parse(context, body)
        return ExtractionReport(page, ProviderId.YOUTUBE, parsed.media, parsed.diagnostics)
    }

    private fun player(vararg formats: JsonObject, status: String = "OK"): String = buildJsonObject {
        putJsonObject("videoDetails") {
            put("videoId", videoId)
            put("title", "Authored original-track fixture")
            put("lengthSeconds", "1")
        }
        putJsonObject("playabilityStatus") { put("status", status) }
        putJsonObject("streamingData") { putJsonArray("adaptiveFormats") { formats.forEach { add(it) } } }
    }.toString()

    private fun audioFormat(trackId: String, label: String, default: Boolean? = null,
        autoDubbed: Boolean? = null): JsonObject = buildJsonObject {
        put("itag", 140)
        put("url", "https://media.example/$trackId.m4a")
        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
        put("bitrate", 96_000)
        put("contentLength", "2000")
        putJsonObject("audioTrack") {
            put("id", trackId)
            put("displayName", label)
            default?.let { put("audioIsDefault", it) }
            autoDubbed?.let { put("isAutoDubbed", it) }
        }
    }
}
