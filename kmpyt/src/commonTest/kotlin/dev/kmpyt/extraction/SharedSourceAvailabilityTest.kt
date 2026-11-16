package dev.kmpyt.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SharedSourceAvailabilityTest {
    private fun track(id: String, transport: TransportKind, availableAt: Long?) =
        Representation(id, "https://r1---fixture.googlevideo.com/videoplayback?id=$id", TrackRole.AUDIO,
            transport = transport, mimeType = "audio/mp4", availableAtEpochMs = availableAt)

    @Test fun theLongestDeclaredWaitGatesEveryTrackOfTheInspection() {
        val media = ExtractedMedia("AUTHORED001", ProviderId.YOUTUBE, "Authored", "https://www.youtube.com/watch?v=AUTHORED001",
            listOf(track("sabr-web", TransportKind.SABR, null), track("140#progressive", TransportKind.PROGRESSIVE, 5_000),
                track("251", TransportKind.PROGRESSIVE, 9_000)))
        assertEquals(listOf(9_000L, 9_000L, 9_000L), sharedSourceAvailability(media).representations.map { it.availableAtEpochMs })
    }

    @Test fun anInspectionWithoutADeclaredWaitIsUnchanged() {
        val media = ExtractedMedia("AUTHORED001", ProviderId.YOUTUBE, "Authored", "https://www.youtube.com/watch?v=AUTHORED001",
            listOf(track("sabr-web", TransportKind.SABR, null)))
        assertSame(media, sharedSourceAvailability(media))
    }
}
