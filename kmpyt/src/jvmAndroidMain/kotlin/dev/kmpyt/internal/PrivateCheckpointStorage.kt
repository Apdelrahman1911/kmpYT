package dev.kmpyt.internal

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Platform no-follow checks for caller-private checkpoint storage. Never recurse
 * through a link, including a dangling one, when inspecting or deleting state.
 * The directory must remain private to this process/caller; java.io cannot make
 * a concurrent hostile same-user filesystem mutation race-free on every target.
 */
internal suspend fun checkpointTree(directory: File): List<File> {
    val result = mutableListOf<File>()
    suspend fun visit(file: File, depth: Int) {
        currentCoroutineContext().ensureActive()
        check(depth <= 16 && result.size < 20_000) { "Private download checkpoint storage exceeds its bounds." }
        requireCheckpointEntry(file)
        result += file
        if (file.isDirectory) {
            val children = checkNotNull(file.listFiles()) { "Could not inspect private download checkpoint storage." }
            check(children.size <= 20_000 - result.size) { "Private download checkpoint storage exceeds its bounds." }
            for (child in children) visit(child, depth + 1)
        }
    }
    visit(directory, 0)
    return result
}

private fun requireCheckpointEntry(file: File) {
    requirePrivateCheckpointPath(file)
}

/** Validate the entire tree before the first deletion, then remove only those entries. */
internal suspend fun deleteCheckpointTree(directory: File, keepLock: Boolean = false) {
    val entries = checkpointTree(directory)
    for (entry in entries.asReversed()) {
        currentCoroutineContext().ensureActive()
        if (keepLock && (entry == directory || entry.parentFile == directory && entry.name == "lock")) continue
        // Recheck the no-follow parent/entry before each operation. File.delete
        // never follows a directory link and refuses unexpected new children.
        requireCheckpointEntry(entry)
        check(entry.delete()) { "Could not release private download checkpoint storage." }
    }
}
