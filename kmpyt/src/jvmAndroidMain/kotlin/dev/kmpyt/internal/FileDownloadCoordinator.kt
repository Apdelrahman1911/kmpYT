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
    private companion object {
        val activeDestinations = ConcurrentHashMap<String, Boolean>()
        const val MAX_CHECKPOINT_BYTES = 8_000_000
        val SHA256 = Regex("[0-9a-f]{64}")
    }

    suspend fun download(input: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult = withContext(Dispatchers.IO) {
        require(destinationPath.isNotBlank()) { "Choose a destination file." }
        YouTubeDownloadPolicy.requirePlan(input)
        require(input.credentialRef == null) { "This adapter supports anonymous inspected resources; account credentials are not accepted." }
        require(input.sabrCheckpoint == null) { "An external SABR checkpoint has no owned track files. Retry the original destination instead." }
        YouTubeFormatCapabilities.downloadProblem(input.primary, input.audio, platformName)?.let { error(it) }
        val requested = File(destinationPath).absoluteFile
        require(requested.name.isNotBlank() && requested.name !in setOf(".", "..")) { "Choose a destination file." }
        // Resolve intentional caller-parent aliases (for example Android app directories or
        // macOS /var), but never erase a link at the destination leaf before checking it.
        val parent = requireNotNull(requested.parentFile).canonicalFile
        val destination = File(parent, requested.name)
        val key = destination.path.let { if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) it.lowercase(Locale.ROOT) else it }
        check(activeDestinations.putIfAbsent(key, true) == null) { "Another download is using this destination." }
        try {
            requirePrivateCheckpointPath(destination, allowMissing = true)
            require(!destination.exists()) { "The destination already exists; choose another file." }
            requirePrivateCheckpointPath(parent, allowMissing = true)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create the destination directory." }
            val directory = File(parent, ".${destination.name}.kmpyt")
            requirePrivateCheckpointPath(directory, allowMissing = true)
            check(directory.isDirectory || directory.mkdir()) { "Could not create download checkpoint storage." }
            // Inspect the entire existing tree before even opening the lock for
            // writing. Future/corrupt-schema resets must not follow child links.
            checkpointTree(directory)
            val lockPath = File(directory, "lock")
            requirePrivateCheckpointPath(lockPath, allowMissing = true)
            val lockFile = RandomAccessFile(lockPath, "rw")
            val fileLock = try { lockFile.channel.tryLock() } catch (_: Exception) { null }
            if (fileLock == null) { lockFile.close(); error("Another download is using this destination.") }
            var complete = false
            try {
                requirePrivateCheckpointPath(lockPath)
                checkpointTree(directory)
                val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(PlannedDownload.serializer(),
                    input.copy(sabrCheckpoint = null, sabrDiagnostics = emptyList())).encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val checkpointFile = File(directory, "checkpoint.json")
                val selectionDigest = progressiveSelectionDigest(input)
                val resourceScopes = if (selectionDigest == null) emptyMap() else TransferPlanning.parts(input).associate {
                    it.key to progressiveResourceScope(it.resource.url, input.mediaId, allowLocalHttp)
                }
                requirePrivateCheckpointPath(checkpointFile, allowMissing = true)
                val decoded = runCatching {
                    require(checkpointFile.length() <= MAX_CHECKPOINT_BYTES)
                    json.decodeFromString(FileCheckpoint.serializer(), checkpointFile.readText())
                }.getOrNull()
                val restored = decoded?.takeIf {
                    // Raw legacy SABR checkpoints contain endpoint/configuration/session
                    // state and no local byte hashes. Purge them, never replay or re-save.
                    if (input.primary.transport == TransportKind.SABR) it.schema == 3 && it.planDigest == digest
                    else
                    // Legacy progressive checkpoints remain exact-plan only.
                    when (it.schema) {
                        1 -> it.planDigest == digest
                        2, 3 -> if (selectionDigest == null) it.planDigest == digest
                            else it.selectionDigest == selectionDigest && it.resourceScopes == resourceScopes
                        else -> false
                    }
                }
                if (restored == null) deleteCheckpointTree(directory, keepLock = true)
                val sabrReceipts = if (input.primary.transport == TransportKind.SABR) SabrFileReceipts(directory, input) else null
                val sabrCheckpoint = sabrReceipts?.restore(restored?.sabrReceipt)
                var plan = input.copy(sabrCheckpoint = sabrCheckpoint)
                val strictResume = selectionDigest != null && restored?.schema != 1
                var prefixDigests = restored?.prefixDigests.orEmpty()
                var responseScopes = restored?.responseScopes.orEmpty()
                val expectedParts = if (plan.primary.transport == TransportKind.SABR) emptyMap()
                    else TransferPlanning.parts(plan).associateBy { it.key }
                var states = if (sabrReceipts != null) sabrCheckpoint?.partStates(plan).orEmpty() else restored?.parts.orEmpty().mapNotNull { state ->
                    val file = File(directory, state.key)
                    // Only keys authored by TransferPlanning are allowed to name local files.
                    require(Regex("\\d+-\\d+").matches(state.key)) { "Invalid local checkpoint resource key." }
                    requirePrivateCheckpointPath(file, allowMissing = true)
                    if (!file.isFile) null else {
                        val expected = requireNotNull(expectedParts[state.key]) { "The checkpoint does not belong to the selected media parts." }.expectedBytes
                        val length = file.length()
                        if (expected != null && (length > expected || state.total != null && state.total != expected ||
                                state.complete && length != expected)) {
                            // A legacy/corrupted completed checkpoint must not skip the exact-size
                            // transport check and publish an incomplete inspected representation.
                            check(file.delete()) { "Could not discard an incompatible media checkpoint." }
                            null
                        } else if (strictResume) {
                            val receipt = prefixDigests[state.key]
                            if (state.bytes <= 0 || length < state.bytes || state.total?.let { it < state.bytes } == true ||
                                strongResumeEtag(state.etag) == null || receipt?.let(SHA256::matches) != true ||
                                responseScopes[state.key]?.let(SHA256::matches) != true) {
                                check(file.delete()) { "Could not discard an unverifiable media checkpoint." }
                                null
                            } else {
                                // Never adopt bytes written after the last committed receipt.
                                if (length > state.bytes) RandomAccessFile(file, "rw").use { it.setLength(state.bytes); it.fd.sync() }
                                if (prefixDigest(file, state.bytes) != receipt) {
                                    check(file.delete()) { "Could not discard a changed private media prefix." }
                                    null
                                } else state.copy(complete = false) // Revalidate complete retained files as well.
                            }
                        } else state.copy(bytes = length, complete = state.complete && length == state.bytes)
                    }
                }
                val active = AtomicReference<HttpURLConnection?>(null)
                suspend fun current(): Boolean { currentCoroutineContext().ensureActive(); return true }
                suspend fun persist(updated: PlannedDownload, parts: List<PartState>, speed: Long, total: Long?) {
                    current()
                    plan = updated; states = parts
                    val temporary = File(directory, "checkpoint.tmp")
                    requirePrivateCheckpointPath(temporary, allowMissing = true)
                    requirePrivateCheckpointPath(checkpointFile, allowMissing = true)
                    val bytes = json.encodeToString(FileCheckpoint.serializer(),
                        FileCheckpoint(digest, parts, sabrReceipt = updated.sabrCheckpoint?.let { requireNotNull(sabrReceipts).snapshot(it) }, schema = 3,
                            selectionDigest = selectionDigest, resourceScopes = resourceScopes,
                            prefixDigests = prefixDigests.filterKeys { key -> parts.any { it.key == key && it.bytes > 0 } },
                            responseScopes = responseScopes.filterKeys { key -> parts.any { it.key == key && it.bytes > 0 } })).encodeToByteArray()
                    check(bytes.size <= MAX_CHECKPOINT_BYTES) { "The download checkpoint exceeds its metadata bound." }
                    RandomAccessFile(temporary, "rw").use { it.setLength(0); it.write(bytes); it.fd.sync() }
                    check(!checkpointFile.exists() || checkpointFile.delete()) { "Could not replace download checkpoint." }
                    check(temporary.renameTo(checkpointFile)) { "Could not persist download checkpoint." }
                    onProgress(YouTubeDownloadProgress(MediaStorageSpace.checkedSum(parts.map { it.bytes }), total, speed))
                }
                val published = File(directory, "published.mp4")
                requirePrivateCheckpointPath(published, allowMissing = true)
                check(!published.exists() || published.delete()) { "Could not reset private output storage." }
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
                                        requirePrivateCheckpointPath(file, allowMissing = true)
                                        val result = if (part.resource.rangeStart != null)
                                            RangedFileTransfer(allowLocalHttp, { active.set(it) }).fetch(part, state, file, plan, ::current) { update, speed ->
                                                state = update; progress(update, speed)
                                            }
                                        else {
                                            val result = HttpDownloadTransfer(allowLocalHttp, plan.requestHeaders,
                                                refreshOnExpiry = true, onConnection = { active.set(it) }).fetch(
                                                FileTransferRequest(part.resource.url, plan, state.total, state.etag, state.lastModified,
                                                    expectedBytes = part.expectedBytes, requireStrongResume = strictResume,
                                                    resumeResponseScope = responseScopes[part.key]),
                                                file, ::current) {
                                                    prefixDigests = if (it.prefixDigest == null) prefixDigests - part.key else prefixDigests + (part.key to it.prefixDigest)
                                                    responseScopes = if (it.responseScope == null) responseScopes - part.key else responseScopes + (part.key to it.responseScope)
                                                    state = PartState(part.key, it.bytes, it.total, it.etag, it.lastModified)
                                                    progress(state, it.speedBytesPerSecond)
                                                }
                                            prefixDigests = if (result.prefixDigest == null) prefixDigests - part.key else prefixDigests + (part.key to result.prefixDigest)
                                            responseScopes = if (result.responseScope == null) responseScopes - part.key else responseScopes + (part.key to result.responseScope)
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
                                requirePrivateCheckpointPath(source)
                                requirePrivateCheckpointPath(target, allowMissing = true)
                                MediaStorageSpace.requireAvailable(target, source.length())
                                source.inputStream().use { inputStream -> target.outputStream().use { output ->
                                    MediaStorageSpace.copy(inputStream, output, target) { current() }
                                } }
                            }, published)
                        }
                        current()
                        requirePrivateCheckpointPath(destination, allowMissing = true)
                        require(!destination.exists()) { "The destination was created by another writer." }
                        requirePrivateCheckpointPath(published)
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
                try {
                    // Keep the reusable lock inode and its parent. Deleting them
                    // after unlock (or unlinking a held lock) lets a later process
                    // lock a different inode while an earlier owner still works.
                    if (complete) withContext(NonCancellable) { deleteCheckpointTree(directory, keepLock = true) }
                } finally {
                    try { fileLock.release() } finally { lockFile.close() }
                }
            }
        } finally { activeDestinations.remove(key) }
    }

    @Serializable private data class FileCheckpoint(val planDigest: String, val parts: List<PartState>,
        val sabrReceipt: SabrSavedReceipt? = null, val schema: Int = 1,
        val selectionDigest: String? = null, val resourceScopes: Map<String, String> = emptyMap(),
        val prefixDigests: Map<String, String> = emptyMap(), val responseScopes: Map<String, String> = emptyMap())
}
