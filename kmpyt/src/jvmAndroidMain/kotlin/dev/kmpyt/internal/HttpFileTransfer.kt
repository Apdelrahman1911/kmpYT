package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.UrlPolicy
import dev.kmpyt.downloads.DownloadProtocol
import dev.kmpyt.extraction.YouTubeDownloadPolicy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

internal class StaleDownloadException : IOException("Download operation was superseded.")
internal class PermanentDownloadException(message: String) : IOException(message)
internal class RetryableDownloadException(message: String, val retryAfterSeconds: Long? = null) : IOException(message)
internal class ExpiredResourceException : IOException("Authorized media resource expired")

internal data class FileTransferRequest(
    val url: String,
    val transferPlan: PlannedDownload,
    val totalBytes: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Exact inspected length, kept separate from a previous response/checkpoint's total. */
    val expectedBytes: Long? = null,
)

internal data class TransferProgress(
    val bytes: Long,
    val total: Long?,
    val speedBytesPerSecond: Long,
    val etag: String?,
    val lastModified: String?,
)

internal data class TransferOutcome(val bytes: Long, val total: Long?, val etag: String?, val lastModified: String?)

/** Both complete files and explicit byte ranges must contain the requested unencoded media. */
internal fun requireMediaResponse(connection: HttpURLConnection) {
    val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (contentType != null && (contentType.startsWith("text/") || contentType.contains("json") ||
            contentType.contains("xml") || contentType.contains("mpegurl"))) {
        throw PermanentDownloadException("This URL returned a page or streaming manifest instead of a downloadable media file.")
    }
    if (!contentType.isNullOrBlank() && !contentType.startsWith("audio/") && !contentType.startsWith("video/") &&
        contentType !in setOf("application/ogg", "application/mp4", "application/octet-stream", "binary/octet-stream")) {
        throw PermanentDownloadException("This URL did not return a supported audio or video file.")
    }
    val encoding = connection.getHeaderField("Content-Encoding")
    if (!encoding.isNullOrBlank() && !encoding.trim().equals("identity", true)) {
        throw PermanentDownloadException("Server returned a compressed representation that cannot be resumed safely.")
    }
}

internal fun mediaContentLength(connection: HttpURLConnection): Long? {
    val header = connection.getHeaderField("Content-Length") ?: return null
    return header.trim().toLongOrNull()?.takeIf { it >= 0 }
        ?: throw PermanentDownloadException("The media server returned an invalid Content-Length.")
}

/** Progressive direct media transport. No HTML extraction, accounts, cookies or DRM processing. */
internal class HttpDownloadTransfer(
    private val allowLocalHttp: Boolean,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val refreshOnExpiry: Boolean = false,
    private val maximumBytes: Long? = null,
    private val sessionOrigin: String? = null,
    private val onConnection: (HttpURLConnection?) -> Unit = {},
) {
    suspend fun fetch(
        task: FileTransferRequest,
        partial: File,
        isCurrent: suspend () -> Boolean,
        onProgress: suspend (TransferProgress) -> Unit,
    ): TransferOutcome = withContext(Dispatchers.IO) {
        YouTubeDownloadPolicy.requirePlan(task.transferPlan)
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw StaleDownloadException()
        }
        require(task.totalBytes == null || task.totalBytes >= 0) { "Invalid stored media size" }
        require(task.expectedBytes == null || task.expectedBytes >= 0) { "Invalid inspected media size" }
        partial.parentFile?.mkdirs()
        var offset = partial.takeIf { it.exists() }?.length() ?: 0L
        require(maximumBytes == null || maximumBytes > 0) { "Invalid explicit transfer byte budget" }
        if(maximumBytes!=null && offset>maximumBytes) throw PermanentDownloadException("Cached media exceeds the research byte budget")
        var storedEtag = task.etag
        var storedModified = task.lastModified
        if (offset > 0 && DownloadProtocol.strongValidator(storedEtag, storedModified) == null) offset = 0
        var restarted = false
        suspend fun restart(reason: String) {
            if (restarted) throw PermanentDownloadException(reason)
            checkCurrent()
            RandomAccessFile(partial, "rw").use { it.setLength(0) }
            offset = 0
            storedEtag = null
            storedModified = null
            restarted = true
        }
        while (true) {
            checkCurrent()
            val connection = open(task.url, offset, storedEtag, storedModified, ::checkCurrent,
                task.transferPlan.inspectionUrl, task.transferPlan.mediaId)
            try {
                val status = connection.responseCode
                if (refreshOnExpiry && status in listOf(401, 403, 410)) throw ExpiredResourceException()
                if (DownloadProtocol.isRetryableStatus(status)) {
                    throw RetryableDownloadException("Server returned HTTP $status.", DownloadProtocol.retryAfterSeconds(connection.getHeaderField("Retry-After")))
                }
                val etag = connection.getHeaderField("ETag")
                val modified = connection.getHeaderField("Last-Modified")
                val length = mediaContentLength(connection)
                val plan = DownloadProtocol.plan(offset, status, connection.getHeaderField("Content-Range"), length, storedEtag, storedModified, etag, modified)
                when (plan) {
                    is DownloadProtocol.ResponsePlan.Reject -> throw PermanentDownloadException(plan.reason)
                    is DownloadProtocol.ResponsePlan.Restart -> {
                        restart(plan.reason)
                        continue
                    }
                    DownloadProtocol.ResponsePlan.AlreadyComplete -> {
                        if (task.expectedBytes != null && task.expectedBytes != offset) {
                            throw PermanentDownloadException("The completed range conflicts with the inspected media size. Inspect the source again.")
                        }
                        if (task.totalBytes != null && task.totalBytes != offset) {
                            restart("The server's completed range conflicts with the known media size.")
                            continue
                        }
                        checkCurrent()
                        return@withContext TransferOutcome(offset, offset, etag ?: storedEtag, modified ?: storedModified)
                    }
                    is DownloadProtocol.ResponsePlan.Transfer -> {
                        val knownTotal = task.expectedBytes ?: task.totalBytes
                        if (task.expectedBytes != null && plan.total != null && plan.total != task.expectedBytes) {
                            throw PermanentDownloadException("The response length conflicts with the inspected media size. Inspect the source again.")
                        }
                        if (plan.append && knownTotal != null && plan.total != null && plan.total != knownTotal) {
                            restart("The remote file size changed; restarting safely.")
                            continue
                        }
                        // Chunked full responses and ranges can omit the object size. Preserve
                        // the inspected/checkpoint total so EOF cannot certify a short prefix.
                        val total = plan.total ?: knownTotal
                        if (status == 206 && total == null) {
                            // EOF cannot prove that an unknown-size partial is the whole file.
                            // Retry once without Range and require an unambiguous response.
                            restart("The media server did not provide a verifiable complete file.")
                            continue
                        }
                        requireMediaResponse(connection)
                        checkCurrent()
                        var bytes = plan.offset
                        val effectiveEtag = etag ?: storedEtag.takeIf { plan.append }
                        val effectiveModified = modified ?: storedModified.takeIf { plan.append }
                        onProgress(TransferProgress(bytes, total, 0, effectiveEtag, effectiveModified))
                        val started = System.nanoTime()
                        var lastReport = started
                        var lastBytes = bytes
                        var speed = 0L
                        RandomAccessFile(partial, "rw").use { destination ->
                            if (!plan.append) destination.setLength(0)
                            total?.let {
                                if (it < bytes) throw PermanentDownloadException("Stored media exceeds the declared source size.")
                                if (maximumBytes != null && it > maximumBytes) throw PermanentDownloadException("Media exceeds the explicitly configured byte budget.")
                                MediaStorageSpace.requireAvailable(partial, it - bytes)
                            }
                            destination.seek(bytes)
                            val capacity = MediaStorageSpace.monitor(partial)
                            connection.inputStream.use { source ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCurrent()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    checkCurrent()
                                    if (count.toLong() > Long.MAX_VALUE - bytes) throw PermanentDownloadException("Media byte totals overflow supported file offsets.")
                                    if (maximumBytes != null && count.toLong() > maximumBytes - bytes) throw PermanentDownloadException("Media exceeds the explicitly configured byte budget.")
                                    if (total != null && count.toLong() > total - bytes) throw PermanentDownloadException("Server sent more bytes than the declared media size.")
                                    capacity.requireForWrite(count.toLong())
                                    try {
                                        destination.write(buffer, 0, count)
                                    } catch (error: IOException) {
                                        throw PermanentDownloadException("Could not write the media file. Check free storage and try again.")
                                    }
                                    bytes += count
                                    val now = System.nanoTime()
                                    if (lastBytes == plan.offset || now - lastReport >= 400_000_000L) {
                                        val elapsed = (now - lastReport).coerceAtLeast(1)
                                        speed = ((bytes - lastBytes).toDouble() * 1_000_000_000.0 / elapsed).toLong()
                                        onProgress(TransferProgress(bytes, total, speed, effectiveEtag, effectiveModified))
                                        lastBytes = bytes
                                        lastReport = now
                                    }
                                }
                            }
                            destination.fd.sync()
                        }
                        checkCurrent()
                        if (bytes == 0L) throw PermanentDownloadException("The server returned an empty media file.")
                        onProgress(TransferProgress(bytes, total ?: bytes, speed, effectiveEtag, effectiveModified))
                        if (total != null && bytes != total) {
                            throw RetryableDownloadException("Connection ended before the complete media file arrived.")
                        }
                        return@withContext TransferOutcome(bytes, total ?: bytes, effectiveEtag, effectiveModified)
                    }
                }
            } finally {
                connection.disconnect()
                onConnection(null)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Transfer loop finished unexpectedly")
    }

    private suspend fun open(
        input: String, offset: Long, etag: String?, lastModified: String?, checkCurrent: suspend () -> Unit,
        inspectionUrl: String? = null, mediaId: String? = null,
    ): HttpURLConnection {
        var url = input
        repeat(6) { redirect ->
            checkCurrent()
            UrlPolicy.validate(url, allowLocalHttp)?.let { throw PermanentDownloadException(it) }
            // Re-evaluate each concrete destination before opening a connection.
            // A generic/local task cannot acquire approved-video identity by redirect.
            YouTubeDownloadPolicy.requireResource(url, inspectionUrl, mediaId)
            // Session isolation is independent of download policy. Anonymous requests may
            // follow a CDN redirect, but protected headers cannot leave their saved origin.
            sessionOrigin?.let { dev.kmpyt.extraction.ResearchAccess.requireSessionOrigin(url, it) }
            val connection = URL(url).openConnection() as? HttpURLConnection
                ?: throw PermanentDownloadException("Only HTTPS media URLs are supported.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "kmpYT/1.0")
            // A caller-defined header may contain a nonstandard secret. CDN redirects
            // remain possible anonymously, without forwarding the original origin's map.
            if (dev.kmpyt.extraction.ResearchAccess.origin(url) == dev.kmpyt.extraction.ResearchAccess.origin(input))
                requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                DownloadProtocol.strongValidator(etag, lastModified)?.let { connection.setRequestProperty("If-Range", it) }
            }
            val response = try {
                onConnection(connection)
                checkCurrent()
                connection.responseCode
            } catch (error: Throwable) {
                connection.disconnect()
                onConnection(null)
                throw error
            }
            if (response !in listOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            onConnection(null)
            if (location.isNullOrBlank() || redirect == 5) throw PermanentDownloadException("The media server returned too many or invalid redirects.")
            val destination = URL(URL(url), location)
            if (URL(url).protocol.equals("https", true) && !destination.protocol.equals("https", true))
                throw PermanentDownloadException("A media redirect cannot downgrade HTTPS.")
            url = destination.toExternalForm()
        }
        throw PermanentDownloadException("Could not resolve the media URL.")
    }
}
