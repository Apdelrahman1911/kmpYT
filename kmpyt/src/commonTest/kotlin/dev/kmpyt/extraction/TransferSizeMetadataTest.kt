package dev.kmpyt.extraction

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransferSizeMetadataTest {
    private val video = Representation("136", "https://media.example/video.mp4", TrackRole.VIDEO,
        mimeType = "video/mp4", sizeBytes = 100)
    private val audio = Representation("140", "https://media.example/audio.m4a", TrackRole.AUDIO,
        mimeType = "audio/mp4", sizeBytes = 20)
    private fun plan(v: Representation = video, a: Representation? = audio) = PlannedDownload(v, a,
        inspectionUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK", mediaId = "ABCDEFGHIJK")

    @Test fun downloadPreparationPublishesCombinedSizeBeforeAnyMediaRequest() = runTest {
        val selectedPlan = plan()
        val media = ExtractedMedia(selectedPlan.mediaId, ProviderId.YOUTUBE, "Declared video and audio", selectedPlan.inspectionUrl, listOf(video, audio))
        val report = ExtractionReport(selectedPlan.inspectionUrl, ProviderId.YOUTUBE, listOf(media), emptyList())
        val client = dev.kmpyt.YouTubeClient(ResearchTransport { error("Planning typed media must not issue media requests") })
        val selected = client.plan(report, dev.kmpyt.youtube.YouTubeFormatSelection(video.id, audio.id))
        assertEquals(120L, TransferPlanning.totalBytes(selected))
        assertEquals(listOf(100L, 20L), TransferPlanning.parts(selected).map { it.expectedBytes })
        client.close()
        assertNull(TransferPlanning.totalBytes(plan(video, audio.copy(sizeBytes = null))))
    }

    @Test fun knownSizeIsPublishedBeforeFirstByteAndNullUpdatesDoNotEraseItOrDuplicateWrites() = runTest {
        val published = mutableListOf<Pair<List<PartState>, Long?>>()
        TransferCoordinator().run(plan(), emptyList(), { true }, { part, state, progress ->
            assertEquals(120L, published.first().second)
            assertEquals(0L, published.first().first.sumOf { it.bytes })
            assertEquals(part.expectedBytes, state.total)
            val before = published.size
            progress(state.copy(total = null), 0)
            assertEquals(before, published.size, "Unchanged initial progress must not write the database twice")
            progress(state.copy(bytes = 1, total = null), 10)
            state.copy(bytes = part.expectedBytes!!, total = null)
        }, { states, _, total -> published += states to total })
        assertTrue(published.all { it.second == 120L })
        assertTrue(published.last().first.all { it.complete })
    }

    @Test fun unknownAudioNeverTurnsKnownVideoSubtotalIntoAFullSize() = runTest {
        val totals = mutableListOf<Long?>()
        TransferCoordinator().run(plan(video, audio.copy(sizeBytes = null)), emptyList(), { true }, { part, state, progress ->
            if (part.key == "0-0") {
                progress(state.copy(bytes = 50), 10)
                assertNull(totals.last())
                state.copy(bytes = 100)
            } else {
                assertTrue(totals.all { it == null })
                progress(state.copy(total = 20), 0)
                assertEquals(120L, totals.last())
                state.copy(bytes = 20, total = 20)
            }
        }, { _, _, total -> totals += total })
        assertEquals(120L, totals.last())
    }

    @Test fun authoritativeHeadersAndCompletionReplaceTheDeclaredHint() = runTest {
        val totals = mutableListOf<Long?>()
        TransferCoordinator().run(plan(), emptyList(), { true }, { part, state, progress ->
            if (part.key == "0-0") {
                progress(state.copy(total = 110), 0)
                assertEquals(130L, totals.last())
                state.copy(bytes = 110, total = 110)
            } else state.copy(bytes = 18, total = null)
        }, { _, _, total -> totals += total })
        assertEquals(120L, totals.first())
        assertEquals(128L, totals.last())
    }

    @Test fun initializationAndRangesAreCountedOnceAndPartialRangesRemainUnknown() {
        val ranged = video.copy(sizeBytes = 999, transport = TransportKind.DASH,
            initialization = SegmentResource("https://media.example/init", 0, 5),
            segments = listOf(SegmentResource("https://media.example/one", 5, 10), SegmentResource("https://media.example/two", 15, 15)))
        assertEquals(30L, TransferPlanning.totalBytes(plan(ranged, null)))
        assertEquals(listOf(5L, 10L, 15L), TransferPlanning.parts(plan(ranged, null)).map { it.expectedBytes })
        val unknown = ranged.copy(sizeBytes = null, segments = ranged.segments + SegmentResource("https://media.example/unknown"))
        assertNull(TransferPlanning.totalBytes(plan(unknown, null)))
        assertEquals(50L, TransferPlanning.totalBytes(plan(unknown.copy(sizeBytes = 50), null)))
        assertNull(TransferPlanning.totalBytes(plan(unknown.copy(sizeBytes = 1), null)))
    }

    @Test fun aDeclaredWholeSegmentedTrackCanRemainKnownBeforeEveryPartHeaderArrives() = runTest {
        val segmented = video.copy(sizeBytes = 100, transport = TransportKind.DASH,
            segments = listOf(SegmentResource("https://media.example/one"), SegmentResource("https://media.example/two")))
        val totals = mutableListOf<Long?>()
        TransferCoordinator().run(plan(segmented), emptyList(), { true }, { part, state, _ ->
            assertEquals(120L, totals.last())
            state.copy(bytes = if (part.key.startsWith("0-")) 50 else 20)
        }, { _, _, total -> totals += total })
        assertTrue(totals.all { it == 120L })
    }

    @Test fun knownLowerBoundsStillRejectOverflowWhenAnotherSizeIsUnknown() {
        assertFailsWith<IllegalArgumentException> { TransferPlanning.totalBytes(plan(video.copy(sizeBytes = Long.MAX_VALUE), audio)) }
        val oversizedRanges = video.copy(sizeBytes = null, transport = TransportKind.DASH,
            segments = listOf(SegmentResource("https://media.example/one", 0, Long.MAX_VALUE),
                SegmentResource("https://media.example/two", 0, 1), SegmentResource("https://media.example/unknown")))
        assertFailsWith<IllegalArgumentException> { TransferPlanning.totalBytes(plan(oversizedRanges, null)) }
    }
}
