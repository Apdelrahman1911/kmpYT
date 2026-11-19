@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.downloads.DownloadProtocol
import dev.kmpyt.internal.boundedProgressiveRangeEnd
import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

/** A response validator is checked before an existing file is opened for appending. */
internal class IosHttpPartTransfer(private val http: HttpClient, private val store: IosDownloadResumeStore,
    private val plan: PlannedDownload, private val allowLocalHttp: Boolean,
    private val sectionBytes: Long = IOS_SECTION_BYTES,
    private val observer: ((IosDownloadStageEvent) -> Unit)? = null) {
    suspend fun fetch(part: TransferPart, progress: suspend (PartState, Long) -> Unit): PartState {
        var previous = store.parts.singleOrNull { it.key == part.key }
        var url = part.resource.url
        val originalOrigin = ResearchAccess.origin(url)
        var redirects = 0
        var restarted = false
        val resourceDigest = iosDigest(part.resource.url)
        val scopeDigest = iosResourceScope(part, plan.mediaId)
        // Large googlevideo objects are paced when requested as one range; fetch consecutive
        // bounded sections that each resume from the committed, validated checkpoint.
        var sectioned = true
        while (true) {
            currentCoroutineContext().ensureActive()
            UrlPolicy.validate(url, allowLocalHttp)?.let { throw IllegalArgumentException(it) }
            YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
            val old = previous
            val resuming = old != null && old.bytes > 0 && old.validator != null
            val completeProbe = resuming && old!!.total == old.bytes
            val relativeStart = if (resuming && !completeProbe) old!!.bytes else 0L
            val requestStart = (part.resource.rangeStart ?: 0) + relativeStart
            val automaticEnd = if (part.resource.rangeStart == null && !completeProbe)
                boundedProgressiveRangeEnd(url, requestStart, part.expectedBytes)?.let { end ->
                    if (sectioned) minOf(end, requestStart + sectionBytes - 1) else end
                } else null
            val ranged = part.resource.rangeStart != null || resuming || automaticEnd != null
            val requestEnd = part.resource.rangeLength?.let { requireNotNull(part.resource.rangeStart) + it - 1 }
                ?: 0L.takeIf { completeProbe } ?: automaticEnd
            var next: String? = null
            var reset = false
            var sectionEnded = false
            var unsectioned = false
            var result: PartState? = null
            http.prepareGet(url) {
                header(HttpHeaders.AcceptEncoding, "identity")
                if (ResearchAccess.origin(url) == originalOrigin) plan.requestHeaders.forEach { (key, value) -> header(key, value) }
                if (ranged) header(HttpHeaders.Range, "bytes=$requestStart-${requestEnd ?: ""}")
            }.execute { response ->
                val status = response.status.value
                debugIosTransferResponse(status, ranged, requestStart, requestEnd, plan.primary)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    check(++redirects <= 5) { "Too many media redirects." }
                    val location = response.headers[HttpHeaders.Location] ?: error("The media redirect has no destination.")
                    next = NSURL(string = location, relativeToURL = NSURL(string = url)).absoluteURL?.absoluteString
                        ?: error("The media redirect has an invalid destination.")
                    return@execute
                }
                val validator = iosResponseValidator(response.headers[HttpHeaders.ETag],
                    response.headers[HttpHeaders.LastModified], response.headers[HttpHeaders.Date])
                if (status == 416 && resuming) {
                    // An unsatisfied range does not certify a checkpoint's complete coverage.
                    reset = true
                    return@execute
                }
                check(status in setOf(200, 206)) {
                    if (status in setOf(401, 403, 410)) "The media link is no longer available. Inspect the video again and retry."
                    else "The media server returned HTTP $status."
                }
                if (resuming && (status != 206 || validator == null || validator != old!!.validator)) {
                    // No response body bytes may be joined to a different or unverifiable entity.
                    reset = true
                    return@execute
                }
                // A server may ignore an optimization on the initial full object. Explicit
                // selected ranges and resumed entities still require their exact 206 response.
                val fullFallback = automaticEnd != null && !resuming && part.resource.rangeStart == null && status == 200
                check(fullFallback || status == if (ranged) 206 else 200) { "The media server ignored the selected byte range." }
                check(status != 200 || response.headers[HttpHeaders.ContentRange] == null) {
                    "A full media response must not contain a partial content range."
                }
                check(response.headers[HttpHeaders.ContentEncoding].let { it == null || it.equals("identity", true) }) {
                    "Compressed media responses cannot be saved as the inspected byte stream."
                }
                val type = (response.headers[HttpHeaders.ContentType] ?: part.mimeType).substringBefore(';').trim().lowercase()
                check(!type.contains("mpegurl") && !type.contains("xml") && (type.startsWith("video/") || type.startsWith("audio/") ||
                    type in setOf("application/mp4", "application/ogg", "application/octet-stream"))) {
                    "The media server returned a document or unsupported content type instead of media."
                }
                val length = response.headers[HttpHeaders.ContentLength]?.let {
                    it.toLongOrNull()?.takeIf { value -> value >= 0 } ?: error("The media server returned an invalid content length.")
                }
                var objectTotal = old?.objectTotalBytes
                val total = if (ranged && !fullFallback) {
                    val range = DownloadProtocol.parseContentRange(response.headers[HttpHeaders.ContentRange])
                        ?: error("The media server returned an invalid byte range.")
                    check(range.first == requestStart && (requestEnd == null || range.last == requestEnd) &&
                        (length == null || length == range.last - range.first + 1)) { "The media server returned a different byte range." }
                    check(objectTotal == null || range.total == null || objectTotal == range.total) {
                        "The ranged source length changed. Inspect the video again."
                    }
                    objectTotal = range.total ?: objectTotal
                    val expected = part.resource.rangeLength ?: range.total ?: old?.total
                    check(expected != null && expected > 0) { "The resumed response does not establish the complete media length." }
                    if (completeProbe && part.resource.rangeStart == null) check(range.total == old!!.bytes) {
                        "The validation response does not establish the complete saved media length."
                    }
                    if (part.resource.rangeStart == null && !completeProbe) check(range.last == (automaticEnd ?: expected - 1)) {
                        "The resumed response does not cover the complete remaining track."
                    }
                    check(part.expectedBytes == null || part.expectedBytes == expected) { "The media length changed. Inspect the video again." }
                    check(old?.total == null || old.total == expected) { "The resumed media length differs from its checkpoint." }
                    expected
                } else {
                    check(part.expectedBytes == null || length == null || part.expectedBytes == length) {
                        "The media length changed. Inspect the video again."
                    }
                    (length ?: part.expectedBytes).also { objectTotal = it }
                }
                if (completeProbe) {
                    check(total == old!!.bytes && store.fileLength(store.partPath(part.key)) == old.bytes) {
                        "The completed private track no longer matches its checkpoint."
                    }
                    val complete = old.copy(resourceDigest = resourceDigest, total = total, complete = true, objectTotalBytes = objectTotal)
                    store.savePart(complete)
                    result = complete.state()
                    return@execute
                }
                val retained = if (resuming) old!!.bytes else 0L
                total?.let { requireIosStorage(store.directory, it - retained) }
                val path = store.partPath(part.key)
                val exists = store.fileLength(path) != null
                val file = IosMediaFile(path, expected = total, existing = exists)
                var bytes = retained
                var complete = false
                var oversized = false
                var failure: Throwable? = null
                var lastTime = NSDate().timeIntervalSince1970
                var lastBytes = bytes
                fun saved(done: Boolean = false) = IosSavedPart(part.key, resourceDigest, scopeDigest, bytes,
                    if (done) bytes else total, done, validator, objectTotal)
                suspend fun commit() { file.flush(); store.savePart(saved()) }
                try {
                    if (resuming) check(file.length() == retained) { "The private media file changed while resuming." }
                    else { file.truncate(0); file.flush() }
                    commit()
                    progress(saved().state(), 0)
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (bytes > Long.MAX_VALUE - count || total != null && bytes + count > total) {
                                oversized = true
                                error("The source sent more media than its reported length.")
                            }
                            file.write(bytes, buffer, count)
                            bytes += count
                            val now = NSDate().timeIntervalSince1970
                            if (lastBytes == retained || now - lastTime >= 0.4) {
                                commit()
                                val speed = if (now > lastTime) ((bytes - lastBytes) / (now - lastTime)).toLong().coerceAtLeast(0) else 0
                                progress(saved().state(), speed)
                                lastTime = now; lastBytes = bytes
                            }
                        }
                    } finally { channel.cancel() }
                    if (automaticEnd != null && !fullFallback && total != null && automaticEnd < total - 1 && bytes == automaticEnd + 1) {
                        // One section is complete; the finally block commits it before the next.
                        if (validator == null) unsectioned = true else sectionEnded = true
                        return@execute
                    }
                    check(bytes > 0 && (total == null || bytes == total)) { "The media response ended before the complete track was received." }
                    val final = traceIosDownload(observer, IosDownloadStage.CHECKPOINT_FINISH) {
                        file.flush()
                        saved(done = true).also { store.savePart(it) }
                    }
                    complete = true
                    result = final.state()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        if (!complete) withContext(NonCancellable) {
                            if (!oversized && bytes > 0 && validator != null) commit()
                            else store.resetPart(part.key)
                        }
                    } catch (cleanup: Throwable) {
                        // The previous atomic checkpoint remains valid if committing newer bytes fails.
                        if (failure == null) throw cleanup
                    } finally { file.close() }
                }
            }
            result?.let { return it }
            if (sectionEnded) {
                previous = store.parts.singleOrNull { it.key == part.key }
                url = part.resource.url
                redirects = 0
            } else if (unsectioned) {
                // Without a strong validator, sections cannot be joined: restart as one request.
                sectioned = false
                store.resetPart(part.key)
                previous = null
                url = part.resource.url
                redirects = 0
            } else if (reset) {
                check(!restarted) { "The media changed repeatedly. Inspect the video again before retrying." }
                store.resetPart(part.key)
                previous = null
                restarted = true
                url = part.resource.url
                redirects = 0
            } else url = requireNotNull(next)
        }
    }

    private fun IosSavedPart.state() = PartState(key, bytes, total, complete = complete)
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun debugIosTransferResponse(status: Int, ranged: Boolean, start: Long, end: Long?, selected: dev.kmpyt.extraction.Representation) {
    if (!kotlin.native.Platform.isDebugBinary) return
    val language = when (selected.audioLanguage?.substringBefore('-')?.lowercase()) {
        "ar" -> "ARABIC"
        "en" -> "ENGLISH"
        else -> "OTHER"
    }
    println("VEYRA_SOURCE_TIMING stage=TRANSFER status=$status ranged=$ranged start=$start end=$end language=$language provenance=${selected.audioProvenance}")
}

/** Below the measured ~10–20 MiB size above which googlevideo paces one range. */
internal const val IOS_SECTION_BYTES = 8L * 1024 * 1024
