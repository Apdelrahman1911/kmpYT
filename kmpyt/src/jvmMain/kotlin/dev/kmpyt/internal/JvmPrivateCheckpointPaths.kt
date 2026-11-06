package dev.kmpyt.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal actual fun requirePrivateCheckpointPath(file: File, allowMissing: Boolean) {
    try {
        val leaf = file.toPath().toAbsolutePath()
        check(leaf == leaf.normalize())
        var path: java.nio.file.Path? = leaf
        var missingAllowed = allowMissing
        while (path != null) {
            val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { null }
            if (attributes == null) {
                check(missingAllowed)
            } else {
                // On OpenJDK Windows, junctions/other reparse tags may be directories but are
                // not isSymbolicLink. isOther reports FILE_ATTRIBUTE_REPARSE_POINT too.
                check(!attributes.isSymbolicLink && !attributes.isOther &&
                    (attributes.isRegularFile || attributes.isDirectory))
                if (path != leaf) check(attributes.isDirectory)
                // Also reject a provider resolving an extant path through a different target.
                // Unsupported NOFOLLOW/real-path queries fail closed; no java.io fallback.
                check(path.toRealPath(NOFOLLOW_LINKS) == path.toRealPath())
                missingAllowed = false
            }
            path = path.parent
        }
    } catch (_: Exception) {
        throw IllegalStateException("Private download checkpoint path contains a link, special entry, or unverifiable ancestor.")
    }
}
