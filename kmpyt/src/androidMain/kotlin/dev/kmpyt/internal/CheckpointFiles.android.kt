package dev.kmpyt.internal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

internal actual fun checkpointEntry(file: File): CheckpointEntry {
    val mode = try {
        Os.lstat(file.path).st_mode
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) return CheckpointEntry.Missing
        throw IOException("Could not inspect private download checkpoint storage.", error)
    }
    if (file.canonicalFile != file.absoluteFile) return CheckpointEntry.Unsupported
    return when {
        OsConstants.S_ISDIR(mode) -> CheckpointEntry.Directory
        OsConstants.S_ISREG(mode) -> CheckpointEntry.File
        else -> CheckpointEntry.Unsupported
    }
}

internal actual fun replaceCheckpoint(temporary: File, destination: File) {
    try {
        Os.rename(temporary.path, destination.path)
    } catch (error: ErrnoException) {
        throw IOException("Could not atomically replace the download checkpoint.", error)
    }
}
