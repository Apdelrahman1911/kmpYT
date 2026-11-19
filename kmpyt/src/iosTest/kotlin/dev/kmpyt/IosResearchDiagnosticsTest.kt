package dev.kmpyt

import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import kotlinx.coroutines.withContext
import kotlin.test.*

class IosResearchDiagnosticsTest {
    @Test fun recordsResponsesAndSkipsCaptureWhenOffWithoutCopyingPrivatePayloads() = iosTest {
        var enabled = false
        val events = mutableListOf<IosResearchEvent>()
        val url = "https://fixture.invalid?token=private"
        val delegate = ResearchTransport { ResearchResponse(url, 403, "private fixture body") }
        val transport = observeIosResearch(delegate, { events += it }, { enabled })
        val request = ResearchRequest("GET", url, stage = "youtube-player-client")
        assertEquals(403, transport.execute(request).status)
        assertTrue(events.isEmpty())
        enabled = true
        withContext(ResearchTraceContext(42L)) { assertEquals(403, transport.execute(request).status) }
        assertEquals(listOf(IosResearchOutcome.STARTED, IosResearchOutcome.HTTP_FAILURE), events.map { it.outcome })
        assertEquals(1, events.map { it.requestId }.distinct().size)
        assertTrue(events.all { it.stage == IosResearchStage.PLAYER && it.correlationId == 42L })
        assertEquals(403, events.last().status)
        assertFalse(events.toString().contains("private"))
    }
}
