package dev.kmpyt.internal

import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.YouTubePlaybackEndpoint
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.SabrStreamingLimits
import dev.kmpyt.extraction.SourcePlaybackAuthorization
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.YouTubeSabrPlaybackCache
import dev.kmpyt.extraction.YouTubeSabrStreamingSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Java sockets/files shared by JVM and Android API23+, never a second SABR transport. */
internal class FileSabrPlaybackAdapter(
    private val http: HttpClient,
    private val cacheDirectory: String,
    private val limits: YouTubeNativePlaybackLimits,
) : YouTubePlaybackAdapter {
    override suspend fun open(request: SourcePlaybackRequest,
        authorization: SourcePlaybackAuthorization): YouTubePlaybackResource {
        var resource: FileSabrPlaybackResource? = null
        try {
            return withContext(Dispatchers.IO) {
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
                val parent = File(cacheDirectory)
                require(parent.isAbsolute && parent.canonicalFile == parent.absoluteFile && parent.isDirectory && parent.canWrite()) {
                    "Choose an existing writable private playback cache directory."
                }
                MediaStorageSpace.requireAvailable(parent, total)
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.resumeHex()
                // A filesystem directory name is not the HTTP bearer capability:
                // shared temporary roots may reveal child names to local users.
                val directory = File(parent, "kmpyt-playback-${UUID.randomUUID()}")
                check(directory.mkdir()) { "Could not create private playback storage." }
                val caches = mutableMapOf<TrackRole, YouTubeSabrPlaybackCache>()
                var server: ServerSocket? = null
                try {
                    // The caller's root is already private. Restrict the fresh
                    // child before creating any media bytes on Java platforms.
                    if (File.separatorChar == '/') {
                        check(directory.setReadable(false, false) && directory.setWritable(false, false) &&
                            directory.setExecutable(false, false) && directory.setReadable(true, true) &&
                            directory.setWritable(true, true) && directory.setExecutable(true, true)) {
                            "Could not restrict private playback storage permissions."
                        }
                    }
                    for (track in tracks) caches[track.role] = PlaybackFileCache(
                        File(directory, "${track.role.name.lowercase()}.media"), requireNotNull(track.sizeBytes))
                    val socket = ServerSocket().also { server = it }
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), limits.maxConnections)
                    val created = FileSabrPlaybackResource(http, request, authorization, socket, directory, caches.toMap(), token, limits)
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
                        release { server?.close() }
                        caches.values.forEach { cache -> release { cache.close() } }
                        release { deleteCheckpointTree(directory) }
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            // Covers prompt cancellation while dispatching the acquired result
            // back to the caller; no resource is orphaned before handoff.
            withContext(NonCancellable) { resource?.close() }
            throw failure
        }
    }
}

private class PlaybackFileCache(private val file: File, private val expected: Long) : YouTubeSabrPlaybackCache {
    private val mutex = Mutex()
    private var closed = false
    private var closeFailure: Throwable? = null
    init { check(file.createNewFile()) { "Could not create a private playback track." } }

    private fun requireOpen() {
        check(!closed && file.canonicalFile == file.absoluteFile && file.isFile) { "Private playback track is closed or changed." }
    }
    override suspend fun size(): Long = mutex.withLock {
        requireOpen()
        file.length().also { check(it <= expected) { "Private playback track exceeds its declared size." } }
    }
    override suspend fun read(offset: Long, count: Int): ByteArray = mutex.withLock {
        currentCoroutineContext().ensureActive()
        requireOpen()
        require(count in 0..1_048_576 && offset >= 0 && offset <= expected - count && offset + count <= file.length())
        ByteArray(count).also { bytes -> RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) } }
    }
    override suspend fun write(offset: Long, bytes: ByteArray) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        requireOpen()
        require(bytes.isNotEmpty() && offset >= 0 && offset <= expected - bytes.size)
        MediaStorageSpace.requireAvailable(file, bytes.size.toLong())
        RandomAccessFile(file, "rw").use { destination ->
            destination.seek(offset); destination.write(bytes); destination.fd.sync()
        }
    }
    override suspend fun close() = mutex.withLock {
        closeFailure?.let { throw it }
        if (!closed) {
            closed = true
            try { check(file.delete()) { "Could not release a private playback track." } }
            catch (failure: Throwable) { closeFailure = failure; throw failure }
        }
    }
}

private class FileSabrPlaybackResource(
    http: HttpClient,
    request: SourcePlaybackRequest,
    authorization: SourcePlaybackAuthorization,
    private val server: ServerSocket,
    private val directory: File,
    caches: Map<TrackRole, YouTubeSabrPlaybackCache>,
    token: String,
    private val limits: YouTubeNativePlaybackLimits,
) : YouTubePlaybackResource {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val session = YouTubeSabrStreamingSession(http, request, caches, scope, authorization,
        SabrStreamingLimits(limits.maxCacheBytes, limits.chunkBytes, limits.waitTimeoutMs), nowEpochMs = System::currentTimeMillis)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var accepting: Job? = null
    override val endpoints = listOfNotNull(request.plan.primary, request.plan.audio).map { track ->
        YouTubePlaybackEndpoint(track.role, "http://127.0.0.1:${server.localPort}/$token/${track.role.name.lowercase()}",
            track.mimeType.substringBefore(';').trim().lowercase(), requireNotNull(track.sizeBytes))
    }

    suspend fun start() {
        session.start()
        accepting = scope.launch {
            try {
                while (isActive && !closing.get()) {
                    val socket = server.accept()
                    if (closing.get() || sockets.size >= limits.maxConnections) { socket.close(); continue }
                    sockets += socket
                    if (closing.get()) { sockets -= socket; socket.close(); continue }
                    launch {
                        try { serve(socket) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Fail closed, never expose a URL/exception in HTTP or logs. */ }
                        finally { sockets -= socket; runCatching { socket.close() } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) {
                // Close the failed network surface without joining our own job.
                // The external owner still releases ingestion and cache files.
                runCatching { server.close() }
                sockets.toList().forEach { socket -> runCatching { socket.close() } }
            }
        }
    }

    private suspend fun serve(socket: Socket) = coroutineScope {
        socket.soTimeout = minOf(15_000L, limits.connectionIdleTimeoutMs).toInt()
        socket.tcpNoDelay = true
        val lastProgress = AtomicLong(System.nanoTime())
        val headerStarted = System.nanoTime()
        val headerComplete = AtomicBoolean(false)
        // Java SO_TIMEOUT bounds reads, not blocked writes. This coroutine closes
        // stalled readers/writers without accumulating unbounded IO threads.
        val idle = launch {
            while (isActive) {
                delay(minOf(1_000, limits.connectionIdleTimeoutMs / 4))
                if (System.nanoTime() - lastProgress.get() >= limits.connectionIdleTimeoutMs * 1_000_000 ||
                    !headerComplete.get() && System.nanoTime() - headerStarted >= minOf(15_000L, limits.connectionIdleTimeoutMs) * 1_000_000) {
                    socket.close(); break
                }
            }
        }
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(limits.maxHeaderBytes)
            var size = 0
            var complete = false
            while (size < buffer.size) {
                currentCoroutineContext().ensureActive()
                val value = input.read()
                if (value < 0) return@coroutineScope
                buffer[size++] = value.toByte()
                lastProgress.set(System.nanoTime())
                if (size >= 4 && buffer[size - 4] == 13.toByte() && buffer[size - 3] == 10.toByte() &&
                    buffer[size - 2] == 13.toByte() && buffer[size - 1] == 10.toByte()) { complete = true; break }
            }
            val response = if (complete) PlaybackHttpProtocol.response(buffer.copyOf(size), endpoints, limits.maxHeaderBytes)
                else PlaybackHttpResponse(431)
            headerComplete.set(true)
            val output = socket.getOutputStream()
            if (!response.sendBody) { output.write(response.headers()); output.flush(); return@coroutineScope }
            val endpoint = checkNotNull(response.endpoint)
            var offset = response.offset
            var remaining = response.length
            val first = try { session.read(endpoint.role, offset, minOf(limits.chunkBytes.toLong(), remaining).toInt()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { output.write(PlaybackHttpResponse(503).headers()); output.flush(); return@coroutineScope }
            check(first.isNotEmpty()) { "No verified playback bytes were available." }
            output.write(response.headers())
            output.write(first)
            lastProgress.set(System.nanoTime())
            offset += first.size; remaining -= first.size
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val bytes = session.read(endpoint.role, offset, minOf(limits.chunkBytes.toLong(), remaining).toInt())
                check(bytes.isNotEmpty() && bytes.size <= remaining) { "The playback track ended before its declared boundary." }
                output.write(bytes)
                lastProgress.set(System.nanoTime())
                offset += bytes.size; remaining -= bytes.size
            }
            output.flush()
        } finally { idle.cancelAndJoin() }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (!closing.compareAndSet(false, true)) { closed.await(); return@withContext }
        var failed = false
        suspend fun release(block: suspend () -> Unit) { try { block() } catch (_: Throwable) { failed = true } }
        release { server.close() }
        sockets.toList().forEach { socket -> release { socket.close() } }
        release { accepting?.cancelAndJoin() }
        release { session.close() }
        release { job.cancelAndJoin() }
        release { deleteCheckpointTree(directory) }
        if (failed) {
            val failure = IllegalStateException("Could not close all private playback resources.")
            closed.completeExceptionally(failure)
            throw failure
        }
        closed.complete(Unit)
    }

    override fun toString() = "YouTubePlaybackResource(<redacted>)"
}
