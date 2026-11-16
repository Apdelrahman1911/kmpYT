package dev.kmpyt.extraction

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YouTubePlayerProgramStoreTest {
    private val watch = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    private val context = ExtractionContext(watch, watch)
    private val programUrl = "https://www.youtube.com/s/player/0123abcd/player_ias.vflset/en_US/base.js"
    private val page = """<script>ytcfg.set({"PLAYER_JS_URL":"/s/player/0123abcd/player_ias.vflset/en_US/base.js"});</script>"""
    private val source = """
        var _yt_player={};(function(realm){
        realm.Resource=function(input,enabled){this.set=function(key,value){};this.get=function(key){return key;};};
        var assemble;assemble=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set("alr","yes");challenge&&uri.set(key,challenge);return uri;};
        var configuration={signatureTimestamp:21002};
        })(_yt_player);
    """.trimIndent()

    @Test fun aStoredProgramSkipsTheDownloadButIsCompiledAgainInANewProcess() = runTest {
        val store = MemoryStore()
        val first = Fixture(store)
        try {
            val cold = first.cache.use { it.load(context, page) }
            assertFalse(cold.storeHit)
            assertNotNull(cold.compilation?.transforms)
            assertEquals(1, first.fetches)
            assertEquals(source, store.entries[programUrl])
            assertEquals(programUrl, store.latest())
        } finally { first.cache.clear() }

        // A fresh loader models a process restart: no in-memory program survives.
        val second = Fixture(store)
        try {
            val restarted = second.cache.use { it.load(context, page) }
            assertTrue(restarted.storeHit)
            assertFalse(restarted.cacheHit)
            assertTrue(restarted.diagnostics.any { it.code == "PLAYER_PROGRAM_STORE_HIT" })
            assertEquals("cba", assertNotNull(restarted.compilation?.transforms).signature("abc"))
            assertEquals(0, second.fetches)
            assertEquals(1, second.runtime.attempts)
            assertTrue(second.cache.use { it.load(context, page) }.cacheHit)
            assertEquals(1, second.runtime.attempts)
        } finally { second.cache.clear() }
    }

    @Test fun prewarmPreparesTheLatestStoredProgramWithoutAnyRequest() = runTest {
        val store = MemoryStore().apply { write(programUrl, source) }
        val fixture = Fixture(store)
        try {
            assertTrue(fixture.cache.prewarm())
            assertEquals(0, fixture.fetches)
            assertEquals(1, fixture.runtime.attempts)
            // Already prepared: a repeated prewarm and the next inspection reuse it.
            assertTrue(fixture.cache.prewarm())
            val loaded = fixture.cache.use { it.load(context, page) }
            assertTrue(loaded.cacheHit)
            assertEquals(0, fixture.fetches)
            assertEquals(1, fixture.runtime.attempts)
        } finally { fixture.cache.clear() }
    }

    @Test fun aDifferentDeclaredPlayerIsDownloadedEvenAfterPrewarming() = runTest {
        val store = MemoryStore().apply { write(programUrl, source) }
        val fixture = Fixture(store)
        try {
            assertTrue(fixture.cache.prewarm())
            val newer = page.replace("0123abcd", "4567ef01")
            val loaded = fixture.cache.use { it.load(context, newer) }
            assertFalse(loaded.cacheHit)
            assertFalse(loaded.storeHit)
            assertEquals(1, fixture.fetches)
            assertEquals(programUrl.replace("0123abcd", "4567ef01"), store.latest())
        } finally { fixture.cache.clear() }
    }

    @Test fun unusableOrMissingStoredProgramsFallBackToTheDeclaredNetworkSource() = runTest {
        val store = MemoryStore().apply { entries[programUrl] = "not a player program"; latestUrl = programUrl }
        val fixture = Fixture(store)
        try {
            assertFalse(fixture.cache.prewarm())
            assertEquals(0, fixture.fetches)
            val loaded = fixture.cache.use { it.load(context, page) }
            assertFalse(loaded.storeHit)
            assertNotNull(loaded.compilation?.transforms)
            assertEquals(1, fixture.fetches)
            assertEquals(source, store.entries[programUrl])
        } finally { fixture.cache.clear() }

        val failing = Fixture(object : YouTubePlayerProgramStore {
            override fun read(url: String): String = error("unreadable")
            override fun write(url: String, program: String) = error("full")
            override fun latest(): String = error("unreadable")
        })
        try {
            assertFalse(failing.cache.prewarm())
            assertNotNull(failing.cache.use { it.load(context, page) }.compilation?.transforms)
            assertEquals(1, failing.fetches)
        } finally { failing.cache.clear() }
    }

    @Test fun prewarmRefusesStoredUrlsThatAreNotPublicPlayerPrograms() = runTest {
        val store = MemoryStore().apply {
            entries["https://capture.example/owned/version-1.js"] = source
            latestUrl = "https://capture.example/owned/version-1.js"
        }
        val fixture = Fixture(store)
        try {
            assertFalse(fixture.cache.prewarm())
            assertEquals(0, fixture.runtime.attempts)
        } finally { fixture.cache.clear() }
        assertFalse(YouTubePlayerProgramCache(ResearchTransport { error("no request") }).prewarm())
    }

    @Test fun failedCompilationsAreNotStored() = runTest {
        val store = MemoryStore()
        val fixture = Fixture(store, rejectFirst = true)
        try {
            assertNull(fixture.cache.use { it.load(context, page) }.compilation?.transforms)
            assertTrue(store.entries.isEmpty())
            assertNull(store.latest())
        } finally { fixture.cache.clear() }
    }

    private inner class Fixture(store: YouTubePlayerProgramStore, rejectFirst: Boolean = false) {
        var fetches = 0
        val runtime = RecordingRuntime().apply { rejectNext = rejectFirst }
        val cache = YouTubePlayerProgramCache(ResearchTransport { request ->
            fetches++
            assertTrue(request.url.startsWith("https://www.youtube.com/s/player/"))
            ResearchResponse(request.url, 200, source, mapOf("content-type" to "text/javascript"))
        }, ContextYouTubePlayerProgramCompiler(runtime), store = store)
    }

    private class MemoryStore : YouTubePlayerProgramStore {
        val entries = linkedMapOf<String, String>()
        var latestUrl: String? = null
        override fun read(url: String) = entries[url]
        override fun write(url: String, program: String) { entries[url] = program; latestUrl = url }
        override fun latest() = latestUrl
    }

    private class RecordingRuntime : YouTubeJavaScriptRuntime {
        var attempts = 0
        var rejectNext = false
        override fun prepare(program: String): YouTubeJavaScriptSession {
            attempts++
            if (rejectNext) { rejectNext = false; throw YouTubeJavaScriptFailure("FIXTURE_TRANSIENT_STARTUP") }
            return object : YouTubeJavaScriptSession {
                override val capabilities = YouTubeJavaScriptCapabilities(true, true)
                override fun call(function: YouTubeJavaScriptFunction, input: String) = input.reversed()
                override fun close() {}
            }
        }
    }
}
