package dev.kmpyt.internal

import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SegmentResource
import java.io.File

internal interface FileMediaProcessor {
    suspend fun materialize(primary: Representation, audio: Representation?,
        fetch: suspend (SegmentResource, File) -> Unit, output: File)
}
