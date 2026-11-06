@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.internal.SabrSavedReceipt
import dev.kmpyt.internal.SabrSavedTrack
import dev.kmpyt.internal.resumeResourceIdentity
import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.posix.*

/** Never stores request URLs, headers, client/session contexts, or raw response validators. */
@Serializable internal data class IosResumeValidator(val kind: String, val digest: String)
@Serializable internal data class IosSavedPart(val key: String, val resourceDigest: String, val scopeDigest: String, val bytes: Long = 0,
    val total: Long? = null, val complete: Boolean = false, val validator: IosResumeValidator? = null,
    val objectTotalBytes: Long? = null, val prefixDigest: String? = null, val responseScope: String? = null)
internal typealias IosSavedSabrTrack = SabrSavedTrack
internal typealias IosSavedSabr = SabrSavedReceipt
// The old writer omitted its default schema=1. Keep that decoding default so a
// schema-less legacy checkpoint cannot accidentally be interpreted as schema 2.
@Serializable private data class IosSavedDownload(val schema: Int = 1, val selectionDigest: String,
    val parts: List<IosSavedPart> = emptyList(), val sabr: IosSavedSabr? = null)

internal fun iosDigest(text: String): String = IosResumeSha256().apply { update(text.encodeToByteArray()) }.snapshot()

internal fun iosSelectionDigest(plan: PlannedDownload): String {
    fun track(value: Representation): JsonObject {
        val fields = Json.encodeToJsonElement(Representation.serializer(), value).jsonObject.toMutableMap()
        listOf("url", "manifestUrl", "expiresAtEpochMs", "manifestBody", "unavailableReason").forEach(fields::remove)
        fun resource(value: JsonElement) = JsonObject(value.jsonObject.filterKeys { it != "url" })
        fields["segments"]?.let { fields["segments"] = JsonArray(it.jsonArray.map(::resource)) }
        fields["initialization"]?.takeUnless { it is JsonNull }?.let { fields["initialization"] = resource(it) }
        fields["sabr"]?.takeUnless { it is JsonNull }?.let {
            fields["sabr"] = JsonObject(it.jsonObject.filterKeys { key -> key in setOf("videoId", "formatId", "clientInfo") })
        }
        return JsonObject(fields)
    }
    return iosDigest(buildJsonObject {
        put("mediaId", plan.mediaId)
        put("source", YouTubeDownloadPolicy.videoId(plan.inspectionUrl) ?: plan.inspectionUrl)
        put("primary", track(plan.primary))
        plan.audio?.let { put("audio", track(it)) }
        put("headers", JsonObject(plan.requestHeaders.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) }))
    }.toString())
}

internal fun iosResourceScope(part: TransferPart, mediaId: String, allowLocalHttp: Boolean = false): String =
    iosResourceScope(part.resource.url, mediaId, allowLocalHttp)
internal fun iosResourceScope(url: String, mediaId: String, allowLocalHttp: Boolean = false): String =
    iosDigest(resumeResourceIdentity(url, mediaId, allowLocalHttp))

internal fun iosResponseValidator(etag: String?, modified: String?, date: String?): IosResumeValidator? {
    val tag = etag?.trim()?.takeIf { it.length in 2..8192 && it.startsWith('"') && it.endsWith('"') &&
        it.substring(1, it.lastIndex).all { c -> c.code == 0x21 || c.code in 0x23..0x7e || c.code in 0x80..0xff } }
    if (tag != null) return IosResumeValidator("etag", iosDigest(tag))
    // A response at least 60 seconds newer makes Last-Modified a strong date validator.
    fun parse(value: String?): Double? {
        if (value == null || value.length > 128) return null
        val formatter = NSDateFormatter()
        formatter.locale = NSLocale(localeIdentifier = "en_US_POSIX")
        formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return formatter.dateFromString(value)?.timeIntervalSince1970
    }
    val last = parse(modified) ?: return null
    val served = parse(date) ?: return null
    return if (served - last >= 60) IosResumeValidator("last-modified", iosDigest(last.toLong().toString())) else null
}

/**
 * Stable caller-private storage. The reusable lock inode/parent are never unlinked by this store,
 * including on success. Earlier dependent close uncertainty retains the held OS lock until process
 * exit, not just an in-process destination fence. Same-user hostile directory mutation is outside
 * this ownership contract; a lock-release syscall error cannot prove continued cross-process fencing.
 */
internal class IosDownloadResumeStore private constructor(val destination: String, val directory: String,
    private val lockDescriptor: Int, private val selectionDigest: String) {
    private val json = Json { ignoreUnknownKeys = false }
    private var saved = IosSavedDownload(schema = 2, selectionDigest = selectionDigest)
    private var loaded = false
    private var closed = false
    private var closeFailure: Throwable? = null
    private var ownershipFailure: Throwable? = null
    val parts: List<IosSavedPart> get() = saved.parts
    val sabr: IosSavedSabr? get() = saved.sabr

    suspend fun load(plan: PlannedDownload, allowedParts: List<TransferPart>, allowLocalHttp: Boolean = false) = ownedIo {
        checkUsable()
        val decoded = try {
            read("checkpoint.json", MAX_CHECKPOINT)?.decodeToString()?.let { json.decodeFromString(IosSavedDownload.serializer(), it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) { if (iosReleaseUncertain(invalid)) throw invalid else null }
        val allowed = allowedParts.associateBy { it.key }
        val valid = decoded != null && decoded.schema == 2 && decoded.selectionDigest == selectionDigest &&
            decoded.parts.map { it.key }.distinct().size == decoded.parts.size && decoded.parts.all {
                it.key in allowed && it.bytes >= 0 && (it.total == null || it.total >= it.bytes) &&
                    (it.objectTotalBytes == null || it.objectTotalBytes >= 0) &&
                    (!it.complete || it.total == it.bytes) && DIGEST.matches(it.resourceDigest) && DIGEST.matches(it.scopeDigest) &&
                    (it.validator == null || it.validator.kind in setOf("etag", "last-modified") && DIGEST.matches(it.validator.digest)) &&
                    (it.bytes == 0L || it.prefixDigest?.let(DIGEST::matches) == true && it.responseScope?.let(DIGEST::matches) == true)
            } && (decoded.sabr == null || DIGEST.matches(decoded.sabr.sourceDigest))
        if (!valid) {
            withContext(NonCancellable) { clearMedia() }
            saved = IosSavedDownload(schema = 2, selectionDigest = selectionDigest)
            loaded = true
            return@ownedIo
        }
        val candidate = requireNotNull(decoded)
        val retained = mutableListOf<IosSavedPart>()
        for (part in candidate.parts) {
            currentCoroutineContext().ensureActive()
            val path = partPath(part.key)
            val length = fileLength(path)
            if (part.scopeDigest != iosResourceScope(allowed.getValue(part.key), plan.mediaId, allowLocalHttp) ||
                part.bytes <= 0 || part.validator == null || length == null || length < part.bytes ||
                allowed.getValue(part.key).expectedBytes?.let { part.total != null && part.total != it || part.bytes > it } == true) {
                remove(path)
                continue
            }
            val file = IosMediaFile(path, existing = true)
            var failure: Throwable? = null
            val digest = try {
                // Only the acknowledged prefix can survive an interrupted checkpoint commit.
                if (length > part.bytes) { file.truncate(part.bytes); file.flush() }
                iosPrefixHasher(file, part.bytes).snapshot()
            } catch (error: Throwable) { failure = error; throw error }
            finally { iosCleanup(failure, { file.close() }) }
            if (digest != part.prefixDigest) { remove(path); continue }
            retained += part.copy(complete = false) // Even a fully retained file is response-revalidated.
        }
        // A cancelled hash must not leave the default empty saved state authorizing deletion.
        saved = candidate.copy(parts = retained)
        loaded = true
        removeProcessingFiles()
    }

    fun partPath(key: String): String {
        require(Regex("\\d+-\\d+").matches(key))
        return "$directory/part-$key.mp4"
    }

    fun fileLength(path: String): Long? = regularLength(path)

    suspend fun savePart(value: IosSavedPart) = withContext(NonCancellable) {
        checkUsable()
        update(saved.copy(parts = saved.parts.filterNot { it.key == value.key } + value))
    }

    suspend fun resetPart(key: String) = withContext(NonCancellable) {
        checkUsable()
        update(saved.copy(parts = saved.parts.filterNot { it.key == key }))
        remove(partPath(key))
    }

    suspend fun saveSabr(value: IosSavedSabr) = withContext(NonCancellable) {
        checkUsable()
        if (saved.sabr != value) update(saved.copy(sabr = value))
    }

    suspend fun resetSabr(removeFiles: Boolean = true) = withContext(NonCancellable) {
        checkUsable()
        update(saved.copy(sabr = null))
        if (removeFiles) removeChildren(listOf("sabr-0.mp4", "sabr-1.mp4"))
    }

    private suspend fun update(next: IosSavedDownload) = ownedIo {
        checkUsable()
        check(loaded)
        val bytes = json.encodeToString(IosSavedDownload.serializer(), next).encodeToByteArray()
        check(bytes.size <= MAX_CHECKPOINT) { "The download checkpoint exceeds its metadata bound." }
        checkedChildren(directory)
        val temporary = "$directory/checkpoint.tmp"
        remove(temporary)
        val file = IosMediaFile(temporary)
        var failure: Throwable? = null
        try { file.write(0, bytes); file.flush() }
        catch (error: Throwable) { failure = error; throw error }
        finally { iosCleanup(failure, { file.close() }) }
        if (rename(temporary, "$directory/checkpoint.json") != 0) throw IosMediaStorageException("Could not commit the download checkpoint.")
        val fd = platform.posix.open(directory, O_RDONLY or O_NOFOLLOW)
        if (fd < 0) throw IosMediaStorageException("Could not open the checkpoint directory for commit.")
        failure = null
        try {
            if (fsync(fd) != 0) throw IosMediaStorageException("Could not commit the checkpoint directory.")
        } catch (error: Throwable) { failure = error; throw error }
        finally { iosCleanup(failure, { iosCloseCheckpointDescriptor(fd) }) }
        saved = next
    }

    private suspend fun read(name: String, maximum: Int): ByteArray? = ownedIo {
        val path = "$directory/$name"
        val size = fileLength(path) ?: return@ownedIo null
        require(size <= maximum) { "The private checkpoint exceeds its size bound." }
        val file = IosMediaFile(path, existing = true)
        var failure: Throwable? = null
        try { file.read(0, size.toInt()) }
        catch (error: Throwable) { failure = error; throw error }
        finally { iosCleanup(failure, { file.close() }) }
    }

    private fun checkUsable() {
        check(!closed)
        ownershipFailure?.let { throw it }
    }

    private suspend fun <T> ownedIo(block: suspend () -> T): T {
        checkUsable()
        return try { block() }
        catch (error: Throwable) {
            // The protocol engine may publish one final checkpoint after a callback fails.
            // Never reopen or replace files whose earlier descriptor release was unconfirmed.
            if (iosReleaseUncertain(error) && ownershipFailure == null) ownershipFailure = error
            throw error
        }
    }

    private fun clearMedia() {
        removeChildren(checkedChildren(directory).filter { it != "owner" && it != "lock" })
    }

    private fun removeProcessingFiles() {
        removeChildren(checkedChildren(directory).filter { it.startsWith("assembled-") || it == "output.mp4" || it == "checkpoint.tmp" })
    }

    private fun removeChildren(names: List<String>) =
        iosCleanup(null, *names.map<String, () -> Unit> { name -> { remove("$directory/$name") } }.toTypedArray())

    fun close(completed: Boolean = false, discard: Boolean = false, priorFailure: Throwable? = null) {
        if (closed) { closeFailure?.let { throw it }; return }
        closed = true
        var released = false
        val earlierOwnershipFailure = priorFailure?.takeIf(::iosReleaseUncertain) ?: ownershipFailure
        val unconfirmedMediaRelease = earlierOwnershipFailure != null
        try {
            val cleanupFailure = iosCleanupFailure(earlierOwnershipFailure,
                { if (!unconfirmedMediaRelease) removeProcessingFiles() },
                {
                    val useful = !loaded || saved.parts.any { it.bytes > 0 && it.validator != null && it.prefixDigest != null } ||
                        saved.sabr?.tracks?.any { it.coverage.isNotEmpty() } == true
                    if (!unconfirmedMediaRelease && (completed || discard || !useful)) clearMedia()
                },
            )
            val result = iosReleaseCheckpointLock(lockDescriptor, held = true, earlierFailure = cleanupFailure)
            released = result.descriptorReleased
            result.failure?.let { throw it }
        } catch (error: Throwable) { closeFailure = error; throw error }
        finally {
            // Neither dependent uncertainty nor an uncertain lock close authorizes an in-process owner.
            if (released && !unconfirmedMediaRelease && !iosReleaseUncertain(closeFailure)) unregister(destination)
        }
    }

    companion object {
        private const val MAX_CHECKPOINT = 8_000_000
        private const val MAX_ENTRIES = 8_192
        private const val OWNER = "kmpyt-ios-resume-v1"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val destinations = NSLock()
        private val activeDestinations = mutableSetOf<String>()

        suspend fun open(path: String, selectionDigest: String): IosDownloadResumeStore {
            require(path.startsWith('/') && '\u0000' !in path && !path.endsWith('/') && path.split('/').none { it == "." || it == ".." }) {
                "Choose an absolute local destination file path."
            }
            val manager = NSFileManager.defaultManager
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            check(manager.createDirectoryAtPath(parent, true, null, null)) { "Could not create the destination directory." }
            val canonicalParent = canonical(parent)
            val destination = canonicalParent.trimEnd('/') + "/" + path.substringAfterLast('/')
            destinations.lock()
            try { check(activeDestinations.add(destination)) { "Another download is already using this destination." } }
            finally { destinations.unlock() }
            var fd = -1
            var heldLock = false
            try {
                val directory = canonicalParent.trimEnd('/') + "/.kmpyt-" + iosDigest(destination)
                if (mkdir(directory, 448.convert()) != 0 && errno != EEXIST) throw IosMediaStorageException("Could not create private download storage.")
                check(canonical(directory) == directory) { "The checkpoint directory must not be a symbolic link." }
                // Reject FIFO/symlink/special entries and excessive trees BEFORE opening anything writable.
                checkedChildren(directory)
                fd = platform.posix.open("$directory/lock", O_CREAT or O_RDWR or O_NOFOLLOW or O_NONBLOCK, 384)
                check(fd >= 0) { "Could not open the private checkpoint lock." }
                memScoped {
                    val held = alloc<stat>()
                    val current = alloc<stat>()
                    check(fstat(fd, held.ptr) == 0 && lstat("$directory/lock", current.ptr) == 0 &&
                        (held.st_mode.toInt() and S_IFMT) == S_IFREG &&
                        held.st_dev == current.st_dev && held.st_ino == current.st_ino) {
                        "The destination's private storage changed while acquiring its lock. Retry the download."
                    }
                }
                check(flock(fd, LOCK_EX or LOCK_NB) == 0) { "Another download is already using this destination." }
                heldLock = true
                val store = IosDownloadResumeStore(destination, directory, fd, selectionDigest)
                val names = checkedChildren(directory)
                val owner = store.read("owner", 128)?.decodeToString()
                if (owner == null) {
                    check(names.all { it == "lock" }) { "The reserved download directory has no valid owner marker." }
                    withContext(NonCancellable) {
                        val file = IosMediaFile("$directory/owner")
                        var failure: Throwable? = null
                        var released = false
                        try { file.write(0, OWNER.encodeToByteArray()); file.flush() }
                        catch (error: Throwable) { failure = error; throw error }
                        finally {
                            iosCleanup(failure,
                                { file.close(); released = true },
                                { if (failure != null && released && !iosReleaseUncertain(failure)) remove("$directory/owner") },
                            )
                        }
                    }
                } else check(owner == OWNER) { "The reserved download directory belongs to another format." }
                return store
            } catch (failure: Throwable) {
                val result = iosReleaseCheckpointLock(fd, heldLock, failure)
                if (result.descriptorReleased && !iosReleaseUncertain(result.failure)) unregister(destination)
                throw requireNotNull(result.failure)
            }
        }

        private fun unregister(destination: String) {
            destinations.lock()
            try { activeDestinations.remove(destination) } finally { destinations.unlock() }
        }

        private fun checkedChildren(directory: String): List<String> {
            memScoped {
                val info = alloc<stat>()
                check(lstat(directory, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFDIR) {
                    "The checkpoint root must be a private directory."
                }
            }
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
                ?.map { it as String } ?: throw IosMediaStorageException("Could not inspect private checkpoint storage.")
            check(names.size <= MAX_ENTRIES && names.all { it.length <= 96 &&
                (it in setOf("owner", "lock", "checkpoint.json", "checkpoint.tmp", "output.mp4") ||
                    Regex("(?:part-\\d+-\\d+|sabr-[01]|assembled-[01])\\.mp4").matches(it)) }) {
                "The reserved download directory contains unrelated or excessive files."
            }
            names.forEach { check(regularLength("$directory/$it") != null) { "The checkpoint entry disappeared while inspecting storage." } }
            return names
        }

        private fun regularLength(path: String): Long? = memScoped {
            val info = alloc<stat>()
            if (lstat(path, info.ptr) != 0) {
                if (errno == ENOENT) return@memScoped null
                throw IosMediaStorageException("Could not inspect private media storage.")
            }
            check((info.st_mode.toInt() and S_IFMT) == S_IFREG && info.st_size >= 0) {
                "The reserved download directory contains a non-regular file."
            }
            info.st_size
        }

        private fun remove(path: String) {
            if (regularLength(path) == null) return
            if (unlink(path) != 0 && errno != ENOENT) throw IosMediaStorageException("Could not remove private checkpoint data.")
        }

        private fun canonical(path: String): String = memScoped {
            val buffer = allocArray<ByteVar>(PATH_MAX)
            realpath(path, buffer)?.toKString() ?: throw IosMediaStorageException("Could not resolve the destination directory.")
        }
    }
}
