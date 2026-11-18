package dev.kmpyt.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressiveHttpRangeTest {
    private val media = "https://r1---fixture.googlevideo.com/videoplayback?itag=140&sig=authored"

    @Test fun exactInspectedExtentBoundsInitialAndResumedMediaWithoutChangingTheUrl() {
        assertEquals(1_247_701L, boundedProgressiveRangeEnd(media, 0, 1_247_702))
        assertEquals(1_247_701L, boundedProgressiveRangeEnd(media, 262_144, 1_247_702))
        assertEquals(Long.MAX_VALUE - 1, boundedProgressiveRangeEnd(media, 0, Long.MAX_VALUE))
    }

    @Test fun missingCompletedOrContradictoryLengthDoesNotInventAnEnd() {
        assertNull(boundedProgressiveRangeEnd(media, 0, null))
        assertNull(boundedProgressiveRangeEnd(media, 0, 0))
        assertNull(boundedProgressiveRangeEnd(media, -1, 100))
        assertNull(boundedProgressiveRangeEnd(media, 100, 100))
        assertNull(boundedProgressiveRangeEnd(media, 101, 100))
    }

    @Test fun otherOriginsAndLookalikeHostsRetainTheirExistingProtocol() {
        for (url in listOf("https://fixture.invalid/media", "https://googlevideo.com.fixture.invalid/media",
            "https://notgooglevideo.com/media", "invalid")) {
            assertNull(boundedProgressiveRangeEnd(url, 0, 100))
        }
    }
}
