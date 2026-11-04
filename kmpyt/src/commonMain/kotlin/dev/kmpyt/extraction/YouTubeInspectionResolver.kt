package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Anonymous inspection is separate from authorization to fetch a media resource.
 *
 * Formats come from actual watch/player responses. The browser WEB contract may be
 * read from that same page; it is undocumented and may stop working. oEmbed only
 * supplements metadata. No account cookie, integrity program or media GET is sent.
 */
class YouTubeInspectionResolver(
    private val transport: ResearchTransport,
    private val provider: YouTubeProvider = YouTubeProvider(),
    private val useObservedWebClient: Boolean = true,
    private val compiler: YouTubePlayerProgramCompiler = BoundedYouTubePlayerProgramCompiler(),
    private val usePublicPlayerClients: Boolean = false,
    private val programCache: YouTubePlayerProgramCache? = null,
) {
    suspend fun inspect(input: String, nowEpochMs: Long = 0): ExtractionReport {
        val canonical = provider.normalize(input)
            ?: throw IllegalArgumentException("Enter a recognized YouTube video URL.")
        val videoId = requireNotNull(Url(canonical).parameters["v"])
        val diagnostics = mutableListOf(
            ExtractionDiagnostic("youtube-url", "VIDEO_ID_RECOGNIZED", "Recognized YouTube video $videoId."),
        )
        val requests = mutableListOf<ResearchRequest>()
        val watch = fetch(canonical, canonical, "youtube-watch", "text/html", requests, diagnostics)
        var item: ExtractedMedia? = null
        if (watch != null) {
            val context = ExtractionContext(canonical, watch.url, nowEpochMs)
            val observed = if (useObservedWebClient) YouTubeObservedWebClient.discover(context, watch.body) else null
            if (observed != null) diagnostics += ExtractionDiagnostic("youtube-player-client", "OBSERVED_WEB_CLIENT_CONTRACT",
                "Read the WEB client and signature timestamp declared by this watch page. This is an undocumented browser interface; account credentials and integrity state are not inferred from it.")
            val traced = ResearchTransport { request ->
                // Diagnostic receipts carry no response context, API keys, visitor IDs,
                // cookies or signed URLs. The original request only reaches transport.
                requests += request.copy(url = ResearchRedaction.url(request.url), headers = emptyMap(), body = null)
                transport.execute(request)
            }
            val publicClients = if (usePublicPlayerClients && observed != null) YouTubePublicPlayerClients.fromObservedWeb(observed) else emptyList()
            suspend fun extract(programs: YouTubePlayerProgramLoader?): ProviderResult {
                val extractor = YouTubeResponseExtractor(traced, compiler = compiler, programLoader = programs,
                    options = YouTubeResponseExtractionOptions(clients = publicClients + listOfNotNull(observed),
                        requestObservedProgressiveAlternatives = true,
                        requestPublicProgressiveAlternatives = publicClients.isNotEmpty()))
                return try { extractor.extract(context, watch) } finally { extractor.close() }
            }
            // Public watch requests happen before taking the program lease, so
            // another video's metadata fetch can overlap a slow first compilation.
            val parsed = programCache?.use { programs ->
                val result = extract(programs)
                // Do not retain a VM that stopped producing valid transforms.
                // The next inspection may recreate it from the declared program.
                if (result.diagnostics.any { it.code in setOf("SIGNATURE_CIPHER_UNRESOLVED",
                        "THROTTLING_PARAMETER_UNRESOLVED", "SABR_THROTTLING_PARAMETER_UNRESOLVED") }) programs.clear()
                result
            } ?: extract(null)
            diagnostics += parsed.diagnostics
            item = parsed.media.firstOrNull { it.id == videoId }
            val metadata = YouTubePublicMetadata.fromHtml(watch.body, canonical)
            if (metadata != null) {
                item = merge(item, metadata, videoId, canonical)
                diagnostics += ExtractionDiagnostic("youtube-watch", "PUBLIC_HTML_METADATA", "Read identifying metadata supplied in the watch page. Duration is included only when the response supplies it.")
            }
        }

        // A working metadata endpoint cannot repair a missing streamingData catalogue.
        // Avoid another request when the page already supplies all fields oEmbed can add.
        if (item == null || item.title.isBlank() || item.thumbnailUrl == null || item.artist.isNullOrBlank()) {
            val endpoint = "https://www.youtube.com/oembed?url=${canonical.encodeURLParameter()}&format=json"
            val response = fetch(endpoint, canonical, "youtube-oembed", "application/json", requests, diagnostics)
            if (response != null) {
                val metadata = YouTubePublicMetadata.fromOEmbed(response.body)
                if (metadata != null) {
                    item = merge(item, metadata, videoId, canonical)
                    diagnostics += ExtractionDiagnostic("youtube-oembed", "OEMBED_METADATA", "Read actual title, author and thumbnail from the public oEmbed response. This endpoint does not provide duration or a media-format catalogue.")
                } else diagnostics += ExtractionDiagnostic("youtube-oembed", "INVALID_OEMBED_METADATA", "The response did not contain a valid YouTube video metadata object.")
            }
        }
        val media = listOfNotNull(item)
        if (media.isEmpty()) {
            diagnostics += ExtractionDiagnostic("youtube-result", "METADATA_UNAVAILABLE", "Neither the anonymous watch response nor public oEmbed supplied usable video metadata. No result or quality list was manufactured.")
        } else {
            val resolved = media.single()
            if (resolved.durationMs == null) diagnostics += ExtractionDiagnostic("youtube-result", "DURATION_NOT_PROVIDED", "The accessible responses did not provide a video duration.")
            if (resolved.representations.isEmpty()) diagnostics += ExtractionDiagnostic("youtube-result", "FORMAT_CATALOGUE_UNAVAILABLE", "The accessible watch response did not expose a media-format catalogue. oEmbed metadata does not imply playable or downloadable representations.")
        }
        diagnostics += ExtractionDiagnostic("youtube-inspection", "INSPECTION_ONLY", "Inspection retrieves source metadata and supplied representations without starting a media transfer.")
        return ExtractionReport(canonical, ProviderId.YOUTUBE, media, diagnostics.distinct(), requests, nowEpochMs)
    }

    private fun merge(original: ExtractedMedia?, metadata: YouTubePublicMetadata,
        videoId: String, canonical: String): ExtractedMedia {
        return (original ?: ExtractedMedia(videoId, ProviderId.YOUTUBE, "", canonical, emptyList())).copy(
            title = original?.title?.takeIf { it.isNotBlank() } ?: metadata.title.orEmpty(),
            thumbnailUrl = original?.thumbnailUrl ?: metadata.thumbnailUrl,
            durationMs = original?.durationMs ?: metadata.durationMs,
            artist = original?.artist?.takeIf { it.isNotBlank() } ?: metadata.author,
        )
    }

    private suspend fun fetch(initial: String, canonical: String, stage: String, accept: String,
        requests: MutableList<ResearchRequest>, diagnostics: MutableList<ExtractionDiagnostic>): ResearchResponse? {
        var url = initial
        repeat(3) { attempt ->
            val request = ResearchRequest("GET", url, mapOf("Accept" to accept), stage = stage)
            requests += request.copy(url = ResearchRedaction.url(request.url), headers = emptyMap(), body = null)
            val response = try {
                transport.execute(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // HTTP/decoder exception text can contain response snippets or signed URLs.
                diagnostics += ExtractionDiagnostic(stage, "NETWORK_OR_RESPONSE_LIMIT", "The anonymous metadata request could not complete, or exceeded its bounded response limit.")
                diagnostics += safeTransportFailure(stage, error)
                return null
            }
            diagnostics += ExtractionDiagnostic(stage, "HTTP_RESPONSE", "GET ${ResearchRedaction.url(url)} returned HTTP ${response.status}; ${response.body.length} characters.")
            if (response.status in 200..299) return response
            if (response.status in 300..399) {
                val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value
                val target = location?.let { runCatching { EmbeddedData.absolute(url, it) }.getOrNull() }
                val allowed = target?.let { validPublicRedirect(it, canonical, stage) } == true
                if (!allowed || attempt == 2) {
                    diagnostics += ExtractionDiagnostic(stage, "REDIRECT_NOT_FOLLOWED", "The response requires an unsupported redirect, consent/account flow, or a different video. No account or challenge endpoint was opened.")
                    return null
                }
                url = requireNotNull(target)
            } else {
                val code = when (response.status) {
                    401, 403 -> "PUBLIC_RESPONSE_ACCESS_DENIED"
                    404 -> "PUBLIC_VIDEO_NOT_FOUND"
                    429 -> "PUBLIC_RESPONSE_RATE_LIMITED"
                    else -> "PUBLIC_RESPONSE_HTTP_ERROR"
                }
                diagnostics += ExtractionDiagnostic(stage, code, "The public metadata interface returned HTTP ${response.status}. No private client or access-control fallback was attempted.")
                return null
            }
        }
        return null
    }

    /** Type names separate DNS/TLS/timeouts from parser failures without leaking messages. */
    private fun safeTransportFailure(stage: String, error: Exception): ExtractionDiagnostic {
        val names = generateSequence<Throwable>(error) { it.cause }.take(4)
            .mapNotNull { it::class.simpleName?.takeIf { name -> name.length <= 96 && Regex("[A-Za-z0-9_$]+").matches(name) } }
            .distinct().toList()
        val code = when {
            names.any { it.contains("Timeout", ignoreCase = true) } -> "TRANSPORT_TIMEOUT"
            names.any { it.contains("UnknownHost", ignoreCase = true) } -> "TRANSPORT_DNS_FAILURE"
            names.any { it.startsWith("SSL") || it.contains("Certificate", ignoreCase = true) } -> "TRANSPORT_TLS_FAILURE"
            names.any { it.contains("ConnectException", ignoreCase = true) } -> "TRANSPORT_CONNECTION_FAILURE"
            names.any { it == "IllegalArgumentException" || it == "IllegalStateException" } -> "TRANSPORT_VALIDATION_FAILURE"
            else -> "TRANSPORT_FAILURE"
        }
        return ExtractionDiagnostic(stage, code,
            "Anonymous request failed with exception types: ${names.joinToString(" -> ").ifBlank { "Unavailable" }}. Exception messages, stack traces and response payloads are omitted.")
    }

    private fun validPublicRedirect(target: String, canonical: String, stage: String): Boolean {
        val url = runCatching { Url(target) }.getOrNull() ?: return false
        if (url.protocol.name != "https" || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return false
        if (url.host.lowercase().trimEnd('.') !in setOf("www.youtube.com", "youtube.com", "m.youtube.com")) return false
        return if (stage == "youtube-oembed") {
            url.encodedPath == "/oembed" && provider.normalize(url.parameters["url"].orEmpty()) == canonical
        } else provider.normalize(target) == canonical
    }
}

/** Reads public metadata declarations without evaluating script bodies or rendering an iframe. */
internal data class YouTubePublicMetadata(val title: String? = null, val thumbnailUrl: String? = null,
    val durationMs: Long? = null, val author: String? = null) {
    companion object {
        fun fromOEmbed(body: String): YouTubePublicMetadata? {
            if (body.length > EmbeddedData.MAX_BODY) return null
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if (string(root, "type") != "video") return null
            if (string(root, "provider_name")?.let { it != "YouTube" } == true) return null
            val title = string(root, "title")?.takeIf { it.isNotBlank() } ?: return null
            return YouTubePublicMetadata(title, imageUrl(string(root, "thumbnail_url")), author = string(root, "author_name")?.takeIf { it.isNotBlank() })
        }

        fun fromHtml(body: String, canonical: String): YouTubePublicMetadata? {
            if (body.length > YouTubeProvider.MAX_PAGE_BODY) return null
            val fields = mutableMapOf<String, String>()
            Regex("<(?:meta|link)\\b[^>]{0,16000}>", RegexOption.IGNORE_CASE).findAll(body).take(512).forEach { match ->
                val values = attributes(match.value)
                val key = values["property"] ?: values["name"] ?: values["itemprop"] ?: values["rel"]
                val value = values["content"] ?: values["href"]
                if (key != null && value != null && key.lowercase() !in fields) fields[key.lowercase()] = decodeEntities(value)
            }
            val declared = fields["og:url"] ?: fields["canonical"]
            if (declared != null && YouTubeProvider().normalize(declared)?.let { it != canonical } == true) return null
            var title = fields["og:title"] ?: fields["title"] ?: fields["name"]
            var thumbnail = imageUrl(fields["og:image"] ?: fields["thumbnailurl"])
            var duration = parseDuration(fields["duration"])
            var author: String? = fields["author"]
            Regex("<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
                .findAll(body).take(16).forEach { script ->
                    val text = script.groupValues[1]
                    if (text.length > EmbeddedData.MAX_BODY) return@forEach
                    val objects = runCatching { EmbeddedData.objects(listOf(Json.parseToJsonElement(text))) }.getOrNull() ?: return@forEach
                    val video = objects.firstOrNull { objectValue ->
                        string(objectValue, "@type") == "VideoObject" &&
                            listOfNotNull(string(objectValue, "url"), string(objectValue, "embedUrl"), string(objectValue, "@id"))
                                .all { YouTubeProvider().normalize(it)?.let { normalized -> normalized == canonical } != false }
                    } ?: return@forEach
                    title = title ?: string(video, "name")
                    duration = duration ?: parseDuration(string(video, "duration"))
                    thumbnail = thumbnail ?: imageUrl(string(video, "thumbnailUrl") ?: video.array("thumbnailUrl")?.firstOrNull()?.let { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content })
                    author = author ?: string(video, "author") ?: video.obj("author")?.let { string(it, "name") }
                }
            if (title.isNullOrBlank() && thumbnail == null && duration == null && author.isNullOrBlank()) return null
            return YouTubePublicMetadata(title?.takeIf { it.isNotBlank() }, thumbnail, duration, author?.takeIf { it.isNotBlank() })
        }

        private fun attributes(tag: String): Map<String, String> = Regex("([A-Za-z_:][A-Za-z0-9_:.\\-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
            .findAll(tag).associate { match ->
                match.groupValues[1].lowercase() to (match.groups[2]?.value ?: match.groups[3]?.value ?: match.groupValues[4])
            }

        private fun string(objectValue: JsonObject, key: String): String? = (objectValue[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.length <= 16000 }

        private fun imageUrl(value: String?): String? = value?.let {
            val url = runCatching { Url(it) }.getOrNull() ?: return@let null
            it.takeIf { url.protocol.name == "https" && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() }
        }

        private fun parseDuration(value: String?): Long? {
            val match = value?.let { Regex("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").matchEntire(it) } ?: return null
            if (match.groupValues.drop(1).all { it.isEmpty() }) return null
            val seconds = listOf(86400.0, 3600.0, 60.0, 1.0).mapIndexed { index, factor -> (match.groupValues[index + 1].toDoubleOrNull() ?: 0.0) * factor }.sum()
            return seconds.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
        }

        private fun decodeEntities(value: String): String = Regex("&(?:#([0-9]+)|#x([0-9A-Fa-f]+)|(amp|quot|apos|lt|gt|nbsp));").replace(value) { match ->
            val code = match.groups[1]?.value?.toIntOrNull() ?: match.groups[2]?.value?.toIntOrNull(16)
            if (code != null && code in 1..0x10ffff && code !in 0xd800..0xdfff) {
                if (code <= 0xffff) code.toChar().toString() else {
                    val offset = code - 0x10000
                    "${(0xd800 + (offset shr 10)).toChar()}${(0xdc00 + (offset and 1023)).toChar()}"
                }
            } else when (match.groups[3]?.value) {
                "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                else -> match.value
            }
        }
    }
}
