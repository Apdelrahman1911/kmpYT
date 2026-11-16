package dev.kmpyt.extraction

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NeutralLanguageTransportTest {
    @Test fun requestsCarryANeutralLanguageUnlessTheCallerChoseOne() = runTest {
        val seen = mutableListOf<String?>()
        val transport = NeutralLanguageTransport(ResearchTransport { request ->
            seen += request.headers.entries.firstOrNull { it.key.equals("accept-language", true) }?.value
            ResearchResponse(request.url, 200, "")
        })
        transport.execute(ResearchRequest("GET", "https://www.youtube.com/results?search_query=x"))
        transport.execute(ResearchRequest("GET", "https://www.youtube.com/", headers = mapOf("accept-language" to "ar")))
        assertEquals(listOf<String?>("*", "ar"), seen)
    }
}
