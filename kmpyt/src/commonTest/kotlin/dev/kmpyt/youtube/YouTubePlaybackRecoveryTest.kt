package dev.kmpyt.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubePlaybackRecoveryTest {
    @Test fun canonicalWatchPageKeepsTheLastWholeSecondWithoutSignedResourceParameters() {
        assertEquals("https://www.youtube.com/watch?v=bk-q2YsqZQY&t=42s",
            youTubePlaybackRecoveryUrl("bk-q2YsqZQY", 42_999))
    }

    @Test fun invalidOrInjectedIdentitiesDoNotBecomeExternalLinks() {
        listOf("", "short", "ABCDEFGHIJK&url=https://example.com", "https://youtu.be/ABCDEFGHIJK").forEach {
            assertNull(youTubePlaybackRecoveryUrl(it, 1_000))
        }
    }

    @Test fun negativeAndVeryLargePositionsCannotOverflowTheWatchTimestamp() {
        assertEquals("https://www.youtube.com/watch?v=ABCDEFGHIJK&t=0s", youTubePlaybackRecoveryUrl("ABCDEFGHIJK", -1))
        assertEquals("https://www.youtube.com/watch?v=ABCDEFGHIJK&t=9223372036854775s",
            youTubePlaybackRecoveryUrl("ABCDEFGHIJK", Long.MAX_VALUE))
    }
}
