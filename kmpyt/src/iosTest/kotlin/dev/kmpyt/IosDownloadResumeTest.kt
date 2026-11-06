@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.Foundation.*
import kotlin.test.*

/** Authored media, actual POSIX files and new downloader instances; no network or account access. */
class IosDownloadResumeTest {
    private val bytes get() = IosAuthoredMedia.video
    private val cut get() = bytes.size / 3
    private val validator = "\"private-validator-v1\""

    @Test fun interruptedDirectDownloadResumesAcrossInstancesAndSignedUrlRefreshWithoutLeakingRequestData() = iosTest {
        inIosTestDirectory { directory ->
            val first = plan(url = "https://rr1.googlevideo.com/video?itag=136&sig=SECRET_OLD&expire=4000000000")
                .copy(inspectionUrl = "https://www.youtube.com/watch?v=AUTHORED001")
            interrupt(directory, first)
            val text = checkpointText(directory)
            listOf("https", "fixture.invalid", "SECRET_OLD", "private-validator", "User-Agent").forEach {
                assertFalse(it in text, "Checkpoint must contain fingerprints, not request/validator values.")
            }
            val refreshed = first.copy(primary = first.primary.copy(url = "https://rr1.googlevideo.com/video?itag=136&sig=SECRET_NEW&expire=4000003600"))
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
                assertIosResumeResidue(directory, listOf("output.mp4"))
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
                assertIosResumeResidue(directory, listOf("output.mp4"))
            } finally { resumed.close() }
        }
    }

    @Test fun weakOrMissingInitialValidatorsDoNotRetainUnverifiablePartialBytes() = iosTest {
        for (tag in listOf(null, "W/$validator", "\"malformed\" \"tag\"")) {
            inIosTestDirectory { directory ->
                interrupt(directory, plan(), tag = tag)
                assertIosResumeResidue(directory)
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
                assertIosResumeResidue(directory)
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
                assertIosResumeResidue(directory, listOf("output.mp4"))
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
            assertIosResumeResidue(directory)
        }
    }

    @Test fun sameLengthCorruptedPrivatePrefixCannotBeReusedWithAMatchingServerValidator() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val file = IosMediaFile(savedPath(directory), existing = true)
            try {
                val byte = file.read(0, 1).single()
                file.write(0, byteArrayOf((byte.toInt() xor 1).toByte())); file.flush()
                assertEquals(cut.toLong(), file.length())
            } finally { file.close() }
            val http = HttpClient(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Range], "A matching ETag does not authenticate changed local bytes.")
                respond(bytes, headers = mediaHeaders(bytes.size, validator))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it } }
            finally { http.close() }
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(bytes, read("$directory/output.mp4"))
            assertIosResumeResidue(directory, listOf("output.mp4"))
        }
    }

    @Test fun arbitrarySigningParametersRawQueryEncodingAndDifferentCdnHostsNeverShareAReceipt() = iosTest {
        val cases = listOf(
            "https://fixture.invalid/video?sig=old" to "https://fixture.invalid/video?sig=new",
            "https://fixture.invalid/video?a=1&b=2" to "https://fixture.invalid/video?b=2&a=1",
            "https://fixture.invalid/video?q=+" to "https://fixture.invalid/video?q=%20",
            "https://fixture.invalid/video?a=1&&b=2" to "https://fixture.invalid/video?a=1&b=2",
            "https://rr1.googlevideo.com/video?itag=136&sig=old" to "https://rr2.googlevideo.com/video?itag=136&sig=new",
        )
        for ((original, changed) in cases) inIosTestDirectory { directory ->
            val first = plan(url = original).let {
                if ("googlevideo.com" in original) it.copy(inspectionUrl = "https://www.youtube.com/watch?v=AUTHORED001") else it
            }
            interrupt(directory, first)
            val refreshed = first.copy(primary = first.primary.copy(url = changed))
            val http = HttpClient(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Range])
                respond(bytes, headers = mediaHeaders(bytes.size, validator))
            })
            try { IosYouTubeDownloader(http).download(refreshed, "$directory/output.mp4") {} }
            finally { http.close() }
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun aChangedFinalRedirectScopeWithTheSameEtagRestartsBeforeAnyResponseBytesAppend() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan(url = "https://fixture.invalid/start")
            val first = HttpClient(MockEngine { request ->
                when (request.url.encodedPath) {
                    "/start" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/first"))
                    "/first" -> respond(bytes.copyOf(cut), headers = mediaHeaders(bytes.size, validator))
                    else -> error("Unplanned fixture request")
                }
            }) { followRedirects = true }
            try {
                assertFailsWith<IllegalStateException> { IosYouTubeDownloader(first).download(selected, "$directory/output.mp4") {} }
            } finally { first.close() }
            val replacement = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
            val requests = mutableListOf<Pair<String, String?>>()
            val http = HttpClient(MockEngine { request ->
                val range = request.headers[HttpHeaders.Range]
                requests += request.url.encodedPath to range
                when (request.url.encodedPath) {
                    "/start" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/second"))
                    "/second" -> if (range != null) respond(replacement.copyOfRange(cut, replacement.size), HttpStatusCode.PartialContent,
                        mediaHeaders(replacement.size - cut, validator, "bytes $cut-${replacement.lastIndex}/${replacement.size}"))
                    else respond(replacement, headers = mediaHeaders(replacement.size, validator))
                    else -> error("Unplanned fixture request")
                }
            }) { followRedirects = true }
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {} }
            finally { http.close() }
            assertEquals(listOf("/start" to "bytes=$cut-", "/second" to "bytes=$cut-", "/start" to null, "/second" to null), requests)
            assertContentEquals(replacement, read("$directory/output.mp4"), "A new final response scope cannot inherit the old prefix.")
        }
    }

    @Test fun callerDefaultHeadersAndAutomaticRedirectsCannotBypassExplicitTransportPolicy() = iosTest {
        inIosTestDirectory { directory ->
            var requests = 0
            val http = HttpClient(MockEngine { request ->
                requests++
                assertEquals("fixture.invalid", request.url.host)
                assertNull(request.headers["X-Caller-Session"])
                respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1/private"))
            }) {
                followRedirects = true
                defaultRequest { header("X-Caller-Session", "authored-private-session") }
            }
            try {
                assertFailsWith<IllegalArgumentException> { IosYouTubeDownloader(http).download(plan(), "$directory/output.mp4") {} }
                assertEquals(1, requests, "The forbidden destination must fail before a second engine request.")
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$directory/output.mp4"))
                assertIosResumeResidue(directory)
            } finally { http.close() }
        }
    }

    @Test fun explicitSourceHeadersStayOnTheirOriginalOriginAcrossRedirects() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan(url = "https://fixture.invalid/start").copy(requestHeaders = mapOf("X-Source-Header" to "authored-source"))
            val requests = mutableListOf<String>()
            val http = HttpClient(MockEngine { request ->
                requests += request.url.host + request.url.encodedPath
                assertNull(request.headers["X-Caller-Session"])
                assertEquals(if (request.url.host == "fixture.invalid") "authored-source" else null, request.headers["X-Source-Header"])
                when (request.url.encodedPath) {
                    "/start" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/hop"))
                    "/hop" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.invalid/final"))
                    "/final" -> respond(bytes, headers = mediaHeaders(bytes.size, validator))
                    else -> error("Unplanned fixture request")
                }
            }) {
                followRedirects = true
                defaultRequest { header("X-Caller-Session", "authored-private-session") }
            }
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") {} }
            finally { http.close() }
            assertEquals(listOf("fixture.invalid/start", "fixture.invalid/hop", "other.invalid/final"), requests)
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun absentOrLegacySchemaIsResetRatherThanReceivingTheNewDefaultVersion() = iosTest {
        for (absent in listOf(false, true)) inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val root = Json.parseToJsonElement(checkpointText(directory)).jsonObject
            assertEquals(2, root.getValue("schema").jsonPrimitive.int)
            val legacy = if (absent) JsonObject(root - "schema") else JsonObject(root + ("schema" to JsonPrimitive(1)))
            val file = IosMediaFile("${working(directory)}/checkpoint.json", existing = true)
            try { file.truncate(0); file.write(0, legacy.toString().encodeToByteArray()); file.flush() }
            finally { file.close() }
            val http = HttpClient(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Range])
                respond(bytes, headers = mediaHeaders(bytes.size, validator))
            })
            val updates = mutableListOf<YouTubeDownloadProgress>()
            try { IosYouTubeDownloader(http).download(selected, "$directory/output.mp4") { updates += it } }
            finally { http.close() }
            assertEquals(0L, updates.first().bytesDownloaded)
            assertContentEquals(bytes, read("$directory/output.mp4"))
        }
    }

    @Test fun aCancelledLoadKeepsThePriorDurableReceiptInsteadOfDiscardingItsUninitializedState() = iosTest {
        inIosTestDirectory { directory ->
            val selected = plan()
            interrupt(directory, selected)
            val checkpoint = checkpointText(directory)
            val prefix = read(savedPath(directory))
            val store = IosDownloadResumeStore.open("$directory/output.mp4", iosSelectionDigest(selected))
            try {
                coroutineScope {
                    launch {
                        currentCoroutineContext().cancel()
                        assertFailsWith<CancellationException> { store.load(selected, TransferPlanning.parts(selected)) }
                    }.join()
                }
            } finally { store.close() }
            assertEquals(checkpoint, checkpointText(directory))
            assertContentEquals(prefix, read(savedPath(directory)))
            assertEquals(cut.toLong(), savedPart(directory, selected).bytes)
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
