package dev.kmpyt.internal

import dev.kmpyt.UrlPolicy
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
        val start = requireNotNull(part.resource.rangeStart); val size = requireNotNull(part.resource.rangeLength)
        require(size > 0 && start >= 0 && start <= Long.MAX_VALUE - size) { "Ranged segment overflows supported file offsets" }
        var offset = file.takeIf { it.exists() }?.length() ?: 0
        if (offset > size || offset > 0 && old.etag == null) { RandomAccessFile(file, "rw").use { it.setLength(0) }; offset = 0 }
        if (offset == size && old.complete) return@withContext old
        MediaStorageSpace.requireAvailable(file, size - offset)
        UrlPolicy.validate(part.resource.url, allowLocalHttp)?.let { error(it) }
        YouTubeDownloadPolicy.requireResource(part.resource.url, plan.inspectionUrl, plan.mediaId)
        val headers = headersFor(plan, part.resource.url)
        val c = URL(part.resource.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Range", "bytes=${start + offset}-${start + size - 1}")
        old.etag?.let { c.setRequestProperty("If-Range", it) }
        headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        connection(c)
        try {
            if (c.responseCode in listOf(401,403,410)) throw ExpiredResourceException()
            require(c.responseCode == 206) { "Byte-range segment requires an exact HTTP 206 response" }
            val actual = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(c.getHeaderField("Content-Range").orEmpty()) ?: error("Invalid segment Content-Range")
            require(actual.groupValues[1].toLong() == start + offset && actual.groupValues[2].toLong() == start + size - 1) { "Segment range did not match the request" }
            val etag = c.getHeaderField("ETag")
            if (offset > 0) require(etag == old.etag && !etag.orEmpty().startsWith("W/")) { "Segment representation changed during resume" }
            val started = System.nanoTime()
            var lastReport = started
            var lastBytes = offset
            var reportedBytes = offset
            var sentFirstBytes = false
            val capacity = MediaStorageSpace.monitor(file)
            progress(PartState(part.key, offset, size, etag), 0)
            c.inputStream.use { input -> RandomAccessFile(file,"rw").use { out ->
                out.seek(offset); val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive(); if (!current()) throw StaleDownloadException()
                    val n = input.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    currentCoroutineContext().ensureActive(); if (!current()) throw StaleDownloadException()
                    require(n.toLong() <= size - offset) { "Segment exceeded its declared byte range" }
                    capacity.requireForWrite(n.toLong())
                    out.write(buffer,0,n); offset += n
                    val now = System.nanoTime()
                    if (!sentFirstBytes || now - lastReport >= 400_000_000L) {
                        progress(PartState(part.key,offset,size,etag),
                            ((offset-lastBytes).toDouble()*1_000_000_000.0 / (now-lastReport).coerceAtLeast(1)).toLong())
                        sentFirstBytes = true; reportedBytes = offset; lastBytes = offset; lastReport = now
                    }
                }
                out.fd.sync()
            } }
            if (offset != size) throw java.io.IOException("Segment connection interrupted")
            if (reportedBytes != offset) progress(PartState(part.key, offset, size, etag),
                ((offset-lastBytes).toDouble()*1_000_000_000.0 / (System.nanoTime()-lastReport).coerceAtLeast(1)).toLong())
            PartState(part.key,offset,size,etag,complete=true)
        } finally { c.disconnect(); connection(null) }
    }
    /** Protected credentials are obtained only for the actual resource origin; plain plans are unrestricted by origin. */
    private fun headersFor(plan: PlannedDownload, url: String): Map<String, String> {
        YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
        require(plan.credentialRef == null) { "This adapter does not accept account sessions." }
        return plan.requestHeaders
    }
}
