package dev.kmpyt.internal



import dev.kmpyt.extraction.Representation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class InsufficientMediaStorageException : IOException("Not enough free storage to save this media. Free some space and retry.")
/** Not an IOException: a protocol adapter must not retry a local storage failure as a network interruption. */
internal class MediaStorageAbort : Exception("Native media storage is unavailable.")

/** Filesystem capacity checks, with no application-imposed media size ceiling. */
internal object MediaStorageSpace {
    fun storageFailure(error: IOException): InsufficientMediaStorageException? = error as? InsufficientMediaStorageException
    fun checkedSum(values: Iterable<Long>): Long = values.fold(0L) { sum, value ->
        require(value >= 0 && value <= Long.MAX_VALUE - sum) { "Media byte totals overflow the supported file-offset range." }
        sum + value
    }

    fun declaredBytes(tracks: List<Representation>): Long = checkedSum(tracks.map { track ->
        val declared = track.sizeBytes
        require(declared == null || declared >= 0) { "The declared media size is invalid." }
        val ranged = checkedSum((listOfNotNull(track.initialization) + track.segments).mapNotNull { it.rangeLength })
        maxOf(declared ?: 0L, ranged)
    })

    fun requireAvailable(path: File, additionalBytes: Long) {
        require(additionalBytes >= 0) { "The required storage size is invalid." }
        if (additionalBytes == 0L) return
        if (additionalBytes > existingDirectory(path).usableSpace) throw InsufficientMediaStorageException()
    }

    private fun existingDirectory(path: File): File {
        var existing: File? = if (path.isDirectory) path else path.parentFile
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return requireNotNull(existing) { "The media storage directory is unavailable." }
    }

    /** Deduct our writes from real capacity, and periodically observe other filesystem users. */
    fun monitor(path: File): WriteCapacity {
        val directory = existingDirectory(path)
        return WriteCapacity({ directory.usableSpace })
    }

    internal class WriteCapacity(private val availableBytes: () -> Long,
        private val nowNanos: () -> Long = System::nanoTime) {
        private var remaining = -1L
        private var untilRefresh = 0L
        private var checkedAt = 0L

        fun requireForWrite(bytes: Long) {
            require(bytes >= 0) { "The required storage size is invalid." }
            if (bytes == 0L) return
            val now = nowNanos()
            if (remaining < bytes || untilRefresh == 0L || now - checkedAt >= 1_000_000_000L) {
                remaining = availableBytes()
                checkedAt = now
                untilRefresh = 1024 * 1024L
            }
            if (bytes > remaining) throw InsufficientMediaStorageException()
            remaining -= bytes
            untilRefresh = (untilRefresh - minOf(bytes, untilRefresh)).coerceAtLeast(0)
        }
    }

    suspend fun copy(input: InputStream, output: OutputStream, destination: File, checkCurrent: suspend () -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        val capacity = monitor(destination)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            require(count.toLong() <= Long.MAX_VALUE - copied) { "Media byte totals overflow the supported file-offset range." }
            capacity.requireForWrite(count.toLong())
            output.write(buffer, 0, count)
            copied += count
        }
    }
}
