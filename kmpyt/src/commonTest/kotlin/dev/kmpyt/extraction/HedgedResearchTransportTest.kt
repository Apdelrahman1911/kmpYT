package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Virtual time; authored responses only. */
class HedgedResearchTransportTest {
    private val watch = ResearchRequest("GET", "https://www.youtube.com/watch?v=AUTHORED0001", stage = "youtube-watch")

    @Test fun aTimelyResponseIsNeverDuplicated() = runTest {
        var calls = 0
        val transport = HedgedResearchTransport(ResearchTransport { calls++; delay(1_000); ResearchResponse(it.url, 200, "first") })
        assertEquals("first", transport.execute(watch).body)
        assertEquals(1, calls)
    }

    @Test fun aStalledReadIsHedgedOnceAndTheLoserIsCancelled() = runTest {
        var calls = 0
        var cancelled = false
        val transport = HedgedResearchTransport(ResearchTransport {
            val number = ++calls
            try { delay(if (number == 1) 60_000 else 500) } catch (stop: CancellationException) { cancelled = true; throw stop }
            ResearchResponse(it.url, 200, "attempt-$number")
        })
        assertEquals("attempt-2", transport.execute(watch).body)
        assertEquals(2, calls)
        assertTrue(cancelled)
        assertTrue(testScheduler.currentTime < 5_000)
    }

    @Test fun anEarlyFailureIsReturnedWithoutAHiddenRetry() = runTest {
        var calls = 0
        val transport = HedgedResearchTransport(ResearchTransport { calls++; throw IllegalStateException("authored failure") })
        assertFailsWith<IllegalStateException> { transport.execute(watch) }
        assertEquals(1, calls)
    }

    @Test fun aFailedHedgeWaitsForTheOriginalAttempt() = runTest {
        var calls = 0
        val transport = HedgedResearchTransport(ResearchTransport {
            if (++calls == 2) throw IllegalStateException("hedge failed")
            delay(6_000); ResearchResponse(it.url, 200, "original")
        })
        assertEquals("original", transport.execute(watch).body)
        assertEquals(2, calls)
    }

    @Test fun aTransportThrownCancellationPropagatesWithoutAHedge() = runTest {
        var calls = 0
        val cancellation = CancellationException("authored cancellation")
        val transport = HedgedResearchTransport(ResearchTransport { calls++; throw cancellation })
        assertEquals("authored cancellation", assertFailsWith<CancellationException> { transport.execute(watch) }.message)
        assertEquals(1, calls)
    }

    @Test fun otherStagesAreNeverHedged() = runTest {
        var calls = 0
        val transport = HedgedResearchTransport(ResearchTransport { calls++; delay(60_000); ResearchResponse(it.url, 200, "manifest") })
        transport.execute(watch.copy(stage = "selected-manifest"))
        assertEquals(1, calls)
    }
}
