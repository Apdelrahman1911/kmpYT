@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import platform.Foundation.*
import kotlin.test.*

/** Authored media, actual POSIX files and new downloader instances; no network or account access. */
class IosDownloadResumeTest {
    private val bytes get() = IosAuthoredMedia.video
    private val cut get() = bytes.size / 3
    private val validator = "\"private-validator-v1\""

    @Test fun interruptedDirectDownloadResumesAcrossInstancesAndSignedUrlRefreshWithoutLeakingRequestData() = iosTest {
        inIosTestDirectory { directory ->
            val first = plan(url = "https://fixture.invalid/video?itag=136&sig=SECRET_OLD&expires=1")
            interrupt(directory, first)
            val text = checkpointText(directory)
            listOf("https", "fixture.invalid", "SECRET_OLD", "private-validator", "User-Agent").forEach {
                assertFalse(it in text, "Checkpoint must contain fingerprints, not request/validator values.")
            }
            val refreshed = first.copy(primary = first.primary.copy(url = "https://fixture.invalid/video?itag=136&sig=SECRET_NEW&expires=2"))
            val updates = mutableListOf<YouTubeDownloadProgress>()
            val http = HttpClient(MockEngine { request ->
                assertEquals("bytes=$cut-", request.headers[HttpHeaders.Range])
                assertEquals("SECRET_NEW", request.url.parameters["sig"])
                respond(bytes.copyOfRange(cut, bytes.size), HttpStatusCode.PartialContent,
                    mediaHeaders(bytes.size - cut, validator, "bytes $cut-${bytes.lastIndex}/${bytes.size}"))
            }) { followRedirects = false }
            try {
                val result = IosYouTubeDownloader(http).download(refreshed, "$directory/output.mp4") { updates += it }
                assertEquals(bytes.size.toLong(), result.bytesWritten)
                assertEquals(cut.toLong(), updates.first().bytesDownloaded)
                assertTrue(updates.all { it.totalBytes == bytes.size.toLong() })
                assertContentEquals(bytes, read(result.destinationPath))
                assertEquals(listOf("output.mp4"), children(directory))
            } finally { http.close() }
        }
    }

    @Test fun changedMissingWeakOrIgnoredResumeValidatorsRestartBeforeAppendingAnyResponseBytes() = iosTest {
        for (changed in listOf("\"different\"", "W/$validator", null, "ignored-range")) {
            inIosTestDirectory { directory ->
                val selected = plan()
                interrupt(directory, selected)
                val replacement = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
                var requests = 0
                val http = HttpClient(MockEngine { request ->
                    requests++
                    if (requests == 1) {
                        assertEquals("bytes=$cut-", request.headers[HttpHeaders.Range])
                        if (changed == "ignored-range") respond(replacement, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
                        else respond(replacement.copyOfRange(cut, bytes.size), HttpStatusCode.PartialContent,
                            mediaHeaders(bytes.size - cut, changed, "bytes $cut-${bytes.lastIndex}/${bytes.size}"))
                    } else {
                        assertNull(request.headers[HttpHeaders.Range])
                        respond(replacement, HttpStatusCode.OK, mediaHeaders(bytes.size, "\"different\""))
                    }
                }) { followRedirects = false }
                try {
                    val result = IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {}
                    assertEquals(2, requests)
                    assertContentEquals(replacement, read(result.destinationPath), "No old prefix may be mixed with the replacement response.")
                } finally { http.close() }
            }
        }
    }

    @Test fun interruptedChunkedReplacementForgetsTheOldSizeAndResumesAtItsActualNewLength() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan(size = null)
            interrupt(directory, selected)
            // A valid MP4 free atom changes the entity length without changing the authored media tracks.
            val freeAtom = ByteArray(136).also {
                it[3] = 136.toByte()
                "free".encodeToByteArray().copyInto(it, 4)
            }
            val replacement = bytes + freeAtom
            val replacementCut = bytes.size + 32
            val newTag = "\"replacement-v2\""
            val updates = mutableListOf<YouTubeDownloadProgress>()
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                if (requests == 1) {
                    assertEquals("bytes=$cut-", request.headers[HttpHeaders.Range])
                    respond(replacement.copyOfRange(cut, replacement.size), HttpStatusCode.PartialContent,
                        mediaHeaders(replacement.size - cut, newTag, "bytes $cut-${replacement.lastIndex}/${replacement.size}"))
                } else {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(replacement.copyOfRange(0, replacementCut), HttpStatusCode.OK,
                        Headers.build {
                            appendAll(mediaHeaders(null, newTag))
                            append(HttpHeaders.TransferEncoding, "chunked")
                        })
                }
            }) { followRedirects = false }
            try {
                assertFailsWith<CancellationException> {
                    IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {
                        updates += it
                        if (it.bytesDownloaded > bytes.size) throw CancellationException("interrupt the larger replacement")
                    }
                }
                assertEquals(2, requests)
                assertEquals(bytes.size.toLong(), updates.first().totalBytes)
                val reset = updates.indexOfFirst { it.bytesDownloaded == 0L && it.totalBytes == null }
                assertTrue(reset >= 0, "The changed entity must publish an unknown-size restart before its first bytes.")
                assertTrue(updates.drop(reset).all { it.totalBytes == null })
                val saved = savedPart(directory, selected)
                assertEquals(replacementCut.toLong(), saved.bytes)
                assertNull(saved.total)
                assertNull(saved.objectTotalBytes)
                assertFalse(saved.complete)
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/output.mp4"))
            } finally { http.close() }
            val resumed = HttpClient(MockEngine { request ->
                assertEquals("bytes=$replacementCut-", request.headers[HttpHeaders.Range])
                respond(replacement.copyOfRange(replacementCut, replacement.size), HttpStatusCode.PartialContent,
                    mediaHeaders(replacement.size - replacementCut, newTag,
                        "bytes $replacementCut-${replacement.lastIndex}/${replacement.size}"))
            }) { followRedirects = false }
            val resumedUpdates = mutableListOf<YouTubeDownloadProgress>()
            try {
                val result = IosYouTubeDownloader(resumed).download(selected, "$directory/output.mp4") { resumedUpdates += it }
                assertNull(resumedUpdates.first().totalBytes)
                assertEquals(replacement.size.toLong(), resumedUpdates.last().totalBytes)
                assertEquals(replacement.size.toLong(), result.bytesWritten)
                assertContentEquals(replacement, read(result.destinationPath))
                assertEquals(listOf("output.mp4"), children(directory))
            } finally { resumed.close() }
        }
    }

    @Test fun weakOrMissingInitialValidatorsDoNotRetainUnverifiablePartialBytes() = iosTest {
        for (tag in listOf(null, "W/$validator", "\"malformed\" \"tag\"")) {
            inIosTestDirectory { directory ->
                interrupt(directory, plan(), tag = tag)
                assertTrue(children(directory).isEmpty())
                val http = HttpClient(MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
                })
                try { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
                finally { http.close() }
                assertContentEquals(bytes, read("$directory/output.mp4"))
            }
        }
    }

    @Test fun changedMediaIdentityOrResourceScopeRestartsEvenWhenServerReusesTheSameEtag() = iosTest {
        for (change in 0..3) inIosTestDirectory { directory ->
            val original = plan()
            interrupt(directory, original)
            val changed = when (change) {
                0 -> original.copy(mediaId = "AUTHORED002")
                1 -> original.copy(primary = original.primary.copy(width = 64))
                2 -> original.copy(primary = original.primary.copy(url = "https://fixture.invalid/different"))
                else -> original.copy(primary = original.primary.copy(url = "https://fixture.invalid/video?entity=different"))
            }
            val http = HttpClient(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Range])
                respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
            })
            try { IosYouTubeDownloader(http).download(changed, "$directory/output.mp4") {} }
            finally { http.close() }
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun declaredSizeRemainsMandatoryWhenContentLengthIsAbsent() = iosTest {
        inIosTestDirectory { directory ->
            val http = HttpClient(MockEngine {
                respond(bytes.copyOfRange(0, cut), HttpStatusCode.OK, mediaHeaders(null, validator))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try {
                assertFailsWith<IllegalStateException> {
                    IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") { updates += it }
                }
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/output.mp4"))
                assertTrue(updates.all { it.totalBytes == bytes.size.toLong() })
                assertEquals(cut.toLong(), savedPart(directory, plan()).bytes)
            } finally { http.close() }
        }
    }

    @Test fun cancellationPreservesCommittedPrefixAndAQuickCancelledRetryDoesNotDeleteIt() = iosTest {
        inIosTestDirectory { directory ->
            val http = HttpClient(MockEngine {
                respond(bytes.copyOfRange(0, cut), HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
            })
            try {
                coroutineScope {
                    val committed = CompletableDeferred<Unit>()
                    val job = launch {
                        IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {
                            if (it.bytesDownloaded > 0) { committed.complete(Unit); awaitCancellation() }
                        }
                    }
                    committed.await(); job.cancelAndJoin()
                }
                assertEquals(cut.toLong(), savedPart(directory, plan()).bytes)
                val before = checkpointText(directory)
                coroutineScope {
                    val cancelled = launch(start = CoroutineStart.LAZY) {
                        IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {}
                    }
                    cancelled.cancelAndJoin()
                }
                assertEquals(before, checkpointText(directory))
            } finally { http.close() }
        }
    }

    @Test fun incorrectRangeAnd416CannotCertifyTruncatedSavedCoverage() = iosTest {
        for (bad in listOf("start", "total", "416")) inIosTestDirectory { directory ->
            val selected = plan(size = null)
            interrupt(directory, selected)
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                if (bad == "416" && requests == 1) {
                    assertEquals("bytes=$cut-", request.headers[HttpHeaders.Range])
                    respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable,
                        mediaHeaders(0, validator, "bytes */$cut"))
                } else if (bad == "416") {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
                } else respond(bytes.copyOfRange(cut, bytes.size), HttpStatusCode.PartialContent,
                    mediaHeaders(bytes.size - cut, validator,
                        if (bad == "start") "bytes ${cut + 1}-${bytes.lastIndex}/${bytes.size}"
                        else "bytes $cut-${bytes.lastIndex}/${bytes.size + 1}"))
            }) { followRedirects = false }
            try {
                if (bad == "416") {
                    val result = IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {}
                    assertEquals(2, requests)
                    assertContentEquals(bytes, read(result.destinationPath))
                } else {
                    assertFailsWith<IllegalStateException> { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {} }
                    assertEquals(cut.toLong(), savedPart(directory, selected).bytes)
                    assertContentEquals(bytes.copyOfRange(0, cut), read(savedPath(directory)))
                }
            } finally { http.close() }
        }
    }

    @Test fun fullResponseWithContentRangeIsNotPublished() = iosTest {
        inIosTestDirectory { directory ->
            val http = HttpClient(MockEngine {
                respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator, "bytes 0-${bytes.lastIndex}/${bytes.size}"))
            })
            try {
                assertFailsWith<IllegalStateException> { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
                assertTrue(children(directory).isEmpty())
            } finally { http.close() }
        }
    }

    @Test fun explicitPartRangeResumesAtAbsoluteSourceOffsetAndKeepsOnlySelectedBytes() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan(size = 100)
            val part = TransferPart("0-0", SegmentResource(selected.primary.url!!, rangeStart = 37, rangeLength = 100), "video/mp4", 100)
            val payload = ByteArray(100) { it.toByte() }
            val first = HttpClient(MockEngine { request ->
                assertEquals("bytes=37-136", request.headers[HttpHeaders.Range])
                respond(payload.copyOfRange(0, 31), HttpStatusCode.PartialContent, mediaHeaders(100, validator, "bytes 37-136/900"))
            })
            val initial = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(selected))
            try {
                initial.load(selected, listOf(part))
                assertFailsWith<IllegalStateException> { IosHttpPartTransfer(first, initial, selected, false).fetch(part) { _, _ -> } }
            } finally { initial.close(); first.close() }
            val changed = HttpClient(MockEngine {
                respond(payload.copyOfRange(31, 100), HttpStatusCode.PartialContent, mediaHeaders(69, validator, "bytes 68-136/901"))
            })
            val rejected = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(selected))
            try {
                rejected.load(selected, listOf(part))
                assertFailsWith<IllegalStateException> { IosHttpPartTransfer(changed, rejected, selected, false).fetch(part) { _, _ -> } }
                assertEquals(31L, rejected.parts.single().bytes)
                assertEquals(900L, rejected.parts.single().objectTotalBytes)
            } finally { rejected.close(); changed.close() }
            val http = HttpClient(MockEngine { request ->
                assertEquals("bytes=68-136", request.headers[HttpHeaders.Range])
                respond(payload.copyOfRange(31, 100), HttpStatusCode.PartialContent, mediaHeaders(69, validator, "bytes 68-136/900"))
            })
            val resumed = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(selected))
            try {
                resumed.load(selected, listOf(part))
                val result = IosHttpPartTransfer(http, resumed, selected, false).fetch(part) { _, _ -> }
                assertEquals(100L, result.bytes)
                assertTrue(result.complete)
                assertContentEquals(payload, read(resumed.partPath(part.key)))
            } finally { resumed.close(discard = true); http.close() }
        }
    }

    @Test fun fullUnfinishedCheckpointIsRevalidatedAndNeverProducesAnInvertedRange() = iosTest {
        inIosTestDirectory { directory ->
            val first = HttpClient(MockEngine { respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator)) })
            try {
                assertFailsWith<CancellationException> {
                    IosYouTubeDownloader(first).download(plan(), "$directory/output.mp4") {
                        if (it.bytesDownloaded == bytes.size.toLong()) throw CancellationException("after durable bytes, before EOF")
                    }
                }
            } finally { first.close() }
            assertFalse(savedPart(directory, plan()).complete)
            val http = HttpClient(MockEngine { request ->
                assertEquals("bytes=0-0", request.headers[HttpHeaders.Range])
                respond(bytes.copyOfRange(0, 1), HttpStatusCode.PartialContent, mediaHeaders(1, validator, "bytes 0-0/${bytes.size}"))
            })
            try { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
            finally { http.close() }
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun uncheckpointedCrashTailIsTruncatedBeforeResumeAndMalformedCheckpointRestarts() = iosTest {
        for (malformed in listOf(false, true)) inIosTestDirectory { directory ->
            interrupt(directory, plan())
            val privatePath = if (malformed) "${working(directory)}/checkpoint.json" else savedPath(directory)
            val file = IosMediaFile(privatePath, existing = true)
            try {
                if (malformed) { file.truncate(0); file.write(0, "{invalid".encodeToByteArray()) }
                else file.write(cut.toLong(), byteArrayOf(99, 98, 97))
                file.flush()
            } finally { file.close() }
            val http = HttpClient(MockEngine { request ->
                if (malformed) {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
                } else {
                    assertEquals("bytes=$cut-", request.headers[HttpHeaders.Range])
                    respond(bytes.copyOfRange(cut, bytes.size), HttpStatusCode.PartialContent,
                        mediaHeaders(bytes.size - cut, validator, "bytes $cut-${bytes.lastIndex}/${bytes.size}"))
                }
            })
            try { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
            finally { http.close() }
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun concurrentCallsToSameCanonicalDestinationAreExcludedBeforeSecondHttpRequest() = iosTest {
        inIosTestDirectory { directory ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val http = HttpClient(MockEngine {
                requests++; entered.complete(Unit); release.await()
                respond(bytes, HttpStatusCode.OK, mediaHeaders(bytes.size, validator))
            })
            try {
                coroutineScope {
                    val first = async { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
                    entered.await()
                    try {
                        val failure = assertFailsWith<IllegalStateException> {
                            IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {}
                        }
                        assertTrue(failure.message.orEmpty().contains("already using"))
                        assertEquals(1, requests)
                    } finally { release.complete(Unit) }
                    assertContentEquals(bytes, read(first.await().destinationPath))
                }
                assertEquals(listOf("output.mp4"), children(directory))
            } finally { release.complete(Unit); http.close() }
        }
    }

    @Test fun onlyStrongEntityTagsOrSufficientlyOldLastModifiedDatesCanValidateResume() {
        assertNotNull(iosResponseValidator(validator, null, null))
        listOf("W/$validator", "unquoted", "\"x y\"", "\"x\"\"y\"", "\"x\ny\"").forEach {
            assertNull(iosResponseValidator(it, null, null))
        }
        val modified = "Thu, 05 Nov 2026 10:00:00 GMT"
        assertNull(iosResponseValidator(null, modified, null))
        assertNull(iosResponseValidator(null, modified, "Thu, 05 Nov 2026 10:00:59 GMT"))
        assertNotNull(iosResponseValidator(null, modified, "Thu, 05 Nov 2026 10:01:00 GMT"))
    }

    @Test fun cancellationDuringNewOwnerInitializationCannotPoisonTheNextAttempt() = iosTest {
        inIosTestDirectory { directory ->
            coroutineScope {
                launch {
                    currentCoroutineContext().cancel()
                    val store = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(plan()))
                    store.close()
                }.join()
            }
            val retried = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(plan()))
            try { retried.load(plan(), TransferPlanning.parts(plan())); assertTrue(retried.parts.isEmpty()) }
            finally { retried.close() }
            assertTrue(children(directory).isEmpty())
        }
    }

    private fun plan(size: Long? = bytes.size.toLong(), url: String = "https://fixture.invalid/video") = PlannedDownload(
        primary = Representation("136", url, TrackRole.VIDEO, mimeType = "video/mp4", codecs = "avc1.42c00a",
            sizeBytes = size, width = 32, height = 32), inspectionUrl = "https://fixture.invalid/watch", mediaId = "AUTHORED001")

    private suspend fun interrupt(directory: String, plan: PlannedDownload, tag: String? = validator) {
        val http = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Range])
            respond(bytes.copyOfRange(0, cut), HttpStatusCode.OK, mediaHeaders(bytes.size, tag))
        }) { followRedirects = false }
        try {
            assertFailsWith<IllegalStateException> { IosYouTubeDownloader(http).download(plan, "$directory/output.mp4") {} }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/output.mp4"))
        } finally { http.close() }
    }

    private fun mediaHeaders(length: Int?, tag: String?, range: String? = null) = Headers.build {
        append(HttpHeaders.ContentType, "video/mp4")
        length?.let { append(HttpHeaders.ContentLength, it.toString()) }
        tag?.let { append(HttpHeaders.ETag, it) }
        range?.let { append(HttpHeaders.ContentRange, it) }
    }

    private fun children(directory: String) = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
        .orEmpty().map { it as String }.sorted()
    private fun working(directory: String) = "$directory/${children(directory).single { it.startsWith(".kmpyt-") }}"
    private fun savedPath(directory: String) = "${working(directory)}/part-0-0.mp4"
    private suspend fun checkpointText(directory: String) = read("${working(directory)}/checkpoint.json").decodeToString()
    private suspend fun read(path: String): ByteArray {
        val file = IosMediaFile(path, existing = true)
        return try { file.read(0, file.length().toInt()) } finally { file.close() }
    }
    private suspend fun savedPart(directory: String, plan: PlannedDownload): IosSavedPart {
        val store = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(plan))
        return try { store.load(plan, TransferPlanning.parts(plan)); store.parts.single() } finally { store.close() }
    }
}
