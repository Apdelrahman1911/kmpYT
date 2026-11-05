@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.http.Url
import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.*
import platform.posix.*

/** Never stores request URLs, headers, client/session contexts, or raw response validators. */
@Serializable internal data class IosResumeValidator(val kind: String, val digest: String)
@Serializable internal data class IosSavedPart(val key: String, val resourceDigest: String, val scopeDigest: String, val bytes: Long = 0,
    val total: Long? = null, val complete: Boolean = false, val validator: IosResumeValidator? = null,
    val objectTotalBytes: Long? = null)
@Serializable internal data class IosSavedSabrTrack(val index: Int, val expectedBytes: Long,
    val coverage: List<SabrTransferByteRange>, val completedSegments: List<SabrTransferSegment>)
@Serializable internal data class IosSavedSabr(val sourceDigest: String, val tracks: List<IosSavedSabrTrack>)
@Serializable private data class IosSavedDownload(val schema: Int = 1, val selectionDigest: String,
    val parts: List<IosSavedPart> = emptyList(), val sabr: IosSavedSabr? = null)

internal fun iosDigest(text: String): String {
    val bytes = text.encodeToByteArray()
    val buffer = bytes.takeIf { it.isNotEmpty() } ?: byteArrayOf(0)
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    buffer.usePinned { input -> digest.usePinned { output ->
        CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0).reinterpret())
    } }
    return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

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

internal fun iosResourceScope(part: TransferPart, mediaId: String): String {
    val url = Url(part.resource.url)
    val googleVideo = url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")
    val signing = setOf("sig", "signature", "expire", "expires") + if (googleVideo)
        setOf("lsig", "sparams", "lsparams", "n", "ei", "ip", "ipbits", "spc", "pot") else emptySet()
    return iosDigest(buildJsonObject {
        put("media", mediaId)
        put("origin", "${url.protocol.name}://${if (googleVideo) "googlevideo.com" else url.host}:${url.port}")
        put("path", url.encodedPath)
        put("query", JsonObject(url.parameters.entries().filterNot { it.key.lowercase() in signing }.sortedBy { it.key }
            .associate { it.key to JsonArray(it.value.map(::JsonPrimitive)) }))
    }.toString())
}

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

/** A stable app-private staging directory and advisory lock survive process termination. */
internal class IosDownloadResumeStore private constructor(val destination: String, val directory: String,
    private val lockDescriptor: Int, private val selectionDigest: String) {
    private val manager = NSFileManager.defaultManager
    private val json = Json { ignoreUnknownKeys = false }
    private var saved = IosSavedDownload(selectionDigest = selectionDigest)
    private var closed = false
    val parts: List<IosSavedPart> get() = saved.parts
    val sabr: IosSavedSabr? get() = saved.sabr

    suspend fun load(plan: PlannedDownload, allowedParts: List<TransferPart>) = withContext(NonCancellable) {
        val decoded = try {
            read("checkpoint.json", MAX_CHECKPOINT)?.decodeToString()?.let { json.decodeFromString(IosSavedDownload.serializer(), it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val allowed = allowedParts.associateBy { it.key }
        val valid = decoded != null && decoded.schema == 1 && decoded.selectionDigest == selectionDigest &&
            decoded.parts.map { it.key }.distinct().size == decoded.parts.size && decoded.parts.all {
                it.key in allowed && it.bytes >= 0 && (it.total == null || it.total >= it.bytes) &&
                    (it.objectTotalBytes == null || it.objectTotalBytes >= 0) &&
                    (!it.complete || it.total == it.bytes) && DIGEST.matches(it.resourceDigest) && DIGEST.matches(it.scopeDigest) &&
                    (it.validator == null || it.validator.kind in setOf("etag", "last-modified") && DIGEST.matches(it.validator.digest))
            } && (decoded.sabr == null || DIGEST.matches(decoded.sabr.sourceDigest))
        if (!valid) {
            clearMedia()
            saved = IosSavedDownload(selectionDigest = selectionDigest)
            return@withContext
        }
        saved = requireNotNull(decoded)
        val retained = mutableListOf<IosSavedPart>()
        for (part in saved.parts) {
            val path = partPath(part.key)
            val length = fileLength(path)
            if (part.scopeDigest != iosResourceScope(allowed.getValue(part.key), plan.mediaId) ||
                part.bytes <= 0 || part.validator == null || length == null || length < part.bytes ||
                allowed.getValue(part.key).expectedBytes?.let { part.total != null && part.total != it || part.bytes > it } == true) {
                manager.removeItemAtPath(path, null)
                continue
            }
            if (length > part.bytes) IosMediaFile(path, existing = true).let { file ->
                try { file.truncate(part.bytes); file.flush() } finally { file.close() }
            }
            retained += part
        }
        saved = saved.copy(parts = retained)
        removeProcessingFiles()
    }

    fun partPath(key: String): String {
        require(Regex("\\d+-\\d+").matches(key))
        return "$directory/part-$key.mp4"
    }

    fun fileLength(path: String): Long? {
        val attributes = manager.attributesOfItemAtPath(path, null) ?: return null
        if (attributes[NSFileType] != NSFileTypeRegular) return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue?.takeIf { it >= 0 }
    }

    suspend fun savePart(value: IosSavedPart) = withContext(NonCancellable) {
        saved = saved.copy(parts = saved.parts.filterNot { it.key == value.key } + value)
        persist()
    }

    suspend fun resetPart(key: String) = withContext(NonCancellable) {
        saved = saved.copy(parts = saved.parts.filterNot { it.key == key })
        persist()
        manager.removeItemAtPath(partPath(key), null)
    }

    suspend fun saveSabr(value: IosSavedSabr) = withContext(NonCancellable) {
        if (saved.sabr != value) { saved = saved.copy(sabr = value); persist() }
    }

    suspend fun resetSabr() = withContext(NonCancellable) {
        saved = saved.copy(sabr = null)
        persist()
        listOf("sabr-0.mp4", "sabr-1.mp4").forEach { manager.removeItemAtPath("$directory/$it", null) }
    }

    private suspend fun persist() {
        val bytes = json.encodeToString(IosSavedDownload.serializer(), saved).encodeToByteArray()
        check(bytes.size <= MAX_CHECKPOINT) { "The download checkpoint exceeds its metadata bound." }
        val temporary = "$directory/checkpoint.tmp"
        manager.removeItemAtPath(temporary, null)
        val file = IosMediaFile(temporary)
        try { file.write(0, bytes); file.flush() } finally { file.close() }
        if (rename(temporary, "$directory/checkpoint.json") != 0) throw IosMediaStorageException("Could not commit the download checkpoint.")
        val fd = platform.posix.open(directory, O_RDONLY)
        if (fd < 0) throw IosMediaStorageException("Could not open the checkpoint directory for commit.")
        try {
            if (fsync(fd) != 0) throw IosMediaStorageException("Could not commit the checkpoint directory.")
        } finally { platform.posix.close(fd) }
    }

    private suspend fun read(name: String, maximum: Int): ByteArray? {
        val path = "$directory/$name"
        val size = fileLength(path) ?: return null
        require(size <= maximum)
        val file = IosMediaFile(path, existing = true)
        return try { file.read(0, size.toInt()) } finally { file.close() }
    }

    private fun clearMedia() {
        children().filter { it != "owner" && it != "lock" }.forEach { manager.removeItemAtPath("$directory/$it", null) }
    }

    private fun removeProcessingFiles() {
        children().filter { it.startsWith("assembled-") || it == "output.mp4" || it == "checkpoint.tmp" }
            .forEach { manager.removeItemAtPath("$directory/$it", null) }
    }

    private fun children(): List<String> = manager.contentsOfDirectoryAtPath(directory, null).orEmpty().map { it as String }

    fun close(completed: Boolean = false, discard: Boolean = false) {
        if (closed) return
        closed = true
        try {
            removeProcessingFiles()
            val useful = saved.parts.any { it.bytes > 0 && it.validator != null } ||
                saved.sabr?.tracks?.any { it.coverage.isNotEmpty() } == true
            if (completed || discard || !useful) manager.removeItemAtPath(directory, null)
        } finally {
            flock(lockDescriptor, LOCK_UN)
            close(lockDescriptor)
            destinations.lock()
            try { activeDestinations.remove(destination) } finally { destinations.unlock() }
        }
    }

    companion object {
        private const val MAX_CHECKPOINT = 8_000_000
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
            try {
                val directory = canonicalParent.trimEnd('/') + "/.kmpyt-" + iosDigest(destination)
                if (!manager.fileExistsAtPath(directory)) check(manager.createDirectoryAtPath(directory, false, null, null)) {
                    "Could not create private download storage."
                }
                check(canonical(directory) == directory) { "The checkpoint directory must not be a symbolic link." }
                fd = open("$directory/lock", O_CREAT or O_RDWR or O_NOFOLLOW, 384)
                check(fd >= 0 && flock(fd, LOCK_EX or LOCK_NB) == 0) { "Another download is already using this destination." }
                memScoped {
                    val held = alloc<stat>()
                    val current = alloc<stat>()
                    check(fstat(fd, held.ptr) == 0 && lstat("$directory/lock", current.ptr) == 0 &&
                        held.st_dev == current.st_dev && held.st_ino == current.st_ino) {
                        "The destination's private storage changed while acquiring its lock. Retry the download."
                    }
                }
                val store = IosDownloadResumeStore(destination, directory, fd, selectionDigest)
                val names = store.children()
                check(names.all { it in setOf("owner", "lock", "checkpoint.json", "checkpoint.tmp", "output.mp4") ||
                    Regex("(?:part-\\d+-\\d+|sabr-[01]|assembled-[01])\\.mp4").matches(it) }) {
                    "The reserved download directory contains unrelated files."
                }
                check(names.all { store.fileLength("$directory/$it") != null }) {
                    "The reserved download directory contains a non-regular file."
                }
                val owner = store.read("owner", 128)?.decodeToString()
                if (owner == null) {
                    check(names.all { it == "lock" }) { "The reserved download directory has no valid owner marker." }
                    val file = IosMediaFile("$directory/owner")
                    try { withContext(NonCancellable) { file.write(0, OWNER.encodeToByteArray()); file.flush() } }
                    catch (failure: Throwable) { manager.removeItemAtPath("$directory/owner", null); throw failure }
                    finally { file.close() }
                } else check(owner == OWNER) { "The reserved download directory belongs to another format." }
                return store
            } catch (failure: Throwable) {
                if (fd >= 0) { flock(fd, LOCK_UN); close(fd) }
                destinations.lock()
                try { activeDestinations.remove(destination) } finally { destinations.unlock() }
                throw failure
            }
        }

        private fun canonical(path: String): String = memScoped {
            val buffer = allocArray<ByteVar>(PATH_MAX)
            realpath(path, buffer)?.toKString() ?: throw IosMediaStorageException("Could not resolve the destination directory.")
        }
    }
}
