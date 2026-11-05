package dev.kmpyt.internal

import dev.kmpyt.UrlPolicy
import dev.kmpyt.downloads.DownloadProtocol
import dev.kmpyt.extraction.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

internal class RangedFileTransfer(private val allowLocalHttp: Boolean,
    private val connection: (HttpURLConnection?) -> Unit) {
    suspend fun fetch(part: TransferPart, old: PartState, file: File, plan: PlannedDownload,
        current: suspend () -> Boolean, progress: suspend (PartState, Long) -> Unit): PartState = withContext(Dispatchers.IO) {
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw StaleDownloadException()
        }
        checkCurrent()
        val start = requireNotNull(part.resource.rangeStart); val size = requireNotNull(part.resource.rangeLength)
        require(size > 0 && start >= 0 && start <= Long.MAX_VALUE - size) { "Ranged segment overflows supported file offsets" }
        var offset = file.takeIf { it.exists() }?.length() ?: 0
        val validator = DownloadProtocol.strongValidator(old.etag, null)
        if (offset == size && old.complete) return@withContext old
        // Progress can persist the last bytes before EOF/fsync/completion is confirmed.
        // A full uncommitted part cannot produce an empty Range or certify its own EOF.
        if (offset >= size || offset > 0 && validator == null) { RandomAccessFile(file, "rw").use { it.setLength(0) }; offset = 0 }
        MediaStorageSpace.requireAvailable(file, size - offset)
        UrlPolicy.validate(part.resource.url, allowLocalHttp)?.let { error(it) }
        YouTubeDownloadPolicy.requireResource(part.resource.url, plan.inspectionUrl, plan.mediaId)
        val headers = headersFor(plan, part.resource.url)
        val c = URL(part.resource.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Range", "bytes=${start + offset}-${start + size - 1}")
        if (offset > 0) validator?.let { c.setRequestProperty("If-Range", it) }
        headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        try {
            connection(c)
            checkCurrent()
            val status = c.responseCode
            if (status in listOf(401,403,410)) throw ExpiredResourceException()
            if (DownloadProtocol.isRetryableStatus(status)) {
                throw RetryableDownloadException("Server returned HTTP $status.", DownloadProtocol.retryAfterSeconds(c.getHeaderField("Retry-After")))
            }
            if (status != 206) throw PermanentDownloadException("Byte-range segment requires an exact HTTP 206 response")
            val actual = DownloadProtocol.parseContentRange(c.getHeaderField("Content-Range"))
                ?: throw PermanentDownloadException("Invalid segment Content-Range")
            if (actual.first != start + offset || actual.last != start + size - 1) {
                throw PermanentDownloadException("Segment range did not match the request")
            }
            if (offset > 0 && old.objectTotalBytes != null && actual.total != null && old.objectTotalBytes != actual.total) {
                throw PermanentDownloadException("The HTTP object size changed during segment resume")
            }
            val objectTotal = actual.total ?: old.objectTotalBytes.takeIf { offset > 0 }
            if (objectTotal != null && objectTotal <= actual.last) {
                throw PermanentDownloadException("The segment exceeds its saved HTTP object size")
            }
            val contentLength = mediaContentLength(c)
            if (contentLength != null && contentLength != size - offset) {
                throw PermanentDownloadException("The segment response length conflicts with its byte range")
            }
            requireMediaResponse(c)
            val etag = c.getHeaderField("ETag")
            if (offset > 0 && (etag?.trim() != validator || DownloadProtocol.strongValidator(etag, null) == null)) {
                throw PermanentDownloadException("Segment representation changed during resume")
            }
            val started = System.nanoTime()
            var lastReport = started
            var lastBytes = offset
            var reportedBytes = offset
            var sentFirstBytes = false
            val capacity = MediaStorageSpace.monitor(file)
            progress(PartState(part.key, offset, size, etag, objectTotalBytes = objectTotal), 0)
            c.inputStream.use { input -> RandomAccessFile(file,"rw").use { out ->
                out.seek(offset); val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCurrent()
                    val n = input.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    checkCurrent()
                    if (n.toLong() > size - offset) throw PermanentDownloadException("Segment exceeded its declared byte range")
                    capacity.requireForWrite(n.toLong())
                    out.write(buffer,0,n); offset += n
                    val now = System.nanoTime()
                    if (!sentFirstBytes || now - lastReport >= 400_000_000L) {
                        progress(PartState(part.key,offset,size,etag,objectTotalBytes = objectTotal),
                            ((offset-lastBytes).toDouble()*1_000_000_000.0 / (now-lastReport).coerceAtLeast(1)).toLong())
                        sentFirstBytes = true; reportedBytes = offset; lastBytes = offset; lastReport = now
                    }
                }
                out.fd.sync()
            } }
            checkCurrent()
            if (offset != size) throw java.io.IOException("Segment connection interrupted")
            if (reportedBytes != offset) progress(PartState(part.key, offset, size, etag, objectTotalBytes = objectTotal),
                ((offset-lastBytes).toDouble()*1_000_000_000.0 / (System.nanoTime()-lastReport).coerceAtLeast(1)).toLong())
            PartState(part.key,offset,size,etag,complete=true,objectTotalBytes = objectTotal)
        } finally { c.disconnect(); connection(null) }
    }
    /** Protected credentials are obtained only for the actual resource origin; plain plans are unrestricted by origin. */
    private fun headersFor(plan: PlannedDownload, url: String): Map<String, String> {
        YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
        require(plan.credentialRef == null) { "This adapter does not accept account sessions." }
        return plan.requestHeaders
    }
}
