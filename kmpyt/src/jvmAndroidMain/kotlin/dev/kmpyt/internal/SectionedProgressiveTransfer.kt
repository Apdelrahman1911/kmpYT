package dev.kmpyt.internal

import dev.kmpyt.downloads.DownloadProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * How a large progressive object is split into bounded HTTP ranges.
 *
 * Googlevideo serves bounded ranges of up to about 10 MiB at network speed, but paces a single
 * larger range (measured: 20 MiB and full-object ranges) to roughly the media bitrate. Ordinary
 * `Range` requests of at most [sectionBytes], fetched by up to [connections] connections, keep
 * every request in the unpaced regime. This changes only the request shape: the same authorized
 * URL, headers, validators and declared availability apply to every section.
 */
internal data class TransferSectioning(
    val connections: Int = 4,
    val sectionBytes: Long = 8L * 1024 * 1024,
    /** Test-only: also section authored loopback fixtures, which are otherwise unbounded. */
    val loopbackFixtures: Boolean = false,
) {
    init {
        require(connections in 1..8) { "Section connections must be between 1 and 8." }
        require(sectionBytes >= MIN_SECTION_BYTES) { "Sections must be at least 64 KiB." }
    }

    /** The exact final byte of the remaining object, or null when the URL must stay unbounded. */
    fun objectEnd(url: String, offset: Long, expectedBytes: Long?, allowLocalHttp: Boolean): Long? =
        boundedProgressiveRangeEnd(url, offset, expectedBytes) ?: run {
            if (!loopbackFixtures || !allowLocalHttp || expectedBytes == null || offset < 0 || expectedBytes <= offset) return null
            val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull()
            if (host in setOf("127.0.0.1", "localhost", "::1", "[::1]")) expectedBytes - 1 else null
        }

    /** The last byte of the first section starting at [offset]. */
    fun sectionEnd(offset: Long, objectEnd: Long): Long =
        if (objectEnd - offset < sectionBytes) objectEnd else offset + sectionBytes - 1

    companion object {
        const val MIN_SECTION_BYTES = 64L * 1024
        val Default = TransferSectioning()
        /** One unbounded/full-extent request, the pre-2026-11-16 behavior. */
        val Disabled = TransferSectioning(connections = 1, sectionBytes = Long.MAX_VALUE / 2)
    }
}

/** A section request was answered without an exact partial response: fall back to one connection. */
internal class SectionRangeUnsupportedException(val committedBytes: Long, val etag: String?, val lastModified: String?) :
    IOException("The media server did not honor a bounded section range.")

internal data class OpenedRange(val connection: HttpURLConnection, val requestedEnd: Long?)

/**
 * Downloads `[head.first .. total-1]` as consecutive sections. The first section is already open.
 *
 * Integrity model, identical to the single-connection path:
 * - Only the contiguous prefix from the file start is ever reported as committed bytes, and only
 *   after the file has been synced. Its SHA-256 receipt covers exactly those bytes.
 * - Sections ahead of the prefix may be written early, but are discarded (the file is truncated
 *   to the last reported prefix) whenever this transfer ends without completing.
 * - Every section must be an exact HTTP 206 for its requested bytes, with the same object size and
 *   the same strong ETag as the first response, and must contain media.
 */
internal class SectionedProgressiveTransfer(
    private val sectioning: TransferSectioning,
    /** Opens exactly `bytes=start-end` on the head's final URL, with `If-Range` when [validator] exists. */
    private val openSection: suspend (start: Long, end: Long, validator: String?) -> HttpURLConnection,
) {
    private class Section(val start: Long, val end: Long) {
        val written = AtomicLong(0)
        val size get() = end - start + 1
        val complete get() = written.get() == size
    }

    suspend fun run(
        head: HttpURLConnection,
        headStart: Long,
        headEnd: Long,
        total: Long,
        etag: String?,
        lastModified: String?,
        responseScope: String?,
        partial: File,
        prefix: MessageDigest?,
        checkCurrent: suspend () -> Unit,
        onProgress: suspend (TransferProgress) -> Unit,
    ): TransferOutcome = withContext(Dispatchers.IO) {
        require(headStart in 0..headEnd && headEnd < total - 1)
        val validator = DownloadProtocol.strongValidator(etag, null)
        val sections = buildList {
            add(Section(headStart, headEnd))
            var next = headEnd + 1
            while (next < total) {
                val end = sectioning.sectionEnd(next, total - 1)
                add(Section(next, end)); next = end + 1
            }
        }
        var committed = headStart
        var reported = headStart
        val received = AtomicLong(0)
        val signal = Channel<Unit>(Channel.CONFLATED)
        RandomAccessFile(partial, "rw").use { file ->
            val channel = file.channel
            try {
                coroutineScope {
                    val nextIndex = AtomicInteger(1)
                    suspend fun worker(first: Section?) {
                        var section = first
                        while (true) {
                            val current = section ?: sections.getOrNull(nextIndex.getAndIncrement()) ?: return
                            fetchSection(current, if (current === first) head else null, total, validator, channel,
                                received, partial, signal, checkCurrent)
                            section = null
                        }
                    }
                    val workers = (0 until minOf(sectioning.connections, sections.size)).map { index ->
                        launch { worker(if (index == 0) sections[0] else null) }
                    }
                    var lastReport = System.nanoTime()
                    var lastReceived = 0L
                    val readBack = ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        withTimeoutOrNull(REPORT_INTERVAL_MS) { signal.receive() }
                        checkCurrent()
                        // Observe worker completion before reading the frontier. A worker
                        // can finish writing while the prefix is being scanned.
                        val workersFinished = workers.all { it.isCompleted }
                        // Advance the contiguous prefix; hash bytes in file order.
                        for (section in sections) {
                            if (section.end < committed) continue
                            val written = section.written.get()
                            val frontier = section.start + written
                            if (frontier > committed) {
                                if (prefix != null) digestRange(channel, committed, frontier, prefix, readBack)
                                committed = frontier
                            }
                            if (written < section.size) break
                        }
                        val now = System.nanoTime()
                        val done = committed == total
                        // Completion is reported once, below, with the final receipt.
                        if (!done && committed > reported && now - lastReport >= REPORT_INTERVAL_MS * 1_000_000L) {
                            channel.force(false)
                            val bytesNow = received.get()
                            val speed = ((bytesNow - lastReceived).toDouble() * 1_000_000_000.0 /
                                (now - lastReport).coerceAtLeast(1)).toLong()
                            onProgress(TransferProgress(committed, total, speed, etag, lastModified,
                                prefix?.resumeSnapshot(), responseScope))
                            reported = committed
                            lastReport = now
                            lastReceived = bytesNow
                        }
                        if (done) break
                        if (workersFinished) {
                            // Every worker returned yet the prefix is incomplete: defensive.
                            throw RetryableDownloadException("A media section ended before its declared range arrived.")
                        }
                    }
                }
                channel.force(false)
                checkCurrent()
                val committedDigest = prefix?.digest()?.resumeHex()
                onProgress(TransferProgress(total, total, 0, etag, lastModified, committedDigest, responseScope))
                TransferOutcome(total, total, etag, lastModified, committedDigest, responseScope)
            } catch (failure: Throwable) {
                // Never leave unreported bytes, or sections ahead of the prefix, in the private file.
                withContext(NonCancellable) {
                    runCatching { file.setLength(reported); channel.force(true) }
                }
                if (failure is SectionRangeUnsupportedException) {
                    throw SectionRangeUnsupportedException(reported, etag, lastModified)
                }
                throw failure
            }
        }
    }

    private suspend fun fetchSection(
        section: Section,
        opened: HttpURLConnection?,
        total: Long,
        validator: String?,
        channel: FileChannel,
        received: AtomicLong,
        partial: File,
        signal: Channel<Unit>,
        checkCurrent: suspend () -> Unit,
    ) {
        var connection = opened
        var attempt = 0
        val capacity = MediaStorageSpace.monitor(partial)
        while (!section.complete) {
            checkCurrent()
            val start = section.start + section.written.get()
            try {
                val c = connection ?: openSection(start, section.end, validator)
                    .also { verify(it, start, section.end, total, validator) }
                connection = null
                readInto(c, section, start, channel, received, capacity, signal, checkCurrent)
            } catch (failure: IOException) {
                if (failure is PermanentDownloadException || failure is ExpiredResourceException ||
                    failure is InsufficientMediaStorageException || failure is SectionRangeUnsupportedException ||
                    failure is StaleDownloadException || attempt >= SECTION_RETRIES) throw failure
                checkCurrent()
                delay((failure as? RetryableDownloadException)?.retryAfterSeconds?.times(1000) ?: (500L shl attempt))
                attempt++
            }
        }
    }

    private fun verify(c: HttpURLConnection, start: Long, end: Long, total: Long, validator: String?) {
        val status = c.responseCode
        try {
            if (status in listOf(401, 403, 410)) throw ExpiredResourceException()
            if (DownloadProtocol.isRetryableStatus(status)) {
                throw RetryableDownloadException("Server returned HTTP $status.",
                    DownloadProtocol.retryAfterSeconds(c.getHeaderField("Retry-After")))
            }
            if (status == 200) throw SectionRangeUnsupportedException(0, null, null)
            if (status != 206) throw PermanentDownloadException("Server returned HTTP $status for a media section.")
            val range = DownloadProtocol.parseContentRange(c.getHeaderField("Content-Range"))
                ?: throw PermanentDownloadException("Server returned an invalid section Content-Range.")
            if (range.first != start || range.last != end || range.total != total) {
                throw PermanentDownloadException("A media section did not match its requested byte range.")
            }
            mediaContentLength(c)?.let {
                if (it != end - start + 1) throw PermanentDownloadException("A media section length conflicts with its byte range.")
            }
            val etag = c.getHeaderField("ETag")?.trim()
            if (validator != null && etag != validator) {
                throw PermanentDownloadException("The media representation changed between sections.")
            }
            requireMediaResponse(c)
        } catch (failure: Throwable) {
            c.disconnect()
            throw failure
        }
    }

    private suspend fun readInto(
        c: HttpURLConnection, section: Section, start: Long, channel: FileChannel, received: AtomicLong,
        capacity: MediaStorageSpace.WriteCapacity, signal: Channel<Unit>, checkCurrent: suspend () -> Unit,
    ) = coroutineScope {
        // A blocking socket read cannot observe cancellation; closing the connection ends it.
        val closer = launch { try { awaitCancellation() } finally { c.disconnect() } }
        try {
            var position = start
            c.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (position <= section.end) {
                    checkCurrent()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), section.end - position + 1).toInt())
                    if (count < 0) break
                    if (count == 0) continue
                    capacity.requireForWrite(count.toLong())
                    val data = ByteBuffer.wrap(buffer, 0, count)
                    var at = position
                    try {
                        while (data.hasRemaining()) at += channel.write(data, at)
                    } catch (error: IOException) {
                        throw PermanentDownloadException("Could not write the media file. Check free storage and try again.")
                    }
                    position += count
                    section.written.addAndGet(count.toLong())
                    received.addAndGet(count.toLong())
                    signal.trySend(Unit)
                }
            }
            if (position <= section.end) throw RetryableDownloadException("Connection ended before a media section arrived.")
        } finally {
            closer.cancel()
            c.disconnect()
        }
    }

    private fun digestRange(channel: FileChannel, from: Long, until: Long, digest: MessageDigest, buffer: ByteBuffer) {
        var position = from
        while (position < until) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), until - position).toInt())
            val count = channel.read(buffer, position)
            check(count > 0) { "The private media file changed while hashing its prefix." }
            buffer.flip()
            digest.update(buffer)
            position += count
        }
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 400L
        const val SECTION_RETRIES = 2
    }
}
