@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.YouTubeSabrTrackSink
import kotlinx.cinterop.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.Foundation.*
import platform.posix.*

/** Local failures deliberately are not network IOExceptions: SABR must never retry a failed disk write. */
internal class IosMediaStorageException(message: String) : Exception(message)

internal fun requireIosStorage(path: String, additionalBytes: Long) {
    require(additionalBytes >= 0)
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null)
        ?: throw IosMediaStorageException("Could not determine available storage.")
    val free = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue
        ?: throw IosMediaStorageException("Could not determine available storage.")
    if (free < additionalBytes) throw IosMediaStorageException("Not enough free storage for the selected media.")
}

internal fun checkedIosSum(values: Iterable<Long>): Long = values.fold(0L) { total, bytes ->
    require(bytes >= 0 && bytes <= Long.MAX_VALUE - total) { "Media size exceeds the supported byte-count range." }
    total + bytes
}

/** Bounded buffers, 64-bit offsets and errno-based failures avoid Objective-C file-write exceptions. */
internal class IosMediaFile(val path: String, private val expected: Long? = null,
    private val durableWrites: Boolean = false, existing: Boolean = false) : YouTubeSabrTrackSink {
    private var descriptor = open(path, O_RDWR or O_NOFOLLOW or (if (existing) 0 else (O_CREAT or O_EXCL)), 384)
    private var storageAllowance = 0L
    private var closeFailure: IosMediaStorageException? = null
    var storageFailure: IosMediaStorageException? = null
        private set
    init { if (descriptor < 0) throw IosMediaStorageException("Could not create private media storage.") }

    override suspend fun size(): Long {
        currentCoroutineContext().ensureActive()
        return length()
    }

    fun length(): Long {
        check(descriptor >= 0) { "Media file is closed." }
        val value = lseek(descriptor, 0, SEEK_END)
        if (value < 0) throw IosMediaStorageException("Could not inspect private media storage.")
        return value
    }

    override suspend fun read(offset: Long, count: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        require(offset >= 0 && count >= 0 && offset <= Long.MAX_VALUE - count && offset + count <= length())
        val result = ByteArray(count)
        if (count == 0) return result
        result.usePinned { pinned ->
            var copied = 0
            while (copied < count) {
                currentCoroutineContext().ensureActive()
                val amount = pread(descriptor, pinned.addressOf(copied), (count - copied).convert(), offset + copied)
                if (amount < 0 && errno == EINTR) continue
                if (amount <= 0) throw IosMediaStorageException("Could not read the complete private media range.")
                copied += amount.toInt()
            }
        }
        return result
    }

    override suspend fun write(offset: Long, bytes: ByteArray) = write(offset, bytes, bytes.size)

    suspend fun write(offset: Long, bytes: ByteArray, count: Int) {
        try { writeBytes(offset, bytes, count) }
        catch (failure: IosMediaStorageException) { storageFailure = failure; throw failure }
    }

    private suspend fun writeBytes(offset: Long, bytes: ByteArray, count: Int) {
        currentCoroutineContext().ensureActive()
        check(descriptor >= 0) { "Media file is closed." }
        require(count in 1..bytes.size && offset >= 0 && offset <= Long.MAX_VALUE - count)
        require(expected == null || offset + count <= expected) { "Source sent more media than its declared track size." }
        if (storageAllowance < count) {
            // Recheck actual disk capacity periodically; native writes still report intervening disk exhaustion.
            val allowance = maxOf(count.toLong(), 4L * 1024 * 1024)
            requireIosStorage(path.substringBeforeLast('/'), count.toLong())
            storageAllowance = allowance
        }
        bytes.usePinned { pinned ->
            var copied = 0
            while (copied < count) {
                currentCoroutineContext().ensureActive()
                val amount = pwrite(descriptor, pinned.addressOf(copied), (count - copied).convert(), offset + copied)
                if (amount < 0 && errno == EINTR) continue
                if (amount <= 0) throw IosMediaStorageException(
                    if (errno == ENOSPC) "Not enough free storage for the selected media." else "Could not write private media storage.")
                copied += amount.toInt()
            }
        }
        storageAllowance -= count
        // Each SABR acknowledged write is durable before the shared engine publishes its checkpoint.
        if (durableWrites) flush()
    }

    fun flush() {
        if (fsync(descriptor) != 0) throw IosMediaStorageException("Could not commit private media storage.")
    }

    fun truncate(bytes: Long) {
        require(bytes >= 0 && (expected == null || bytes <= expected))
        if (ftruncate(descriptor, bytes) != 0) throw IosMediaStorageException("Could not reset private media storage.")
        storageAllowance = 0
    }

    fun close() {
        closeFailure?.let { throw it }
        if (descriptor >= 0) {
            val owned = descriptor
            descriptor = -1
            // A failed close leaves descriptor ownership uncertain. Do not
            // retry a numeric descriptor that the OS may already have reused.
            if (platform.posix.close(owned) != 0) {
                val failure = IosMediaStorageException("Could not confirm release of private media storage.")
                closeFailure = failure
                throw failure
            }
        }
    }
}
