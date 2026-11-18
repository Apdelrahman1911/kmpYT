@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContextYouTubePlayerPreparationTest {
    private val context = ExtractionContext("https://capture.example/owned/watch", "https://capture.example/owned/watch")
    private val source = """
        var _yt_player={};(function(realm){
        realm.Resource=function(input,enabled){this.set=function(key,value){};this.get=function(key){return key;};};
        var assemble;assemble=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set("alr","yes");challenge&&uri.set(key,challenge);return uri;};
        var configuration={signatureTimestamp:21002};
        })(_yt_player);
    """.trimIndent()

    private fun page(version: Int) = "<script src='/owned/version-$version.js'></script>"

    @Test fun coldAndWarmLoadsReuseOnlyTheExactVersionedCompilation() = runTest {
        var fetches = 0
        val runtime = RecordingRuntime()
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            fetches++
            ResearchResponse(request.url, 200, source)
        }, ContextYouTubePlayerProgramCompiler(runtime))
        try {
            cache.use { loader ->
                val cold = loader.load(context, page(1))
                assertFalse(cold.cacheHit)
                assertEquals("cba", assertNotNull(cold.compilation?.transforms).signature("abc"))
            }
            cache.use { loader ->
                val warm = loader.load(context, page(1))
                assertTrue(warm.cacheHit)
                assertEquals("cba", assertNotNull(warm.compilation?.transforms).throttling("abc"))
            }
            assertEquals(1, fetches)
            assertEquals(1, runtime.attempts)
            val firstSession = runtime.sessions.single()
            cache.use { loader ->
                val replacement = loader.load(context, page(2))
                assertFalse(replacement.cacheHit)
                assertNotNull(replacement.compilation?.transforms)
            }
            assertEquals(2, fetches)
            assertEquals(2, runtime.attempts)
            assertEquals(1, firstSession.closes)
            assertEquals(0, runtime.sessions.last().closes)
        } finally { cache.clear() }
        assertTrue(runtime.sessions.all { it.closes == 1 })
    }

    @Test fun simultaneousColdLoadsCoalesceWithoutPreparingASecondNativeSession() = runTest {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime()
        var fetches = 0
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            fetches++
            requested.complete(Unit)
            release.await()
            ResearchResponse(request.url, 200, source)
        }, ContextYouTubePlayerProgramCompiler(runtime))
        try {
            val cold = async { cache.use { it.load(context, page(1)) } }
            requested.await()
            val warm = async { cache.use { it.load(context, page(1)) } }
            runCurrent()
            assertEquals(1, fetches)
            assertEquals(0, runtime.attempts)
            release.complete(Unit)
            val first = cold.await()
            val second = warm.await()
            assertFalse(first.cacheHit)
            assertTrue(second.cacheHit)
            assertSame(first.compilation?.transforms, second.compilation?.transforms)
            assertEquals(1, runtime.attempts)
        } finally { release.complete(Unit); cache.clear() }
    }

    @Test fun transientNativePreparationFailureIsNotCachedAsASuccess() = runTest {
        val runtime = RecordingRuntime().apply { rejectNext = true }
        var fetches = 0
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            fetches++
            ResearchResponse(request.url, 200, source)
        }, ContextYouTubePlayerProgramCompiler(runtime))
        try {
            val failed = cache.use { it.load(context, page(1)) }
            assertFalse(failed.cacheHit)
            assertNull(failed.compilation?.transforms)
            val recovered = cache.use { it.load(context, page(1)) }
            assertFalse(recovered.cacheHit)
            assertNotNull(recovered.compilation?.transforms)
            assertEquals(2, fetches)
            assertEquals(2, runtime.attempts)
            assertTrue(cache.use { it.load(context, page(1)) }.cacheHit)
            assertEquals(2, runtime.attempts)
        } finally { cache.clear() }
    }

    @Test fun cancellingAColdFetchReleasesTheLeaseAndDoesNotPublishAPartialProgram() = runTest {
        val requested = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime()
        var fetches = 0
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            fetches++
            if (fetches == 1) { requested.complete(Unit); awaitCancellation() }
            ResearchResponse(request.url, 200, source)
        }, ContextYouTubePlayerProgramCompiler(runtime))
        try {
            val cancelled = async { cache.use { it.load(context, page(1)) } }
            requested.await()
            val next = async { cache.use { it.load(context, page(1)) } }
            runCurrent()
            assertEquals(1, fetches)
            cancelled.cancelAndJoin()
            assertFailsWith<CancellationException> { cancelled.await() }
            val recovered = next.await()
            assertFalse(recovered.cacheHit)
            assertNotNull(recovered.compilation?.transforms)
            assertEquals(2, fetches)
            assertEquals(1, runtime.attempts)
        } finally { cache.clear() }
    }

    @Test fun clearWaitsForTheLeaseAndRevokedSessionsCannotServeWarmLoads() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime()
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            ResearchResponse(request.url, 200, source)
        }, ContextYouTubePlayerProgramCompiler(runtime))
        var previous: YouTubeUrlTransforms? = null
        try {
            val active = async { cache.use { loader ->
                val transforms = assertNotNull(loader.load(context, page(1)).compilation?.transforms)
                previous = transforms
                entered.complete(Unit)
                release.await()
                assertEquals("cba", transforms.throttling("abc"))
            } }
            entered.await()
            val cancelled = async { cache.use { error("A cancelled waiter must not obtain the lease") } }
            val clearing = async { cache.clear() }
            runCurrent()
            cancelled.cancelAndJoin()
            assertFalse(clearing.isCompleted)
            assertEquals(0, runtime.sessions.single().closes)
            release.complete(Unit)
            active.await()
            clearing.await()
            assertEquals(1, runtime.sessions.single().closes)
            assertFailsWith<IllegalStateException> { assertNotNull(previous).throttling("abc") }
            val replacement = cache.use { it.load(context, page(1)) }
            assertFalse(replacement.cacheHit)
            assertNotNull(replacement.compilation?.transforms)
            assertEquals(2, runtime.attempts)
            assertEquals(0, runtime.sessions.last().closes)
        } finally { release.complete(Unit); cache.clear() }
    }

    private class RecordingRuntime : YouTubeJavaScriptRuntime {
        var attempts = 0
        var rejectNext = false
        val sessions = mutableListOf<Session>()
        override fun prepare(program: String): YouTubeJavaScriptSession {
            attempts++
            if (rejectNext) { rejectNext = false; throw YouTubeJavaScriptFailure("FIXTURE_TRANSIENT_STARTUP") }
            assertTrue(program.contains("globalThis.__kmpyt_capabilities"))
            return Session().also { sessions += it }
        }

        class Session : YouTubeJavaScriptSession {
            var closes = 0
            override val capabilities = YouTubeJavaScriptCapabilities(true, true)
            override fun call(function: YouTubeJavaScriptFunction, input: String): String {
                check(closes == 0)
                return input.reversed()
            }
            override fun close() { closes++ }
        }
    }
}
