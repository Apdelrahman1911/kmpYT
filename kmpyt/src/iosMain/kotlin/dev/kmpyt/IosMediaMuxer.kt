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
    suspend fun mux(primaryPath: String, primaryRole: TrackRole, audioPath: String?, outputPath: String) {
        currentCoroutineContext().ensureActive()
        require(!NSFileManager.defaultManager.fileExistsAtPath(outputPath)) { "The mux destination already exists." }
        val composition = AVMutableComposition.composition()
        fun add(path: String, mediaType: String) {
            val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), options = null)
            val tracks = asset.tracksWithMediaType(mediaType)
            val source = tracks.firstOrNull() as? AVAssetTrack
                ?: error("The downloaded file does not contain the selected ${if (mediaType == AVMediaTypeVideo) "video" else "audio"} track.")
            val duration = CMTimeGetSeconds(source.timeRange.useContents { duration.readValue() })
            check(duration.isFinite() && duration > 0) { "The downloaded track has no valid media duration." }
            val destination = composition.addMutableTrackWithMediaType(mediaType, preferredTrackID = kCMPersistentTrackID_Invalid)
                ?: error("AVFoundation could not create the selected track.")
            check(destination.insertTimeRange(source.timeRange, ofTrack = source, atTime = kCMTimeZero, error = null)) {
                "AVFoundation could not read the downloaded track for MP4 muxing."
            }
            if (mediaType == AVMediaTypeVideo) destination.preferredTransform = source.preferredTransform
        }
        if (primaryRole != TrackRole.AUDIO) add(primaryPath, requireNotNull(AVMediaTypeVideo))
        if (primaryRole != TrackRole.VIDEO) add(primaryPath, requireNotNull(AVMediaTypeAudio))
        audioPath?.let { add(it, requireNotNull(AVMediaTypeAudio)) }
        val exporter = AVAssetExportSession(asset = composition, presetName = AVAssetExportPresetPassthrough)
        check(exporter.supportedFileTypes.contains(AVFileTypeMPEG4)) { "The selected tracks cannot be combined into MP4 on this device." }
        exporter.outputURL = NSURL.fileURLWithPath(outputPath)
        exporter.outputFileType = AVFileTypeMPEG4
        exporter.shouldOptimizeForNetworkUse = true
        val finished = CompletableDeferred<Unit>()
        exporter.exportAsynchronouslyWithCompletionHandler { finished.complete(Unit) }
        try {
            finished.await()
            currentCoroutineContext().ensureActive()
            check(exporter.status == AVAssetExportSessionStatus.AVAssetExportSessionStatusCompleted) {
                "AVFoundation could not combine the downloaded media into MP4 (code ${exporter.error?.code ?: 0})."
            }
            val output = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(outputPath), options = null)
            if (primaryRole != TrackRole.AUDIO) check(output.tracksWithMediaType(AVMediaTypeVideo).isNotEmpty()) { "The saved MP4 has no video track." }
            if (primaryRole != TrackRole.VIDEO || audioPath != null) check(output.tracksWithMediaType(AVMediaTypeAudio).isNotEmpty()) { "The saved MP4 has no audio track." }
        } finally {
            if (!finished.isCompleted) {
                exporter.cancelExport()
                withContext(NonCancellable) { withTimeoutOrNull(10_000) { finished.await() } }
            }
        }
    }
}
