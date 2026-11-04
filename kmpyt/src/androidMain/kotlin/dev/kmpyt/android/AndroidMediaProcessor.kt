package dev.kmpyt.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SegmentResource
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import dev.kmpyt.internal.MediaStorageSpace
import dev.kmpyt.internal.FileMediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Authorized transport writes resources to supplied files. This class never opens a network connection. */
internal class AndroidMediaProcessor(private val maximumBytes: Long = Long.MAX_VALUE) : FileMediaProcessor {

    override suspend fun materialize(primary: Representation, audio: Representation?,
        fetch: suspend (SegmentResource, File) -> Unit, output: File): Unit = withContext(Dispatchers.IO) {
        val destination = output.canonicalFile
        val parent = requireNotNull(destination.parentFile) { "Output directory is missing" }
        require(!destination.exists()) { "Research output already exists" }
        require(maximumBytes > 0) { "Invalid research size limit" }
        YouTubeFormatCapabilities.downloadProblem(primary, audio, "Android")?.let { throw IllegalArgumentException(it) }
        YouTubeFormatCapabilities.budgetProblem(listOfNotNull(primary, audio), maximumBytes)?.let { throw IllegalArgumentException(it) }
        require(primary.selectable && (audio == null || audio.selectable)) { "An unavailable representation cannot be saved" }
        require(audio == null || (primary.role == TrackRole.VIDEO && audio.role == TrackRole.AUDIO)) { "Pair a video-only representation with audio-only media" }
        MediaStorageSpace.requireAvailable(parent, MediaStorageSpace.declaredBytes(listOfNotNull(primary, audio)))
        check(parent.isDirectory || parent.mkdirs()) { "Could not create output directory" }
        val temporary = File(parent, ".research-${UUID.randomUUID()}")
        check(temporary.mkdir()) { "Could not create research working directory" }
        val published = File(temporary, "published")
        var total = 0L
        try {
            suspend fun assemble(representation: Representation, name: String): File {
                require(representation.segments.size <= 4096) { "Research representation exceeds the segment limit" }
                require(representation.transport != TransportKind.HLS || representation.segments.isNotEmpty()) {
                    "Resolve the HLS child VOD playlist before saving"
                }
                require(representation.initialization == null || representation.segments.isNotEmpty()) { "Initialization has no media segments" }
                val target = File(temporary, name)
                val resources = buildList {
                    representation.initialization?.let(::add)
                    if (representation.segments.isEmpty()) add(SegmentResource(requireNotNull(representation.url))) else addAll(representation.segments)
                }
                var segmentation: String? = null
                FileOutputStream(target).use { stream ->
                    resources.forEachIndexed { index, resource ->
                        currentCoroutineContext().ensureActive()
                        val rangeStart = resource.rangeStart
                        val rangeLength = resource.rangeLength
                        require((rangeStart == null) == (rangeLength == null)) { "Incomplete research byte range" }
                        require(rangeStart == null || (rangeStart >= 0 && rangeLength!! > 0 && rangeStart <= Long.MAX_VALUE - rangeLength)) { "Invalid research byte range" }
                        val downloaded = File(temporary, "resource")
                        check(!downloaded.exists()) { "Stale research resource" }
                        MediaStorageSpace.requireAvailable(downloaded, rangeLength ?: if (resources.size == 1) representation.sizeBytes ?: 0L else 0L)
                        fetch(resource, downloaded)
                        currentCoroutineContext().ensureActive()
                        check(downloaded.isFile && downloaded.length() > 0) { "Research transport returned an empty resource" }
                        val length = downloaded.length()
                        require(length <= maximumBytes - total) { "Research media exceeds the size limit" }
                        require(rangeLength == null || length == rangeLength) { "Research transport returned the wrong byte range length" }
                        total += length
                        if (representation.initialization != null) {
                            if (index == 0) require(hasMp4Box(downloaded, "moov")) { "Fragmented media initialization is not an MP4 movie" }
                            else require(hasMp4Box(downloaded, "moof")) { "Unsupported segmented container: MP4 fragments must contain moof" }
                        } else if (representation.segments.isNotEmpty()) {
                            val kind = segmentKind(downloaded)
                            if (representation.segments.size > 1) {
                                require(kind in setOf("ts", "adts") && (segmentation == null || segmentation == kind)) {
                                    "Multiple media segments require MP4 initialization, contiguous MPEG-TS, or ADTS AAC"
                                }
                            }
                            segmentation = kind
                            if (kind == "ts") validateTransportStream(downloaded)
                        }
                        copy(downloaded, stream, target)
                        check(downloaded.delete()) { "Could not remove temporary research resource" }
                    }
                    stream.fd.sync()
                }
                return target
            }
            val primaryFile = assemble(primary, "primary")
            val audioFile = audio?.let { assemble(it, "audio") }
            if (audioFile == null && primary.segments.isEmpty()) {
                validate(primaryFile, primary.role)
                MediaStorageSpace.requireAvailable(published, primaryFile.length())
                FileOutputStream(published).use { stream -> copy(primaryFile, stream, published); stream.fd.sync() }
            } else mux(primaryFile, primary.role, audioFile, published)
            currentCoroutineContext().ensureActive()
            check(published.length() > 0 && !destination.exists() && published.renameTo(destination)) { "Could not publish processed media" }
        } finally {
            temporary.listFiles()?.forEach { it.delete() }
            temporary.delete()
        }
    }

    private suspend fun copy(source: File, output: FileOutputStream, destination: File) {
        source.inputStream().use { input ->
            MediaStorageSpace.copy(input, output, destination)
        }
    }

    private data class Track(val file: File, val index: Int, val format: MediaFormat)

    private fun tracks(file: File, role: TrackRole): List<Track> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            require(extractor.psshInfo.isNullOrEmpty()) { "Encrypted or DRM-protected research media is unsupported" }
            val candidates = (0 until extractor.trackCount).map { index -> Track(file, index, extractor.getTrackFormat(index)) }
            val selected = candidates.filter { track ->
                val mime = track.format.getString(MediaFormat.KEY_MIME).orEmpty()
                when (role) { TrackRole.AUDIO -> mime.startsWith("audio/"); TrackRole.VIDEO -> mime.startsWith("video/"); TrackRole.MUXED -> mime.startsWith("audio/") || mime.startsWith("video/") }
            }
            require(selected.isNotEmpty()) { "Downloaded media has no ${role.name.lowercase()} track" }
            require(selected.size <= 2 && selected.map { it.format.getString(MediaFormat.KEY_MIME)?.substringBefore('/') }.distinct().size == selected.size) {
                "Research processing supports one video and one audio track"
            }
            selected
        } finally { extractor.release() }
    }

    private suspend fun validate(file: File, role: TrackRole) {
        // Validate every audio/video track in a complete source, including tracks outside a provider's role hint.
        val selected = tracks(file, TrackRole.MUXED)
        tracks(file, role)
        selected.forEach { track -> read(track) { _, _, _, _ -> } }
    }

    private suspend fun mux(primary: File, role: TrackRole, audio: File?, destination: File) {
        val inputs = tracks(primary, role) + (audio?.let { tracks(it, TrackRole.AUDIO) } ?: emptyList())
        require(inputs.size in 1..2) { "Research muxing supports one video and one audio track" }
        inputs.forEach { track ->
            val mime = track.format.getString(MediaFormat.KEY_MIME)
            require(mime in setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")) {
                "The track container/codec ($mime) cannot be remuxed to MP4 by this processor"
            }
        }
        val firstTimes = inputs.map { firstPresentationTimeForMux(it) }
        val origin = firstTimes.min()
        val muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var primaryFailure: Throwable? = null
        try {
            val outputs = inputs.map { muxer.addTrack(it.format) }
            muxer.start(); started = true
            val capacity = MediaStorageSpace.monitor(destination)
            inputs.forEachIndexed { index, track ->
                read(track, allowVideoReordering = Build.VERSION.SDK_INT >= 25) { buffer, size, time, flags ->
                    val info = MediaCodec.BufferInfo().apply { set(0, size, normalizedMuxTimestamp(time, origin),
                        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
                    capacity.requireForWrite(size.toLong())
                    muxer.writeSampleData(outputs[index], buffer, info)
                }
            }
            started = false
            muxer.stop()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(operation: () -> Unit) {
                try { operation() } catch (failure: Throwable) {
                    val original = primaryFailure ?: cleanupFailure
                    if (original != null) original.addSuppressed(failure) else cleanupFailure = failure
                }
            }
            if (started) cleanup { muxer.stop() }
            cleanup { muxer.release() }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
        validate(destination, if (audio == null) role else TrackRole.MUXED)
    }

    private fun hasVideoPresentationReordering(track: Track): Boolean =
        track.format.getString(MediaFormat.KEY_MIME) in setOf("video/avc", "video/hevc")

    /** Presentation order can differ from extractor/decode order; use the earliest PTS as the shared origin. */
    private suspend fun firstPresentationTimeForMux(track: Track): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(track.file.path); extractor.selectTrack(track.index)
            require(extractor.sampleTrackIndex >= 0) { "Media track contains no samples" }
            var earliest = extractor.sampleTime.also { validateFirstTimestamp(track.format, it) }
            if (hasVideoPresentationReordering(track)) {
                var previous = earliest
                var samples = 0L
                while (extractor.sampleTrackIndex >= 0) {
                    currentCoroutineContext().ensureActive()
                    val time = extractor.sampleTime
                    require(time >= 0) { "Negative video presentation timestamps are unsupported" }
                    require(time >= previous || Build.VERSION.SDK_INT >= 25) {
                        "Combining video with B-frames requires Android 7.1 or newer."
                    }
                    require(++samples <= 2_000_000) { "Research media exceeds the sample limit" }
                    earliest = minOf(earliest, time); previous = time
                    extractor.advance()
                }
            }
            earliest
        } finally { extractor.release() }
    }

    private suspend fun read(track: Track, allowVideoReordering: Boolean = true,
        consume: (ByteBuffer, Int, Long, Int) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(track.file.path); extractor.selectTrack(track.index)
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            var lastTime: Long? = null
            var samples = 0L
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted research samples are unsupported" }
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME == 0) { "Partial research samples are unsupported" }
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                require(count > 0 && count <= buffer.capacity()) { "Empty or excessive media sample" }
                val time = extractor.sampleTime
                if (lastTime == null) validateFirstTimestamp(track.format, time)
                else if (allowVideoReordering && hasVideoPresentationReordering(track)) {
                    // AVC/HEVC B-frames are delivered in decode order with nonmonotonic PTS.
                    // Preserve that order and every timestamp; sorting the samples corrupts references.
                    require(time >= 0) { "Negative video presentation timestamps are unsupported" }
                } else require(time >= lastTime) { "Media sample timestamp discontinuity is unsupported" }
                require(++samples <= 2_000_000) { "Research media exceeds the sample limit" }
                buffer.position(0); buffer.limit(count)
                consume(buffer, count, time, extractor.sampleFlags)
                lastTime = time
                extractor.advance()
            }
            require(samples > 0) { "Media track contains no samples" }
        } finally { extractor.release() }
    }

    private fun validateFirstTimestamp(format: MediaFormat, time: Long) {
        if (time >= 0) return
        // A valid AAC encoder-delay/edit-list packet can precede presentation time zero.
        // Keep that packet; only a short AAC prefix is allowed, not arbitrary negative timelines.
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
        require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" && rate > 0) {
            "Negative media start is supported only for bounded AAC encoder priming"
        }
        val maximumPrimingUs = (4096L * 1_000_000 / rate).coerceAtMost(250_000)
        require(time >= -maximumPrimingUs) { "AAC encoder priming exceeds the supported timestamp bound" }
    }

    private fun normalizedMuxTimestamp(time: Long, origin: Long): Long {
        require(time >= origin && (origin >= 0 || time <= Long.MAX_VALUE + origin)) { "Media timestamp normalization overflow or discontinuity" }
        return time - origin
    }

    private fun segmentKind(file: File): String = FileInputStream(file).use { input ->
        val header = ByteArray(4); val count = input.read(header)
        when { count >= 1 && header[0].toInt() and 255 == 0x47 -> "ts"
            count >= 2 && header[0].toInt() and 255 == 0xff && header[1].toInt() and 0xf6 == 0xf0 -> "adts"
            else -> "unknown" }
    }

    private suspend fun validateTransportStream(file: File) {
        require(file.length() % 188 == 0L) { "Unsupported MPEG-TS packet alignment" }
        file.inputStream().use { input ->
            val packet = ByteArray(188)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(packet)
                if (count < 0) break
                require(count == 188 && packet[0].toInt() and 255 == 0x47) { "Malformed MPEG-TS packet" }
                require(packet[3].toInt() and 0xc0 == 0) { "Encrypted MPEG-TS is unsupported" }
            }
        }
    }

    private fun hasMp4Box(file: File, wanted: String): Boolean = file.inputStream().use { input ->
        var remaining = file.length()
        val header = ByteArray(8)
        var found = false
        while (remaining >= 8) {
            require(input.read(header) == 8) { "Truncated MP4 box" }
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var size = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            var headerSize = 8L
            if (size == 1L) {
                require(input.read(header) == 8) { "Truncated extended MP4 box" }
                size = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).long
                headerSize = 16
            } else if (size == 0L) size = remaining
            require(size >= headerSize && size <= remaining) { "Invalid MP4 box bounds" }
            if (type == wanted) found = true
            var skip = size - headerSize
            while (skip > 0) { val count = input.skip(skip); require(count > 0) { "Truncated MP4 box payload" }; skip -= count }
            remaining -= size
        }
        require(remaining == 0L) { "Trailing invalid MP4 bytes" }
        found
    }
}
