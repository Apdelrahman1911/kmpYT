package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.jvm.JvmYouTubeDownloader
import dev.kmpyt.testfixtures.loopbackUrl
import dev.kmpyt.testfixtures.startLoopback
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Uses the existing authored CC0 AAC fixture, never a platform recording. */
class RefreshedProgressiveResumeTest {
    @Test fun changedSignatureAndExpiryResumeTheCommittedPrefixWithMatchingStrongValidator(): Unit = runBlocking {
        val body = requireNotNull(javaClass.getResourceAsStream("/research/audio.m4a")).use { it.readBytes() }
        val directory = Files.createTempDirectory("kmpyt-refreshed-resume-").toFile()
        val output = File(directory, "saved.m4a")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                return MockResponse().setResponseCode(if (start == 0) 200 else 206)
                    .setHeader("Content-Type", "audio/mp4").setHeader("ETag", "\"authored-audio\"")
                    .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${body.lastIndex}/${body.size}") }
                    .setBody(Buffer().write(body, start, body.size - start))
                    .throttleBody(4096, 20, TimeUnit.MILLISECONDS)
            }
        }
        server.startLoopback()
        val http = HttpClient(CIO)
        try {
            fun plan(signature: String, expiry: Long) = PlannedDownload(
                Representation("140", server.loopbackUrl("/videoplayback?id=authored&itag=140&sig=$signature&expire=$expiry").toString(),
                    TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = body.size.toLong(),
                    expiresAtEpochMs = expiry * 1000),
                inspectionUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK", mediaId = "ABCDEFGHIJK",
            )
            val original = plan("authored-first", 4_000_000_000)
            assertFailsWith<CancellationException> {
                JvmYouTubeDownloader(http, allowLocalHttp = true).download(original, output.path) {
                    if (it.bytesDownloaded > 0) throw CancellationException("Authored interruption")
                }
            }
            val checkpoint = File(directory, ".saved.m4a.kmpyt/checkpoint.json")
            val prefix = File(directory, ".saved.m4a.kmpyt/0-0").length()
            assertTrue(prefix in 1 until body.size.toLong())
            assertTrue(checkpoint.isFile)
            assertFalse(checkpoint.readText().contains("authored-first"))
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Range"))
            assertFalse(output.exists())

            // New downloader instance simulates caller/process recreation. Only
            // signature/expiry change; selected content, format and origin do not.
            val result = JvmYouTubeDownloader(http, allowLocalHttp = true)
                .download(plan("authored-refreshed", 4_000_003_600), output.path) { }
            val resumed = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("bytes=$prefix-", resumed.getHeader("Range"))
            assertEquals("\"authored-audio\"", resumed.getHeader("If-Range"))
            assertContentEquals(body, output.readBytes())
            assertEquals(body.size.toLong(), result.bytesWritten)
            assertEquals(2, server.requestCount)
            assertFalse(checkpoint.exists())
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun missingWeakAndMalformedStoredValidatorsRestartWithoutARange(): Unit = runBlocking {
        for (etag in listOf(null, "W/\"authored-audio\"", "not-an-entity-tag")) ResumeFixture().use { fixture ->
            fixture.responder = { fixture.response(it, etag = etag) }
            fixture.interrupt()
            fixture.resume()
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
    }

    @Test fun renewedResponseMustReturnTheSameStrongValidatorBeforeAnyAppend(): Unit = runBlocking {
        for (etag in listOf(null, "W/\"authored-audio\"", "\"different-entity\"")) ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            fixture.responder = { fixture.response(it, etag = etag) }
            fixture.resume()
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test fun changedContentPathQueryOrSelectedTrackDoesNotReuseAValidatorFromAnotherScope(): Unit = runBlocking {
        val changes: List<(PlannedDownload, ResumeFixture) -> PlannedDownload> = listOf(
            { plan, fixture -> plan.copy(primary = plan.primary.copy(url = fixture.url("/other-media?id=authored&itag=140&sig=second"))) },
            { plan, fixture -> plan.copy(primary = plan.primary.copy(url = fixture.url("/videoplayback?id=another&itag=140&sig=second"))) },
            { plan, _ -> plan.copy(primary = plan.primary.copy(audioLanguage = "ar")) },
            { plan, _ -> plan.copy(primary = plan.primary.copy(id = "141")) },
        )
        for (change in changes) ResumeFixture().use { fixture ->
            fixture.interrupt()
            fixture.resume(change(fixture.plan("second"), fixture))
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
        val source = "https://one.googlevideo.com/videoplayback?id=content&itag=140&sig=first&expire=1"
        assertEquals(progressiveResourceScope(source, "video", false),
            progressiveResourceScope(source.replace("first", "second").replace("expire=1", "expire=2"), "video", false))
        listOf(source.replace("one.googlevideo.com", "two.googlevideo.com"), source.replace("https://", "http://"),
            source.replace("/videoplayback", ":444/videoplayback"), source.replace("id=content", "id=other"),
            source.replace("itag=140", "itag=141")).forEach {
            assertNotEquals(progressiveResourceScope(source, "video", false), progressiveResourceScope(it, "video", false))
        }
        // Do not assume a parameter named sig is merely signing on an arbitrary external service.
        assertNotEquals(progressiveResourceScope("https://media.example/audio?sig=one", "video", false),
            progressiveResourceScope("https://media.example/audio?sig=two", "video", false))
    }

    @Test fun resourceScopePreservesRawContentQueryOrderEncodingCasingAndEmptyPairs() {
        val base = "https://one.googlevideo.com/videoplayback?id=content&itag=140&sig=first"
        listOf(
            base.replace("id=content&itag=140", "itag=140&id=content"),
            base.replace("content", "%63ontent"),
            base.replace("id=", "ID="),
            base.replace("&itag", "&&itag"),
            "$base&",
            base.replace("sig=first", "Sig=first"),
            base.replace("sig=first", "%73ig=first"),
        ).forEach { different ->
            assertNotEquals(progressiveResourceScope(base, "video", false), progressiveResourceScope(different, "video", false))
        }
        val cases = listOf(
            "id=a+b" to "id=a%20b",
            "id=a%2fb" to "id=a%2Fb",
            "id=one&id=two" to "id=two&id=one",
            "id" to "id=",
            "id=content&Sig=first" to "id=content&Sig=second",
            "id=content&%73ig=first" to "id=content&%73ig=second",
        )
        for ((first, second) in cases) {
            val prefix = "https://one.googlevideo.com/videoplayback?"
            assertNotEquals(progressiveResourceScope(prefix + first, "video", false),
                progressiveResourceScope(prefix + second, "video", false))
        }
        assertEquals(progressiveResourceScope(base, "video", false),
            progressiveResourceScope(base.replace("&sig=first", "&sig=second&expire=2"), "video", false))
    }

    @Test fun rawContentQueryChangesRestartTheActualTransfer(): Unit = runBlocking {
        for (query in listOf("itag=140&id=authored&sig=second", "id=%61uthored&itag=140&sig=second")) {
            ResumeFixture().use { fixture ->
                fixture.interrupt()
                val refreshed = fixture.plan().let { it.copy(primary = it.primary.copy(url = fixture.url("/videoplayback?$query"))) }
                fixture.resume(refreshed)
                assertNull(fixture.request().getHeader("Range"))
                assertContentEquals(fixture.body, fixture.output.readBytes())
            }
        }
    }

    @Test fun corruptCommittedBytesAreDiscardedButUncommittedTailIsOnlyTruncated(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            fixture.interrupt()
            RandomAccessFile(fixture.part, "rw").use { file -> val byte = file.read(); file.seek(0); file.write(byte xor 1) }
            fixture.resume()
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
        ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            fixture.part.appendBytes(byteArrayOf(1, 2, 3))
            fixture.resume()
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
    }

    @Test fun wrongOffsetRangeEndAndObjectLengthNeverPublish(): Unit = runBlocking {
        for (corruption in listOf("offset", "range-end", "total")) ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            fixture.responder = { request ->
                val start = if (corruption == "offset") prefix + 1 else prefix
                val end = if (corruption == "range-end") fixture.body.lastIndex - 1 else fixture.body.lastIndex
                val total = fixture.body.size + if (corruption == "total") 1 else 0
                fixture.response(request).setHeader("Content-Range", "bytes $start-$end/$total")
            }
            assertFailsWith<PermanentDownloadException> { fixture.resume() }
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertFalse(fixture.output.exists())
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test fun fullResponseAfterRangeReplacesInsteadOfAppendingTheOldPrefix(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            fixture.responder = { fixture.response(it, forceFull = true) }
            fixture.resume()
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test fun changedRedirectDestinationCannotBorrowTheOriginalResponseValidator(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            fixture.responder = { request ->
                if (request.path.orEmpty().startsWith("/videoplayback")) MockResponse().setResponseCode(302).setHeader("Location", "/another-object")
                else fixture.response(request)
            }
            fixture.resume()
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertEquals("bytes=$prefix-", fixture.request().getHeader("Range"))
            assertNull(fixture.request().getHeader("Range"))
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
    }

    @Test fun legacyCheckpointsCannotUseSignatureRefreshButCanUseTheExactOriginalPlan(): Unit = runBlocking {
        for (refresh in listOf(false, true)) ResumeFixture().use { fixture ->
            val prefix = fixture.interrupt()
            val original = Json.parseToJsonElement(fixture.checkpoint.readText()).jsonObject
            fixture.checkpoint.writeText(JsonObject(original.filterKeys { it in setOf("planDigest", "parts", "sabrCheckpoint") }).toString())
            fixture.resume(fixture.plan(if (refresh) "second" else "first"))
            assertEquals(if (refresh) null else "bytes=$prefix-", fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
    }

    @Test fun futureCheckpointVersionRestartsConservatively(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            fixture.interrupt()
            val original = Json.parseToJsonElement(fixture.checkpoint.readText()).jsonObject
            fixture.checkpoint.writeText(JsonObject(original + ("schema" to JsonPrimitive(99))).toString())
            fixture.resume()
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
        }
    }

    @Test fun linkedCheckpointEntriesAreRejectedBeforeTheFirstWriteOrRequest(): Unit = runBlocking {
        for (entry in listOf("lock", "checkpoint.json", "checkpoint.tmp", "0-0", "processing-stale", ".research-stale", "dangling")) {
            ResumeFixture().use { fixture ->
                val storage = fixture.checkpoint.parentFile.apply { check(mkdirs()) }
                val outside = File(fixture.directory, "caller-owned").apply { check(mkdir()) }
                val marker = File(outside, "keep.txt").apply { writeText("authored retained marker") }
                val target = when (entry) {
                    "processing-stale", ".research-stale" -> outside
                    "dangling" -> File(outside, "missing")
                    else -> marker
                }
                val link = File(storage, entry)
                Files.createSymbolicLink(link.toPath(), target.toPath())
                try {
                    assertFailsWith<IllegalStateException> { fixture.resume() }
                    assertEquals("authored retained marker", marker.readText())
                    assertEquals(0, fixture.server.requestCount)
                    assertFalse(fixture.output.exists())
                    if (entry != "lock") assertFalse(File(storage, "lock").exists())
                    assertEquals(listOf(entry), storage.listFiles()!!.map { it.name })
                } finally { check(link.delete()) }
            }
        }
    }

    @Test fun matchingAndFutureSchemaCleanupNeverFollowsAnInjectedChildDirectory(): Unit = runBlocking {
        for (future in listOf(false, true)) ResumeFixture().use { fixture ->
            fixture.interrupt()
            if (future) {
                val original = Json.parseToJsonElement(fixture.checkpoint.readText()).jsonObject
                fixture.checkpoint.writeText(JsonObject(original + ("schema" to JsonPrimitive(99))).toString())
            }
            val originalCheckpoint = fixture.checkpoint.readText()
            val outside = File(fixture.directory, "caller-owned").apply { check(mkdir()) }
            val marker = File(outside, "keep.txt").apply { writeText("authored retained marker") }
            val link = File(fixture.checkpoint.parentFile, "processing-stale")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            try {
                assertFailsWith<IllegalStateException> { fixture.resume() }
                assertEquals("authored retained marker", marker.readText())
                assertEquals(originalCheckpoint, fixture.checkpoint.readText())
                assertEquals(1, fixture.server.requestCount)
                assertFalse(fixture.output.exists())
            } finally { check(link.delete()) }
        }
    }

    @Test fun boundedCleanupValidatesEveryEntryBeforeDeletingAnyEntry(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            val storage = fixture.checkpoint.parentFile.apply { check(mkdirs()) }
            val lock = File(storage, "lock").apply { writeText("authored lock") }
            File(storage, "0-0").writeText("authored partial")
            val nested = File(storage, "processing-stale").apply { check(mkdir()) }
            File(nested, "primary").writeText("authored temporary")
            val link = File(nested, "dangling")
            Files.createSymbolicLink(link.toPath(), File(fixture.directory, "missing").toPath())
            try {
                assertFailsWith<IllegalStateException> { deleteCheckpointTree(storage, keepLock = true) }
                assertTrue(File(storage, "0-0").isFile)
                assertTrue(File(nested, "primary").isFile)
                assertTrue(lock.isFile)
            } finally { check(link.delete()) }
            deleteCheckpointTree(storage, keepLock = true)
            assertEquals(listOf("lock"), storage.listFiles()!!.map { it.name })
            deleteCheckpointTree(storage)
            assertFalse(storage.exists())
        }
    }

    @Test fun successfulCompletionPurgesMediaButPreservesTheReusableLockInode(): Unit = runBlocking {
        ResumeFixture().use { fixture ->
            fixture.interrupt()
            val storage = fixture.checkpoint.parentFile
            val lock = File(storage, "lock")
            val identity = assertNotNull(Files.readAttributes(lock.toPath(), "basic:fileKey")["fileKey"])
            fixture.resume()
            fixture.request()
            assertContentEquals(fixture.body, fixture.output.readBytes())
            assertEquals(listOf("lock"), storage.listFiles()!!.map { it.name })
            assertEquals(identity, Files.readAttributes(lock.toPath(), "basic:fileKey")["fileKey"])
            // A later owner must lock the same inode, not race a stale cleanup
            // that removed/recreated the path after releasing its OS lock.
            check(fixture.output.delete())
            fixture.resume()
            assertNull(fixture.request().getHeader("Range"))
            assertContentEquals(fixture.body, fixture.output.readBytes())
            assertEquals(listOf("lock"), storage.listFiles()!!.map { it.name })
            assertEquals(identity, Files.readAttributes(lock.toPath(), "basic:fileKey")["fileKey"])
        }
    }

    private class ResumeFixture : AutoCloseable {
        val body = requireNotNull(javaClass.getResourceAsStream("/research/audio.m4a")).use { it.readBytes() }
        val directory = Files.createTempDirectory("kmpyt-resume-negative-").toFile()
        val output = File(directory, "saved.m4a")
        val part = File(directory, ".saved.m4a.kmpyt/0-0")
        val checkpoint = File(directory, ".saved.m4a.kmpyt/checkpoint.json")
        val server = MockWebServer()
        val http = HttpClient(CIO)
        var responder: (RecordedRequest) -> MockResponse = { response(it) }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = responder(request)
            }
            server.startLoopback()
        }
        fun url(path: String) = server.loopbackUrl(path).toString()
        fun plan(signature: String = "second") = PlannedDownload(
            Representation("140", url("/videoplayback?id=authored&itag=140&sig=$signature"), TrackRole.AUDIO,
                mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = body.size.toLong()),
            inspectionUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK", mediaId = "ABCDEFGHIJK",
        )
        fun response(request: RecordedRequest, etag: String? = "\"authored-audio\"", forceFull: Boolean = false): MockResponse {
            val start = if (forceFull) 0 else request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            return MockResponse().setResponseCode(if (start == 0) 200 else 206).setHeader("Content-Type", "audio/mp4")
                .apply {
                    if (etag != null) setHeader("ETag", etag)
                    if (start > 0) setHeader("Content-Range", "bytes $start-${body.lastIndex}/${body.size}")
                }.setBody(Buffer().write(body, start, body.size - start)).throttleBody(4096, 10, TimeUnit.MILLISECONDS)
        }
        suspend fun interrupt(): Long {
            assertFailsWith<CancellationException> {
                JvmYouTubeDownloader(http, allowLocalHttp = true).download(plan("first"), output.path) {
                    if (it.bytesDownloaded > 0) throw CancellationException("Authored interruption")
                }
            }
            assertNull(request().getHeader("Range"))
            return part.length().also { assertTrue(it in 1 until body.size.toLong()) }
        }
        suspend fun resume(plan: PlannedDownload = plan()) = JvmYouTubeDownloader(http, allowLocalHttp = true).download(plan, output.path) { }
        fun request(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        override fun close() { http.close(); server.shutdown(); directory.deleteRecursively() }
    }
}
