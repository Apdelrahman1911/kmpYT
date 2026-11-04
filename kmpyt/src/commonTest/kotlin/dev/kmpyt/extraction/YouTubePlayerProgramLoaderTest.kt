package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Network behavior is isolated here; live compatibility is recorded by integration tests. */
class YouTubePlayerProgramLoaderTest {
    private val context = ExtractionContext("https://www.youtube.com/watch?v=ABCDEFGHIJK", "https://owned.test/youtube/capture")
    private val binding = YouTubeProgramBinding("ownedSig", "ownedN")
    private val program = """function ownedSig(a){a=a.split("");a.reverse();return a.join("");}function ownedN(a){a=a.split("");a.reverse();return a.join("");}var signatureTimestamp=12345;"""

    private class RecordingTransport(private val reply: (ResearchRequest) -> ResearchResponse) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse { requests += request; return reply(request) }
    }

    @Test fun discoversOnlyActualPermittedReferencesIncludingEscapedJsonAndScriptSrc() {
        val loader = YouTubePlayerProgramLoader(RecordingTransport { error("Discovery must not fetch") })
        assertEquals("https://owned.test/player.js", loader.discover(context, """{"PLAYER_JS_URL":"\/player.js"}"""))
        assertEquals("https://owned.test/program.js", loader.discover(context, "<script type='text/javascript' src='/program.js'></script>"))
        assertEquals("https://www.youtube.com/s/player/11a45ffb/player_ias.vflset/ar_EG/base.js",
            loader.discover(context, """{"jsUrl":"https://www.youtube.com/s/player/11a45ffb/player_ias.vflset/ar_EG/base.js"}"""))
        listOf("https://accounts.google.com/challenge.js", "https://www.youtube.com/getpo.js", "https://foreign.test/player.js", "file:///tmp/player.js", "https://user:secret@owned.test/player.js")
            .forEach { value -> assertNull(loader.discover(context, """{"jsUrl":"$value"}""")) }
        assertNull(loader.discover(context, "<html>No declared player</html>"))
    }

    @Test fun actualProgramCompilationIsCachedByDeclaredVersionAndBindings() = runTest {
        val transport = RecordingTransport { ResearchResponse(it.url, 200, program) }
        val loader = YouTubePlayerProgramLoader(transport)
        val page = """{"jsUrl":"/player.js"}"""
        val first = loader.load(context, page, binding)
        val transforms = assertNotNull(first.compilation?.transforms)
        assertTrue(transforms.signatureAvailable)
        assertEquals("fedcba", transforms.signature("abcdef"))
        assertEquals("cba", transforms.throttling("abc"))
        val second = loader.load(context, page, binding)
        assertTrue(second.cacheHit)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().headers.keys.none { it.lowercase() in setOf("cookie", "authorization") })
        loader.load(context, """{"jsUrl":"/player-next.js"}""", binding)
        assertEquals(2, transport.requests.size)
        loader.load(context, page, YouTubeProgramBinding("ownedN", "ownedSig"))
        assertEquals(3, transport.requests.size)
        loader.clear()
        assertFalse(loader.load(context, page, binding).cacheHit)
        assertEquals(4, transport.requests.size)
    }

    @Test fun concurrentRequestsDeduplicateADeclaredProgram() = runTest {
        val transport = RecordingTransport { ResearchResponse(it.url, 200, program) }
        val loader = YouTubePlayerProgramLoader(transport)
        val results = List(4) { async { loader.load(context, """{"jsUrl":"/player.js"}""", binding) } }.awaitAll()
        assertEquals(1, transport.requests.size)
        assertEquals(3, results.count { it.cacheHit })
        assertTrue(results.all { it.compilation?.transforms?.signatureAvailable == true })
    }

    @Test fun failedRedirectOversizedAndMismatchedProgramsAreNotCompiledOrCached() = runTest {
        listOf(
            ResearchResponse("https://owned.test/player.js", 302, "", mapOf("location" to "https://private.test/?token=private-value")),
            ResearchResponse("https://owned.test/player.js", 200, "x".repeat(YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS + 1)),
            ResearchResponse("https://foreign.test/player.js", 200, program),
            ResearchResponse("https://owned.test/player.js", 200, program, mapOf("Content-Type" to "text/html")),
        ).forEach { reply ->
            val transport = RecordingTransport { reply }
            val loader = YouTubePlayerProgramLoader(transport)
            val result = loader.load(context, """{"jsUrl":"/player.js"}""", binding)
            assertNull(result.compilation)
            assertFalse(result.diagnostics.toString().contains("private-value"))
            loader.load(context, """{"jsUrl":"/player.js"}""", binding)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun networkFailureIsRedactedAndCancellationPropagates() = runTest {
        val failing = RecordingTransport { error("Authorization: private-token; https://private.test/?token=private-value") }
        val result = YouTubePlayerProgramLoader(failing).load(context, """{"jsUrl":"/player.js"}""", binding)
        assertNull(result.compilation)
        assertFalse(result.diagnostics.toString().contains("private-token"))
        assertFalse(result.diagnostics.toString().contains("private-value"))
        val cancelled = RecordingTransport { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { YouTubePlayerProgramLoader(cancelled).load(context, """{"jsUrl":"/player.js"}""", binding) }
    }
}
