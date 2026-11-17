@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.internal.PlaybackHttpProtocol
import dev.kmpyt.internal.PlaybackHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFSwapInt16BigToHost
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.darwin.inet_pton
import platform.posix.*
import kotlin.time.TimeSource

/** The caller retains its HTTP client; the private cache is owned by each returned resource. */
fun createIosYouTubeSabrPlaybackAdapter(httpClient: HttpClient, cacheDirectory: String = NSTemporaryDirectory(),
    limits: YouTubeNativePlaybackLimits = YouTubeNativePlaybackLimits()): YouTubePlaybackAdapter =
    IosSabrPlaybackAdapter(httpClient, cacheDirectory, limits)

private class IosSabrPlaybackAdapter(private val http: HttpClient, private val cacheDirectory: String,
    private val limits: YouTubeNativePlaybackLimits) : YouTubePlaybackAdapter {
    override suspend fun open(request: SourcePlaybackRequest,
        authorization: SourcePlaybackAuthorization): YouTubePlaybackResource {
        var resource: IosSabrPlaybackResource? = null
        try {
            return withContext(Dispatchers.Default) {
                val progressive = request.plan.primary.transport == TransportKind.PROGRESSIVE
                if (progressive) request.requireStaticProgressive() else request.requireStaticSabr()
                SourceAvailability.await(request.plan)
                val tracks = listOfNotNull(request.plan.primary, request.plan.audio)
                var total = 0L
                for (track in tracks) {
                    val bytes = requireNotNull(track.sizeBytes)
                    require(bytes > 0 && bytes <= limits.maxCacheBytes - total) { "The static playback cache exceeds its configured byte bound." }
                    total += bytes
                }
                authorization.requireResource(request,
                    if (progressive) requireNotNull(request.plan.primary.url) else requireNotNull(request.plan.primary.sabr).endpoint)
                currentCoroutineContext().ensureActive()
                require(cacheDirectory.startsWith('/') && '\u0000' !in cacheDirectory)
                val parent = memScoped {
                    val output = allocArray<ByteVar>(PATH_MAX)
                    realpath(cacheDirectory, output)?.toKString() ?: error("Choose an existing private playback cache directory.")
                }
                requireIosStorage(parent, total)
                val directory = "$parent/kmpyt-playback-${NSUUID().UUIDString}"
                check(mkdir(directory, 448.convert()) == 0) { "Could not create private playback storage." }
                val caches = mutableMapOf<TrackRole, YouTubeSabrPlaybackCache>()
                var listener: IosPlaybackSocket? = null
                try {
                    val capability = ByteArray(32).also { bytes -> bytes.usePinned {
                        check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0)) == 0) {
                            "Could not create a private playback capability."
                        }
                    } }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    // Progressive bytes are served from bounded in-memory range windows; no cache file.
                    if (!progressive) for (track in tracks) caches[track.role] = IosPlaybackCache(
                        "$directory/${track.role.name.lowercase()}.media", requireNotNull(track.sizeBytes))
                    val (socket, port) = createIosPlaybackListener(limits.maxConnections)
                    listener = socket
                    val cacheMap = caches.toMap()
                    val created = IosSabrPlaybackResource(request, socket, port, directory, capability, limits) { scope ->
                        if (progressive) ProgressiveRangePlaybackBytes(http, request, authorization)
                        else SabrPlaybackBytes(YouTubeSabrStreamingSession(http, request, cacheMap, scope, authorization,
                            SabrStreamingLimits(limits.maxCacheBytes, limits.chunkBytes, limits.waitTimeoutMs),
                            nowEpochMs = { (NSDate().timeIntervalSince1970 * 1000).toLong() }))
                    }
                    resource = created
                    created.start()
                    created
                } catch (failure: Throwable) {
                    if (resource == null) withContext(NonCancellable) {
                        suspend fun release(block: suspend () -> Unit) {
                            try { block() } catch (_: Throwable) {
                                failure.addSuppressed(IllegalStateException("Could not release private playback construction resources."))
                            }
                        }
                        release { listener?.close() }
                        caches.values.forEach { cache -> release { cache.close() } }
                        release { check(rmdir(directory) == 0) { "Could not release private playback storage." } }
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { resource?.close() }
            throw failure
        }
    }
}

internal class IosPlaybackCache(private val path: String, private val expected: Long,
    synchronize: (Int) -> Int = { fileDescriptor -> fsync(fileDescriptor) }) : YouTubeSabrPlaybackCache {
    private val mutex = Mutex()
    private val file = IosMediaFile(path, expected, durableWrites = false, synchronize = synchronize)
    private var closed = false
    private var closeFailure: Throwable? = null
    override suspend fun size(): Long = mutex.withLock {
        check(!closed)
        file.size().also { require(it <= expected) }
    }
    override suspend fun read(offset: Long, count: Int): ByteArray = mutex.withLock {
        check(!closed)
        require(count in 0..1_048_576 && offset >= 0 && offset <= expected - count)
        file.read(offset, count)
    }
    override suspend fun write(offset: Long, bytes: ByteArray) = mutex.withLock {
        check(!closed)
        file.write(offset, bytes)
    }
    override suspend fun close() = mutex.withLock {
        closeFailure?.let { throw it }
        if (!closed) {
            closed = true
            try {
                file.close()
                check(unlink(path) == 0) { "Could not release a private playback track." }
            } catch (failure: Throwable) { closeFailure = failure; throw failure }
        }
    }
}

/** Nonblocking syscalls are mutex-guarded against close/descriptor reuse. No global signal changes. */
private class IosPlaybackSocket(private var descriptor: Int) {
    private val mutex = Mutex()
    private var closeFailure: Throwable? = null
    suspend fun accept(): IosPlaybackSocket? = mutex.withLock {
        check(descriptor >= 0) { "Playback listener is closed." }
        val accepted = platform.posix.accept(descriptor, null, null)
        if (accepted < 0) {
            if (errno in setOf(EAGAIN, EWOULDBLOCK, EINTR)) null else error("Could not accept a native playback connection.")
        } else {
            try { configureIosPlaybackSocket(accepted); IosPlaybackSocket(accepted) }
            catch (failure: Throwable) { platform.posix.close(accepted); throw failure }
        }
    }
    suspend fun receive(bytes: ByteArray, offset: Int, count: Int): Int = mutex.withLock {
        check(descriptor >= 0) { "Playback connection is closed." }
        val read = bytes.usePinned { recv(descriptor, it.addressOf(offset), count.convert(), 0) }
        if (read < 0) {
            if (errno in setOf(EAGAIN, EWOULDBLOCK, EINTR)) -1 else error("Native playback connection read failed.")
        } else read.toInt()
    }
    suspend fun send(bytes: ByteArray, offset: Int, count: Int): Int = mutex.withLock {
        check(descriptor >= 0) { "Playback connection is closed." }
        val written = bytes.usePinned { platform.posix.send(descriptor, it.addressOf(offset), count.convert(), 0) }
        if (written < 0) {
            if (errno in setOf(EAGAIN, EWOULDBLOCK, EINTR)) -1 else error("Native playback connection write failed.")
        } else written.toInt()
    }
    suspend fun close() = mutex.withLock {
        closeFailure?.let { throw it }
        if (descriptor >= 0) {
            val owned = descriptor
            descriptor = -1
            shutdown(owned, SHUT_RDWR)
            if (platform.posix.close(owned) != 0) {
                val failure = IllegalStateException("Could not confirm release of a native playback socket.")
                closeFailure = failure
                throw failure
            }
        }
    }
}

private fun configureIosPlaybackSocket(descriptor: Int) {
    val flags = fcntl(descriptor, F_GETFL)
    check(flags >= 0 && fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) == 0) { "Could not bound native playback socket operations." }
    memScoped {
        val enabled = alloc<IntVar>(); enabled.value = 1
        check(setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert()) == 0) {
            "Could not isolate native playback socket failures."
        }
    }
}

private fun createIosPlaybackListener(backlog: Int): Pair<IosPlaybackSocket, Int> = memScoped {
    val descriptor = socket(AF_INET, SOCK_STREAM, 0)
    check(descriptor >= 0) { "Could not create a native playback listener." }
    try {
        configureIosPlaybackSocket(descriptor)
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_len = sizeOf<sockaddr_in>().convert()
        address.sin_family = AF_INET.convert()
        address.sin_port = 0u
        check(inet_pton(AF_INET, "127.0.0.1", address.sin_addr.ptr) == 1)
        check(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0 && listen(descriptor, backlog) == 0) {
            "Could not bind the private IPv4-loopback playback listener."
        }
        val length = alloc<socklen_tVar>(); length.value = sizeOf<sockaddr_in>().convert()
        check(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0) { "Could not inspect the native playback listener." }
        IosPlaybackSocket(descriptor) to CFSwapInt16BigToHost(address.sin_port).toInt()
    } catch (failure: Throwable) { platform.posix.close(descriptor); throw failure }
}

private class IosSabrPlaybackResource(request: SourcePlaybackRequest,
    private val listener: IosPlaybackSocket, port: Int,
    private val directory: String, capability: String,
    private val limits: YouTubeNativePlaybackLimits, source: (CoroutineScope) -> PlaybackBytes) : YouTubePlaybackResource {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val session = source(scope)
    private val closing = MutableStateFlow(false)
    private val closed = CompletableDeferred<Unit>()
    private val connections = Mutex()
    private val sockets = mutableSetOf<IosPlaybackSocket>()
    private var accepting: Job? = null
    override val failureCode: String? get() = session.failureCode
    override val endpoints = listOfNotNull(request.plan.primary, request.plan.audio).map { track ->
        YouTubePlaybackEndpoint(track.role, "http://127.0.0.1:$port/$capability/${track.role.name.lowercase()}",
            track.mimeType.substringBefore(';').trim().lowercase(), requireNotNull(track.sizeBytes))
    }
    suspend fun start() {
        session.start()
        accepting = scope.launch {
            try {
                while (isActive && !closing.value) {
                    val socket = listener.accept()
                    if (socket == null) { delay(20); continue }
                    var registered = false
                    try {
                        registered = connections.withLock {
                            if (!closing.value && sockets.size < limits.maxConnections) { sockets += socket; true } else false
                        }
                        if (registered) launch {
                            try { serve(socket) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Fail closed without URLs or arbitrary diagnostic text. */ }
                            finally { withContext(NonCancellable) { connections.withLock { sockets -= socket }; socket.close() } }
                        }
                    } finally { if (!registered) withContext(NonCancellable) { socket.close() } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Do not self-join by calling resource.close from its accept job.
                // Close the network surface; the external owner still disposes
                // ingestion/cache and receives any cleanup failure from close.
                withContext(NonCancellable) {
                    runCatching { listener.close() }
                    for (socket in connections.withLock { sockets.toList() }) runCatching { socket.close() }
                }
            }
        }
    }
    private suspend fun serve(socket: IosPlaybackSocket) {
        var lastProgress = TimeSource.Monotonic.markNow()
        suspend fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                check(lastProgress.elapsedNow().inWholeMilliseconds < limits.connectionIdleTimeoutMs) { "Native playback connection became idle." }
                val count = socket.send(bytes, offset, bytes.size - offset)
                if (count <= 0) { delay(10); continue }
                offset += count; lastProgress = TimeSource.Monotonic.markNow()
            }
        }
        val buffer = ByteArray(limits.maxHeaderBytes)
        val began = TimeSource.Monotonic.markNow()
        var size = 0
        var complete = false
        while (size < buffer.size) {
            currentCoroutineContext().ensureActive()
            check(began.elapsedNow().inWholeMilliseconds < minOf(15_000L, limits.connectionIdleTimeoutMs)) { "Native playback headers timed out." }
            val count = socket.receive(buffer, size, 1)
            if (count == 0) return
            if (count < 0) { delay(10); continue }
            size += count; lastProgress = TimeSource.Monotonic.markNow()
            if (size >= 4 && buffer[size - 4] == 13.toByte() && buffer[size - 3] == 10.toByte() &&
                buffer[size - 2] == 13.toByte() && buffer[size - 1] == 10.toByte()) { complete = true; break }
        }
        val response = if (complete) PlaybackHttpProtocol.response(buffer.copyOf(size), endpoints, limits.maxHeaderBytes)
            else PlaybackHttpResponse(431)
        if (!response.sendBody) { write(response.headers()); return }
        val endpoint = checkNotNull(response.endpoint)
        var offset = response.offset
        var remaining = response.length
        val first = try { session.read(endpoint.role, offset, minOf(limits.chunkBytes.toLong(), remaining).toInt()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { write(PlaybackHttpResponse(503).headers()); return }
        check(first.isNotEmpty()) { "No verified playback bytes were available." }
        write(response.headers()); write(first)
        offset += first.size; remaining -= first.size
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val bytes = session.read(endpoint.role, offset, minOf(limits.chunkBytes.toLong(), remaining).toInt())
            check(bytes.isNotEmpty() && bytes.size <= remaining) { "The playback track ended before its declared boundary." }
            write(bytes)
            offset += bytes.size; remaining -= bytes.size
        }
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.Default) {
        if (!closing.compareAndSet(false, true)) { closed.await(); return@withContext }
        var failed = false
        suspend fun release(block: suspend () -> Unit) { try { block() } catch (_: Throwable) { failed = true } }
        release { listener.close() }
        for (socket in connections.withLock { sockets.toList() }) release { socket.close() }
        release { accepting?.cancelAndJoin() }
        release { session.close() }
        release { job.cancelAndJoin() }
        release { check(rmdir(directory) == 0) { "Could not release private playback storage." } }
        if (failed) {
            val failure = IllegalStateException("Could not close all private playback resources.")
            closed.completeExceptionally(failure)
            throw failure
        }
        closed.complete(Unit)
    }
    override fun toString() = "YouTubePlaybackResource(<redacted>)"
}

/** The bytes behind one loopback playback resource: a static SABR session or a progressive object. */
internal interface PlaybackBytes {
    val failureCode: String?
    suspend fun start()
    suspend fun read(role: TrackRole, offset: Long, count: Int): ByteArray
    suspend fun close()
}

private class SabrPlaybackBytes(private val session: YouTubeSabrStreamingSession) : PlaybackBytes {
    override val failureCode: String? get() = session.state.value.takeIf { it.status == SabrStreamingStatus.FAILED }?.errorCode
    override suspend fun start() { session.start() }
    override suspend fun read(role: TrackRole, offset: Long, count: Int) = session.read(role, offset, count)
    override suspend fun close() { session.close() }
}

/**
 * Serves one progressive audio object to AVPlayer through the owned loopback, as exact
 * `Range: bytes=a-b` windows of at most [windowBytes]. AVPlayer's own open-ended request for
 * these objects is refused (2026-11-11 record: HTTP 403 / -11800), while exact ranges return 206.
 * Every request and redirect destination passes the caller's authorization and the download
 * policy; a 401/403/410 fails closed with a stable code. Nothing is persisted.
 *
 * Windows sit on fixed [windowBytes] boundaries and are never fetched twice while cached: a read
 * that crosses a boundary returns the bytes up to it (the loopback asks again for the rest).
 * Starting a window at each read offset instead re-downloaded overlapping megabytes whenever
 * AVPlayer's reads straddled a boundary, about four times the object before playback began.
 */
internal class ProgressiveRangePlaybackBytes(private val http: HttpClient, private val request: SourcePlaybackRequest,
    private val authorization: SourcePlaybackAuthorization, private val windowBytes: Long = 1_048_576L,
    private val cachedWindows: Int = 8, private val readAheadWindows: Int = 2) : PlaybackBytes {
    private class Window(val start: Long, val end: Long, val bytes: Deferred<ByteArray>)
    private class RefusedException : IllegalStateException("Media access was refused.")

    private val track = request.plan.primary
    private val size = requireNotNull(track.sizeBytes)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Recent windows, most recent last; AVPlayer re-reads earlier offsets after probing. */
    private val windows = ArrayDeque<Window>()
    /** The validated final URL of the last redirect chain; each piece otherwise costs a 302 first. */
    @kotlin.concurrent.Volatile private var resolvedUrl: String? = null
    @kotlin.concurrent.Volatile private var failure: String? = null
    override val failureCode: String? get() = failure

    override suspend fun start() = Unit
    override suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        mutex.withLock { windows.clear() }
    }

    override suspend fun read(role: TrackRole, offset: Long, count: Int): ByteArray {
        require(role == track.role && count > 0 && offset >= 0 && offset < size)
        val index = offset / windowBytes
        var attempt = 0
        while (true) {
            val window = mutex.withLock { cached(index) ?: open(index) }
            val bytes = try { window.bytes.await() } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                // The shared window itself was cancelled (close or eviction); fetch it again.
                mutex.withLock { windows.remove(window) }
                if (++attempt > 1) throw cancelled
                continue
            } catch (error: Exception) {
                mutex.withLock { windows.remove(window) }
                if (++attempt > 1) {
                    failure = if (error is RefusedException) "PROGRESSIVE_RESOURCE_NOT_AUTHORIZED" else "PROGRESSIVE_HTTP_REQUEST_FAILED"
                    throw error
                }
                continue
            }
            failure = null
            // Read ahead so the next sequential reads are already local.
            mutex.withLock {
                for (next in index + 1..index + readAheadWindows) {
                    if (next * windowBytes >= size) break
                    if (windows.none { it.start == next * windowBytes }) open(next)
                }
            }
            val from = (offset - window.start).toInt()
            val wanted = minOf(count.toLong(), window.end - offset + 1).toInt()
            return bytes.copyOfRange(from, from + wanted)
        }
    }

    /** Caller holds [mutex]. A hit becomes the most recently used window. */
    private fun cached(index: Long): Window? {
        val window = windows.firstOrNull { it.start == index * windowBytes } ?: return null
        windows.remove(window); windows.addLast(window)
        return window
    }

    /** Caller holds [mutex]. Evicts the least recently used window beyond [cachedWindows]. */
    private fun open(index: Long): Window {
        val start = index * windowBytes
        val end = minOf(size, start + windowBytes) - 1
        val window = Window(start, end, scope.async { fetch(start, end) })
        windows.addLast(window)
        while (windows.size > cachedWindows) windows.removeFirst()
        return window
    }

    private suspend fun fetch(start: Long, end: Long): ByteArray {
        val original = requireNotNull(track.url)
        resolvedUrl?.let { known ->
            try { return fetchFrom(known, start, end) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { resolvedUrl = null } // A stale redirect target: restart from the inspected URL.
        }
        return fetchFrom(original, start, end)
    }

    private suspend fun fetchFrom(first: String, start: Long, end: Long): ByteArray {
        var url = first
        repeat(6) {
            currentCoroutineContext().ensureActive()
            authorization.requireResource(request, url)
            YouTubeDownloadPolicy.requireResource(url, request.plan.inspectionUrl, request.plan.mediaId)
            var next: String? = null
            val bytes = http.prepareGet(url) {
                header(io.ktor.http.HttpHeaders.AcceptEncoding, "identity")
                header(io.ktor.http.HttpHeaders.Range, "bytes=$start-$end")
            }.execute { response ->
                val status = response.status.value
                debugProgressiveLoopback(status, start, end, response.headers[io.ktor.http.HttpHeaders.ContentRange] != null)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = response.headers[io.ktor.http.HttpHeaders.Location] ?: error("Invalid media redirect.")
                    next = platform.Foundation.NSURL(string = location, relativeToURL = platform.Foundation.NSURL(string = url))
                        .absoluteURL?.absoluteString ?: error("Invalid media redirect.")
                    return@execute null
                }
                if (status in setOf(401, 403, 410)) throw RefusedException()
                check(status == 206) { "The media server ignored the exact range." }
                val range = dev.kmpyt.downloads.DownloadProtocol.parseContentRange(response.headers[io.ktor.http.HttpHeaders.ContentRange])
                check(range != null && range.first == start && range.last == end && (range.total == null || range.total == size)) {
                    "The media server returned a different range."
                }
                val expected = (end - start + 1).toInt()
                val output = ByteArray(expected)
                val channel = response.bodyAsChannel()
                var filled = 0
                while (filled < expected) {
                    val n = channel.readAvailable(output, filled, expected - filled)
                    if (n < 0) break
                    filled += n
                }
                check(filled == expected && channel.readAvailable(ByteArray(1), 0, 1) <= 0) { "The media range length differed." }
                output
            }
            if (bytes != null) { resolvedUrl = url; return bytes }
            url = requireNotNull(next)
        }
        error("Too many media redirects.")
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun debugProgressiveLoopback(status: Int, start: Long, end: Long, hasRange: Boolean) {
    if (kotlin.native.Platform.isDebugBinary) println("VEYRA_PROGRESSIVE_LOOPBACK status=$status range=$start-$end contentRange=$hasRange")
}
