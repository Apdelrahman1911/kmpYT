package dev.kmpyt.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal actual fun checkpointEntry(file: File): CheckpointEntry {
    val path = file.toPath().toAbsolutePath().normalize()
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return CheckpointEntry.Missing
    }
    // Windows File.getCanonicalFile() need not resolve symbolic links. Check the
    // entry without following it, then reject paths redirected by a junction.
    return when {
        attributes.isSymbolicLink || attributes.isOther || path.toRealPath() != path -> CheckpointEntry.Unsupported
        attributes.isDirectory -> CheckpointEntry.Directory
        attributes.isRegularFile -> CheckpointEntry.File
        else -> CheckpointEntry.Unsupported
    }
}

internal actual fun replaceCheckpoint(temporary: File, destination: File) {
    Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}
