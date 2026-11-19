@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.TrackRole
import kotlinx.cinterop.useContents
import kotlinx.cinterop.readValue
import kotlinx.coroutines.*
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*

/** Local-only, lossless passthrough. It preserves video transforms and each source track's native timescale. */
internal object IosMediaMuxer {
    suspend fun mux(primaryPath: String, primaryRole: TrackRole, audioPath: String?, outputPath: String, onOwnershipUnconfirmed: () -> Unit = {}) {
        currentCoroutineContext().ensureActive()
        require(!NSFileManager.defaultManager.fileExistsAtPath(outputPath)) { "The mux destination already exists." }
        val composition = AVMutableComposition.composition()
        val selected = mutableListOf<Pair<AVAssetTrack, String>>()
        fun select(path: String, mediaType: String) {
            val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), options = null)
            val tracks = asset.tracksWithMediaType(mediaType)
            val source = tracks.firstOrNull() as? AVAssetTrack
                ?: error("The downloaded file does not contain the selected ${if (mediaType == AVMediaTypeVideo) "video" else "audio"} track.")
            val duration = CMTimeGetSeconds(source.timeRange.useContents { duration.readValue() })
            check(duration.isFinite() && duration > 0) { "The downloaded track has no valid media duration." }
            selected += source to mediaType
        }
        if (primaryRole != TrackRole.AUDIO) select(primaryPath, requireNotNull(AVMediaTypeVideo))
        if (primaryRole != TrackRole.VIDEO) select(primaryPath, requireNotNull(AVMediaTypeAudio))
        audioPath?.let { select(it, requireNotNull(AVMediaTypeAudio)) }
        // One origin for the selection preserves relative audio/video offsets and edit-list gaps.
        val origin = selected.map { it.first.timeRange.useContents { start.readValue() } }
            .reduce { earliest, time -> if (CMTimeCompare(time, earliest) < 0) time else earliest }
        selected.forEach { (source, mediaType) ->
            val destination = composition.addMutableTrackWithMediaType(mediaType, preferredTrackID = kCMPersistentTrackID_Invalid)
                ?: error("AVFoundation could not create the selected track.")
            val offset = CMTimeSubtract(source.timeRange.useContents { start.readValue() }, origin)
            check(destination.insertTimeRange(source.timeRange, ofTrack = source, atTime = offset, error = null)) {
                "AVFoundation could not read the downloaded track for MP4 muxing."
            }
            if (mediaType == AVMediaTypeVideo) destination.preferredTransform = source.preferredTransform
        }
        val exporter = AVAssetExportSession(asset = composition, presetName = AVAssetExportPresetPassthrough)
        check(exporter.supportedFileTypes.contains(AVFileTypeMPEG4)) { "The selected tracks cannot be combined into MP4 on this device." }
        exporter.outputURL = NSURL.fileURLWithPath(outputPath)
        exporter.outputFileType = AVFileTypeMPEG4
        exporter.shouldOptimizeForNetworkUse = true
        val finished = CompletableDeferred<Unit>()
        awaitIosExport(
            start = { exporter.exportAsynchronouslyWithCompletionHandler { finished.complete(Unit) } },
            state = {
                when (exporter.status) {
                    AVAssetExportSessionStatusCompleted -> IosExportState.COMPLETED
                    AVAssetExportSessionStatusFailed -> IosExportState.FAILED
                    AVAssetExportSessionStatusCancelled -> IosExportState.CANCELLED
                    else -> IosExportState.ACTIVE
                }
            },
            completion = finished,
            cancel = exporter::cancelExport,
            onOwnershipUnconfirmed = onOwnershipUnconfirmed,
        )
        val output = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(outputPath), options = null)
        if (primaryRole != TrackRole.AUDIO) check(output.tracksWithMediaType(AVMediaTypeVideo).isNotEmpty()) { "The saved MP4 has no video track." }
        if (primaryRole != TrackRole.VIDEO || audioPath != null) check(output.tracksWithMediaType(AVMediaTypeAudio).isNotEmpty()) { "The saved MP4 has no audio track." }
    }
}

internal enum class IosExportState { ACTIVE, COMPLETED, FAILED, CANCELLED }

/** Poll terminal status as well as observing the callback. Only a terminal status releases file ownership. */
internal suspend fun awaitIosExport(
    start: () -> Unit,
    state: () -> IosExportState,
    completion: Deferred<Unit>,
    cancel: () -> Unit,
    onOwnershipUnconfirmed: () -> Unit,
    timeoutMs: Long = 120_000,
    drainMs: Long = 10_000,
) {
    suspend fun awaitTerminal(): IosExportState {
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = state()
            if (observed != IosExportState.ACTIVE) return observed
            if (completion.isCompleted) delay(100) else withTimeoutOrNull(100) { completion.await() }
        }
    }
    var reason = DownloadProcessingException.Reason.EXPORT_FAILED
    var drained = false
    try {
        start()
        val terminal = withTimeoutOrNull(timeoutMs) { awaitTerminal() }
        drained = terminal != null
        currentCoroutineContext().ensureActive()
        if (terminal == IosExportState.COMPLETED) return
        reason = when (terminal) {
            null -> DownloadProcessingException.Reason.EXPORT_TIMEOUT
            IosExportState.CANCELLED -> DownloadProcessingException.Reason.EXPORT_CANCELLED
            else -> DownloadProcessingException.Reason.EXPORT_FAILED
        }
        throw DownloadProcessingException(reason)
    } catch (cancelled: CancellationException) {
        reason = DownloadProcessingException.Reason.EXPORT_CANCELLED
        throw cancelled
    } finally {
        if (!drained) {
            val terminal = try {
                withContext(NonCancellable) {
                    cancel()
                    withTimeoutOrNull(drainMs) { awaitTerminal() }
                }
            } catch (_: Exception) { null }
            if (terminal == null) {
                onOwnershipUnconfirmed()
                throw DownloadProcessingException(reason, restartRequired = true)
            }
        }
    }
}
