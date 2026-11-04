package dev.kmpyt.extraction

import dev.kmpyt.UrlPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

data class YouTubeSabrProbeAuthorization(val videoId: String) {
    init { require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Explicit authorized video identity is required" } }
}

data class YouTubeSabrProbeLimits(
    val maxResponseBytes: Int = 4_194_304,
    val maxPartBytes: Int = 1_048_576,
    val maxParts: Int = 256,
    val timeoutMs: Long = 30_000,
) {
    init {
        require(maxResponseBytes in 1..16_777_216 && maxPartBytes in 1..maxResponseBytes && maxParts in 1..4_096)
        require(timeoutMs in 1..120_000)
    }
}

data class YouTubeSabrSelection(val audio: SabrFormatId? = null, val video: SabrFormatId? = null) {
    init { require(audio != null || video != null) { "Select an actual audio or video format" } }
}

data class YouTubeSabrSourceFormat(val id: SabrFormatId, val role: TrackRole, val mimeType: String,
    val width: Int?, val height: Int?, val bitrate: Long?, val audioTrackId: String?, val defaultAudio: Boolean?)

class YouTubeSabrSource(val videoId: String, val endpoint: String, val ustreamerConfig: ByteArray,
    val formats: List<YouTubeSabrSourceFormat>, val expiresAtEpochMs: Long?) {
    /** Chooses supplied descriptors, without creating a missing resolution or format ID. */
    fun select(maxVideoHeight: Int = 360): YouTubeSabrSelection? {
        require(maxVideoHeight > 0)
        val video = formats.filter { it.role == TrackRole.VIDEO && it.height != null && it.height <= maxVideoHeight }
            .maxWithOrNull(compareBy<YouTubeSabrSourceFormat> { it.height }.thenBy { it.bitrate })
        val audio = formats.filter { it.role == TrackRole.AUDIO }
            .maxWithOrNull(compareBy<YouTubeSabrSourceFormat> { it.defaultAudio == true }.thenBy { it.bitrate })
        return if (video == null && audio == null) null else YouTubeSabrSelection(audio?.id, video?.id)
    }
    override fun toString() = "YouTubeSabrSource(videoId=$videoId, endpoint=${ResearchRedaction.url(endpoint)}, configBytes=${ustreamerConfig.size}, formats=${formats.size})"
}

data class YouTubeSabrDiscovery(val source: YouTubeSabrSource?, val diagnostics: List<ExtractionDiagnostic>)

data class YouTubeSabrMediaHeaderSummary(val headerId: UInt?, val itag: Int?, val formatItag: Int?,
    val sequenceNumber: Long?, val initialization: Boolean?, val compression: Int?, val contentLength: Long?,
    val videoMatches: Boolean?, val formatMatches: Boolean?)
data class YouTubeSabrProtectionSummary(val status: Int?, val field2: Int?)
data class YouTubeSabrErrorSummary(val code: Int?, val typePresent: Boolean)
data class YouTubeSabrPartSummary(val type: Int, val kind: SabrUmpPartKind, val payloadBytes: Int,
    val headerId: Int? = null, val mediaBytes: Int = 0, val header: YouTubeSabrMediaHeaderSummary? = null,
    val protection: YouTubeSabrProtectionSummary? = null, val error: YouTubeSabrErrorSummary? = null)
data class YouTubeSabrProbeResult(val videoId: String, val httpStatus: Int?, val responseHeaders: Map<String, String>,
    val selectedFormats: List<SabrFormatId>, val receivedBytes: Int, val completeFraming: Boolean,
    val parts: List<YouTubeSabrPartSummary>, val diagnostics: List<ExtractionDiagnostic>) {
    override fun toString() = "YouTubeSabrProbeResult(videoId=$videoId, status=$httpStatus, selected=${selectedFormats.size}, receivedBytes=$receivedBytes, completeFraming=$completeFraming, parts=${parts.size}, payloads=redacted)"
}

/**
 * A single initial SABR protocol inspection, not a production downloader. Endpoint,
 * Ustreamer bytes and FormatIds come from one matching actual player response;
 * client metadata is supplied by its observed configuration. No PoToken, cookie,
 * client profile or continuation state is generated. Unknown response payloads are
 * counted and discarded after framing. Redirect/protection records cause no requests.
 */
class YouTubeInitialSabrProbe(client: HttpClient, private val authorization: YouTubeSabrProbeAuthorization,
    private val limits: YouTubeSabrProbeLimits = YouTubeSabrProbeLimits()) {
    // Share only the native engine. Copying client.config would also inherit auth,
    // default cookies/headers and retry plugins, violating this anonymous one-POST
    // boundary. External-engine construction does not take ownership of the engine.
    private val http = HttpClient(client.engine) { followRedirects = false; expectSuccess = false }

    fun close() = http.close()

    fun discover(context: ExtractionContext, response: ResearchResponse): YouTubeSabrDiscovery {
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        fun unavailable(code: String, message: String) = YouTubeSabrDiscovery(null, diagnostics + diagnostic(code, message))
        if (response.status !in 200..299) return unavailable("SABR_SOURCE_HTTP_UNAVAILABLE", "Player response returned HTTP ${response.status}; no SABR request was prepared.")
        val expected = YouTubeProvider().normalize(context.pageUrl)?.let { Url(it).parameters["v"] }
            ?: return unavailable("SABR_VIDEO_ID_UNAVAILABLE", "SABR discovery needs an explicit recognizable video URL.")
        val player = try { playerDocuments(response.body).firstOrNull { it.obj("videoDetails")?.text("videoId") == expected } }
            catch (_: Exception) { null }
            ?: return unavailable("SABR_PLAYER_DATA_UNAVAILABLE", "No bounded matching player object supplied the actual video identity.")
        if (player.obj("playabilityStatus")?.text("status")?.let { it != "OK" } == true)
            return unavailable("SABR_SOURCE_ACCESS_REQUIRED", "The player reports unavailable or restricted playback; initial probing is not attempted.")
        val stream = player.obj("streamingData") ?: return unavailable("SABR_STREAMING_DATA_NOT_PROVIDED", "The matching player did not supply streamingData.")
        if (!stream.array("licenseInfos").isNullOrEmpty() || !stream.array("drmFamilies").isNullOrEmpty())
            return unavailable("SABR_ENCRYPTED_SOURCE_UNSUPPORTED", "The supplied source declares encrypted media; it is not used for this initial probe.")
        val endpoint = stream.text("serverAbrStreamingUrl")
            ?: return unavailable("SABR_ENDPOINT_NOT_PROVIDED", "The matching player did not supply serverAbrStreamingUrl.")
        if (!permittedEndpoint(response.url, endpoint)) return unavailable("SABR_ENDPOINT_UNSUPPORTED", "The supplied SABR endpoint is not a permitted source-bound HTTP(S) destination.")
        val config = player.obj("playerConfig")?.obj("mediaCommonConfig")?.obj("mediaUstreamerRequestConfig")?.text("videoPlaybackUstreamerConfig")
            ?: return unavailable("SABR_USTREAMER_CONFIG_NOT_PROVIDED", "The matching player did not supply videoPlaybackUstreamerConfig; no configuration was fabricated.")
        val decoded = try { decodeConfiguration(config) } catch (_: Exception) { null }
            ?: return unavailable("SABR_USTREAMER_CONFIG_INVALID", "The supplied configuration is invalid/oversized base64; its contents are not logged.")
        val formats = buildList {
            stream.array("adaptiveFormats")?.take(512)?.forEach { element ->
                val format = element as? JsonObject ?: return@forEach
                if (format["isDrm"] == JsonPrimitive(true) || !format.array("drmFamilies").isNullOrEmpty() || !format.array("licenseInfos").isNullOrEmpty()) return@forEach
                val itag = format.number("itag")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return@forEach
                val rawModified = format.text("lastModified")
                val modified = rawModified?.toULongOrNull()
                if (rawModified != null && modified == null) return@forEach
                val mime = format.text("mimeType")?.substringBefore(';')?.trim() ?: return@forEach
                val role = when { mime.startsWith("audio/") -> TrackRole.AUDIO; mime.startsWith("video/") -> TrackRole.VIDEO; else -> return@forEach }
                val track = format.obj("audioTrack")
                val xtags = format.text("xtags")?.takeIf { it.length <= 4_096 }
                if (format.text("xtags") != null && xtags == null) return@forEach
                add(YouTubeSabrSourceFormat(SabrFormatId(itag, modified, xtags), role, mime,
                    format.number("width")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
                    format.number("height")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
                    (format.number("averageBitrate") ?: format.number("bitrate"))?.takeIf { it >= 0 },
                    track?.text("id"), (track?.get("audioIsDefault") as? JsonPrimitive)?.booleanOrNull))
            }
        }
        if (formats.isEmpty()) return unavailable("SABR_FORMAT_IDS_UNAVAILABLE", "No usable source audio/video FormatIds were declared; no itags were invented.")
        val expiry = runCatching { Url(endpoint).parameters["expire"]?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000) }.getOrNull()
        if (Url(endpoint).parameters["n"] != null) diagnostics += diagnostic("SABR_ENDPOINT_N_UNRESOLVED", "The source endpoint contains n. It is preserved exactly; this probe neither guesses a transform nor implies media-server acceptance.")
        diagnostics += diagnostic("SABR_SOURCE_DISCOVERED", "Read ${formats.size} actual adaptive FormatIds and ${decoded.size} Ustreamer configuration bytes for the matching video. Context payloads and endpoint query values are hidden.")
        return YouTubeSabrDiscovery(YouTubeSabrSource(expected, endpoint, decoded, formats, expiry), diagnostics)
    }

    suspend fun inspect(context: ExtractionContext, response: ResearchResponse, clientInfo: SabrClientInfo,
        selection: YouTubeSabrSelection, headers: Map<String, String> = emptyMap()): YouTubeSabrProbeResult {
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        val selected = listOfNotNull(selection.audio, selection.video)
        val summaries = mutableListOf<YouTubeSabrPartSummary>()
        var status: Int? = null
        var replyHeaders = emptyMap<String, String>()
        var received = 0
        var complete = false
        fun result() = YouTubeSabrProbeResult(authorization.videoId, status, replyHeaders, selected, received, complete, summaries.toList(), diagnostics.toList())
        val discovery = discover(context, response)
        diagnostics += discovery.diagnostics
        val source = discovery.source ?: return result()
        if (source.videoId != authorization.videoId) {
            diagnostics += diagnostic("SABR_VIDEO_NOT_AUTHORIZED_FOR_PROBE", "The source video is outside the caller's explicit initial-probe authorization. No binary request was sent.")
            return result()
        }
        if (source.expiresAtEpochMs != null && context.nowEpochMs > 0 && source.expiresAtEpochMs <= context.nowEpochMs) {
            diagnostics += diagnostic("SABR_SOURCE_URL_EXPIRED", "The actual source endpoint has expired. Obtain a fresh authorized source response; no URL parameter was changed.")
            return result()
        }
        val audio = selection.audio?.let { id -> source.formats.firstOrNull { it.id == id && it.role == TrackRole.AUDIO } }
        val video = selection.video?.let { id -> source.formats.firstOrNull { it.id == id && it.role == TrackRole.VIDEO } }
        if (selection.audio != null && audio == null || selection.video != null && video == null) {
            diagnostics += diagnostic("SABR_SELECTION_NOT_IN_SOURCE", "Selected FormatIds do not exactly match source-declared roles, lastModified and xtags. No request was sent.")
            return result()
        }
        validateHeaders(headers, clientInfo)
        val tracks = when { audio != null && video != null -> SabrEnabledTrackTypes.VIDEO_AND_AUDIO; audio != null -> SabrEnabledTrackTypes.AUDIO_ONLY; else -> SabrEnabledTrackTypes.VIDEO_ONLY }
        val request = SabrInitialRequest(source.ustreamerConfig, selectedAudioFormatIds = listOfNotNull(selection.audio),
            selectedVideoFormatIds = listOfNotNull(selection.video),
            // This is our explicit initial playback state: beginning at time zero,
            // visible at normal speed with a fixed viewport. These are application
            // state, not claimed response fields. Format/resolution come from source.
            // Initial received-format/range lists remain empty.
            clientAbrState = SabrClientAbrState(stickyResolution = video?.height, viewportFlexible = false,
                playerTimeMs = 0, visibility = 1, playbackRate = 1f, enabledTrackTypesBitfield = tracks.value),
            streamerContext = SabrStreamerContext(clientInfo = clientInfo))
        val body = YouTubeSabrProtocol.encodeInitialRequest(request)
        diagnostics += diagnostic("SABR_INITIAL_REQUEST", "POST ${ResearchRedaction.url(source.endpoint)} with ${body.size} protobuf bytes and ${selected.size} selected actual FormatIds. One initial request; no PoToken, account cookie, challenge or continuation.")
        try {
            val completed = withTimeoutOrNull(limits.timeoutMs) {
                http.prepareRequest(source.endpoint) {
                    method = HttpMethod.Post
                    headers.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.Accept, "application/vnd.yt-ump")
                    header(HttpHeaders.AcceptEncoding, "identity")
                    contentType(ContentType.parse("application/x-protobuf"))
                    setBody(body)
                }.execute { reply ->
                    status = reply.status.value
                    replyHeaders = reply.headers.entries().associate { (name, values) ->
                        name.lowercase() to if (name.equals(HttpHeaders.ContentType, true) || name.equals(HttpHeaders.ContentLength, true) || name.equals(HttpHeaders.ContentEncoding, true))
                            values.joinToString(",").take(128) else "[redacted]"
                    }
                    diagnostics += diagnostic("SABR_HTTP_RESPONSE", "Initial binary request returned HTTP $status.")
                    if (reply.status.value !in 200..299) {
                        diagnostics += diagnostic(if (reply.status.value in setOf(401, 403, 429)) "SABR_HTTP_ACCESS_OR_RATE_LIMIT" else "SABR_HTTP_UNAVAILABLE", "The response is not successful. No redirect, client fallback, retry or authorization workaround was attempted.")
                        return@execute
                    }
                    val mime = reply.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                    if (mime != "application/vnd.yt-ump" && mime != "application/octet-stream") {
                        diagnostics += diagnostic("SABR_HTTP_CONTENT_TYPE_UNSUPPORTED", "The endpoint did not return a declared UMP/binary content type; its body was not parsed as media.")
                        return@execute
                    }
                    val encoding = reply.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
                    if (!encoding.isNullOrEmpty() && encoding != "identity") {
                        diagnostics += diagnostic("SABR_HTTP_ENCODING_UNSUPPORTED", "The binary HTTP response uses an unsupported content encoding. No decompression or media equivalence was assumed.")
                        return@execute
                    }
                    require((reply.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= limits.maxResponseBytes) { "SABR response-size limit exceeded" }
                    val decoder = YouTubeUmpDecoder(SabrUmpLimits(limits.maxPartBytes, limits.maxResponseBytes.toLong(), limits.maxParts))
                    val channel = reply.bodyAsChannel()
                    val buffer = ByteArray(8_192)
                    try {
                        while (true) {
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            require(count <= limits.maxResponseBytes - received) { "SABR response-size limit exceeded" }
                            received += count
                            if (count > 0) decoder.feed(buffer.copyOf(count)).forEach { part ->
                                summaries += summarize(part, source.videoId, selected)
                            }
                        }
                        decoder.finish()
                        complete = true
                    } finally { channel.cancel() }
                }
            }
            if (completed == null) diagnostics += diagnostic("SABR_PROBE_TIMEOUT", "The single initial request exceeded its configured time bound. It was cancelled without a retry or continuation request.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val type = error::class.simpleName?.takeIf { it.length <= 80 && Regex("[A-Za-z0-9_$]+").matches(it) } ?: "unavailable"
            diagnostics += diagnostic("SABR_TRANSPORT_OR_FRAMING_FAILURE", "The one initial request failed, exceeded its bounds or returned incomplete/unsupported framing (exception type=$type). Exception messages and binary payloads are omitted; retained summaries cover only fully received records.")
        }
        summaries.mapNotNull { it.protection }.forEach { protection ->
            diagnostics += diagnostic("SABR_STREAM_PROTECTION_STATUS", "The response declares protection status ${protection.status ?: "absent"} and field2 ${protection.field2 ?: "absent"}. No exact token-cause inference, token acquisition or refresh was attempted.")
        }
        summaries.mapNotNull { it.error }.forEach { error ->
            diagnostics += diagnostic("SABR_ERROR_RECORD", "The response declares SABR error code ${error.code ?: "absent"}; type present=${error.typePresent}. Error text/payloads are hidden.")
        }
        if (summaries.any { it.kind == SabrUmpPartKind.REDIRECT }) diagnostics += diagnostic("SABR_REDIRECT_RECORD_NOT_FOLLOWED", "The response includes a redirect record. This single-request probe did not contact its destination.")
        if (summaries.any { it.header?.videoMatches == false }) diagnostics += diagnostic("SABR_MEDIA_HEADER_VIDEO_MISMATCH", "A supplied media header declares a different video identity. Its bytes are not accepted as authorized media.")
        if (summaries.any { it.header?.compression == 2 }) diagnostics += diagnostic("SABR_GZIP_MEDIA_DECLARED", "A media header declares compression enum GZIP=2. Compressed bytes were counted only; this probe does not decompress or save them as media.")
        diagnostics += diagnostic("SABR_INITIAL_PROBE_ONLY", "Observed ${summaries.size} fully framed parts; media payload bytes=${summaries.sumOf { it.mediaBytes }}. This does not establish playable output, complete SABR transfer, continuation, muxing or download support.")
        return result()
    }

    private fun summarize(part: SabrUmpPart, expectedVideo: String, selected: List<SabrFormatId>): YouTubeSabrPartSummary {
        val header = part.mediaHeader()?.let { actual ->
            YouTubeSabrMediaHeaderSummary(actual.headerId, actual.itag, actual.formatId?.itag, actual.sequenceNumber,
                actual.isInitialization, actual.compression, actual.contentLength, actual.videoId?.let { it == expectedVideo },
                identityMatches(actual, selected))
        }
        fun fields() = YouTubeProtobufWire.decode(part.payload)
        fun integer(fields: List<SabrWireField>, number: Int): Int? {
            val values = fields.filter { it.number == number }
            require(values.size <= 1) { "Ambiguous SABR summary field" }
            return values.firstOrNull()?.value?.let { require(it is SabrWireValue.Varint); YouTubeProtobufWire.decodeInt32(it.value) }
        }
        val protection = if (part.kind == SabrUmpPartKind.STREAM_PROTECTION_STATUS) fields().let { YouTubeSabrProtectionSummary(integer(it, 1), integer(it, 2)) } else null
        val error = if (part.kind == SabrUmpPartKind.ERROR) fields().let { values -> YouTubeSabrErrorSummary(integer(values, 2), values.any { it.number == 1 && it.value is SabrWireValue.Bytes }) } else null
        return YouTubeSabrPartSummary(part.type, part.kind, part.payload.size, part.mediaHeaderId(),
            if (part.kind == SabrUmpPartKind.MEDIA_DATA) part.payload.size - 1 else 0, header, protection, error)
    }

    private fun identityMatches(actual: SabrUmpMediaHeader, selected: List<SabrFormatId>): Boolean? {
        val nested = actual.formatId
        if (nested != null && (actual.itag != null && actual.itag != nested.itag ||
            actual.lastModified != null && nested.lastModified != null && actual.lastModified != nested.lastModified ||
            actual.xtags != null && nested.xtags != null && actual.xtags != nested.xtags)) return false
        val itag = nested?.itag ?: actual.itag ?: return null
        val candidates = selected.filter { it.itag == itag }
        if (candidates.isEmpty()) return false
        val modified = nested?.lastModified ?: actual.lastModified ?: return null
        val xtags = nested?.xtags ?: actual.xtags
        // Missing identity fields are unknown, not wildcard matches. xtags is
        // optional only when the selected source also declares no xtags.
        if (candidates.any { it.lastModified == modified && it.xtags == xtags }) return true
        if (candidates.any { it.lastModified == null || it.xtags != null && xtags == null }) return null
        return false
    }

    private fun permittedEndpoint(responseUrl: String, endpoint: String): Boolean = runCatching {
        require(UrlPolicy.validate(endpoint, allowLocalHttp = true) == null && endpoint.length <= 16_384)
        val sourceHost = Url(responseUrl).host.lowercase().trimEnd('.')
        val targetHost = Url(endpoint).host.lowercase().trimEnd('.')
        if (sourceHost == "youtube.com" || sourceHost.endsWith(".youtube.com") || sourceHost == "youtubei.googleapis.com")
            require(Url(endpoint).protocol.name == "https" && (targetHost == "googlevideo.com" || targetHost.endsWith(".googlevideo.com")))
        else require(ResearchAccess.origin(responseUrl) == ResearchAccess.origin(endpoint))
        true
    }.getOrDefault(false)

    private fun validateHeaders(headers: Map<String, String>, clientInfo: SabrClientInfo) {
        require(headers.size <= 12 && headers.keys.map(String::lowercase).distinct().size == headers.size) { "Invalid SABR header count/duplicates" }
        headers.forEach { (name, value) ->
            require(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name) && name.lowercase() !in setOf("cookie", "authorization", "set-cookie", "host", "content-length", "transfer-encoding", "connection", "accept", "accept-encoding", "content-type", "range", "if-range")) { "SABR probe does not accept account/framing override headers" }
            require(value.length <= 2048 && value.none { it.code < 32 || it.code == 127 }) { "Invalid SABR header value" }
            if (name.equals("X-YouTube-Client-Name", true)) require(value == clientInfo.clientName.toString()) { "Client header disagrees with supplied observed client metadata" }
            if (name.equals("X-YouTube-Client-Version", true)) require(value == clientInfo.clientVersion) { "Client header disagrees with supplied observed client metadata" }
        }
    }

    private fun playerDocuments(body: String): List<JsonObject> {
        require(body.length <= YouTubeProvider.MAX_PAGE_BODY)
        val candidates = mutableListOf<String>()
        if (body.trimStart().startsWith('{') && body.length <= EmbeddedData.MAX_BODY) candidates += body
        Regex("(?:ytInitialPlayerResponse|playerResponse)\\s*=").findAll(body).take(8).forEach { match ->
            var start = match.range.last + 1
            while (start < body.length && body[start].isWhitespace()) start++
            if (start < body.length && body[start] == '{') EmbeddedData.balanced(body, start)?.takeIf { it.length <= EmbeddedData.MAX_BODY }?.let(candidates::add)
        }
        return candidates.mapNotNull { candidate -> runCatching { Json.parseToJsonElement(candidate) as? JsonObject }.getOrNull() }
    }

    private fun decodeConfiguration(encoded: String): ByteArray {
        require(encoded.length in 1..699_052)
        val raw = encoded.trimEnd('=')
        require(encoded.length - raw.length <= 2 && '=' !in raw && raw.length % 4 != 1)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ByteArray(raw.length * 6 / 8)
        require(output.isNotEmpty() && output.size <= 524_288)
        var bits = 0; var accumulator = 0; var offset = 0
        raw.forEach { char ->
            val value = alphabet.indexOf(when (char) { '-' -> '+'; '_' -> '/'; else -> char })
            require(value >= 0)
            accumulator = (accumulator shl 6) or value; bits += 6
            if (bits >= 8) { bits -= 8; output[offset++] = (accumulator shr bits).toByte(); accumulator = accumulator and ((1 shl bits) - 1) }
        }
        require(accumulator == 0 && offset == output.size)
        return output
    }

    private fun diagnostic(code: String, message: String) = ExtractionDiagnostic("youtube-sabr-initial", code, message)
}
