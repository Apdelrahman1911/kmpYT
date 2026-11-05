package dev.kmpyt.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import dev.kmpyt.youtube.YouTubeFormatSelection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs

/** Explicit local smoke check only. Ordinary CI never makes these live requests. */
@RunWith(AndroidJUnit4::class)
class AndroidApprovedVideoSmokeTest {
    @Test fun approvedVideoInspectsDownloadsAndDecodesOnTheNativeDevice(): Unit = runBlocking {
        assumeTrue("Enable this one-video smoke check explicitly.",
            InstrumentationRegistry.getArguments().getString("kmpytLiveSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "kmpyt-live-smoke-${UUID.randomUUID()}").apply { mkdirs() }
        val receiptFile = File(context.filesDir, "kmpyt-live-smoke.json")
        val client = createAndroidYouTubeClient(context)
        val started = SystemClock.elapsedRealtime()
        var phase = "starting"
        var failureCode: String? = null
        var passed = false
        val phases = mutableListOf<JsonObject>()
        val receipt = linkedMapOf<String, JsonElement>(
            "videoId" to JsonPrimitive(VIDEO_ID), "approvedChannelId" to JsonPrimitive(CHANNEL_ID),
            "androidApi" to JsonPrimitive(Build.VERSION.SDK_INT), "passed" to JsonPrimitive(false),
            "signedUrlsRecorded" to JsonPrimitive(false), "maximumTransferBytes" to JsonPrimitive(BYTE_BUDGET),
            "timeoutMs" to JsonPrimitive(TIMEOUT_MS), "inspectionCalls" to JsonPrimitive(0),
            "downloadCalls" to JsonPrimitive(0),
        )
        fun save() {
            receipt["phase"] = JsonPrimitive(phase)
            receipt["elapsedMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - started)
            receipt["phases"] = JsonArray(phases.toList())
            receiptFile.writeText(JsonObject(receipt).toString())
        }
        fun enter(next: String) {
            phase = next
            phases += buildJsonObject { put("phase", next); put("elapsedMs", SystemClock.elapsedRealtime() - started) }
            save()
        }
        try {
            withTimeout(TIMEOUT_MS) {
                enter("inspection")
                receipt["inspectionCalls"] = JsonPrimitive(1)
                val report = client.inspect("https://www.youtube.com/watch?v=$VIDEO_ID")
                val codes = report.diagnostics.map { it.code }.filter { Regex("[A-Za-z0-9_]{1,100}").matches(it) }.distinct()
                receipt["diagnosticCodes"] = JsonArray(codes.map(::JsonPrimitive))
                receipt["requestStages"] = JsonArray(report.requestPlan.map { it.stage }.distinct()
                    .filter { Regex("[A-Za-z0-9_-]{1,80}").matches(it) }.map(::JsonPrimitive))
                val media = report.media.singleOrNull { it.id == VIDEO_ID && it.provider == ProviderId.YOUTUBE }
                receipt["mediaCount"] = JsonPrimitive(report.media.size)
                receipt["uploaderChannelId"] = media?.uploaderChannelId?.let(::JsonPrimitive) ?: JsonNull
                receipt["formatCount"] = JsonPrimitive(media?.representations?.size ?: 0)
                enter("verify-uploader")
                // Explicit access/rate/DRM restrictions end this check; no alternate video or retry loop follows.
                val denied = codes.filter { it.contains("AUTHORIZATION") || it.contains("ACCESS_DENIED") ||
                    it.contains("ACCESS_OR_RATE_LIMIT") || it.contains("RATE_LIMITED") || it.contains("ENCRYPTED") }
                receipt["restrictionCodes"] = JsonArray(denied.map(::JsonPrimitive))
                ensureSmoke(denied.isEmpty(), "INSPECTION_ACCESS_OR_PROTECTION_RESTRICTION")
                if (media == null) {
                    val networkFailure = codes.firstOrNull { it.startsWith("TRANSPORT_") }
                    ensureSmoke(networkFailure == null, networkFailure ?: "INSPECTION_METADATA_UNAVAILABLE")
                }
                ensureSmoke(media?.uploaderChannelId == CHANNEL_ID, "UPLOADER_ID_NOT_VERIFIED")
                val verified = requireNotNull(media)
                receipt["uploaderVerified"] = JsonPrimitive(true)
                val durationMs = verified.durationMs
                ensureSmoke(durationMs != null && durationMs > 0, "SOURCE_DURATION_NOT_PROVIDED")
                receipt["sourceDurationMs"] = JsonPrimitive(requireNotNull(durationMs))
                enter("selection")
                val choice = choices(verified, report.input).firstOrNull()
                ensureSmoke(choice != null, "NO_COMPLETE_COMPATIBLE_LOW_QUALITY_SELECTION_WITHIN_BUDGET")
                val selected = requireNotNull(choice)
                val selection = YouTubeFormatSelection(selected.primary.id, selected.audio?.id)
                val plan = client.plan(report, selection, VIDEO_ID)
                val bytes = TransferPlanning.totalBytes(plan)
                ensureSmoke(bytes != null && bytes in 1..BYTE_BUDGET, "SOURCE_SIZE_OUTSIDE_SMOKE_BUDGET")
                ensureSmoke(plan.primary.role == TrackRole.MUXED || plan.audio?.role == TrackRole.AUDIO, "COMPLETE_AUDIO_VIDEO_PAIR_REQUIRED")
                receipt["selectedIds"] = JsonArray(listOfNotNull(plan.primary.id, plan.audio?.id)
                    .map { JsonPrimitive(it.takeIf { id -> Regex("[A-Za-z0-9_#.-]{1,100}").matches(id) } ?: "redacted") })
                receipt["transport"] = JsonPrimitive(plan.primary.transport.name)
                receipt["height"] = JsonPrimitive(plan.primary.height)
                receipt["declaredTransferBytes"] = JsonPrimitive(bytes)
                val destination = File(directory, "approved.mp4")
                enter("download")
                receipt["downloadCalls"] = JsonPrimitive(1)
                val downloadStarted = SystemClock.elapsedRealtime()
                var lastReport = 0L
                val result = client.download(plan, destination.absolutePath) { progress ->
                    ensureSmoke(progress.bytesDownloaded in 0..BYTE_BUDGET &&
                        (progress.totalBytes == null || progress.totalBytes in 1..BYTE_BUDGET), "OBSERVED_TRANSFER_EXCEEDS_SMOKE_BUDGET")
                    val now = SystemClock.elapsedRealtime()
                    receipt["transferredBytes"] = JsonPrimitive(progress.bytesDownloaded)
                    receipt["reportedTotalBytes"] = JsonPrimitive(progress.totalBytes)
                    if (progress.bytesDownloaded > 0 && "firstBytesMs" !in receipt)
                        receipt["firstBytesMs"] = JsonPrimitive(now - downloadStarted)
                    if (lastReport == 0L || now - lastReport >= 1_000) { save(); lastReport = now }
                }
                receipt["downloadMs"] = JsonPrimitive(SystemClock.elapsedRealtime() - downloadStarted)
                receipt["savedBytes"] = JsonPrimitive(result.bytesWritten)
                ensureSmoke(destination.isFile && destination.length() == result.bytesWritten && result.bytesWritten > 0, "SAVED_FILE_NOT_PUBLISHED")
                enter("verify-saved-tracks")
                val tracks = inspectTracks(destination, durationMs)
                receipt["tracks"] = JsonArray(tracks)
                ensureSmoke(tracks.any { it["kind"]?.jsonPrimitive?.content == "video" } &&
                    tracks.any { it["kind"]?.jsonPrimitive?.content == "audio" }, "SAVED_AUDIO_VIDEO_TRACKS_MISSING")
                enter("decode-video-frame")
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(destination.absolutePath)
                    val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    receipt["savedDurationMs"] = JsonPrimitive(actual)
                    ensureSmoke(actual != null && abs(actual - durationMs) <= durationTolerance(durationMs), "SAVED_DURATION_MISMATCH")
                    val frame = retriever.getFrameAtTime(minOf(1_000L, durationMs / 2) * 1_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ensureSmoke(frame != null && frame.width > 0 && frame.height > 0, "VIDEO_FRAME_DECODE_FAILED")
                    requireNotNull(frame).let { bitmap ->
                        receipt["decodedFrameWidth"] = JsonPrimitive(bitmap.width)
                        receipt["decodedFrameHeight"] = JsonPrimitive(bitmap.height)
                        bitmap.recycle()
                    }
                } finally { retriever.release() }
                passed = true
                enter("complete")
            }
        } catch (failure: Throwable) {
            failureCode = safeFailure(failure)
            receipt["failureType"] = JsonPrimitive(failure.javaClass.simpleName.take(100))
            receipt["failureTypes"] = JsonArray(generateSequence(failure) { it.cause }.take(5)
                .map { it.javaClass.simpleName }.filter { Regex("[A-Za-z0-9_$]{1,100}").matches(it) }
                .map(::JsonPrimitive).toList())
        } finally {
            withContext(NonCancellable) {
                try { client.close() } catch (_: Throwable) { passed = false; failureCode = failureCode ?: "CLIENT_CLEANUP_FAILED" }
            }
            directory.deleteRecursively()
            receipt["passed"] = JsonPrimitive(passed)
            receipt["failureCode"] = failureCode?.let(::JsonPrimitive) ?: JsonNull
            save()
            println("KMPYT_LIVE_SMOKE_RESULT ${JsonObject(receipt)}")
        }
        if (!passed) throw AssertionError("Live smoke failed in $phase ($failureCode); see kmpyt-live-smoke.json.")
    }

    private fun choices(media: ExtractedMedia, page: String): List<PlannedDownload> {
        val tracks = media.representations.filter { it.selectable && it.transport in setOf(TransportKind.PROGRESSIVE, TransportKind.SABR) &&
            it.initialization == null && it.segments.isEmpty() }
        val audio = tracks.filter { it.role == TrackRole.AUDIO && it.mimeType.substringBefore(';') == "audio/mp4" &&
            it.codecs?.startsWith("mp4a", true) == true }
        return tracks.filter { it.role != TrackRole.AUDIO && it.height?.let { height -> height in 1..360 } == true &&
            it.mimeType.substringBefore(';') == "video/mp4" && it.codecs?.split(',')?.any { codec ->
                listOf("avc1", "avc3", "hvc1", "hev1").any { codec.trim().startsWith(it, true) }
            } == true }.flatMap { video ->
            if (video.role == TrackRole.MUXED) listOf(PlannedDownload(video, inspectionUrl = page, mediaId = VIDEO_ID))
            else audio.map { sound -> PlannedDownload(video, sound, inspectionUrl = page, mediaId = VIDEO_ID) }
        }.filter { plan -> YouTubeFormatCapabilities.downloadProblem(plan.primary, plan.audio, "Android") == null &&
            TransferPlanning.totalBytes(plan)?.let { it in 1..BYTE_BUDGET } == true }
            .sortedWith(compareBy<PlannedDownload> { it.primary.transport != TransportKind.PROGRESSIVE }
                .thenBy { it.primary.height ?: Int.MAX_VALUE }
                .thenByDescending { it.audio?.audioIsDefault == true }
                .thenBy { TransferPlanning.totalBytes(it) ?: Long.MAX_VALUE })
    }

    private suspend fun inspectTracks(file: File, expectedDurationMs: Long): List<JsonObject> {
        val probe = MediaExtractor()
        val trackTypes = try {
            probe.setDataSource(file.absolutePath)
            ensureSmoke(probe.trackCount in 1..16, "INVALID_SAVED_TRACK_COUNT")
            (0 until probe.trackCount).map { index -> index to probe.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally { probe.release() }
        return trackTypes.filter { (_, mime) -> mime.startsWith("video/") || mime.startsWith("audio/") }.map { (index, mime) ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath); extractor.selectTrack(index)
                var buffer = ByteBuffer.allocateDirect(64 * 1024)
                var count = 0L; var bytes = 0L; var maximumTimeUs = Long.MIN_VALUE
                while (extractor.sampleTrackIndex >= 0) {
                    currentCoroutineContext().ensureActive()
                    val size = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else buffer.capacity().toLong()
                    ensureSmoke(size in 1..BYTE_BUDGET, "INVALID_SAVED_SAMPLE_SIZE")
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                    buffer.clear()
                    val read = extractor.readSampleData(buffer, 0)
                    ensureSmoke(read > 0 && read <= buffer.capacity(), "UNREADABLE_SAVED_SAMPLE")
                    ensureSmoke(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0, "ENCRYPTED_SAVED_SAMPLE")
                    maximumTimeUs = maxOf(maximumTimeUs, extractor.sampleTime)
                    bytes += read; count++
                    ensureSmoke(count <= 1_000_000 && bytes <= BYTE_BUDGET, "SAVED_SAMPLE_BUDGET_EXCEEDED")
                    extractor.advance()
                }
                ensureSmoke(count > 0 && maximumTimeUs / 1_000 >= expectedDurationMs - durationTolerance(expectedDurationMs), "SAVED_TRACK_INCOMPLETE")
                buildJsonObject {
                    put("kind", if (mime.startsWith("video/")) "video" else "audio")
                    put("mimeType", mime.take(100)); put("samples", count); put("encodedBytes", bytes)
                    put("maximumPresentationTimeMs", maximumTimeUs / 1_000)
                }
            } finally { extractor.release() }
        }
    }

    private fun durationTolerance(expected: Long) = maxOf(250L, minOf(2_000L, expected / 20))
    private fun ensureSmoke(value: Boolean, code: String) { if (!value) throw SmokeFailure(code) }
    private class SmokeFailure(val code: String) : IllegalStateException(code)
    private fun safeFailure(failure: Throwable): String = when (failure) {
        is SmokeFailure -> failure.code
        is TimeoutCancellationException -> "SMOKE_DEADLINE_EXCEEDED"
        is CancellationException -> "SMOKE_CANCELLED"
        is SabrTransferException -> failure.code
        is YouTubeJavaScriptFailure -> failure.code
        else -> {
            val names = generateSequence(failure) { it.cause }.take(5).map { it.javaClass.simpleName }.toList()
            when {
                names.any { it.contains("UnknownHost") } -> "NETWORK_DNS_FAILURE"
                names.any { it.contains("Timeout") } -> "NETWORK_TIMEOUT"
                names.any { it.contains("SSL") || it.contains("Certificate") } -> "NETWORK_TLS_FAILURE"
                names.any { it == "ExpiredResourceException" } -> "MEDIA_RESOURCE_ACCESS_OR_EXPIRY"
                else -> Regex("\\bSABR_[A-Z0-9_]{1,100}\\b").find(failure.message.orEmpty())?.value ?: "NATIVE_OPERATION_FAILED"
            }
        }
    }

    private companion object {
        const val VIDEO_ID = "5VC9p1nzmks"
        const val CHANNEL_ID = "UCHVDgaiLbpj5Leazw8vsAmg"
        const val BYTE_BUDGET = 8L * 1024 * 1024
        const val TIMEOUT_MS = 300_000L
    }
}
