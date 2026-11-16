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

class YouTubeClientNeutralLanguageTest {
    @Test fun everyClientServiceSendsTheNeutralLanguage() = runTest {
        val seen = mutableListOf<String?>()
        val client = dev.kmpyt.YouTubeClient(ResearchTransport { request ->
            seen += request.headers.entries.firstOrNull { it.key.equals("accept-language", true) }?.value
            ResearchResponse(request.url, 200, "<html></html>")
        })
        runCatching { client.search("Authored complete title") }
        runCatching { client.inspectAudio("https://www.youtube.com/watch?v=AUTHORED001") }
        client.close()
        kotlin.test.assertTrue(seen.isNotEmpty())
        kotlin.test.assertTrue(seen.all { it == "*" }, seen.toString())
    }
}
