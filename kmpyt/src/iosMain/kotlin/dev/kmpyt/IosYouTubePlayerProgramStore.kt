@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.YouTubePlayerProgramLoader
import dev.kmpyt.extraction.YouTubePlayerProgramStore
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSinceReferenceDate
import platform.Foundation.writeToFile
import platform.posix.rename

/**
 * Keeps at most [maxEntries] exact player programs in an application-private [directory].
 *
 * Same semantics and file format as the JVM/Android `FileYouTubePlayerProgramStore`: each
 * entry records its declared URL and a SHA-256 of the program text; a mismatched, truncated,
 * non-UTF-8 or unreadable entry is ignored (and removed), never repaired. Writes go to a
 * temporary file in [directory] that is atomically renamed. Only player program text is stored.
 */
class IosYouTubePlayerProgramStore(
    private val directory: String,
    private val maxEntries: Int = 2,
) : YouTubePlayerProgramStore {
    private val lock = NSLock()
    private val manager get() = NSFileManager.defaultManager

    init { require(maxEntries in 1..8) { "Invalid player-program store bound" } }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    override fun read(url: String): String? = locked {
        val path = entry(url)
        val size = regularFileSize(path) ?: return@locked null
        if (size > MAX_ENTRY_BYTES) return@locked null
        val bytes = readBytes(path) ?: return@locked null
        // Invalid UTF-8 is corruption: rejected and removed, never decoded leniently.
        val text = decodeUtf8(bytes) ?: run { manager.removeItemAtPath(path, null); return@locked null }
        val first = text.indexOf('\n')
        val second = if (first < 0) -1 else text.indexOf('\n', first + 1)
        val third = if (second < 0) -1 else text.indexOf('\n', second + 1)
        val valid = third > 0 && text.substring(0, first) == FORMAT &&
            text.substring(first + 1, second) == url
        val program = if (valid) text.substring(third + 1) else null
        if (program == null || program.isEmpty() || program.length > YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS ||
            iosDigest(program) != text.substring(second + 1, third)) {
            manager.removeItemAtPath(path, null)
            return@locked null
        }
        // Touch so the bounded eviction keeps recently used programs.
        manager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate()), path, null)
        program
    }

    override fun write(url: String, program: String) {
        require(url.length in 1..4096 && url.none { it == '\n' || it == '\r' }) { "Invalid player-program URL" }
        require(program.length in 1..YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS) { "Invalid player-program size" }
        locked {
            manager.createDirectoryAtPath(directory, true, null, null)
            check(isDirectory(directory)) { "Player-program store directory is unavailable" }
            atomicWrite(entry(url), "$FORMAT\n$url\n${iosDigest(program)}\n$program")
            atomicWrite("$directory/$LATEST", url)
            manager.contentsOfDirectoryAtPath(directory, null).orEmpty()
                .map { "$directory/${it as String}" }
                .filter { it.endsWith(SUFFIX) && regularFileSize(it) != null }
                .sortedByDescending { modified(it) }
                .drop(maxEntries)
                .forEach { manager.removeItemAtPath(it, null) }
        }
    }

    override fun latest(): String? = locked {
        val path = "$directory/$LATEST"
        val size = regularFileSize(path) ?: return@locked null
        if (size > 4096) return@locked null
        val url = readBytes(path)?.let(::decodeUtf8) ?: return@locked null
        url.takeIf { it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' } && regularFileSize(entry(it)) != null }
    }

    private fun entry(url: String) = "$directory/${iosDigest(url)}$SUFFIX"

    private fun attributes(path: String): Map<Any?, *>? = manager.attributesOfItemAtPath(path, null)

    private fun regularFileSize(path: String): Long? {
        val attributes = attributes(path) ?: return null
        if (attributes[NSFileType] != NSFileTypeRegular) return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    private fun isDirectory(path: String): Boolean =
        attributes(path)?.get(NSFileType) == platform.Foundation.NSFileTypeDirectory

    private fun modified(path: String): Double =
        (attributes(path)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSinceReferenceDate ?: 0.0

    /** Any read problem yields null (fail closed). */
    private fun readBytes(path: String): ByteArray? {
        return try {
            val data = NSData.dataWithContentsOfFile(path) ?: return null
            val length = data.length.toInt()
            if (length == 0) ByteArray(0) else (data.bytes ?: return null).readBytes(length)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        null
    }

    private fun atomicWrite(target: String, text: String) {
        val temporary = "$directory/program-${NSUUID().UUIDString}.tmp"
        try {
            val bytes = text.encodeToByteArray()
            val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            check(data.writeToFile(temporary, atomically = false)) { "Player-program write failed" }
            check(rename(temporary, target) == 0) { "Player-program rename failed" }
        } finally {
            if (manager.fileExistsAtPath(temporary)) manager.removeItemAtPath(temporary, null)
        }
    }

    private companion object {
        const val FORMAT = "kmpyt-player-program-v1"
        const val SUFFIX = ".program"
        const val LATEST = "latest"
        // UTF-8 may use up to three bytes per UTF-16 unit, plus the small header.
        const val MAX_ENTRY_BYTES = YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS * 3L + 16_384
    }
}
