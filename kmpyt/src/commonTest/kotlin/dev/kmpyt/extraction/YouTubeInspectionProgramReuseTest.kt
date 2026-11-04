package dev.kmpyt.extraction

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Counts real parser/compiler work while checking resource freshness and native-session lifetime. */
class YouTubeInspectionProgramReuseTest {
    private val canonical = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val context = ExtractionContext(canonical, canonical)
    private fun script(version: Int) = "https://www.youtube.com/s/player/version-$version/player_ias.vflset/en_US/base.js"
    private val source = """function s(a){a=a.split("");a.reverse();return a.join("");}function n(a){a=a.split("");a.reverse();return a.join("");}p.set("signature",s(v));p.set("n",n(v));var signatureTimestamp=12345;"""
    private fun page(generation: Int, version: Int = 1) = """
        <script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Generation $generation","author":"Fixture owner","lengthSeconds":"3","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/ABCDEFGHIJK/hqdefault.jpg"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[
        {"itag":18,"url":"https://media.example/low.mp4?generation=$generation&n=abc","mimeType":"video/mp4","contentLength":"100"},
        {"itag":22,"url":"https://media.example/high.mp4?generation=$generation&n=abc","mimeType":"video/mp4","contentLength":"200"}]}};</script>
        <script src="${script(version)}"></script>
    """.trimIndent()

    private class RecordingCompiler : YouTubePlayerProgramCompiler {
        val sessions = mutableListOf<Session>()
        var attempts = 0
        var rejectNext = false
        var failNextSession = false
        override fun compile(source: String, binding: YouTubeProgramBinding): YouTubeProgramCompilation {
            attempts++
            if (rejectNext) { rejectNext = false; return YouTubeProgramCompilation(null, emptyList()) }
            val actual = BoundedYouTubePlayerProgramCompiler().compile(source, binding)
            val session = Session(assertNotNull(actual.transforms)).also {
                it.failCalls = failNextSession; failNextSession = false; sessions.add(it)
            }
            return actual.copy(transforms = session)
        }
        class Session(private val actual: YouTubeUrlTransforms) : YouTubeUrlTransforms by actual {
            var closed = false
            var closes = 0
            var throttlingCalls = 0
            var failCalls = false
            override fun throttling(input: String): String {
                check(!closed) { "An active inspection lost its player session" }
                throttlingCalls++
                check(!failCalls) { "Fixture native runtime failure" }
                return actual.throttling(input)
            }
            override fun close() { closes++; closed = true; actual.close() }
        }
    }

    @Test fun repeatedInspectionsFetchFreshMediaButReuseProgramAndMemoizeEachCatalogue() = runTest {
        var watches = 0
        var programs = 0
        val compiler = RecordingCompiler()
        val transport = ResearchTransport { request -> when (request.stage) {
            "youtube-watch" -> ResearchResponse(request.url, 200, page(++watches))
            "youtube-player-program" -> { programs++; ResearchResponse(request.url, 200, source) }
            else -> error("No media, account, or redundant metadata request expected")
        } }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        val resolver = YouTubeInspectionResolver(transport, compiler = compiler, programCache = cache)
        try {
            val first = resolver.inspect(canonical, 100)
            val second = resolver.inspect(canonical, 200)
            assertEquals(2, watches)
            assertEquals(1, programs)
            assertEquals(1, compiler.attempts)
            assertEquals("Generation 1", first.media.single().title)
            assertEquals("Generation 2", second.media.single().title)
            assertTrue(second.media.single().representations.all { it.selectable })
            assertEquals("1", Url(first.media.single().representations.first().url!!).parameters["generation"])
            assertEquals("2", Url(second.media.single().representations.first().url!!).parameters["generation"])
            assertEquals("cba", Url(second.media.single().representations.first().url!!).parameters["n"])
            assertEquals(2, compiler.sessions.single().throttlingCalls, "Each catalogue reuses its repeated n input, without caching report URLs")
            assertFalse(compiler.sessions.single().closed)
            assertEquals(1, first.requestPlan.count { it.stage == "youtube-player-program" })
            assertEquals(0, second.requestPlan.count { it.stage == "youtube-player-program" })
            assertTrue(second.diagnostics.any { it.code == "PLAYER_PROGRAM_CACHE_HIT" })
            assertFalse(second.diagnostics.any { it.code in setOf("PLAYER_CONTEXT_RUNTIME_PREPARED",
                "SIGNATURE_PROGRAM_COMPILED", "THROTTLING_PROGRAM_COMPILED") }, "A cache hit must not report a fresh initialization")
        } finally { cache.clear() }
        assertEquals(1, compiler.sessions.single().closes)
    }

    @Test fun changedDeclaredPlayerVersionReplacesTheBoundedSession() = runTest {
        var generation = 0
        val compiler = RecordingCompiler()
        val transport = ResearchTransport { request -> if (request.stage == "youtube-watch") {
            val version = ++generation
            ResearchResponse(request.url, 200, page(version, version))
        } else ResearchResponse(request.url, 200, source) }
        val cache = YouTubePlayerProgramCache(transport, compiler, cacheEntries = 1)
        val resolver = YouTubeInspectionResolver(transport, compiler = compiler, programCache = cache)
        try {
            resolver.inspect(canonical)
            resolver.inspect(canonical)
            assertEquals(2, compiler.attempts)
            assertTrue(compiler.sessions.first().closed)
            assertFalse(compiler.sessions.last().closed)
        } finally { cache.clear() }
        assertTrue(compiler.sessions.all { it.closes == 1 })
    }

    @Test fun concurrentWatchRequestsOverlapButCompileTheSamePlayerOnlyOnce() = runTest {
        val programRequested = CompletableDeferred<Unit>()
        val releaseProgram = CompletableDeferred<Unit>()
        var watches = 0
        var programs = 0
        val compiler = RecordingCompiler()
        val transport = ResearchTransport { request -> if (request.stage == "youtube-watch") {
            ResearchResponse(request.url, 200, page(++watches))
        } else {
            programs++; programRequested.complete(Unit); releaseProgram.await()
            ResearchResponse(request.url, 200, source)
        } }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        val resolver = YouTubeInspectionResolver(transport, compiler = compiler, programCache = cache)
        try {
            val first = async { resolver.inspect(canonical) }
            programRequested.await()
            val second = async { resolver.inspect(canonical) }
            runCurrent()
            assertEquals(2, watches, "A slow player load must not block the next public watch request")
            assertEquals(1, programs)
            releaseProgram.complete(Unit)
            assertTrue(first.await().media.single().representations.all { it.selectable })
            assertTrue(second.await().media.single().representations.all { it.selectable })
            assertEquals(1, compiler.attempts)
        } finally { releaseProgram.complete(Unit); cache.clear() }
    }

    @Test fun memoryPressureClearWaitsForTheActiveLeaseAndCancellationReleasesTheQueue() = runTest {
        val compiler = RecordingCompiler()
        val transport = ResearchTransport { ResearchResponse(it.url, 200, source) }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = async { cache.use { loader ->
            val loaded = loader.load(context, page(1))
            entered.complete(Unit); release.await()
            assertFalse(compiler.sessions.single().closed)
            assertEquals("cba", loaded.compilation!!.transforms!!.throttling("abc"))
        } }
        entered.await()
        val cancelled = async { cache.use { error("Cancelled waiter must not take the lease") } }
        val clearing = async { cache.clear() }
        runCurrent()
        cancelled.cancel()
        assertFalse(clearing.isCompleted)
        assertFalse(compiler.sessions.single().closed)
        release.complete(Unit)
        active.await(); clearing.await()
        assertEquals(1, compiler.sessions.single().closes)
        cache.use { loader -> assertNotNull(loader.load(context, page(2)).compilation?.transforms) }
        assertEquals(2, compiler.attempts)
        cache.clear()
    }

    @Test fun transientCompilationFailureAndCancelledFetchDoNotPoisonThePersistentCache() = runTest {
        val compiler = RecordingCompiler().apply { rejectNext = true }
        var cancelNext = true
        var requests = 0
        val transport = ResearchTransport {
            requests++
            if (cancelNext) { cancelNext = false; throw CancellationException("fixture cancellation") }
            ResearchResponse(it.url, 200, source)
        }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        try {
            assertFailsWith<CancellationException> { cache.use { it.load(context, page(1)) } }
            assertNull(cache.use { it.load(context, page(1)) }.compilation?.transforms)
            assertNotNull(cache.use { it.load(context, page(1)) }.compilation?.transforms)
            assertEquals(3, requests)
            assertEquals(2, compiler.attempts)
        } finally { cache.clear() }
    }

    @Test fun cancellingAnActiveInspectionReleasesItsLeaseWithoutClosingAHealthyCachedProgram() = runTest {
        val compiler = RecordingCompiler()
        val transport = ResearchTransport { ResearchResponse(it.url, 200, source) }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        val entered = CompletableDeferred<Unit>()
        val active = launch { cache.use { loader ->
            assertNotNull(loader.load(context, page(1)).compilation?.transforms)
            entered.complete(Unit)
            awaitCancellation()
        } }
        entered.await()
        active.cancelAndJoin()
        try {
            cache.use { loader ->
                val reused = loader.load(context, page(2))
                assertTrue(reused.cacheHit)
                assertEquals("cba", reused.compilation!!.transforms!!.throttling("abc"))
            }
            assertEquals(1, compiler.attempts)
            assertFalse(compiler.sessions.single().closed)
        } finally { cache.clear() }
    }

    @Test fun failedRuntimeIsReleasedSoTheNextInspectionCanPrepareAHealthyVm() = runTest {
        val compiler = RecordingCompiler().apply { failNextSession = true }
        val transport = ResearchTransport { request -> ResearchResponse(request.url, 200,
            if (request.stage == "youtube-watch") page(1) else source) }
        val cache = YouTubePlayerProgramCache(transport, compiler)
        val resolver = YouTubeInspectionResolver(transport, compiler = compiler, programCache = cache)
        try {
            val failed = resolver.inspect(canonical)
            assertTrue(failed.media.single().representations.none { it.selectable })
            assertTrue(compiler.sessions.single().closed)
            assertEquals(2, compiler.sessions.single().throttlingCalls, "Failures must not be memoized")
            val recovered = resolver.inspect(canonical)
            assertTrue(recovered.media.single().representations.all { it.selectable })
            assertEquals(2, compiler.attempts)
            assertEquals(1, compiler.sessions.last().throttlingCalls)
        } finally { cache.clear() }
    }
}
