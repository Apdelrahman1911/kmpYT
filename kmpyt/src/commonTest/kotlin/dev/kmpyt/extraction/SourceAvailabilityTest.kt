@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package dev.kmpyt.extraction

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.youtube.YouTubeFormatSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Clock

/** Timing fixtures are authored; no advertising or platform media is fetched. */
class SourceAvailabilityTest {
    private val watch = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private fun renderer(seconds: String = "10", skip: String? = "5000") = buildJsonObject {
        put("playerVars", "length_seconds=$seconds")
        skip?.let { put("skipOffsetMilliseconds", it) }
    }
    private fun placement(kind: String = "AD_PLACEMENT_KIND_START", renderer: JsonObject = renderer()) = buildJsonObject {
        put("adPlacementRenderer", buildJsonObject {
            put("config", buildJsonObject { put("adPlacementConfig", buildJsonObject { put("kind", kind) }) })
            put("renderer", buildJsonObject { put("instreamVideoAdRenderer", renderer) })
        })
    }
    private fun player(placements: List<JsonElement> = listOf(placement())) = buildJsonObject {
        put("videoDetails", buildJsonObject { put("videoId", "ABCDEFGHIJK"); put("title", "Authored media") })
        put("playabilityStatus", buildJsonObject { put("status", "OK") })
        put("streamingData", buildJsonObject {
            put("adaptiveFormats", buildJsonArray { add(buildJsonObject {
                put("itag", 140); put("url", "https://owned.test/audio.m4a")
                put("mimeType", "audio/mp4"); put("contentLength", "16")
            }) })
        })
        put("adPlacements", JsonArray(placements))
    }
    private fun parse(value: JsonObject, now: Long = 10_000) = YouTubeProvider().parse(ExtractionContext(watch, watch, now), value.toString())
    private fun plan(availableAt: Long?) = PlannedDownload(
        Representation("140", "https://owned.test/audio.m4a", TrackRole.AUDIO, mimeType = "audio/mp4",
            codecs = "mp4a.40.2", sizeBytes = 16, availableAtEpochMs = availableAt), inspectionUrl = watch, mediaId = "ABCDEFGHIJK")

    @Test fun sourceSkipTimeDeterminesAvailabilityAndIsNotAnExpiredLink() {
        val result = parse(player())
        val audio = result.media.single().representations.single()
        assertTrue(audio.selectable)
        assertEquals(15_000L, audio.availableAtEpochMs)
        assertTrue(result.diagnostics.any { it.code == "PLAYBACK_AVAILABILITY_DELAY" })
        assertFalse(result.diagnostics.any { it.code == "SOURCE_URL_EXPIRED" })
    }

    @Test fun nonSkippableSourceWaitUsesDurationAndIgnoresMidroll() {
        val result = parse(player(listOf(placement(renderer = renderer("3", null)), placement("AD_PLACEMENT_KIND_MIDROLL"))))
        assertEquals(13_000L, result.media.single().representations.single().availableAtEpochMs)
        assertNull(parse(player(emptyList())).media.single().representations.single().availableAtEpochMs)
    }

    @Test fun sequentialBeforeContentSlotsAddOnlyTheirDeclaredWaits() {
        val value = buildJsonObject {
            put("adSlots", buildJsonArray { add(buildJsonObject {
                put("adSlotRenderer", buildJsonObject {
                    put("adSlotMetadata", buildJsonObject { put("triggerEvent", "SLOT_TRIGGER_EVENT_BEFORE_CONTENT") })
                    put("fulfillmentContent", buildJsonObject { put("fulfilledLayout", buildJsonObject {
                        put("playerBytesAdLayoutRenderer", buildJsonObject { put("renderingContent", buildJsonObject {
                            put("playerBytesSequentialLayoutRenderer", buildJsonObject { put("sequentialLayouts", buildJsonArray {
                                for (ad in listOf(renderer("10", "2500"), renderer("3", null))) add(buildJsonObject {
                                    put("playerBytesAdLayoutRenderer", buildJsonObject {
                                        put("renderingContent", buildJsonObject { put("instreamVideoAdRenderer", ad) })
                                    })
                                })
                            }) })
                        }) })
                    }) })
                })
            }) })
        }
        assertEquals(5500L, SourceAvailability.waitMillis(value))
    }

    @Test fun excessiveOrOverflowingWaitStaysUnavailableInsteadOfBeingShortened() {
        for (result in listOf(parse(player(listOf(placement(renderer = renderer("3600", null))))),
            parse(player(), Long.MAX_VALUE - 1000), parse(player(List(65) { placement() })))) {
            assertFalse(result.media.single().representations.single().selectable)
            assertTrue(result.diagnostics.any { it.code == "PLAYBACK_AVAILABILITY_UNSUPPORTED" })
        }
    }

    @Test fun transferWaitIsCancellableAndAlreadyAvailableMediaDoesNotWait() = runTest {
        var opened = false
        val job = launch { SourceAvailability.await(plan(15_000)) { 10_000 + currentTime }; opened = true }
        runCurrent()
        advanceTimeBy(4999)
        assertFalse(opened)
        job.cancelAndJoin()
        assertFalse(opened)
        SourceAvailability.await(plan(10_000)) { 10_000 + currentTime }
        assertEquals(4999L, currentTime)
    }

    @Test fun nativeDownloadAndPreparedPlaybackStartAfterAvailability(): Unit = runBlocking {
        var nativeStarted = 0L
        val client = YouTubeClient(ResearchTransport { error("No metadata request needed") }, downloader = YouTubeDownloader { _, destination, _ ->
            nativeStarted = Clock.System.now().toEpochMilliseconds()
            YouTubeDownloadResult(destination, 16, "audio/mp4")
        })
        try {
            val deadline = Clock.System.now().toEpochMilliseconds() + 100
            val plan = plan(deadline)
            client.download(plan, "authored.m4a")
            assertTrue(nativeStarted >= deadline)
            val nextDeadline = Clock.System.now().toEpochMilliseconds() + 100
            val report = ExtractionReport(watch, ProviderId.YOUTUBE, listOf(ExtractedMedia("ABCDEFGHIJK", ProviderId.YOUTUBE,
                "Authored media", watch, listOf(plan.primary.copy(availableAtEpochMs = nextDeadline)))), emptyList())
            client.preparePlayback(report, YouTubeFormatSelection("140"))
            assertTrue(Clock.System.now().toEpochMilliseconds() >= nextDeadline)
        } finally { client.close() }
    }
    @Test fun availabilityIsAnchoredToTheActualPlayerResponseInsteadOfInspectionStart() = runTest {
        var now = 10_000L
        val contract = YouTubeClientContract("Authored client", "https://owned.test/player", buildJsonObject {
            put("clientName", "OWNED"); put("clientVersion", "1")
        })
        val extractor = YouTubeResponseExtractor(ResearchTransport { request ->
            now = 20_000L
            ResearchResponse(request.url, 200, player().toString())
        }, options = YouTubeResponseExtractionOptions(clients = listOf(contract)), nowEpochMs = { now })
        try {
            val initial = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Authored metadata"}}"""
            val result = extractor.extract(ExtractionContext(watch, watch, 10_000), ResearchResponse(watch, 200, initial))
            assertEquals(25_000L, result.media.single().representations.single().availableAtEpochMs)
        } finally { extractor.close() }
    }

    @Test fun combinedPlansWaitForTheLaterTrackAndDoNotRepeatElapsedWaits() = runTest {
        val combined = plan(14_000).copy(audio = plan(15_000).primary)
        SourceAvailability.await(combined) { 10_000 + currentTime }
        assertEquals(5000L, currentTime)
        SourceAvailability.await(combined) { 10_000 + currentTime }
        assertEquals(5000L, currentTime)
    }

}
