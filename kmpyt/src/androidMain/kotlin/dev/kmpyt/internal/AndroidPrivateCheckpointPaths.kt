package dev.kmpyt.internal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Os.lstat is available from API21; do not reference java.nio.file (API26) here. */
internal actual fun requirePrivateCheckpointPath(file: File, allowMissing: Boolean) {
    try {
        val leaf = file.absoluteFile
        check(leaf.canonicalFile == leaf)
        var path: File? = leaf
        var missingAllowed = allowMissing
        while (path != null) {
            val attributes = try { Os.lstat(path.path) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) null else throw error
            }
            if (attributes == null) {
                check(missingAllowed)
            } else {
                // lstat observes dangling links too; only ordinary files/directories qualify.
                val directory = OsConstants.S_ISDIR(attributes.st_mode)
                check(directory || OsConstants.S_ISREG(attributes.st_mode))
                if (path != leaf) check(directory)
                missingAllowed = false
            }
            path = path.parentFile
        }
    } catch (_: Exception) {
        throw IllegalStateException("Private download checkpoint path contains a link, special entry, or unverifiable ancestor.")
    }
}
