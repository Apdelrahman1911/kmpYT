package dev.kmpyt.extraction

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class YouTubeObservedWebClientTest {
    private val canonical = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val context = ExtractionContext(canonical, canonical)
    private val declaration = """{"INNERTUBE_CLIENT_NAME":"WEB","INNERTUBE_CLIENT_VERSION":"observed.123","INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_API_KEY":"public-key","STS":12345,"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"observed.123","hl":"en","visitorData":"anonymous-id","poToken":"must-not-send","authorization":"must-not-send"},"user":{"accountIndex":2}}}"""

    @Test fun copiesObservedBrowserFieldsAndTimestampWithoutInventingCredentials() {
        val contract = assertNotNull(YouTubeObservedWebClient.discover(context, "<script>ytcfg.set($declaration);</script>"))
        assertEquals("WEB", contract.clientContext.text("clientName"))
        assertEquals("observed.123", contract.clientContext.text("clientVersion"))
        assertEquals(12345L, contract.signatureTimestamp)
        assertEquals("1", contract.headers["X-Youtube-Client-Name"])
        assertEquals("public-key", Url(contract.endpoint).parameters["key"])
        assertNull(contract.clientContext["poToken"])
        assertNull(contract.clientContext["authorization"])
        assertNull(contract.clientContext["user"])
        assertFalse(contract.headers.keys.any { it.lowercase() in setOf("cookie", "authorization") })
    }

    @Test fun rejectsUnrelatedOriginsInventedClientsAndMismatchedDeclarations() {
        assertNull(YouTubeObservedWebClient.discover(context.copy(responseUrl = "https://owned.test/watch"), "ytcfg.set($declaration)"))
        assertNull(YouTubeObservedWebClient.discover(context, "ytcfg.set(${declaration.replace("WEB", "ANDROID")})"))
        assertNull(YouTubeObservedWebClient.discover(context, "ytcfg.set(${declaration.replace("\"INNERTUBE_CLIENT_VERSION\":\"observed.123\"", "\"INNERTUBE_CLIENT_VERSION\":\"different\"")})"))
        assertNull(YouTubeObservedWebClient.discover(context, "ytcfg.set({dynamic:true})"))
        assertNull(YouTubeObservedWebClient.discover(context, "ytcfg.set(${declaration.replace("observed.123", "observed\\u007f")})"))
    }

    @Test fun acceptsLargeRealisticDeclarationWithoutForwardingUnrelatedPageSettings() {
        val large = declaration.dropLast(1) + ",\"UNRELATED_SETTINGS\":\"${"x".repeat(800_000)}\"}"
        val contract = assertNotNull(YouTubeObservedWebClient.discover(context, "ytcfg.set($large)"))
        assertEquals("observed.123", contract.clientContext.text("clientVersion"))
        assertTrue(contract.clientContext.toString().length < 1024)
        assertFalse(contract.clientContext.containsKey("UNRELATED_SETTINGS"))
    }

    @Test fun resolverPostsActualContractAndRedactsItsDiagnosticReceipts() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val first = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned","author":"Owner","lengthSeconds":"3","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/ABCDEFGHIJK/default.jpg"}]}}}"""
        val player = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Owned"},"streamingData":{"formats":[{"itag":18,"url":"https://owned.test/test.mp4","mimeType":"video/mp4","width":160,"height":90}]}}"""
        val transport = ResearchTransport { request ->
            requests += request
            ResearchResponse(request.url, 200, if (request.method == "POST") player else "<script>ytcfg.set($declaration);</script><script>var ytInitialPlayerResponse=$first;</script>")
        }
        val report = YouTubeInspectionResolver(transport).inspect(canonical)
        val post = requests.single { it.method == "POST" }
        val body = Json.parseToJsonElement(assertNotNull(post.body)).jsonObject
        assertEquals("ABCDEFGHIJK", body.text("videoId"))
        assertEquals(12345L, body.obj("playbackContext")?.obj("contentPlaybackContext")?.number("signatureTimestamp"))
        assertTrue(report.media.single().representations.single().selectable)
        assertTrue(report.diagnostics.any { it.code == "OBSERVED_WEB_CLIENT_CONTRACT" })
        assertFalse(report.requestPlan.toString().contains("public-key"))
        assertFalse(report.requestPlan.toString().contains("anonymous-id"))
        assertFalse(report.requestPlan.toString().contains("must-not-send"))
        assertTrue(requests.none { it.url.startsWith("https://owned.test") })
    }
}
