package dev.kmpyt.extraction

import dev.kmpyt.MediaKind
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Metadata for source playback; this does not imply that a local file was saved. */
data class SourcePlaybackMetadata(val id: String, val title: String, val kind: MediaKind,
    val durationMs: Long? = null, val artist: String? = null, val thumbnailUrl: String? = null)

data class SourcePlaybackRequest(val metadata: SourcePlaybackMetadata, val plan: PlannedDownload,
    val progressiveFallback: PlannedDownload? = null) {
    override fun toString() = "SourcePlaybackRequest(id=${metadata.id}, kind=${metadata.kind}, resource=redacted)"
    @Throws(Exception::class)
    fun requireSupportedSource() {
        TransferPlanning.parts(plan) // Shared URL, approved-source and representation-boundary checks also apply to playback.
        require(metadata.id == plan.mediaId)
        require(plan.credentialRef == null && plan.requestHeaders.isEmpty()) {
            "This playback adapter does not import credentials or arbitrary request headers."
        }
        require(plan.sabrCheckpoint == null) { "Playback opens a fresh private cache, not a download checkpoint." }
        val tracks = listOfNotNull(plan.primary, plan.audio)
        require(tracks.all { it.selectable && it.segments.isEmpty() && it.initialization == null })
        require(tracks.all { it.transport == TransportKind.SABR } || tracks.all { it.transport == TransportKind.PROGRESSIVE }) {
            "Source playback supports static SABR or progressive media; live manifests are unavailable."
        }
        require(tracks.map { it.role }.distinct().size == tracks.size)
        require(plan.audio == null || plan.primary.role == TrackRole.VIDEO && plan.audio.role == TrackRole.AUDIO)
        require(metadata.kind == if (plan.primary.role == TrackRole.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO)
        require(metadata.durationMs == null || metadata.durationMs > 0)
        dev.kmpyt.youtube.YouTubeFormatCapabilities.downloadProblem(plan.primary, plan.audio, "Desktop")
            ?.let { throw IllegalArgumentException(it) }
        progressiveFallback?.let { fallback ->
            require(plan.primary.transport == TransportKind.SABR && fallback.inspectionUrl == plan.inspectionUrl &&
                fallback.mediaId == plan.mediaId) { "A playback alternative must belong to the same inspected video." }
            require(sameProgressiveFormat(plan.primary, fallback.primary) &&
                (plan.audio == null && fallback.audio == null || plan.audio != null && fallback.audio != null &&
                    sameProgressiveFormat(plan.audio, fallback.audio))) {
                "A playback alternative must preserve the selected formats and audio track."
            }
            SourcePlaybackRequest(metadata, fallback).requireSupportedSource()
        }
    }

    @Throws(Exception::class)
    fun requireStaticSabr() {
        requireSupportedSource()
        require(metadata.id == plan.sabrSource().videoId)
        val tracks = listOfNotNull(plan.primary, plan.audio)
        require(tracks.all { it.selectable && it.transport == TransportKind.SABR && it.segments.isEmpty() &&
            it.initialization == null && it.role in setOf(TrackRole.VIDEO, TrackRole.AUDIO) })
    }

    companion object {
        /** Select source-backed identities only; inspection does not itself authorize playback. */
        @Throws(Exception::class)
        fun fromReport(report: ExtractionReport, mediaId: String, primaryId: String, audioId: String? = null): SourcePlaybackRequest {
            require(report.provider == ProviderId.YOUTUBE)
            val media = report.media.single { it.id == mediaId && it.provider == ProviderId.YOUTUBE }
            val primary = media.representations.single { it.id == primaryId }
            val audio = audioId?.let { id -> media.representations.single { it.id == id } }
            YouTubeDownloadPolicy.videoId(report.input)?.let { require(it == media.id) { "Inspection video identity differs." } }
            val request = SourcePlaybackRequest(SourcePlaybackMetadata(media.id, media.title,
                if (primary.role == TrackRole.AUDIO) MediaKind.AUDIO else MediaKind.VIDEO,
                media.durationMs, media.artist, media.thumbnailUrl),
                PlannedDownload(primary, audio, inspectionUrl = report.input, mediaId = media.id)).also { it.requireSupportedSource() }
            if (primary.transport != TransportKind.SABR) return request
            val alternative = media.representations.firstOrNull { sameProgressiveFormat(primary, it) } ?: return request
            val alternativeAudio = audio?.let { selected ->
                media.representations.firstOrNull { sameProgressiveFormat(selected, it) } ?: return request
            }
            val fallback = PlannedDownload(alternative, alternativeAudio, inspectionUrl = report.input, mediaId = media.id)
            // A malformed optional counterpart must not invalidate an otherwise usable original source.
            return runCatching { request.copy(progressiveFallback = fallback).also { it.requireSupportedSource() } }.getOrDefault(request)
        }

        /** Only actual, equivalent report resources qualify; recovery never silently changes quality or language. */
        fun sameProgressiveFormat(selected: Representation, candidate: Representation): Boolean =
            selected.transport == TransportKind.SABR && candidate.transport == TransportKind.PROGRESSIVE && candidate.selectable &&
                candidate.id.substringBefore('#') == selected.id.substringBefore('#') && candidate.role == selected.role &&
                candidate.mimeType.substringBefore(';').trim().lowercase() == selected.mimeType.substringBefore(';').trim().lowercase() &&
                candidate.codecs?.lowercase()?.replace(" ", "") == selected.codecs?.lowercase()?.replace(" ", "") &&
                candidate.width == selected.width && candidate.height == selected.height && candidate.frameRate == selected.frameRate &&
                candidate.audioTrackId == selected.audioTrackId && candidate.audioLanguage == selected.audioLanguage &&
                candidate.audioChannels == selected.audioChannels && candidate.audioSampleRate == selected.audioSampleRate &&
                candidate.colorInfo == selected.colorInfo && candidate.isHdr == selected.isHdr &&
                candidate.isDrc == selected.isDrc && candidate.segments.isEmpty() && candidate.initialization == null
    }
}

interface SourcePlaybackController {
    @Throws(Exception::class)
    suspend fun playSource(request: SourcePlaybackRequest)
}

/** An actual caller authorization contract, rechecked before every transport request. No default grant exists. */
fun interface SourcePlaybackAuthorization {
    suspend fun requireResource(request: SourcePlaybackRequest, resourceUrl: String)
}

/** Native caches implement actual random-access storage and close/delete their private resources. */
interface YouTubeSabrPlaybackCache : YouTubeSabrTrackSink { suspend fun close() }

enum class SabrStreamingStatus { IDLE, BUFFERING, COMPLETE, FAILED, CLOSED }
data class SabrStreamingCoverage(val role: TrackRole, val expectedBytes: Long,
    val ranges: List<SabrTransferByteRange>, val endedRanges: List<SabrTransferByteRange>)
data class SabrStreamingState(val status: SabrStreamingStatus, val tracks: List<SabrStreamingCoverage>,
    val bytesWritten: Long = 0, val totalBytes: Long = 0, val httpBytes: Long = 0,
    val errorCode: String? = null, val revision: Long = 0,
    val responseDiagnostics: List<SabrTransferResponseDiagnostic> = emptyList())
class SabrStreamingException(val code: String) : Exception(code)
data class SabrStreamingLimits(val maxBytes: Long = Long.MAX_VALUE,
    val maxReadBytes: Int = 1_048_576, val waitTimeoutMs: Long = 45_000) {
    init { require(maxBytes > 0 && maxReadBytes in 1..4_194_304 && waitTimeoutMs in 1..120_000) }
}

/**
 * Static, whole-track SABR playback cache. The existing transfer is the only HTTP
 * engine: it validates/stages UMP packets and fsyncs writes before coverage appears.
 * Reads never mistake sparse file length for received bytes. Seeking selects a
 * cached byte offset or waits for ingestion; it does not invent server-time seek,
 * rolling live windows, signed URLs, player profiles, tokens or integrity issuers.
 */
class YouTubeSabrStreamingSession(client: HttpClient, val request: SourcePlaybackRequest,
    private val caches: Map<TrackRole, YouTubeSabrPlaybackCache>, private val scope: CoroutineScope,
    private val authorization: SourcePlaybackAuthorization? = null,
    val limits: SabrStreamingLimits = SabrStreamingLimits(),
    transferLimits: SabrTransferLimits = SabrTransferLimits(maxOutputBytes = limits.maxBytes),
    private val nowEpochMs: () -> Long = { 0 },
    private val integrityAccess: YouTubeSabrIntegrityAccess? = null) {
    private val source: YouTubeSabrSource
    private val selection: YouTubeSabrSelection
    private val clientInfo: SabrClientInfo
    private val expected: Map<SabrFormatId, Long>
    private val transfer: YouTubeSabrTransfer
    private val responseDiagnosticLimit = minOf(transferLimits.maxResponseDiagnostics, 64)
    private val commands = Mutex()
    private var worker: Job? = null
    private val mutable: MutableStateFlow<SabrStreamingState>
    val state get() = mutable.asStateFlow()

    init {
        request.requireStaticSabr()
        source = request.plan.sabrSource(); selection = request.plan.sabrSelection()
        clientInfo = requireNotNull(request.plan.primary.sabr).clientInfo
        val tracks = listOfNotNull(request.plan.primary, request.plan.audio)
        expected = tracks.associate { requireNotNull(it.sabr).formatId to requireNotNull(it.sizeBytes) }
        val total = expected.values.fold(0L) { sum, bytes -> require(bytes > 0 && bytes <= limits.maxBytes - sum); sum + bytes }
        require(caches.keys == tracks.map { it.role }.toSet())
        require(transferLimits.maxOutputBytes <= limits.maxBytes)
        mutable = MutableStateFlow(SabrStreamingState(SabrStreamingStatus.IDLE,
            tracks.map { SabrStreamingCoverage(it.role, requireNotNull(it.sizeBytes), emptyList(), emptyList()) }, totalBytes = total))
        transfer = YouTubeSabrTransfer(client, transferLimits)
    }

    fun expectedBytes(role: TrackRole): Long = mutable.value.tracks.single { it.role == role }.expectedBytes

    suspend fun start() = commands.withLock {
        when (mutable.value.status) {
            SabrStreamingStatus.CLOSED -> throw SabrStreamingException("SABR_PLAYBACK_CLOSED")
            SabrStreamingStatus.FAILED -> throw SabrStreamingException(mutable.value.errorCode ?: "SABR_PLAYBACK_FAILED")
            SabrStreamingStatus.IDLE -> Unit
            else -> return@withLock
        }
        if (authorization == null) {
            update { it.copy(status = SabrStreamingStatus.FAILED, errorCode = "SABR_PLAYBACK_AUTHORIZATION_REQUIRED") }
            throw SabrStreamingException("SABR_PLAYBACK_AUTHORIZATION_REQUIRED")
        }
        try { authorization.requireResource(request, source.endpoint) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            update { it.copy(status = SabrStreamingStatus.FAILED, errorCode = "SABR_RESOURCE_NOT_AUTHORIZED") }
            throw SabrStreamingException("SABR_RESOURCE_NOT_AUTHORIZED")
        }
        update { it.copy(status = SabrStreamingStatus.BUFFERING) }
        worker = scope.launch {
            try {
                transfer.download(source, selection, clientInfo, expected, caches,
                    validateResource = { url ->
                        currentCoroutineContext().ensureActive()
                        if (mutable.value.status == SabrStreamingStatus.CLOSED) throw CancellationException("SABR playback closed")
                        authorization.requireResource(request, url)
                    }, onCheckpoint = { checkpoint ->
                        update { current -> if (current.status == SabrStreamingStatus.CLOSED) current else current.copy(
                            tracks = checkpoint.tracks.map { track -> SabrStreamingCoverage(track.role, track.expectedBytes,
                                track.coverage, mergeRanges(track.completedSegments.map { SabrTransferByteRange(it.offset, it.offset + it.length) })) },
                            bytesWritten = checkpoint.tracks.sumOf { track -> track.coverage.sumOf { it.length } }, httpBytes = checkpoint.httpBytes) }
                    }, onResponse = { receipt ->
                        // Keep physical response evidence, including late protection records
                        // and rejected framing, without exposing opaque source/context bytes.
                        update { current -> if (current.status == SabrStreamingStatus.CLOSED) current else current.copy(
                            responseDiagnostics = (current.responseDiagnostics + receipt).takeLast(responseDiagnosticLimit)) }
                    }, nowEpochMs = nowEpochMs, integrityAccess = integrityAccess)
                update { current -> if (current.status == SabrStreamingStatus.CLOSED) current else current.copy(status = SabrStreamingStatus.COMPLETE) }
            } catch (cancelled: CancellationException) {
                update { it.copy(status = SabrStreamingStatus.CLOSED, errorCode = "SABR_PLAYBACK_CLOSED") }; throw cancelled
            } catch (failure: SabrTransferException) {
                update { current -> if (current.status == SabrStreamingStatus.CLOSED) current else current.copy(
                    status = SabrStreamingStatus.FAILED, errorCode = failure.code,
                    responseDiagnostics = failure.responseDiagnostics.takeLast(responseDiagnosticLimit)) }
            } catch (_: Exception) {
                update { current -> if (current.status == SabrStreamingStatus.CLOSED) current else current.copy(status = SabrStreamingStatus.FAILED, errorCode = "SABR_PLAYBACK_TRANSFER_FAILED") }
            }
        }
        Unit
    }

    /** Returns only contiguous verified bytes; empty means actual declared track EOF. */
    suspend fun read(role: TrackRole, offset: Long, count: Int, timeoutMs: Long = limits.waitTimeoutMs): ByteArray {
        val length = expectedBytes(role)
        require(offset in 0..length && count in 0..limits.maxReadBytes && timeoutMs in 1..120_000)
        if (count == 0) return ByteArray(0)
        if (mutable.value.status == SabrStreamingStatus.IDLE) start()
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val current = mutable.value
                    if (current.status == SabrStreamingStatus.CLOSED) throw SabrStreamingException("SABR_PLAYBACK_CLOSED")
                    val track = current.tracks.single { it.role == role }
                    val range = track.ranges.firstOrNull { it.start <= offset && it.endExclusive > offset }
                    if (range != null) {
                        val available = minOf(count.toLong(), range.endExclusive - offset, length - offset).toInt()
                        val bytes = caches.getValue(role).read(offset, available)
                        if (bytes.size != available) throw SabrStreamingException("SABR_PLAYBACK_CACHE_TRUNCATED")
                        return@withTimeout bytes
                    }
                    if (offset == length && track.endedRanges.singleOrNull()?.let { it.start == 0L && it.endExclusive == length } == true)
                        return@withTimeout ByteArray(0)
                    if (current.status == SabrStreamingStatus.FAILED) throw SabrStreamingException(current.errorCode ?: "SABR_PLAYBACK_FAILED")
                    if (current.status == SabrStreamingStatus.COMPLETE) throw SabrStreamingException("SABR_PLAYBACK_COVERAGE_MISSING")
                    mutable.first { it.revision != current.revision }
                }
                @Suppress("UNREACHABLE_CODE") error("SABR range observation ended")
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // Caller cancellation remains cancellation, not a invented timeout.
            throw SabrStreamingException("SABR_PLAYBACK_BUFFER_TIMEOUT")
        }
    }

    /** Seek is a byte-range readiness operation; no unobserved dynamic protocol request is manufactured. */
    suspend fun awaitRange(role: TrackRole, offset: Long, timeoutMs: Long = limits.waitTimeoutMs) {
        read(role, offset, 1, timeoutMs)
    }

    suspend fun close() = withContext(NonCancellable) {
        val active = commands.withLock {
            update { it.copy(status = SabrStreamingStatus.CLOSED, errorCode = "SABR_PLAYBACK_CLOSED") }
            worker.also { worker = null }
        }
        var cancellation: CancellationException? = null
        var failed = false
        suspend fun cleanup(operation: suspend () -> Unit) {
            try { operation() }
            catch (cancelled: CancellationException) { if (cancellation == null) cancellation = cancelled }
            catch (_: Throwable) { failed = true }
        }
        cleanup { active?.cancelAndJoin() }
        cleanup { transfer.close() }
        caches.values.forEach { cache -> cleanup { cache.close() } }
        cancellation?.let { throw it }
        if (failed) throw SabrStreamingException("SABR_PLAYBACK_CLEANUP_FAILED")
    }

    private fun update(transform: (SabrStreamingState) -> SabrStreamingState) {
        // Transfer callbacks have one producer; close can race with it, so CAS preserves CLOSED.
        while (true) {
            val before = mutable.value
            val after = transform(before).copy(revision = before.revision + 1)
            if (mutable.compareAndSet(before, after)) return
        }
    }
}

private fun mergeRanges(input: List<SabrTransferByteRange>): List<SabrTransferByteRange> {
    val result = mutableListOf<SabrTransferByteRange>()
    for (range in input.sortedBy { it.start }) {
        val previous = result.lastOrNull()
        if (previous != null && range.start <= previous.endExclusive)
            result[result.lastIndex] = SabrTransferByteRange(previous.start, maxOf(previous.endExclusive, range.endExclusive))
        else result += range
    }
    return result
}
