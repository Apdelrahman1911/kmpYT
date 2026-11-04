package dev.kmpyt.extraction

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransferCoordinatorLargeMediaTest {
    private fun plan(audio: Boolean = true) = PlannedDownload(
        Representation("video", "https://media.test/video", TrackRole.VIDEO, mimeType = "video/mp4"),
        if (audio) Representation("audio", "https://media.test/audio", TrackRole.AUDIO, mimeType = "audio/mp4") else null,
        inspectionUrl = "https://media.test/capture", mediaId = "actual-media")

    @Test fun resumesPast512MiBWithExactAggregateProgressAndSkipsCompletedParts() = runTest {
        val videoBytes = 600L * 1024 * 1024
        val audioBytes = 300L * 1024 * 1024
        val initial = listOf(PartState("0-0", videoBytes, videoBytes, complete = true),
            PartState("1-0", 200L * 1024 * 1024, audioBytes))
        val transferred = mutableListOf<String>()
        val published = mutableListOf<Pair<List<PartState>, Long?>>()
        TransferCoordinator().run(plan(), initial, { true }, { part, state, progress ->
            transferred += part.key
            assertEquals(initial[1], state)
            val done = state.copy(bytes = audioBytes)
            progress(done, 5000)
            done
        }, { states, _, total -> published += states to total })
        assertEquals(listOf("1-0"), transferred)
        assertEquals(videoBytes + audioBytes, published.last().second)
        assertEquals(videoBytes + audioBytes, published.last().first.sumOf { it.bytes })
        assertTrue(published.last().first.all { it.complete })
    }

    @Test fun restoredCompleteOrDeclaredTotalsCannotOverflowBeforeAnyTransfer() = runTest {
        for (initial in listOf(
            listOf(PartState("0-0", Long.MAX_VALUE, Long.MAX_VALUE, complete = true), PartState("1-0", 1, 1, complete = true)),
            listOf(PartState("0-0", 0, Long.MAX_VALUE), PartState("1-0", 0, 1)),
            listOf(PartState("0-0", -1, null)),
            listOf(PartState("0-0", 2, 1)),
        )) {
            var called = false
            assertFailsWith<IllegalArgumentException> {
                TransferCoordinator().run(plan(), initial, { true }, { _, state, _ -> called = true; state }, { _, _, _ -> called = true })
            }
            assertFalse(called)
        }
    }

    @Test fun finalPartUpdatesCannotPublishAnOverflowingAggregate() = runTest {
        val initial = listOf(PartState("0-0", Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, complete = true))
        var published = false
        assertFailsWith<IllegalArgumentException> {
            TransferCoordinator().run(plan(), initial, { true }, { _, state, _ -> state.copy(bytes = 2, total = 2) },
                { _, _, _ -> published = true })
        }
        assertFalse(published)
    }

    @Test fun customBudgetStillRejectsTooLargeRestoredOrReportedMedia() = runTest {
        val budget = 512L * 1024 * 1024
        assertFailsWith<IllegalArgumentException> {
            TransferCoordinator(budget).run(plan(false), listOf(PartState("0-0", budget + 1, budget + 1, complete = true)),
                { true }, { _, state, _ -> state }, { _, _, _ -> error("No oversize checkpoint may publish") })
        }
        assertFailsWith<IllegalArgumentException> {
            TransferCoordinator(budget).run(plan(false), emptyList(), { true },
                { _, state, _ -> state.copy(bytes = budget + 1, total = budget + 1) }, { _, _, _ -> error("No oversize checkpoint may publish") })
        }
    }

    @Test fun sourceRangesAcceptHighOffsetsButRejectExclusiveEndOverflow() {
        val original = plan(false)
        val highRange = SegmentResource("https://media.test/range", Long.MAX_VALUE - 2, 2)
        val ranged = original.copy(primary = original.primary.copy(transport = TransportKind.DASH, segments = listOf(highRange)))
        assertEquals(highRange, TransferPlanning.parts(ranged).single().resource)
        assertFailsWith<IllegalArgumentException> { TransferPlanning.parts(ranged.copy(primary = ranged.primary.copy(
            segments = listOf(highRange.copy(rangeLength = 3))))) }
        assertFailsWith<IllegalArgumentException> { TransferPlanning.parts(ranged.copy(primary = ranged.primary.copy(
            segments = listOf(highRange.copy(rangeLength = null))))) }
    }
}
