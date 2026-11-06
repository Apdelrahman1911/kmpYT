@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.internal.PlaybackHttpProtocol
import dev.kmpyt.internal.PlaybackHttpResponse
import io.ktor.client.HttpClient
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
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
                request.requireStaticSabr()
                val tracks = listOfNotNull(request.plan.primary, request.plan.audio)
                var total = 0L
                for (track in tracks) {
                    val bytes = requireNotNull(track.sizeBytes)
                    require(bytes > 0 && bytes <= limits.maxCacheBytes - total) { "The static playback cache exceeds its configured byte bound." }
                    total += bytes
                }
                authorization.requireResource(request, requireNotNull(request.plan.primary.sabr).endpoint)
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
                    for (track in tracks) caches[track.role] = IosPlaybackCache(
                        "$directory/${track.role.name.lowercase()}.media", requireNotNull(track.sizeBytes))
                    val (socket, port) = createIosPlaybackListener(limits.maxConnections)
                    listener = socket
                    val created = IosSabrPlaybackResource(http, request, authorization, socket, port, directory,
                        caches.toMap(), capability, limits)
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

private class IosPlaybackCache(private val path: String, private val expected: Long) : YouTubeSabrPlaybackCache {
    private val mutex = Mutex()
    private val file = IosMediaFile(path, expected, durableWrites = true)
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
        IosPlaybackSocket(descriptor) to ntohs(address.sin_port).toInt()
    } catch (failure: Throwable) { platform.posix.close(descriptor); throw failure }
}

private class IosSabrPlaybackResource(http: HttpClient, request: SourcePlaybackRequest,
    authorization: SourcePlaybackAuthorization, private val listener: IosPlaybackSocket, port: Int,
    private val directory: String, caches: Map<TrackRole, YouTubeSabrPlaybackCache>, capability: String,
    private val limits: YouTubeNativePlaybackLimits) : YouTubePlaybackResource {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val session = YouTubeSabrStreamingSession(http, request, caches, scope, authorization,
        SabrStreamingLimits(limits.maxCacheBytes, limits.chunkBytes, limits.waitTimeoutMs),
        nowEpochMs = { (NSDate().timeIntervalSince1970 * 1000).toLong() })
    private val closing = MutableStateFlow(false)
    private val closed = CompletableDeferred<Unit>()
    private val connections = Mutex()
    private val sockets = mutableSetOf<IosPlaybackSocket>()
    private var accepting: Job? = null
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
