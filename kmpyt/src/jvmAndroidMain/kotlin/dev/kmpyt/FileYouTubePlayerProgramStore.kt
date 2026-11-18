package dev.kmpyt

import dev.kmpyt.extraction.YouTubePlayerProgramLoader
import dev.kmpyt.extraction.YouTubePlayerProgramStore
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Keeps at most [maxEntries] exact player programs in an application-private [directory].
 *
 * Each entry records its declared URL and a SHA-256 of the program text; a mismatched,
 * truncated or unreadable entry is ignored (and removed), never repaired. Writes go to a
 * temporary file that is atomically renamed. Only player program text is stored.
 */
class FileYouTubePlayerProgramStore(
    private val directory: File,
    private val maxEntries: Int = 2,
) : YouTubePlayerProgramStore {
    private val lock = Any()

    init { require(maxEntries in 1..8) { "Invalid player-program store bound" } }

    override fun read(url: String): String? = synchronized(lock) {
        val file = entry(url)
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return null
        val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return null }
        val first = text.indexOf('\n')
        val second = if (first < 0) -1 else text.indexOf('\n', first + 1)
        val third = if (second < 0) -1 else text.indexOf('\n', second + 1)
        val valid = third > 0 && text.substring(0, first) == FORMAT &&
            text.substring(first + 1, second) == url
        val program = if (valid) text.substring(third + 1) else null
        if (program == null || program.isEmpty() || program.length > YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS ||
            sha256(program) != text.substring(second + 1, third)) {
            file.delete()
            return null
        }
        // Touch so the bounded eviction keeps recently used programs.
        file.setLastModified(System.currentTimeMillis())
        program
    }

    override fun write(url: String, program: String) {
        require(url.length in 1..4096 && url.none { it == '\n' || it == '\r' }) { "Invalid player-program URL" }
        require(program.length in 1..YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS) { "Invalid player-program size" }
        synchronized(lock) {
            directory.mkdirs()
            check(directory.isDirectory) { "Player-program store directory is unavailable" }
            atomicWrite(entry(url), "$FORMAT\n$url\n${sha256(program)}\n$program")
            atomicWrite(File(directory, LATEST), url)
            directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty()
                .sortedByDescending { it.lastModified() }
                .drop(maxEntries)
                .forEach { it.delete() }
        }
    }

    override fun latest(): String? = synchronized(lock) {
        val file = File(directory, LATEST)
        if (!file.isFile || file.length() > 4096) return null
        val url = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return null }
        url.takeIf { it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' } && entry(it).isFile }
    }

    private fun entry(url: String) = File(directory, sha256(url) + SUFFIX)

    private fun atomicWrite(target: File, text: String) {
        val temporary = File.createTempFile("program-", ".tmp", directory)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FORMAT = "kmpyt-player-program-v1"
        const val SUFFIX = ".program"
        const val LATEST = "latest"
        // UTF-8 may use up to three bytes per UTF-16 unit, plus the small header.
        const val MAX_ENTRY_BYTES = YouTubePlayerProgramLoader.MAX_PROGRAM_CHARS * 3L + 16_384
    }
}
