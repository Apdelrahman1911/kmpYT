package dev.kmpyt.internal

import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/** Caller-owned files and resumable checkpoints; no app database, UI, or background scheduler. */
internal class FileDownloadCoordinator(private val processor: FileMediaProcessor, private val client: HttpClient,
    private val platformName: String, private val allowLocalHttp: Boolean = false) {
    private val json = Json { ignoreUnknownKeys = true }
    private companion object { val activeDestinations = ConcurrentHashMap<String, Boolean>() }

    suspend fun download(input: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult = withContext(Dispatchers.IO) {
        require(destinationPath.isNotBlank()) { "Choose a destination file." }
        YouTubeDownloadPolicy.requirePlan(input)
        require(input.credentialRef == null) { "This adapter supports anonymous inspected resources; account credentials are not accepted." }
        YouTubeFormatCapabilities.downloadProblem(input.primary, input.audio, platformName)?.let { error(it) }
        val destination = File(destinationPath).canonicalFile
        val key = destination.path.let { if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) it.lowercase(Locale.ROOT) else it }
        check(activeDestinations.putIfAbsent(key, true) == null) { "Another download is using this destination." }
        try {
            require(!destination.exists()) { "The destination already exists; choose another file." }
            val parent = requireNotNull(destination.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create the destination directory." }
            val directory = File(parent, ".${destination.name}.kmpyt")
            check(directory.isDirectory || directory.mkdir()) { "Could not create download checkpoint storage." }
            val lockFile = RandomAccessFile(File(directory, "lock"), "rw")
            val fileLock = try { lockFile.channel.tryLock() } catch (_: Exception) { null }
            if (fileLock == null) { lockFile.close(); error("Another download is using this destination.") }
            var complete = false
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(PlannedDownload.serializer(),
                    input.copy(sabrCheckpoint = null, sabrDiagnostics = emptyList())).encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val checkpointFile = File(directory, "checkpoint.json")
                val restored = runCatching { json.decodeFromString(FileCheckpoint.serializer(), checkpointFile.readText()) }
                    .getOrNull()?.takeIf { it.planDigest == digest }
                if (restored == null) directory.listFiles()?.filter { it.name != "lock" }?.forEach { it.deleteRecursively() }
                var plan = input.copy(sabrCheckpoint = restored?.sabrCheckpoint)
                var states = restored?.parts.orEmpty().mapNotNull { state ->
                    val file = File(directory, state.key)
                    // Only keys authored by TransferPlanning are allowed to name local files.
                    require(Regex("\\d+-\\d+").matches(state.key)) { "Invalid local checkpoint resource key." }
                    if (!file.isFile) null else if (plan.primary.transport == TransportKind.SABR) state
                    else state.copy(bytes = file.length(), complete = state.complete && file.length() == state.bytes)
                }
                val active = AtomicReference<HttpURLConnection?>(null)
                suspend fun current(): Boolean { currentCoroutineContext().ensureActive(); return true }
                suspend fun persist(updated: PlannedDownload, parts: List<PartState>, speed: Long, total: Long?) {
                    current()
                    plan = updated; states = parts
                    val temporary = File(directory, "checkpoint.tmp")
                    val bytes = json.encodeToString(FileCheckpoint.serializer(),
                        FileCheckpoint(digest, parts, updated.sabrCheckpoint)).encodeToByteArray()
                    RandomAccessFile(temporary, "rw").use { it.setLength(0); it.write(bytes); it.fd.sync() }
                    check(!checkpointFile.exists() || checkpointFile.delete()) { "Could not replace download checkpoint." }
                    check(temporary.renameTo(checkpointFile)) { "Could not persist download checkpoint." }
                    onProgress(YouTubeDownloadProgress(MediaStorageSpace.checkedSum(parts.map { it.bytes }), total, speed))
                }
                val published = File(directory, "published.mp4")
                published.delete()
                coroutineScope {
                    // Cancelling the caller closes a blocking native read immediately.
                    val disconnect = launch(Dispatchers.IO) {
                        try { awaitCancellation() } finally { active.getAndSet(null)?.disconnect() }
                    }
                    try {
                        if (plan.primary.transport == TransportKind.SABR) {
                            SabrFileTransfer(processor, directory, client, allowLocalHttp)
                                .run(plan, published, ::current, ::persist)
                        } else {
                            val expected = MediaStorageSpace.declaredBytes(listOfNotNull(plan.primary, plan.audio))
                            MediaStorageSpace.requireAvailable(directory,
                                expected - minOf(expected, MediaStorageSpace.checkedSum(states.map { it.bytes })))
                            TransferCoordinator().run(plan, states, ::current, { part, initial, progress ->
                                val file = File(directory, part.key)
                                var state = initial
                                var attempt = 0
                                while (true) {
                                    try {
                                        val result = if (part.resource.rangeStart != null)
                                            RangedFileTransfer(allowLocalHttp, { active.set(it) }).fetch(part, state, file, plan, ::current) { update, speed ->
                                                state = update; progress(update, speed)
                                            }
                                        else {
                                            val result = HttpDownloadTransfer(allowLocalHttp, plan.requestHeaders,
                                                refreshOnExpiry = true, onConnection = { active.set(it) }).fetch(
                                                FileTransferRequest(part.resource.url, plan, state.total, state.etag, state.lastModified),
                                                file, ::current) {
                                                    state = PartState(part.key, it.bytes, it.total, it.etag, it.lastModified)
                                                    progress(state, it.speedBytesPerSecond)
                                                }
                                            PartState(part.key, result.bytes, result.total, result.etag, result.lastModified, true)
                                        }
                                        return@run result
                                    } catch (error: IOException) {
                                        if (error is PermanentDownloadException || error is ExpiredResourceException ||
                                            error is InsufficientMediaStorageException || attempt >= 2) throw error
                                        current()
                                        val seconds = (error as? RetryableDownloadException)?.retryAfterSeconds
                                        delay(seconds?.times(1000) ?: (500L shl attempt))
                                        attempt++
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE") error("Transfer did not finish")
                            }) { parts, speed, total -> persist(plan, parts, speed, total) }
                            val parts = TransferPlanning.parts(plan)
                            processor.materialize(plan.primary, plan.audio, { resource, target ->
                                current()
                                val part = parts.singleOrNull { it.resource == resource } ?: error("Unplanned local media resource")
                                val source = File(directory, part.key)
                                MediaStorageSpace.requireAvailable(target, source.length())
                                source.inputStream().use { inputStream -> target.outputStream().use { output ->
                                    MediaStorageSpace.copy(inputStream, output, target) { current() }
                                } }
                            }, published)
                        }
                        current()
                        require(!destination.exists()) { "The destination was created by another writer." }
                        check(published.isFile && published.length() > 0 && published.renameTo(destination)) { "Could not publish the complete media file." }
                        complete = true
                        YouTubeDownloadResult(destination.absolutePath, destination.length(),
                            if (input.audio == null && input.primary.segments.isEmpty()) input.primary.mimeType.substringBefore(';')
                            else if (input.primary.role != TrackRole.AUDIO) "video/mp4" else "audio/mp4")
                    } finally {
                        withContext(NonCancellable) { disconnect.cancelAndJoin(); active.getAndSet(null)?.disconnect() }
                    }
                }
            } finally {
                fileLock.release(); lockFile.close()
                if (complete) directory.deleteRecursively()
            }
        } finally { activeDestinations.remove(key) }
    }

    @Serializable private data class FileCheckpoint(val planDigest: String, val parts: List<PartState>,
        val sabrCheckpoint: SabrTransferCheckpoint? = null)
}
