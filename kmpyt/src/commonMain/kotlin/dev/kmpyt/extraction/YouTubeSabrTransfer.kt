package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Native adapters own durable files, publication, media validation and muxing. */
interface YouTubeSabrTrackSink {
    suspend fun size(): Long
    suspend fun read(offset: Long, count: Int): ByteArray
    suspend fun write(offset: Long, bytes: ByteArray)
}
@Serializable
data class SabrTransferByteRange(val start: Long, val endExclusive: Long) {
    init { require(start >= 0 && endExclusive > start) }
    val length: Long get() = endExclusive - start
}
@Serializable
data class SabrTransferSegment(val offset: Long, val length: Long, val sequence: Int,
    val startTimeMs: Long, val durationMs: Long, val initialization: Boolean) {
    init {
        require(offset >= 0 && length > 0 && offset <= Long.MAX_VALUE - length && sequence >= 0)
        require(startTimeMs >= 0 && durationMs >= 0 && startTimeMs <= Long.MAX_VALUE - durationMs)
    }
}
@Serializable
data class SabrTransferTrackCheckpoint(val role: TrackRole, val formatId: SabrFormatId,
    val expectedBytes: Long, val coverage: List<SabrTransferByteRange> = emptyList(),
    val completedSegments: List<SabrTransferSegment> = emptyList())
@Serializable
data class SabrTransferContextCheckpoint(val type: Int, val value: ByteArray) {
    override fun toString() = "SabrTransferContextCheckpoint(type=$type, bytes=${value.size}, payload=redacted)"
}
/**
 * Transient protocol state, including sensitive endpoint/configuration/cookie/context data.
 * Do not log, export or use its serialization as a privacy-safe durable file receipt. Native
 * download adapters persist independently hashed byte receipts and restart anonymous sessions.
 */
@Serializable
data class SabrTransferCheckpoint(val videoId: String, val selectedAudio: SabrFormatId?,
    val selectedVideo: SabrFormatId?, val clientInfo: SabrClientInfo,
    val tracks: List<SabrTransferTrackCheckpoint>, val nextRequestNumber: Int = 1,
    val playbackCookie: ByteArray? = null, val contexts: List<SabrTransferContextCheckpoint> = emptyList(),
    val activeContextTypes: List<Int> = emptyList(), val receivedFormatIds: List<SabrFormatId> = emptyList(),
    val completedRounds: Int = 0, val httpBytes: Long = 0,
    val consecutiveEmptyResponses: Int = 0, val protectionRetries: Int = 0,
    val resourceGeneration: String? = null, val activeEndpoint: String? = null,
    val protocolRedirects: List<String> = emptyList(),
    /** Same-video metadata registry for bounded discard only, never selected output ACKs. */
    val observedFormatIds: List<SabrFormatId> = emptyList()) {
    override fun toString() = "SabrTransferCheckpoint(videoId=$videoId, tracks=${tracks.size}, nextRequestNumber=$nextRequestNumber, rounds=$completedRounds, redirects=${protocolRedirects.size}, endpoint=redacted, payloads=redacted)"
}
data class SabrTransferProgress(val bytesWritten: Long, val totalBytes: Long, val httpBytes: Long, val requestNumber: Int)
data class SabrTransferResult(val checkpoint: SabrTransferCheckpoint, val outputBytesByRole: Map<TrackRole, Long>, val rounds: Int,
    val responseDiagnostics: List<SabrTransferResponseDiagnostic> = emptyList())
class SabrTransferException(val code: String, val checkpoint: SabrTransferCheckpoint,
    val httpStatus: Int? = null, val protectionStatus: Int? = null,
    val responseDiagnostics: List<SabrTransferResponseDiagnostic> = emptyList()) : Exception(code) {
    override fun toString() = "SabrTransferException(code=$code, httpStatus=$httpStatus, protectionStatus=$protectionStatus, responses=${responseDiagnostics.size}, payloads=redacted)"
}
data class SabrTransferLimits(val maxRounds: Int = 2_048, val maxRetries: Int = 2,
    val requestTimeoutMs: Long = 45_000, val retryDelayMs: Long = 500, val maxBackoffMs: Long = 30_000,
    val maxOutputBytes: Long = Long.MAX_VALUE, val maxRangesPerTrack: Int = 8_192, val maxContexts: Int = 256,
    val maxContextBytes: Int = 262_144,
    val maxStagedMediaBytes: Int = 16_777_216, val maxResponseDiagnostics: Int = 256,
    val maxProtectionRetries: Int = 5, val maxEmptyProtocolRounds: Int = 10,
    val minimumProtectionBackoffMs: Long = 500,
    val wire: SabrWireLimits = SabrWireLimits(), val ump: SabrUmpLimits = SabrUmpLimits(),
    val maxHeaderDiagnosticsPerResponse: Int = 64, val maxProtocolRedirects: Int = 8) {
    init {
        require(maxRounds in 1..65_536 && maxRetries in 0..8 && requestTimeoutMs in 1..120_000)
        require(retryDelayMs in 0..30_000 && maxBackoffMs in 0..120_000 && maxOutputBytes > 0)
        require(maxRangesPerTrack in 1..65_536 && maxContexts in 1..4_096)
        require(maxContextBytes in 1..16_777_216)
        require(maxStagedMediaBytes in 1..67_108_864 && maxResponseDiagnostics in 1..4_096)
        require(maxProtectionRetries in 0..32 && maxEmptyProtocolRounds in 1..256 && minimumProtectionBackoffMs in 0..30_000)
        require(maxHeaderDiagnosticsPerResponse in 1..1_024)
        require(maxProtocolRedirects in 0..32)
    }
}

/**
 * Independent continuing SABR/UMP transfer using the retained WireField schemas
 * and header/data/end framing. Selected identities, offsets, bytes and server
 * context state remain source-backed. Completion requires complete byte coverage
 * of supplied whole-track lengths AND matching MEDIA_END, never just HTTP EOF.
 *
 * Raw payloads (compression absent/0) are the supported controlled-test contract.
 * VAL0/VAL1 names in the APK do not prove current server compression semantics.
 * Media is staged until the complete bounded response has been classified. Numeric
 * protection 2/3 uses the retained empty/nonempty response distinction; it never
 * invents a token-error name or discards already returned, authorized media merely
 * because an optional context refresh is unavailable. No challenges are generated.
 */
class YouTubeSabrTransfer(client: HttpClient, private val limits: SabrTransferLimits = SabrTransferLimits()) {
    // Share engine only: caller credentials/default headers/retries cannot leak.
    private val http = HttpClient(client.engine) { followRedirects = false; expectSuccess = false }
    fun close() = http.close()

    suspend fun download(source: YouTubeSabrSource, selection: YouTubeSabrSelection,
        clientInfo: SabrClientInfo, expectedBytes: Map<SabrFormatId, Long>, sinks: Map<TrackRole, YouTubeSabrTrackSink>,
        validateResource: suspend (String) -> Unit, checkpoint: SabrTransferCheckpoint? = null,
        onCheckpoint: suspend (SabrTransferCheckpoint) -> Unit = {},
        onProgress: suspend (SabrTransferProgress) -> Unit = {},
        nowEpochMs: () -> Long = { 0 },
        onResponse: suspend (SabrTransferResponseDiagnostic) -> Unit = {},
        integrityAccess: YouTubeSabrIntegrityAccess? = null): SabrTransferResult {
        require(Regex("[A-Za-z0-9_-]{11}").matches(source.videoId))
        require(source.endpoint.length <= 65_536 && source.formats.size in 1..512)
        require(source.ustreamerConfig.isNotEmpty() && source.ustreamerConfig.size <= minOf(limits.wire.maxFieldBytes, 98_304))
        val selected = listOfNotNull(selection.audio, selection.video)
        val declared = listOfNotNull(selection.audio?.let { id -> source.formats.singleOrNull { it.id == id && it.role == TrackRole.AUDIO } },
            selection.video?.let { id -> source.formats.singleOrNull { it.id == id && it.role == TrackRole.VIDEO } })
        require(declared.size == selected.size && selected.distinct().size == selected.size) { "Select exact source track identities" }
        require(expectedBytes.keys == selected.toSet() && sinks.keys == declared.map { it.role }.toSet())
        val total = expectedBytes.values.fold(0L) { sum, length ->
            require(length > 0 && length <= limits.maxOutputBytes - sum) { "Actual bounded whole-track contentLength is required" }; sum + length
        }
        val actualGeneration = YouTubeIntegrityBinding.forSabr(source, clientInfo).resourceGeneration
        var state = checkpoint ?: SabrTransferCheckpoint(source.videoId, selection.audio, selection.video, clientInfo,
            declared.map { SabrTransferTrackCheckpoint(it.role, it.id, expectedBytes.getValue(it.id)) }, resourceGeneration = actualGeneration)
        val diagnostics = mutableListOf<SabrTransferResponseDiagnostic>()
        var protectionStatus: Int? = null
        integrityAccess?.requireSource(source, clientInfo)
        var issuedContext: YouTubeIssuedIntegrityContext? = null
        var integrityCode: String? = null
        suspend fun acquireContext(refresh: Boolean): Boolean {
            val access = integrityAccess ?: run { integrityCode = "ISSUER_UNAVAILABLE"; return false }
            return try {
                val issued = if (refresh) access.manager.refresh(access.issuerId, YouTubeIntegrityScope.MEDIA_RESOURCE,
                    access.binding, issuedContext)
                else access.manager.acquire(access.issuerId, YouTubeIntegrityScope.MEDIA_RESOURCE, access.binding)
                require(issued.issuerId == access.issuerId && issued.scope == YouTubeIntegrityScope.MEDIA_RESOURCE && issued.binding == access.binding)
                require(issued.copyValue().size in 1..limits.wire.maxFieldBytes)
                issuedContext = issued; integrityCode = null; true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: YouTubeIntegrityException) { integrityCode = failure.code; false }
            catch (_: Exception) { integrityCode = "INTEGRITY_PROVIDER_FAILED"; false }
        }
        fun fail(code: String, status: Int? = null): Nothing = throw SabrTransferException(code, state, status,
            protectionStatus, diagnostics.toList())
        if (state.resourceGeneration == null) {
            val nonemptyLegacy = state.nextRequestNumber != 1 || state.completedRounds != 0 || state.httpBytes != 0L ||
                state.playbackCookie != null || state.contexts.isNotEmpty() || state.activeContextTypes.isNotEmpty() ||
                state.receivedFormatIds.isNotEmpty() || state.consecutiveEmptyResponses != 0 || state.protectionRetries != 0 ||
                state.activeEndpoint != null || state.protocolRedirects.isNotEmpty() || state.observedFormatIds.isNotEmpty() ||
                state.tracks.any { it.coverage.isNotEmpty() || it.completedSegments.isNotEmpty() }
            if (nonemptyLegacy) fail("SABR_CHECKPOINT_RESOURCE_BINDING_MISSING")
            state = state.copy(resourceGeneration = actualGeneration)
        }
        suspend fun checkedResource(url: String) {
            try { validateResource(url) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("SABR_RESOURCE_NOT_AUTHORIZED") }
        }
        validateCheckpoint(state, source, selection, clientInfo, expectedBytes)
        state.tracks.forEach { if (sinks.getValue(it.role).size() < (it.coverage.lastOrNull()?.endExclusive ?: 0)) fail("SABR_CHECKPOINT_FILE_TRUNCATED") }
        suspend fun publish() {
            onCheckpoint(state)
            onProgress(SabrTransferProgress(state.tracks.sumOf { t -> t.coverage.sumOf { it.length } }, total, state.httpBytes, state.nextRequestNumber - 1))
        }
        var retries = 0; var attempts = 0; var backoff = 0L
        publish()
        while (!complete(state)) {
            currentCoroutineContext().ensureActive()
            if (attempts >= limits.maxRounds || state.nextRequestNumber == Int.MAX_VALUE) fail("SABR_ROUND_LIMIT")
            if (backoff > 0) delay(backoff)
            backoff = 0
            // Native adapters supply their clock. Only a SOURCE-DECLARED expiry
            // can request refresh; HTTP403/protection never establishes expiry.
            val now = nowEpochMs()
            if (now > 0 && source.expiresAtEpochMs?.let { it <= now } == true)
                fail("SABR_SOURCE_URL_EXPIRED")
            val rn = state.nextRequestNumber
            val activeEndpoint = state.activeEndpoint ?: source.endpoint
            val endpointExpiry = declaredEndpointExpiry(activeEndpoint)
            if (now > 0 && endpointExpiry?.let { it <= now } == true)
                fail(if (state.activeEndpoint != null) "SABR_REDIRECT_URL_EXPIRED" else "SABR_SOURCE_URL_EXPIRED")
            checkedResource(activeEndpoint) // Reauthorize a persisted server-issued destination before use.
            val url = requestUrl(activeEndpoint, clientInfo.clientVersion, rn)
            checkedResource(url) // Mandatory task-bound policy before EVERY HTTP request.
            if (integrityAccess != null) {
                val previous = issuedContext
                if (!acquireContext(refresh = false)) {
                    // Retain only precise manager provenance with actual known lifetime.
                    // A rejected, evicted, revoked or known-expired context is never reused.
                    issuedContext = if (previous != null && previous.binding == integrityAccess.binding &&
                        previous.issuerId == integrityAccess.issuerId && previous.scope == YouTubeIntegrityScope.MEDIA_RESOURCE &&
                        integrityAccess.manager.isUsable(previous)) previous else null
                }
            }
            val request = buildRequest(source, declared, state, issuedContext?.copyValue())
            state = state.copy(nextRequestNumber = rn + 1); attempts++
            publish()
            val packets = mutableMapOf<Int, ActivePacket>()
            var bytesThisRound = 0L; var roundBackoff = 0L
            // Original request n() consumes the prior response's cookie before
            // rjb.d(false) clears it for the NEW reply. Only an actual field7
            // in a fully classified reply can supply the subsequent cookie.
            var responseState = state.copy(playbackCookie = null)
            val staged = mutableListOf<StagedWrite>()
            var stagedBytes = 0L
            val statuses = mutableListOf<Int>()
            var missingProtectionStatus = false
            val serverErrors = mutableListOf<Int?>()
            val mediaHeaders = mutableListOf<SabrTransferMediaHeaderDiagnostic>()
            var pendingRedirect: String? = null
            var mediaHeaderCount = 0
            var ignoredMediaPayloadBytes = 0L
            var ignoredMediaPacketCount = 0
            var responseHttpStatus: Int? = null
            var completeFraming = false
            var transportFailureType: String? = null
            var currentStage = SabrTransferFailureStage.HTTP_REQUEST
            var failureFrames = emptyList<String>()
            var action: SabrResponseProtectionAction? = null
            var receiptPublished = false
            val coveredBeforeRound = state.tracks.sumOf { track -> track.coverage.sumOf { it.length } }
            protectionStatus = null
            suspend fun receipt(code: String, failed: Boolean = false) {
                if (receiptPublished) {
                    // A failing response observer cannot be called recursively. Keep
                    // the exception's receipt accurate without a second callback.
                    if (failed) diagnostics[diagnostics.lastIndex] = diagnostics.last().copy(code = code,
                        transportFailureType = transportFailureType, failureStage = currentStage, failureFrames = failureFrames)
                    return
                }
                val observed = SabrTransferResponseDiagnostic(rn, responseHttpStatus, bytesThisRound, completeFraming,
                    stagedBytes, state.tracks.sumOf { track -> track.coverage.sumOf { it.length } } - coveredBeforeRound,
                    statuses.toList(), missingProtectionStatus, serverErrors.toList(), action, code, integrityCode,
                    mediaHeaders.toList(), mediaHeaderCount, transportFailureType,
                    if (failed) currentStage else null, if (failed) failureFrames else emptyList(),
                    ignoredMediaPayloadBytes, ignoredMediaPacketCount)
                if (diagnostics.size == limits.maxResponseDiagnostics) diagnostics.removeAt(0)
                diagnostics += observed; receiptPublished = true
                currentStage = SabrTransferFailureStage.CALLBACK
                onResponse(observed)
            }
            try {
                val finished = withTimeoutOrNull(limits.requestTimeoutMs) {
                    currentStage = SabrTransferFailureStage.HTTP_REQUEST
                    http.prepareRequest(url) {
                        method = HttpMethod.Post
                        header(HttpHeaders.Accept, "application/vnd.yt-ump")
                        header(HttpHeaders.AcceptEncoding, "identity")
                        contentType(ContentType.parse("application/x-protobuf"))
                        setBody(request)
                    }.execute { response ->
                        currentStage = SabrTransferFailureStage.RESPONSE_HEADERS
                        val status = response.status.value
                        responseHttpStatus = status
                        if (status in setOf(401, 403, 429)) fail("SABR_HTTP_ACCESS_OR_RATE_LIMIT", status)
                        if (status in 500..599 || status == 408) throw RetryableTransfer(status, RetryableReason.HTTP_RETRYABLE_STATUS)
                        if (status in 300..399) {
                            response.headers[HttpHeaders.Location]?.let { checkedResource(it) }
                            fail("SABR_HTTP_REDIRECT_UNSUPPORTED", status)
                        }
                        if (status !in 200..299) fail("SABR_HTTP_UNAVAILABLE", status)
                        val mime = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                        if (mime !in setOf("application/vnd.yt-ump", "application/octet-stream")) fail("SABR_HTTP_CONTENT_TYPE_UNSUPPORTED")
                        val encoding = response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
                        if (!encoding.isNullOrEmpty() && encoding != "identity") fail("SABR_HTTP_ENCODING_UNSUPPORTED")
                        val declaredResponseBytes = response.headers[HttpHeaders.ContentLength]?.let {
                            val size = it.toLongOrNull() ?: fail("SABR_HTTP_LENGTH_INVALID")
                            if (size < 0 || size > limits.ump.maxTotalBytes) fail("SABR_HTTP_LENGTH_INVALID")
                            size
                        }
                        val decoder = YouTubeUmpDecoder(limits.ump)
                        currentStage = SabrTransferFailureStage.BODY_READ
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(8_192)
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                currentStage = SabrTransferFailureStage.BODY_READ
                                val count = channel.readAvailable(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                bytesThisRound += count
                                if (bytesThisRound > limits.ump.maxTotalBytes || state.httpBytes > Long.MAX_VALUE - count) fail("SABR_RESPONSE_BYTE_LIMIT")
                                state = state.copy(httpBytes = state.httpBytes + count)
                                responseState = responseState.copy(httpBytes = state.httpBytes)
                                currentStage = SabrTransferFailureStage.UMP_PARSE
                                for (part in decoder.feed(buffer.copyOf(count))) when (part.kind) {
                                    SabrUmpPartKind.MEDIA_HEADER -> {
                                        val header = part.mediaHeader(limits.wire)!!
                                        mediaHeaderCount++
                                        // Record actual nullable source fields BEFORE validation so a
                                        // rejected header remains explainable without logging its identity.
                                        if (mediaHeaders.size < limits.maxHeaderDiagnosticsPerResponse) mediaHeaders +=
                                            SabrTransferMediaHeaderDiagnostic(header.headerId, header.formatId?.itag ?: header.itag,
                                                header.startDataRange, header.contentLength, header.isInitialization,
                                                header.sequenceNumber, header.startMs, header.durationMs, header.compression,
                                                header.fields.binary(13) != null, header.videoId != null, header.fields.binary(15) != null)
                                        val packet = preparePacket(header, source.videoId, responseState)
                                        if (packet.headerId in packets) fail("SABR_HEADER_ID_REUSED_BEFORE_END")
                                        packets[packet.headerId] = packet
                                        if (!packet.ignored && packet.formatId !in responseState.receivedFormatIds)
                                            responseState = responseState.copy(receivedFormatIds = responseState.receivedFormatIds + packet.formatId)
                                    }
                                    SabrUmpPartKind.MEDIA_DATA -> {
                                        val packet = packets[part.mediaHeaderId()!!] ?: fail("SABR_MEDIA_WITHOUT_HEADER")
                                        val bytes = part.mediaBytes()!!
                                        if (bytes.isEmpty()) continue
                                        if (bytes.size > packet.length - packet.received) fail("SABR_PACKET_LENGTH_EXCEEDED")
                                        if (packet.ignored) {
                                            // Consume a registered same-video extra track without
                                            // acknowledging, staging or publishing its media.
                                            packet.received += bytes.size
                                            ignoredMediaPayloadBytes += bytes.size
                                            continue
                                        }
                                        val track = responseState.tracks.single { it.formatId == packet.formatId }
                                        val offset = packet.offset + packet.received
                                        if (bytes.size > limits.maxStagedMediaBytes - stagedBytes) fail("SABR_STAGED_MEDIA_LIMIT")
                                        staged += StagedWrite(packet.formatId, offset, bytes)
                                        stagedBytes += bytes.size
                                        val ranges = mergeRanges(track.coverage + SabrTransferByteRange(offset, offset + bytes.size))
                                        if (ranges.size > limits.maxRangesPerTrack) fail("SABR_BYTE_RANGE_LIMIT")
                                        responseState = responseState.copy(tracks = responseState.tracks.map { if (it.formatId == packet.formatId) it.copy(coverage = ranges) else it })
                                        packet.received += bytes.size
                                    }
                                    SabrUmpPartKind.MEDIA_END -> {
                                        if (part.payload.size != 1) fail("SABR_MEDIA_END_INVALID")
                                        val packet = packets.remove(part.mediaHeaderId()!!) ?: fail("SABR_END_WITHOUT_HEADER")
                                        if (packet.received != packet.length) fail("SABR_PACKET_LENGTH_INCOMPLETE")
                                        if (packet.ignored) { ignoredMediaPacketCount++; continue }
                                        val track = responseState.tracks.single { it.formatId == packet.formatId }
                                        val segment = SabrTransferSegment(packet.offset, packet.length, packet.sequence, packet.startMs, packet.durationMs, packet.initialization)
                                        val sameId = track.completedSegments.filter { it.sequence == segment.sequence && it.initialization == segment.initialization }
                                        if (sameId.any { it != segment }) fail("SABR_SEGMENT_IDENTITY_CONFLICT")
                                        val ended = if (segment in track.completedSegments) track.completedSegments else track.completedSegments + segment
                                        if (ended.size > limits.maxRangesPerTrack) fail("SABR_SEGMENT_LIMIT")
                                        responseState = responseState.copy(tracks = responseState.tracks.map { if (it.formatId == packet.formatId) it.copy(completedSegments = ended) else it })
                                    }
                                    SabrUmpPartKind.NEXT_REQUEST_POLICY -> {
                                        val fields = YouTubeProtobufWire.decode(part.payload, limits.wire)
                                        requireVideo(fields.string(8), source.videoId)
                                        val wait = fields.int32(4) ?: 0
                                        if (wait < 0 || wait.toLong() > limits.maxBackoffMs) fail("SABR_BACKOFF_UNSUPPORTED")
                                        roundBackoff = wait.toLong()
                                        // Every actual policy replaces the per-response cookie,
                                        // including an omitted nullable field7 (original rjb.x).
                                        val next = responseState.copy(playbackCookie = fields.binary(7))
                                        if (!opaqueStateWithinBudget(next)) fail("SABR_CONTEXT_STATE_LIMIT")
                                        responseState = next
                                    }
                                    SabrUmpPartKind.SABR_CONTEXT_UPDATE -> responseState = updateContext(responseState, part.payload)
                                    SabrUmpPartKind.SABR_CONTEXT_SENDING_POLICY -> responseState = sendingPolicy(responseState, part.payload)
                                    SabrUmpPartKind.FORMAT_INITIALIZATION_METADATA -> {
                                        val fields = YouTubeProtobufWire.decode(part.payload, limits.wire)
                                        val observedVideo = fields.string(1)
                                        requireVideo(observedVideo, source.videoId)
                                        val id = fields.binary(2)?.let { YouTubeSabrProtocol.decodeFormatId(it, limits.wire, 1) }
                                            ?: fail("SABR_FORMAT_INITIALIZATION_ID_MISSING")
                                        val observedMime = fields.string(5)?.substringBefore(';')?.trim()
                                        // Validate known scalar wire types even when the server
                                        // describes a track outside the requested selection.
                                        for (tag in listOf(3, 4, 8, 9, 10)) fields.int32(tag)
                                        for (tag in listOf(6, 7)) fields.binary(tag)?.let {
                                            val range = YouTubeProtobufWire.decode(it, limits.wire, 1)
                                            // Recovered Range has four optional INT32 aliases;
                                            // no precedence or offset normalization is inferred.
                                            for (rangeTag in 1..4) range.int32(rangeTag)
                                        }
                                        // An explicitly same-video metadata identity allows only
                                        // bounded discard of extra packets later in this session.
                                        // It never changes output selection or selected ACKs.
                                        if (observedVideo == source.videoId && id !in responseState.observedFormatIds) {
                                            if (responseState.observedFormatIds.size == 512) fail("SABR_OBSERVED_FORMAT_LIMIT")
                                            responseState = responseState.copy(observedFormatIds = responseState.observedFormatIds + id)
                                        }
                                        if (id !in selected) continue
                                        val mimeType = declared.single { it.id == id }.mimeType.substringBefore(';').trim()
                                        if (observedMime?.let { it != mimeType } == true) fail("SABR_FORMAT_MIME_CONFLICT")
                                        if (id !in responseState.receivedFormatIds) responseState = responseState.copy(receivedFormatIds = responseState.receivedFormatIds + id)
                                    }
                                    SabrUmpPartKind.STREAM_PROTECTION_STATUS -> {
                                        val protection = YouTubeProtobufWire.decode(part.payload, limits.wire).int32(1)
                                        if (protection == null) missingProtectionStatus = true
                                        else { statuses += protection; protectionStatus = protection }
                                    }
                                    SabrUmpPartKind.ERROR -> serverErrors += YouTubeProtobufWire.decode(part.payload, limits.wire).int32(2)
                                    SabrUmpPartKind.REDIRECT -> {
                                        val target = YouTubeProtobufWire.decode(part.payload, limits.wire).string(1)
                                            ?.takeIf { it.isNotBlank() } ?: fail("SABR_REDIRECT_INVALID")
                                        if (pendingRedirect != null && pendingRedirect != target) fail("SABR_CONFLICTING_PROTOCOL_REDIRECTS")
                                        // A server-issued protocol43 destination is not an HTTP
                                        // redirect. Stage it privately; keep draining late media,
                                        // errors and protection before authorizing continuation.
                                        pendingRedirect = target
                                    }
                                    SabrUmpPartKind.SNACKBAR_MESSAGE, SabrUmpPartKind.UNKNOWN -> Unit
                                }
                            }
                            // Some native HTTP engines report clean channel EOF even when
                            // Content-Length was not fulfilled. A syntactically whole UMP
                            // prefix cannot authorize bytes from that truncated response.
                            currentStage = SabrTransferFailureStage.RESPONSE_FINALIZE
                            if (declaredResponseBytes != null && bytesThisRound != declaredResponseBytes)
                                throw RetryableTransfer(null, RetryableReason.HTTP_CONTENT_LENGTH_MISMATCH)
                            decoder.finish()
                            // Original rjb.s publishes its packet callback only at MEDIA_END.
                            // Match that boundary before acknowledging coverage/context state.
                            if (packets.isNotEmpty()) throw RetryableTransfer(null, RetryableReason.MEDIA_END_MISSING)
                            completeFraming = true
                        } finally { channel.cancel() }
                    }
                    true
                }
                if (finished == null) throw RetryableTransfer(null, RetryableReason.RESPONSE_TIMEOUT)
                currentStage = SabrTransferFailureStage.RESPONSE_FINALIZE
                if (missingProtectionStatus) fail("SABR_PROTECTION_STATUS_MISSING")
                if (statuses.distinct().size > 1) fail("SABR_CONFLICTING_PROTECTION_STATUSES")
                if (serverErrors.isNotEmpty()) fail("SABR_SERVER_ERROR")
                action = YouTubeSabrResponsePolicy.classify(protectionStatus, stagedBytes > 0,
                    pendingRedirect != null || responseState.contexts.any { it.type in responseState.activeContextTypes })
                if (action == SabrResponseProtectionAction.REJECT_UNKNOWN_STATUS) fail("SABR_PROTECTION_STATUS_UNSUPPORTED")
                checkedResource(url) // Revalidate before issuer traffic or publishing classified response state.
                state = state.copy(completedRounds = state.completedRounds + 1,
                    playbackCookie = responseState.playbackCookie, contexts = responseState.contexts,
                    activeContextTypes = responseState.activeContextTypes, receivedFormatIds = responseState.receivedFormatIds,
                    observedFormatIds = responseState.observedFormatIds)
                when (action) {
                    SabrResponseProtectionAction.REQUIRED_REFRESH_EMPTY -> {
                        if (state.protectionRetries >= limits.maxProtectionRetries) fail("SABR_PROTECTION_RETRY_LIMIT")
                        state = state.copy(protectionRetries = state.protectionRetries + 1)
                        if (!acquireContext(refresh = true)) fail(if (integrityAccess == null) "SABR_PROTECTION_CONTEXT_UNAVAILABLE" else "SABR_PROTECTION_REFRESH_FAILED")
                    }
                    SabrResponseProtectionAction.OPTIONAL_REFRESH_EMPTY_BACKOFF -> {
                        if (state.protectionRetries >= limits.maxProtectionRetries) fail("SABR_PROTECTION_RETRY_LIMIT")
                        state = state.copy(protectionRetries = state.protectionRetries + 1)
                        if (integrityAccess != null && !acquireContext(refresh = true)) fail("SABR_PROTECTION_REFRESH_FAILED")
                        if (integrityAccess == null) integrityCode = "ISSUER_UNAVAILABLE"
                        if (roundBackoff == 0L) roundBackoff = limits.minimumProtectionBackoffMs
                    }
                    SabrResponseProtectionAction.OPTIONAL_REFRESH_WITH_MEDIA -> {
                        // Retained nonempty M catches refresh failure: already returned,
                        // source-authorized media remains usable. No context is invented.
                        acquireContext(refresh = true)
                    }
                    SabrResponseProtectionAction.ADVANCE_EMPTY_PROTOCOL -> {
                        if (state.consecutiveEmptyResponses >= limits.maxEmptyProtocolRounds) fail("SABR_EMPTY_PROTOCOL_ROUND_LIMIT")
                        state = state.copy(consecutiveEmptyResponses = state.consecutiveEmptyResponses + 1)
                    }
                    SabrResponseProtectionAction.REJECT_EMPTY -> fail("SABR_EMPTY_CONTINUATION")
                    else -> Unit
                }
                // Existing protection policy runs FIRST: an empty SPS3 response
                // still requires a real supplied issuer before any redirected POST.
                pendingRedirect?.let { target ->
                    validateRedirectEndpoint(target, activeEndpoint, state)
                    if (target == source.endpoint || target == activeEndpoint || target in state.protocolRedirects)
                        fail("SABR_PROTOCOL_REDIRECT_LOOP")
                    if (state.protocolRedirects.size >= limits.maxProtocolRedirects) fail("SABR_PROTOCOL_REDIRECT_LIMIT")
                    checkedResource(target)
                }
                if (stagedBytes > 0) {
                    checkedResource(url) // Authorization/current-task state may change while the response is staged.
                    state = state.copy(consecutiveEmptyResponses = 0, protectionRetries = 0)
                    currentStage = SabrTransferFailureStage.MEDIA_COMMIT
                    validateStagedWrites(staged, state, sinks)
                    for (write in staged) {
                        currentCoroutineContext().ensureActive()
                        currentStage = SabrTransferFailureStage.MEDIA_COMMIT
                        val track = state.tracks.single { it.formatId == write.formatId }
                        writeVerified(sinks.getValue(track.role), track.coverage, write.offset, write.bytes)
                        val ranges = mergeRanges(track.coverage + SabrTransferByteRange(write.offset, write.offset + write.bytes.size))
                        state = state.copy(tracks = state.tracks.map { if (it.formatId == write.formatId) it.copy(coverage = ranges) else it })
                        currentStage = SabrTransferFailureStage.CALLBACK
                        publish()
                    }
                    state = state.copy(tracks = state.tracks.map { track ->
                        track.copy(completedSegments = responseState.tracks.single { it.formatId == track.formatId }.completedSegments)
                    })
                }
                pendingRedirect?.let { target ->
                    checkedResource(target) // Caller authorization may have changed during durable writes.
                    // Original rjb.z -> UmpRequestHelper.b changes endpoint only.
                    // This is the same validated session, not a fresh extraction:
                    // keep source generation, counter, tracks, contexts and issuer binding.
                    state = state.copy(activeEndpoint = target, protocolRedirects = state.protocolRedirects + target)
                }
                retries = 0
                backoff = roundBackoff
                currentStage = SabrTransferFailureStage.CALLBACK
                publish()
                receipt(if (action == SabrResponseProtectionAction.OPTIONAL_REFRESH_WITH_MEDIA && integrityCode != null)
                    "SABR_AUTHORIZED_MEDIA_OPTIONAL_CONTEXT_REFRESH_UNAVAILABLE" else "SABR_RESPONSE_CLASSIFIED")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: SabrTransferException) {
                receipt(failure.code, failed = true)
                onCheckpoint(state); throw SabrTransferException(failure.code, state, failure.httpStatus, protectionStatus, diagnostics.toList())
            } catch (failure: IllegalArgumentException) {
                transportFailureType = safeFailureType(failure)
                failureFrames = safeFailureFrames(failure)
                // No response callback means no UMP was parsed. Header/body/caller
                // failures likewise are not evidence of malformed protocol data.
                val code = when (currentStage) {
                    SabrTransferFailureStage.HTTP_REQUEST -> "SABR_HTTP_REQUEST_FAILED"
                    SabrTransferFailureStage.UMP_PARSE, SabrTransferFailureStage.RESPONSE_FINALIZE,
                    SabrTransferFailureStage.MEDIA_COMMIT -> "SABR_MALFORMED_PROTOCOL"
                    else -> "SABR_TRANSFER_FAILED"
                }
                receipt(code, failed = true); onCheckpoint(state); fail(code)
            } catch (failure: Exception) {
                // Native I/O exception messages often contain a signed endpoint. Record
                // only the bounded class name; explicit recovery sites have fixed codes.
                transportFailureType = if (failure is RetryableTransfer) failure.reason.name
                    else safeFailureType(failure)
                failureFrames = safeFailureFrames(failure)
                receipt(if (failure is RetryableTransfer || failure is io.ktor.utils.io.errors.IOException) "SABR_INTERRUPTED_RESPONSE" else "SABR_TRANSFER_FAILED", failed = true)
                publish()
                if (failure !is RetryableTransfer && failure !is io.ktor.utils.io.errors.IOException)
                    fail("SABR_TRANSFER_FAILED")
                if (retries >= limits.maxRetries) fail("SABR_NETWORK_RECOVERY_EXHAUSTED", (failure as? RetryableTransfer)?.status)
                backoff = (limits.retryDelayMs * (1L shl retries)).coerceAtMost(8_000)
                retries++
            }
        }
        state.tracks.forEach { if (sinks.getValue(it.role).size() != it.expectedBytes) fail("SABR_OUTPUT_LENGTH_MISMATCH") }
        publish()
        return SabrTransferResult(state, state.tracks.associate { it.role to it.expectedBytes }, state.completedRounds, diagnostics.toList())
    }

    private fun safeFailureType(failure: Exception): String =
        failure::class.simpleName?.takeIf { it.length in 1..96 && it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) }
            ?: "UNNAMED_TRANSPORT_EXCEPTION"

    private fun safeFailureFrames(failure: Exception): List<String> = runCatching {
        val frame = Regex("\\bat ([A-Za-z0-9_.$<>]+)\\(")
        failure.stackTraceToString().lineSequence()
            .filter { it.startsWith("\tat ") || it.startsWith("    at ") }
            .mapNotNull { frame.find(it)?.groupValues?.get(1) }
            .filter { it.length in 1..256 && '.' in it }
            .take(8).toList()
    }.getOrDefault(emptyList())

    private fun requestUrl(endpoint: String, version: String, rn: Int): String {
        val parsed = Url(endpoint)
        require(parsed.protocol.name in setOf("https", "http") && parsed.host.isNotBlank() &&
            parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty() && parsed.fragment.isEmpty())
        val signed = listOfNotNull(parsed.parameters["sparams"], parsed.parameters["lsparams"]).flatMap { it.split(',') }.toSet()
        require("cver" !in signed && "rn" !in signed) { "Source signs the request counters" }
        // Preserve every other signed query byte, including n, rather than reparsing/re-encoding its values.
        return YouTubeUrlAssembly.replaceParameter(YouTubeUrlAssembly.replaceParameter(endpoint, "cver", version), "rn", rn.toString())
    }

    /** Same explicit UNIX-seconds convention as YouTubeProvider; invalid values remain unknown. */
    private fun declaredEndpointExpiry(endpoint: String): Long? = Url(endpoint).parameters["expire"]?.toLongOrNull()
        ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)

    private fun buildRequest(source: YouTubeSabrSource, declared: List<YouTubeSabrSourceFormat>, state: SabrTransferCheckpoint,
        issuedMediaContext: ByteArray? = null): ByteArray {
        val ranges = state.tracks.flatMap { track -> track.completedSegments.filter { !it.initialization && it.durationMs > 0 }.map { segment ->
            // BufferedRange: FormatId1, start2/duration3 INT64, start/end segment4/5 INT32.
            YouTubeProtobufWire.encode(listOf(sabrBytes(1, YouTubeSabrProtocol.encodeFormatId(track.formatId, limits.wire)),
                sabrInt(2, segment.startTimeMs), sabrInt(3, segment.durationMs),
                sabrInt(4, segment.sequence.toLong()), sabrInt(5, segment.sequence.toLong())), limits.wire)
        } }
        val endTimes = state.tracks.map { track ->
            var end = 0L
            for (segment in track.completedSegments.filter { !it.initialization }.sortedBy { it.startTimeMs }) {
                if (segment.startTimeMs > end) break // A future range cannot acknowledge a missing earlier interval.
                end = maxOf(end, segment.startTimeMs + segment.durationMs)
            }
            end
        }
        val enabled = when { state.selectedAudio != null && state.selectedVideo != null -> SabrEnabledTrackTypes.VIDEO_AND_AUDIO
            state.selectedAudio != null -> SabrEnabledTrackTypes.AUDIO_ONLY; else -> SabrEnabledTrackTypes.VIDEO_ONLY }
        val sending = state.contexts.filter { it.type in state.activeContextTypes }
            .map { YouTubeSabrProtocol.encodeSabrContext(SabrContextValue(it.type, it.value), limits.wire) }
        val unsent = state.contexts.filter { it.type !in state.activeContextTypes }.map { it.type }
        return YouTubeSabrProtocol.encodeInitialRequest(SabrInitialRequest(source.ustreamerConfig,
            selectedFormatIds = state.receivedFormatIds, selectedAudioFormatIds = listOfNotNull(state.selectedAudio),
            selectedVideoFormatIds = listOfNotNull(state.selectedVideo), bufferedRanges = ranges,
            clientAbrState = SabrClientAbrState(stickyResolution = declared.firstOrNull { it.role == TrackRole.VIDEO }?.height,
                viewportFlexible = false, playerTimeMs = endTimes.minOrNull() ?: 0, visibility = 1, playbackRate = 1f,
                enabledTrackTypesBitfield = enabled.value),
            streamerContext = SabrStreamerContext(clientInfo = state.clientInfo, poToken = issuedMediaContext, playbackCookie = state.playbackCookie,
                sabrContexts = sending, unsentSabrContexts = unsent)), limits.wire)
    }

    private fun validateCheckpoint(state: SabrTransferCheckpoint, source: YouTubeSabrSource,
        selection: YouTubeSabrSelection, client: SabrClientInfo, sizes: Map<SabrFormatId, Long>) {
        require(state.videoId == source.videoId && state.selectedAudio == selection.audio &&
            state.selectedVideo == selection.video && state.clientInfo == client) { "Checkpoint source, selection or client differs" }
        require(state.resourceGeneration == YouTubeIntegrityBinding.forSabr(source, client).resourceGeneration) {
            "Checkpoint belongs to a different actual resource generation"
        }
        if (state.protocolRedirects.size > limits.maxProtocolRedirects)
            throw SabrTransferException("SABR_PROTOCOL_REDIRECT_LIMIT", state)
        if (state.protocolRedirects.size > state.completedRounds)
            throw SabrTransferException("SABR_REDIRECT_CHECKPOINT_INVALID", state)
        if ((state.protocolRedirects.isEmpty() && state.activeEndpoint != null) ||
            (state.protocolRedirects.isNotEmpty() && state.activeEndpoint != state.protocolRedirects.last()))
            throw SabrTransferException("SABR_REDIRECT_CHECKPOINT_INVALID", state)
        var previousEndpoint = source.endpoint
        val visited = mutableSetOf(source.endpoint)
        for (endpoint in state.protocolRedirects) {
            validateRedirectEndpoint(endpoint, previousEndpoint, state)
            if (!visited.add(endpoint)) throw SabrTransferException("SABR_PROTOCOL_REDIRECT_LOOP", state)
            previousEndpoint = endpoint
        }
        require(state.nextRequestNumber > 0 && state.completedRounds >= 0 && state.httpBytes >= 0)
        require(state.consecutiveEmptyResponses in 0..limits.maxEmptyProtocolRounds && state.protectionRetries in 0..limits.maxProtectionRetries)
        require(state.completedRounds <= state.nextRequestNumber - 1) { "Persisted SABR round count exceeds actual request sequence" }
        require(state.tracks.size == sizes.size && state.tracks.map { it.formatId }.toSet() == sizes.keys &&
            state.tracks.map { it.role }.distinct().size == state.tracks.size)
        state.tracks.forEach { track ->
            require(track.expectedBytes == sizes.getValue(track.formatId))
            require(track.role == if (track.formatId == selection.audio) TrackRole.AUDIO else TrackRole.VIDEO)
            require(track.coverage.size <= limits.maxRangesPerTrack && track.completedSegments.size <= limits.maxRangesPerTrack)
            require(track.coverage == mergeRanges(track.coverage) && track.coverage.all { it.endExclusive <= track.expectedBytes })
            track.completedSegments.forEach { segment ->
                require(segment.offset <= track.expectedBytes - segment.length && covered(track.coverage, segment.offset, segment.offset + segment.length))
            }
            require(track.completedSegments.groupBy { it.sequence to it.initialization }.values.all { group -> group.distinct().size == 1 })
        }
        require(state.contexts.size <= limits.maxContexts && state.contexts.map { it.type }.distinct().size == state.contexts.size)
        require(state.contexts.all { it.value.size <= limits.wire.maxFieldBytes })
        require(state.activeContextTypes.size <= limits.maxContexts && state.activeContextTypes.distinct().size == state.activeContextTypes.size)
        require(state.playbackCookie == null || state.playbackCookie.size <= limits.wire.maxFieldBytes)
        require(opaqueStateWithinBudget(state)) { "Persisted SABR context state exceeds the aggregate byte budget" }
        require(state.receivedFormatIds.distinct().size == state.receivedFormatIds.size && state.receivedFormatIds.all { it in sizes })
        require(state.observedFormatIds.size <= 512 && state.observedFormatIds.distinct().size == state.observedFormatIds.size)
        // Round-trip the actual typed identities, never accept truncated or
        // wildcard-matched persisted IDs as registration for an extra packet.
        require(state.observedFormatIds.all {
            YouTubeSabrProtocol.decodeFormatId(YouTubeSabrProtocol.encodeFormatId(it, limits.wire), limits.wire) == it
        })
    }

    private fun validateRedirectEndpoint(endpoint: String, previous: String, state: SabrTransferCheckpoint) {
        fun fail(code: String): Nothing = throw SabrTransferException(code, state)
        if (endpoint.length !in 1..65_536 || endpoint.any { it.code <= 32 || it.code == 127 }) fail("SABR_REDIRECT_INVALID")
        val parsed = try { Url(endpoint) } catch (_: IllegalArgumentException) { fail("SABR_REDIRECT_INVALID") }
        if (parsed.protocol.name !in setOf("https", "http") || parsed.host.isBlank() ||
            !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty() || parsed.fragment.isNotEmpty())
            fail("SABR_REDIRECT_INVALID")
        if (Url(previous).protocol.name == "https" && parsed.protocol.name != "https") fail("SABR_REDIRECT_TLS_DOWNGRADE")
    }

    private fun preparePacket(header: SabrUmpMediaHeader, videoId: String, state: SabrTransferCheckpoint): ActivePacket {
        fun fail(code: String): Nothing = throw SabrTransferException(code, state)
        val id = header.headerId?.takeIf { it <= 255u }?.toInt() ?: fail("SABR_HEADER_ID_INVALID")
        if (header.videoId != videoId) fail("SABR_MEDIA_VIDEO_ID_MISMATCH")
        if (header.fields.binary(13) != null && header.formatId == null) fail("SABR_MEDIA_FORMAT_ID_INCOMPLETE")
        val top = header.itag?.let { SabrFormatId(it, header.lastModified, header.xtags) }
        val format = header.formatId ?: top ?: fail("SABR_MEDIA_FORMAT_ID_MISSING")
        if (header.formatId != null && (header.itag?.let { it != format.itag } == true ||
                header.lastModified?.let { it != format.lastModified } == true ||
                header.xtags?.let { it != format.xtags } == true)) fail("SABR_MEDIA_FORMAT_ID_CONFLICT")
        val track = state.tracks.singleOrNull { it.formatId == format }
        val ignored = track == null
        if (ignored && format !in state.observedFormatIds) fail("SABR_MEDIA_FORMAT_ID_MISMATCH")
        if (header.compression != null && header.compression != 0) fail("SABR_MEDIA_COMPRESSION_UNSUPPORTED")
        val offset = header.startDataRange?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong() ?: fail("SABR_MEDIA_OFFSET_MISSING")
        val length = header.contentLength?.takeIf { it > 0 } ?: fail("SABR_PACKET_LENGTH_MISSING")
        if (track != null) {
            if (length > track.expectedBytes || offset > track.expectedBytes - length) fail("SABR_PACKET_RANGE_OUTSIDE_SOURCE")
        } else if (length > limits.ump.maxTotalBytes || offset > Long.MAX_VALUE - length)
            fail("SABR_IGNORED_PACKET_RANGE_INVALID")
        // Verified original classes3.dex rjb.r(MediaHeader), code_off 6093756:
        // optional sequence_number uses 0L when absent, regardless of is_init_seg.
        // Preserve null in the structural receipt; never truncate an actual value.
        val sourceSequence = header.sequenceNumber ?: 0L
        val sequence = sourceSequence.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
            ?: fail("SABR_SEGMENT_SEQUENCE_INVALID")
        val initialization = header.isInitialization ?: false
        val (start, duration) = packetTime(header, initialization, state)
        return ActivePacket(id, format, offset, length, sequence, start, duration, initialization, ignored = ignored)
    }

    private fun packetTime(header: SabrUmpMediaHeader, initialization: Boolean, state: SabrTransferCheckpoint): Pair<Long, Long> {
        val range = header.fields.binary(15)?.let { YouTubeProtobufWire.decode(it, limits.wire, 1) }
        val start = header.startMs?.toLong() ?: range?.int64(1)?.let { scaleMs(it, range.int32(3)) }
        val duration = header.durationMs?.toLong() ?: range?.int64(2)?.let { scaleMs(it, range.int32(3)) }
        if (start != null && start < 0 || duration != null && duration < 0) throw SabrTransferException("SABR_SEGMENT_TIME_INVALID", state)
        if (!initialization && (start == null || duration == null)) throw SabrTransferException("SABR_SEGMENT_TIME_MISSING", state)
        val s = start ?: 0L; val d = duration ?: 0L
        if (s > Long.MAX_VALUE - d) throw SabrTransferException("SABR_SEGMENT_TIME_OVERFLOW", state)
        return s to d
    }
    private fun scaleMs(value: Long, scale: Int?): Long {
        require(value >= 0 && scale != null && scale > 0)
        val whole = value / scale
        require(whole <= Long.MAX_VALUE / 1000)
        val fraction = (value % scale) * 1000 / scale
        require(whole * 1000 <= Long.MAX_VALUE - fraction)
        return whole * 1000 + fraction
    }
    private suspend fun writeVerified(sink: YouTubeSabrTrackSink, ranges: List<SabrTransferByteRange>, offset: Long, bytes: ByteArray) {
        verifyExistingOverlap(sink, ranges, offset, bytes)
        sink.write(offset, bytes)
    }
    private suspend fun verifyExistingOverlap(sink: YouTubeSabrTrackSink, ranges: List<SabrTransferByteRange>, offset: Long, bytes: ByteArray) {
        val end = offset + bytes.size
        for (range in ranges) {
            val from = maxOf(offset, range.start); val to = minOf(end, range.endExclusive)
            if (from < to) {
                val count = (to - from).toInt()
                val actual = sink.read(from, count)
                require(actual.size == count && actual.contentEquals(bytes.copyOfRange((from - offset).toInt(), (to - offset).toInt()))) {
                    "Overlapping media bytes disagree"
                }
            }
        }
    }

    /** Reject contradictory bytes anywhere in a classified response before publishing any of it. */
    private suspend fun validateStagedWrites(writes: List<StagedWrite>, state: SabrTransferCheckpoint,
        sinks: Map<TrackRole, YouTubeSabrTrackSink>) {
        for ((index, write) in writes.withIndex()) {
            currentCoroutineContext().ensureActive()
            val track = state.tracks.single { it.formatId == write.formatId }
            verifyExistingOverlap(sinks.getValue(track.role), track.coverage, write.offset, write.bytes)
            for (earlierIndex in 0 until index) {
                val earlier = writes[earlierIndex]
                if (earlier.formatId != write.formatId) continue
                val start = maxOf(earlier.offset, write.offset)
                val end = minOf(earlier.offset + earlier.bytes.size, write.offset + write.bytes.size)
                if (start >= end) continue
                var at = start
                while (at < end) {
                    if ((at - start) % 16_384 == 0L) currentCoroutineContext().ensureActive()
                    require(earlier.bytes[(at - earlier.offset).toInt()] == write.bytes[(at - write.offset).toInt()]) {
                        "Staged media byte ranges disagree"
                    }
                    at++
                }
            }
        }
    }

    private fun updateContext(state: SabrTransferCheckpoint, payload: ByteArray): SabrTransferCheckpoint {
        val fields = YouTubeProtobufWire.decode(payload, limits.wire)
        val type = fields.int32(1) ?: throw SabrTransferException("SABR_CONTEXT_TYPE_MISSING", state)
        val value = fields.binary(3) ?: throw SabrTransferException("SABR_CONTEXT_VALUE_MISSING", state)
        val policy = fields.int32(5) ?: throw SabrTransferException("SABR_CONTEXT_WRITE_POLICY_MISSING", state)
        if (policy !in 0..2) throw SabrTransferException("SABR_CONTEXT_WRITE_POLICY_UNSUPPORTED", state)
        fields.int32(2)?.let { require(it in 0..4) }
        val existing = state.contexts.any { it.type == type }
        val contexts = if (policy == 2 && existing) state.contexts
            else state.contexts.filterNot { it.type == type } + SabrTransferContextCheckpoint(type, value)
        val active = if (fields.boolean(4) == true) (state.activeContextTypes + type).distinct() else state.activeContextTypes
        if (contexts.size > limits.maxContexts || active.size > limits.maxContexts) throw SabrTransferException("SABR_CONTEXT_LIMIT", state)
        val next = state.copy(contexts = contexts, activeContextTypes = active)
        if (!opaqueStateWithinBudget(next)) throw SabrTransferException("SABR_CONTEXT_STATE_LIMIT", state)
        return next
    }
    private fun sendingPolicy(state: SabrTransferCheckpoint, payload: ByteArray): SabrTransferCheckpoint {
        val fields = YouTubeProtobufWire.decode(payload, limits.wire)
        fun ids(number: Int): List<Int> = fields.filter { it.number == number }.map {
            require(it.value is SabrWireValue.Varint); YouTubeProtobufWire.decodeInt32(it.value.value)
        }
        val start = ids(1); val stop = ids(2).toSet(); val discard = ids(3).toSet()
        val active = (state.activeContextTypes + start).distinct().filterNot { it in stop }
        val contexts = state.contexts.filterNot { it.type in discard }
        if (active.size > limits.maxContexts) throw SabrTransferException("SABR_CONTEXT_LIMIT", state)
        return state.copy(contexts = contexts, activeContextTypes = active)
    }
    private fun requireVideo(observed: String?, expected: String) { require(observed == null || observed == expected) { "Continuation source video differs" } }
    private fun opaqueStateWithinBudget(state: SabrTransferCheckpoint): Boolean =
        (state.playbackCookie?.size?.toLong() ?: 0) + state.contexts.sumOf { it.value.size.toLong() } <= limits.maxContextBytes
    private fun complete(state: SabrTransferCheckpoint): Boolean = state.tracks.all { track ->
        val ended = mergeRanges(track.completedSegments.map { SabrTransferByteRange(it.offset, it.offset + it.length) })
        ended.size == 1 && ended[0].start == 0L && ended[0].endExclusive == track.expectedBytes
    }
    private fun covered(ranges: List<SabrTransferByteRange>, start: Long, end: Long) = ranges.any { it.start <= start && it.endExclusive >= end }
    private fun mergeRanges(input: List<SabrTransferByteRange>): List<SabrTransferByteRange> {
        val result = mutableListOf<SabrTransferByteRange>()
        for (range in input.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && range.start <= last.endExclusive) result[result.lastIndex] = SabrTransferByteRange(last.start, maxOf(last.endExclusive, range.endExclusive))
            else result += range
        }
        return result
    }
    private class ActivePacket(val headerId: Int, val formatId: SabrFormatId, val offset: Long, val length: Long,
        val sequence: Int, val startMs: Long, val durationMs: Long, val initialization: Boolean, var received: Long = 0,
        val ignored: Boolean = false)
    private class StagedWrite(val formatId: SabrFormatId, val offset: Long, val bytes: ByteArray)
    private enum class RetryableReason {
        HTTP_RETRYABLE_STATUS, HTTP_CONTENT_LENGTH_MISMATCH, MEDIA_END_MISSING, RESPONSE_TIMEOUT,
    }
    private class RetryableTransfer(val status: Int?, val reason: RetryableReason) : Exception("SABR_TRANSIENT_TRANSFER")
}

private fun sabrInt(number: Int, value: Long) = SabrWireField(number, SabrWireValue.Varint(value.toULong()))
private fun sabrBytes(number: Int, value: ByteArray) = SabrWireField(number, SabrWireValue.Bytes(value))
private fun List<SabrWireField>.singleValue(number: Int): SabrWireValue? {
    val values = filter { it.number == number }; require(values.size <= 1) { "Repeated singleton SABR field" }; return values.firstOrNull()?.value
}
private fun List<SabrWireField>.integer(number: Int): ULong? = singleValue(number)?.let { require(it is SabrWireValue.Varint); it.value }
private fun List<SabrWireField>.int32(number: Int): Int? = integer(number)?.let(YouTubeProtobufWire::decodeInt32)
private fun List<SabrWireField>.int64(number: Int): Long? = integer(number)?.let(YouTubeProtobufWire::decodeInt64)
private fun List<SabrWireField>.binary(number: Int): ByteArray? = singleValue(number)?.let { require(it is SabrWireValue.Bytes); it.value }
private fun List<SabrWireField>.string(number: Int): String? = binary(number)?.decodeToString(throwOnInvalidSequence = true)
private fun List<SabrWireField>.boolean(number: Int): Boolean? = integer(number)?.let { require(it <= 1uL); it == 1uL }
