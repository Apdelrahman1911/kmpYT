package dev.kmpyt.youtube

import dev.kmpyt.extraction.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class YouTubeCaptionServiceTest {
    private val id = "ABCDEFGHIJK"
    private val secondId = "01234567890"
    private val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500\nHello &amp; welcome\n"
    private fun track(index: Int = 0, videoId: String = id, translatable: Boolean = true) = YouTubeCaptionTrack(index,
        if (index == 0) ".en" else ".fr", "https://www.youtube.com/api/timedtext?v=$videoId&signature=a%2Fb%2bc&x=x+y",
        if (index == 0) "en" else "fr", if (index == 0) "English" else "French", null, false, translatable)
    private fun report(videoId: String = id, tracks: List<YouTubeCaptionTrack> = listOf(track(videoId = videoId))): ExtractionReport {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val catalogue = YouTubeCaptionCatalogue(tracks, listOf(YouTubeCaptionLanguage("ar", "Arabic")), emptyList(), null)
        return ExtractionReport(url, ProviderId.YOUTUBE,
            listOf(ExtractedMedia(videoId, ProviderId.YOUTUBE, "Caption test", url, emptyList(), captions = catalogue)), emptyList())
    }
    private fun response(request: ResearchRequest, body: String = vtt) = ResearchResponse(request.url, 200, body, mapOf("content-type" to "text/vtt"))
    private class Store : YouTubeCaptionStore {
        val values = mutableMapOf<String, SavedYouTubeCaptions>()
        override suspend fun load(videoId: String) = values[videoId]
        override suspend fun save(value: SavedYouTubeCaptions) { values[value.videoId] = value }
    }

    @Test fun userSelectionLoadsExactSuppliedResourceCachesAndPersistsOnlyTextIdentity() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val store = Store()
        val service = PublicYouTubeCaptionService(ResearchTransport { request -> requests += request; response(request) }, store)
        service.register(report(), id)
        assertTrue(requests.isEmpty(), "Inspecting a catalogue must not fetch every subtitle automatically")
        service.select(id, 0)
        assertEquals(track().url, requests.single().url)
        assertEquals("GET", requests.single().method)
        assertEquals("Hello & welcome", service.state.value.videos[id]?.document?.cues?.single()?.text)
        assertTrue(service.state.value.videos.getValue(id).enabled)
        service.select(id, 0)
        assertEquals(1, requests.size, "A selected subtitle document is reused within the session")
        val serialized = Json.encodeToString(store.values.getValue(id))
        assertFalse(serialized.contains("https://")); assertFalse(serialized.contains("signature"))
        assertEquals(store.values.getValue(id), Json.decodeFromString<SavedYouTubeCaptions>(serialized))
    }

    @Test fun translationRequiresDeclaredLanguageAndTrackAndPreservesSignedBytes() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val service = PublicYouTubeCaptionService(ResearchTransport { request -> requests += request; response(request) })
        service.register(report(), id)
        assertFailsWith<IllegalArgumentException> { service.select(id, 0, "invented") }
        assertTrue(requests.isEmpty())
        service.select(id, 0, "ar")
        assertEquals(track().url + "&tlang=ar", requests.single().url)
        assertEquals("Arabic (translated)", service.state.value.videos[id]?.label)
        service.register(report(tracks = listOf(track(translatable = false))), id)
        assertFailsWith<IllegalArgumentException> { service.select(id, 0, "ar") }
        assertEquals(1, requests.size)
    }

    @Test fun rejectsCrossVideoTrackAndNonYouTubeResourceBeforeNetwork() = runTest {
        var requests = 0
        val service = PublicYouTubeCaptionService(ResearchTransport { requests++; error("Must not request this resource") })
        service.register(report(tracks = listOf(track(videoId = secondId))), id)
        assertFailsWith<IllegalArgumentException> { service.select(id, 0) }
        service.register(report(tracks = listOf(track().copy(url = "https://example.com/api/timedtext?v=$id"))), id)
        assertFailsWith<IllegalArgumentException> { service.select(id, 0) }
        assertFailsWith<IllegalArgumentException> { service.register(report(), secondId) }
        assertEquals(0, requests)
    }

    @Test fun deniedOrRedirectedResponseNeverBecomesAnExportableSubtitle() = runTest {
        var denied = true
        val store = Store()
        val service = PublicYouTubeCaptionService(ResearchTransport { request ->
            if (denied) ResearchResponse(request.url, 403, "denied") else response(request).copy(url = "https://accounts.google.com/")
        }, store)
        service.register(report(), id)
        service.select(id, 0)
        assertNull(service.state.value.videos[id]?.document)
        assertContains(service.state.value.videos[id]?.error.orEmpty(), "denied")
        assertFailsWith<IllegalArgumentException> { service.export(id, CaptionExportFormat.SRT) }
        denied = false
        service.select(id, 0)
        assertNull(service.state.value.videos[id]?.document)
        assertTrue(store.values.isEmpty())
    }

    @Test fun laterSelectionWinsWhenEarlierNetworkRequestCompletesLast() = runTest {
        val release = CompletableDeferred<Unit>()
        val service = PublicYouTubeCaptionService(ResearchTransport { request ->
            if (!request.url.contains("tlang=")) release.await()
            response(request, if (request.url.contains("tlang=")) vtt.replace("Hello &amp; welcome", "مرحبا") else vtt)
        })
        service.register(report(), id)
        val first = async { service.select(id, 0) }
        runCurrent()
        service.select(id, 0, "ar")
        release.complete(Unit); first.await()
        assertEquals("ar", service.state.value.videos[id]?.selected?.translationLanguage)
        assertEquals("مرحبا", service.state.value.videos[id]?.document?.cues?.single()?.text)
        assertFalse(service.state.value.videos.getValue(id).loading)
    }

    @Test fun togglingOffDuringLoadStaysOffAndSurvivesServiceRestart() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val service = PublicYouTubeCaptionService(ResearchTransport { request -> release.await(); response(request) }, store)
        service.register(report(), id)
        val selection = async { service.select(id, 0) }
        runCurrent(); service.setEnabled(id, false)
        release.complete(Unit); selection.await()
        assertFalse(store.values.getValue(id).enabled)
        val restarted = PublicYouTubeCaptionService(ResearchTransport { error("Restoring a local sidecar must not use the network") }, store)
        restarted.restore(id)
        assertFalse(restarted.state.value.videos.getValue(id).enabled)
        assertNotNull(restarted.state.value.videos[id]?.document)
        restarted.setEnabled(id, true)
        assertTrue(store.values.getValue(id).enabled)
    }

    @Test fun videoSpecificDocumentsDoNotLeakAcrossPlaybackIdentity() = runTest {
        val service = PublicYouTubeCaptionService(ResearchTransport { request -> response(request) })
        service.register(report(), id); service.select(id, 0)
        service.register(report(secondId), secondId)
        assertNull(service.state.value.videos[secondId]?.document)
        assertEquals(id, captionVideoId("https://youtu.be/$id?list=ignored"))
        assertEquals(id, captionVideoId("https://www.youtube.com/shorts/$id"))
        assertNull(captionVideoId("https://example.com/watch?v=$id"))
    }

    @Test fun emptyTrackDoesNotGetCachedAsSuccessfulSubtitleOrPersisted() = runTest {
        var requests = 0
        val store = Store()
        val service = PublicYouTubeCaptionService(ResearchTransport { request -> requests++; response(request, "WEBVTT\n\n") }, store)
        service.register(report(), id)
        service.select(id, 0); service.select(id, 0)
        assertEquals(2, requests)
        assertContains(service.state.value.videos[id]?.error.orEmpty(), "no text")
        assertFailsWith<IllegalArgumentException> { service.export(id, CaptionExportFormat.WEBVTT) }
        assertTrue(store.values.isEmpty())
    }

    @Test fun staleOrCorruptSidecarCannotReplaceTheCurrentVideo() = runTest {
        val store = Store()
        store.values[id] = SavedYouTubeCaptions(secondId, "Wrong video", "English", CaptionSelection(0, ".en", "en"),
            YouTubeCaptions.parse(vtt), true)
        val service = PublicYouTubeCaptionService(ResearchTransport { error("No network") }, store)
        service.restore(id)
        assertNull(service.state.value.videos[id]?.document)
        assertNotNull(service.state.value.videos[id]?.error)
    }

    @Test fun timelineUsesHalfOpenIntervalsSeeksAndTimedWordsWithoutOwnClock() {
        val document = YouTubeCaptionDocument(YouTubeCaptionFormat.JSON3, listOf(
            YouTubeCaptionCue(1000, 3000, "Hello world", segments = listOf(
                YouTubeCaptionSegment("Hello", 0), YouTubeCaptionSegment(" world", 1000))),
            YouTubeCaptionCue(2000, 4000, "Second speaker")))
        val timeline = CaptionTimeline(document)
        assertEquals("", timeline.textAt(999))
        assertEquals("Hello", timeline.textAt(1000))
        assertEquals("Hello world\nSecond speaker", timeline.textAt(2000))
        assertEquals("Second speaker", timeline.textAt(3000))
        assertEquals("", timeline.textAt(4000))
        assertEquals("Hello", timeline.textAt(1500), "Seeking backward recomputes captions from media time")
    }

    @Test fun windowedCaptionUpdatesReplaceOrAppendOnlyTheirOwnWindow() {
        val timeline = CaptionTimeline(YouTubeCaptionDocument(YouTubeCaptionFormat.JSON3, listOf(
            YouTubeCaptionCue(0, 5000, "First", windowId = 1),
            YouTubeCaptionCue(1000, 5000, " second", windowId = 1, append = true),
            YouTubeCaptionCue(2000, 5000, "Replacement", windowId = 1, append = false),
            YouTubeCaptionCue(2000, 5000, "Another speaker", windowId = 2))))
        assertEquals("First second", timeline.textAt(1500))
        assertEquals("Replacement\nAnother speaker", timeline.textAt(2500))
    }

    @Test fun exportsValidTimingAndEscapesMarkupWithoutCreatingExtraCueBlocks() {
        val document = YouTubeCaptionDocument(YouTubeCaptionFormat.TIMEDTEXT,
            listOf(YouTubeCaptionCue(3_661_002, 3_663_999, "A & <B>\n\nnext")))
        val srt = CaptionDocuments.export(document, CaptionExportFormat.SRT)
        assertEquals("1\n01:01:01,002 --> 01:01:03,999\nA &amp; &lt;B&gt;\nnext\n\n", srt)
        val vtt = CaptionDocuments.export(document, CaptionExportFormat.WEBVTT)
        assertTrue(vtt.startsWith("WEBVTT\n\n1\n01:01:01.002 --> 01:01:03.999\n"))
        assertEquals("A & <B>\nnext", YouTubeCaptions.parse(vtt).cues.single().text)
    }
}
