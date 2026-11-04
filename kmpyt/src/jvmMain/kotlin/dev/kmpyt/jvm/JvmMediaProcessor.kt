package dev.kmpyt.jvm

import dev.kmpyt.extraction.*
import dev.kmpyt.internal.FileMediaProcessor
import dev.kmpyt.internal.MediaStorageSpace
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local, stream-copy processing; FFmpeg receives only files downloaded and verified by this adapter. */
internal class JvmMediaProcessor(private val ffmpegPath: String?) : FileMediaProcessor {
    fun preflight(plan: PlannedDownload) {
        if (plan.audio != null || plan.primary.segments.isNotEmpty()) executable()
    }

    private fun executable(): File = ffmpegPath?.let(::File)?.takeIf { it.isFile }
        ?: error("Combining tracks requires a caller-supplied FFmpeg executable (ffmpegPath or KMPYT_FFMPEG).")

    override suspend fun materialize(primary: Representation, audio: Representation?,
        fetch: suspend (SegmentResource, File) -> Unit, output: File): Unit = withContext(Dispatchers.IO) {
        require(!output.exists()) { "The output file already exists." }
        val temporary = File(requireNotNull(output.parentFile), "processing-${UUID.randomUUID()}")
        check(temporary.mkdir()) { "Could not create local processing storage." }
        try {
            suspend fun assemble(track: Representation, name: String): File {
                val resources = if (track.segments.isEmpty()) listOf(SegmentResource(track.requireResourceUrl()))
                    else listOfNotNull(track.initialization) + track.segments
                require(resources.size in 1..4096) { "The source has too many media segments." }
                val assembled = File(temporary, name)
                assembled.outputStream().use { destination ->
                    for ((index, resource) in resources.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val downloaded = File(temporary, "resource-$index")
                        fetch(resource, downloaded)
                        require(downloaded.isFile && downloaded.length() > 0) { "The source returned empty media." }
                        require(resource.rangeLength == null || downloaded.length() == resource.rangeLength) { "The source returned an incomplete media range." }
                        MediaStorageSpace.requireAvailable(assembled, downloaded.length())
                        downloaded.inputStream().use { MediaStorageSpace.copy(it, destination, assembled) }
                        check(downloaded.delete()) { "Could not release local media staging storage." }
                    }
                }
                return assembled
            }
            val video = assemble(primary, "primary")
            val sound = audio?.let { assemble(it, "audio") }
            if (sound == null && primary.segments.isEmpty()) {
                MediaStorageSpace.requireAvailable(output, video.length())
                video.inputStream().use { input -> output.outputStream().use { MediaStorageSpace.copy(input, it, output) } }
            } else {
                val ffmpeg = executable()
                MediaStorageSpace.requireAvailable(output, MediaStorageSpace.checkedSum(listOfNotNull(video, sound).map { it.length() }))
                val command = mutableListOf(ffmpeg.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-protocol_whitelist", "file,crypto,data", "-i", video.absolutePath)
                if (sound != null) command += listOf("-protocol_whitelist", "file,crypto,data", "-i", sound.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0")
                else command += listOf("-map", "0")
                command += listOf("-c", "copy", "-movflags", "+faststart", "-f", "mp4", output.absolutePath)
                val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(File(temporary, "ffmpeg.log")).start()
                process.outputStream.close()
                try {
                    while (process.isAlive) {
                        currentCoroutineContext().ensureActive()
                        MediaStorageSpace.requireAvailable(output, 1)
                        delay(100)
                    }
                    check(process.exitValue() == 0) { "FFmpeg could not combine these tracks into MP4. Verify codec/container compatibility." }
                    require(output.isFile && output.length() > 0) { "FFmpeg produced no media file." }
                } finally {
                    if (process.isAlive) {
                        process.destroy()
                        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    }
                }
            }
        } catch (error: Throwable) { output.delete(); throw error }
        finally { temporary.deleteRecursively() }
    }
}
