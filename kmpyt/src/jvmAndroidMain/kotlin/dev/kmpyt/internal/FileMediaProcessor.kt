package dev.kmpyt.internal

import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SegmentResource
import java.io.File

internal interface FileMediaProcessor {
    suspend fun materialize(primary: Representation, audio: Representation?,
        fetch: suspend (SegmentResource, File) -> Unit, output: File)

    /**
     * Creates [target] as a hard link to the completed private [source] on the same filesystem,
     * so materializing a large single-file download does not copy it. Returns false (and the
     * caller copies) when the platform or filesystem cannot link.
     */
    fun link(source: File, target: File): Boolean = false
}
