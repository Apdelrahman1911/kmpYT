package dev.kmpyt.extraction

import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Bounded response reader. Optional independently compiled transforms process actual supplied URLs. */
class YouTubeProvider(private val transforms: YouTubeUrlTransforms? = null,
    private val sabrClientInfo: SabrClientInfo? = null) : ProviderAdapter {
    override val id = ProviderId.YOUTUBE

    override fun normalize(url: String): String? {
        val parsed = runCatching { Url(url.trim()) }.getOrNull() ?: return null
        if (parsed.protocol.name !in setOf("https", "http") || !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) return null
        val host = parsed.host.lowercase().trimEnd('.')
        val path = parsed.encodedPath.trim('/').split('/')
        val videoId = when {
            host == "youtu.be" -> path.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ->
                when (path.firstOrNull()) {
                    "watch" -> parsed.parameters["v"]
                    "shorts", "embed", "live", "v" -> path.getOrNull(1)
                    else -> null
                }
            else -> null
        } ?: return null
        return videoId.takeIf { VIDEO_ID.matches(it) }?.let { "https://www.youtube.com/watch?v=$it" }
    }

    override fun requestPlan(context: ExtractionContext): List<ResearchRequest> {
        val normalized = normalize(context.pageUrl) ?: return emptyList()
        val videoId = Url(normalized).parameters["v"] ?: return emptyList()
        val origin = researchOrigin(context.responseUrl) ?: return emptyList()
        // Production inspection uses public GET responses only. The JSON request shape
        // below is an owned-origin protocol exercise, not a private API fallback.
        val responseHost = Url(context.responseUrl).host.lowercase().trimEnd('.')
        if (responseHost == "youtube.com" || responseHost.endsWith(".youtube.com") ||
            responseHost == "youtu.be" || responseHost == "youtube-nocookie.com" || responseHost.endsWith(".youtube-nocookie.com")) {
            return listOf(ResearchRequest("GET", normalized, stage = "youtube-watch"))
        }
        val playerBody = buildJsonObject {
            put("videoId", videoId)
            putJsonObject("context") { putJsonObject("client") {} }
        }.toString()
        return listOf(
            ResearchRequest("GET", context.responseUrl, stage = "youtube-page"),
            ResearchRequest("POST", "$origin/youtubei/v1/player",
                headers = mapOf("Content-Type" to "application/json"), body = playerBody,
                stage = "youtube-player-context-required"),
        )
    }

    override fun parse(context: ExtractionContext, body: String): ProviderResult {
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        fun diagnostic(code: String, message: String) {
            diagnostics += ExtractionDiagnostic("youtube-player", code, message)
        }
        val documents = runCatching {
            require(body.length <= MAX_PAGE_BODY)
            // Watch pages contain large unrelated recommendation trees. Read only the
            // named player assignment when available; do not traverse ytInitialData.
            val base = assignedJson(body, "(?:ytInitialPlayerResponse|playerResponse)").toMutableList()
            if (base.isEmpty()) base += EmbeddedData.documents(body)
            val objects = EmbeddedData.objects(base.distinct())
            objects.forEach { obj ->
                listOfNotNull(obj.text("playerResponse"), obj.text("player_response")).forEach { encoded ->
                    if (encoded.length <= EmbeddedData.MAX_BODY) runCatching { Json.parseToJsonElement(encoded) }.getOrNull()?.let(base::add)
                }
            }
            EmbeddedData.objects(base.distinct())
        }.getOrElse {
            return ProviderResult(emptyList(), listOf(ExtractionDiagnostic("youtube-player", "PARSER_LIMIT_OR_INVALID_DATA", "The supplied response could not be scanned within the bounded JSON parser limits.")))
        }
        val expectedId = normalize(context.pageUrl)?.let { Url(it).parameters["v"] }
        val players = documents.filter { it.obj("videoDetails") != null || it.obj("streamingData") != null || it.obj("playabilityStatus") != null }.distinct()
        val media = players.mapNotNull { player ->
            val details = player.obj("videoDetails") ?: JsonObject(emptyMap())
            val videoId = details.text("videoId") ?: expectedId
            if (videoId == null) {
                diagnostic("VIDEO_ID_MISSING", "Player data has no videoDetails.videoId and the input URL supplies no recognizable video ID.")
                return@mapNotNull null
            }
            if (expectedId != null && videoId != expectedId) {
                diagnostic("VIDEO_ID_MISMATCH", "Embedded player data belongs to a different video; its formats were not selected.")
                return@mapNotNull null
            }
            val status = player.obj("playabilityStatus")?.text("status")
            val restriction = status?.takeIf { it != "OK" }?.let {
                diagnostic(if (it == "LOGIN_REQUIRED" || it == "AGE_CHECK_REQUIRED") "AUTHORIZATION_REQUIRED" else "PLAYER_UNAVAILABLE",
                    "Player reports $it. This reader does not authenticate, bypass restrictions, or repair that response.")
                "Player reports $it"
            }
            val durationMs = secondsToMillis(details.text("lengthSeconds"))
            val stream = player.obj("streamingData")
            val hasSabr = !stream?.text("serverAbrStreamingUrl").isNullOrBlank()
            if (player.obj("playerConfig")?.obj("mediaCommonConfig")?.obj("mediaUstreamerRequestConfig")?.text("videoPlaybackUstreamerConfig") != null) {
                diagnostic("USTREAMER_CONFIGURATION_PRESENT", "Verified: playerConfig supplies videoPlaybackUstreamerConfig. Its encoded runtime configuration is neither interpreted nor sent by this reader.")
            }
            if (player["serviceIntegrityDimensions"] != null || player["botguardData"] != null) {
                diagnostic("INTEGRITY_CONTEXT_PRESENT", "Verified: player response contains an integrity-related context field. Its payload is not logged, executed or used to obtain a token.")
            }
            val responseDrm = !stream?.array("licenseInfos").isNullOrEmpty() || !stream?.array("drmFamilies").isNullOrEmpty()
            val sabrConfig = player.obj("playerConfig")?.obj("mediaCommonConfig")?.obj("mediaUstreamerRequestConfig")?.text("videoPlaybackUstreamerConfig")
            var sabrEndpoint = stream?.text("serverAbrStreamingUrl")?.let { validMediaUrl(context.responseUrl, it) }
            var sabrReady = sabrEndpoint != null && sabrClientInfo != null && sabrConfig != null && restriction == null && !responseDrm
            if (sabrReady && Url(requireNotNull(sabrEndpoint)).parameters["n"] != null) {
                try {
                    val endpoint = requireNotNull(sabrEndpoint)
                    require(Url(endpoint).parameters.getAll("n")?.size == 1 && transforms?.throttlingAvailable == true)
                    sabrEndpoint = YouTubeUrlAssembly.replaceParameter(endpoint, "n", transforms.throttling(requireNotNull(Url(endpoint).parameters["n"])))
                    diagnostic("SABR_THROTTLING_PARAMETER_RESOLVED", "Executed the complete player n transform on the actual shared SABR endpoint. Server acceptance remains a separate transfer check.")
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    sabrReady = false
                    diagnostic("SABR_THROTTLING_PARAMETER_UNRESOLVED", "The actual SABR endpoint requires a player n transformation that could not execute. No substitute value was generated.")
                }
            }
            val relativeExpiry = stream?.number("expiresInSeconds")?.takeIf { it >= 0 && context.nowEpochMs > 0 && it <= (Long.MAX_VALUE - context.nowEpochMs) / 1000 }
                ?.let { context.nowEpochMs + it * 1000 }
            val representations = mutableListOf<Representation>()
            fun formats(key: String, adaptive: Boolean) {
                stream?.array(key)?.forEachIndexed { index, element ->
                    val format = element as? JsonObject ?: return@forEachIndexed
                    val cipher = format.text("signatureCipher") ?: format.text("cipher")
                    val cipherFields = cipher?.let { runCatching { parseQueryString(it) }.getOrNull() }
                    val candidate = format.text("url") ?: cipherFields?.get("url")
                    var unavailable = restriction
                    var url = candidate?.let { validMediaUrl(context.responseUrl, it) }
                    val sabrResource = if (candidate == null && adaptive && sabrReady) runCatching {
                        val itag = requireNotNull(format.number("itag")).also { require(it in 1..Int.MAX_VALUE) }.toInt()
                        val modified = requireNotNull(format.text("lastModified")?.toULongOrNull())
                        require(format.number("contentLength")?.let { it > 0 } == true)
                        YouTubeSabrResource(videoId, requireNotNull(sabrEndpoint), requireNotNull(sabrConfig),
                            SabrFormatId(itag, modified, format.text("xtags")), requireNotNull(sabrClientInfo))
                    }.getOrNull() else null
                    if (sabrResource != null) url = sabrResource.endpoint
                    if (cipher != null && restriction == null) {
                        try {
                            require(cipherFields != null && listOf("url", "s", "sig", "signature", "sp").all { (cipherFields.getAll(it)?.size ?: 0) <= 1 })
                            val resource = requireNotNull(url)
                            require(format.text("url") == null || cipherFields["url"] == null || validMediaUrl(context.responseUrl, requireNotNull(cipherFields["url"])) == resource)
                            val parameter = cipherFields["sp"]?.takeIf { it.isNotBlank() } ?: "signature"
                            require(parameter != "n")
                            val obfuscated = cipherFields["s"]?.takeIf { it.isNotEmpty() }
                            val supplied = (cipherFields["sig"] ?: cipherFields["signature"])?.takeIf { it.isNotEmpty() }
                            require(cipherFields["sig"] == null || cipherFields["signature"] == null || cipherFields["sig"] == cipherFields["signature"])
                            require(obfuscated == null || supplied == null)
                            if (obfuscated != null) {
                                require(transforms?.signatureAvailable == true)
                                url = YouTubeUrlAssembly.replaceParameter(resource, parameter, transforms.signature(obfuscated))
                                diagnostic("SIGNATURE_CIPHER_RESOLVED", "The supplied cipher base URL received the result of a complete supported player signature program.")
                            } else if (supplied != null) {
                                // This is independent source-contract assembly, not a claim that
                                // Snaptube's fallback sig field was always an already-final signature.
                                url = YouTubeUrlAssembly.replaceParameter(resource, parameter, supplied)
                                diagnostic("SOURCE_SIGNATURE_ASSEMBLED", "An explicitly supplied signature was assembled under its supplied parameter name; no player transformation was inferred.")
                            } else require(!Url(resource).parameters[parameter].isNullOrEmpty())
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            val reason = if (!cipherFields?.get("s").isNullOrBlank())
                                "Requires a complete supported player-JavaScript signature transformation; this supplied cipher was not resolved."
                            else "Cipher data has no unambiguous usable base URL/signature/parameter contract."
                            unavailable = unavailable ?: reason
                            diagnostic("SIGNATURE_CIPHER_UNRESOLVED", reason)
                        }
                    }
                    if (url == null) {
                        diagnostic("FORMAT_URL_MISSING_OR_INVALID", "A declared player format has no usable HTTP(S) URL. No download URL was invented.")
                        unavailable = unavailable ?: if (hasSabr)
                            "The player supplies this format descriptor without a resource URL and supplies a SABR pointer. Association with SABR is inferred; SABR/UMP runtime transport is unsupported."
                        else "The player supplies this format descriptor without a usable resource URL. Its transfer mechanism cannot be established from this response."
                    }
                    if (url != null && sabrResource == null && Url(url).parameters["n"] != null && restriction == null) {
                        try {
                            require(Url(url).parameters.getAll("n")?.size == 1 && transforms?.throttlingAvailable == true)
                            url = YouTubeUrlAssembly.replaceParameter(url, "n", transforms.throttling(requireNotNull(Url(url).parameters["n"])))
                            diagnostic("THROTTLING_PARAMETER_RESOLVED", "The supplied n parameter was processed by a complete supported player program; media-server acceptance is tested separately.")
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            val reason = "Contains player throttling parameter n without a complete supported player-JavaScript transform."
                            unavailable = unavailable ?: reason
                            diagnostic("THROTTLING_PARAMETER_UNRESOLVED", reason)
                        }
                    }
                    if (responseDrm || !format.array("drmFamilies").isNullOrEmpty() || !format.array("licenseInfos").isNullOrEmpty() || format["isDrm"] == JsonPrimitive(true)) {
                        unavailable = "Format declares DRM; encrypted media is not supported."
                        diagnostic("ENCRYPTED_FORMAT_UNSUPPORTED", unavailable!!)
                    }
                    val rawMime = format.text("mimeType").orEmpty()
                    val mime = rawMime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
                    val codecs = CODECS.find(rawMime)?.groupValues?.get(1)
                    val role = when {
                        mime.startsWith("audio/") -> TrackRole.AUDIO
                        adaptive -> TrackRole.VIDEO
                        else -> TrackRole.MUXED
                    }
                    val expiry = url?.let(::explicitExpiry) ?: relativeExpiry
                    if (expiry != null && context.nowEpochMs > 0 && expiry <= context.nowEpochMs) {
                        unavailable = unavailable ?: "The supplied media URL has expired; a new authorized source response is required."
                        diagnostic("SOURCE_URL_EXPIRED", "A supplied format expiry is at or before the inspection time. No source refresh was performed.")
                    }
                    val audioTrack = format.obj("audioTrack")
                    val audioTrackId = audioTrack?.text("id")?.takeIf { it.isNotBlank() }
                    val sourceId = format.text("itag") ?: "$key-$index"
                    representations += Representation(
                        id = if (audioTrackId != null) "$sourceId@$audioTrackId" else sourceId,
                        url = url, role = role,
                        transport = if (sabrResource != null) TransportKind.SABR else if (url != null) TransportKind.PROGRESSIVE else if (hasSabr) TransportKind.SABR else TransportKind.UNRESOLVED,
                        mimeType = mime, codecs = codecs,
                        width = positiveInt(format.number("width")), height = positiveInt(format.number("height")),
                        bitrate = (format.number("averageBitrate") ?: format.number("bitrate"))?.takeIf { it >= 0 },
                        sizeBytes = format.number("contentLength")?.takeIf { it >= 0 },
                        durationMs = format.number("approxDurationMs")?.takeIf { it >= 0 } ?: durationMs,
                        expiresAtEpochMs = expiry, unavailableReason = unavailable,
                        frameRate = positiveInt(format.number("fps")),
                        audioSampleRate = positiveInt(format.number("audioSampleRate")),
                        audioChannels = positiveInt(format.number("audioChannels")),
                        // A translated display name is not a language code. Keep both
                        // actual fields separately instead of inferring a locale from an ID.
                        audioLanguage = audioTrack?.text("languageCode") ?: format.text("language"),
                        qualityLabel = format.text("qualityLabel") ?: format.text("audioQuality"),
                        adaptive = adaptive, audioTrackId = audioTrackId,
                        sabr = sabrResource,
                        audioIsDefault = (audioTrack?.get("audioIsDefault") as? JsonPrimitive)?.booleanOrNull,
                        audioDisplayName = audioTrack?.text("displayName"),
                        audioQuality = format.text("audioQuality"),
                        isDrc = (format["isDrc"] as? JsonPrimitive)?.booleanOrNull,
                        loudnessDb = (format["loudnessDb"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() },
                        colorInfo = format.obj("colorInfo")?.let { color ->
                            MediaColorInfo(color.text("primaries"), color.text("transferCharacteristics"), color.text("matrixCoefficients"))
                        },
                        isHdr = (format["isHdr"] as? JsonPrimitive)?.booleanOrNull,
                    )
                }
            }
            formats("formats", false)
            formats("adaptiveFormats", true)
            fun manifest(key: String, transport: TransportKind, mime: String) {
                val candidate = stream?.text(key) ?: return
                var url = validMediaUrl(context.responseUrl, candidate)
                if (url == null) {
                    diagnostic("MANIFEST_URL_INVALID", "A $transport manifest pointer is not a usable HTTP(S) URL.")
                    return
                }
                var unresolvedN = Url(url).parameters["n"] != null
                if (restriction == null && transforms?.throttlingAvailable == true && unresolvedN) {
                    try {
                        require(Url(url).parameters.getAll("n")?.size == 1)
                        url = YouTubeUrlAssembly.replaceParameter(url, "n", transforms.throttling(requireNotNull(Url(url).parameters["n"])))
                        unresolvedN = false
                        diagnostic("MANIFEST_THROTTLING_PARAMETER_RESOLVED", "The supplied manifest n parameter was processed with a complete supported player program.")
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        // Keep the original pointer unavailable; an execution failure is not success.
                    }
                }
                val expiry = explicitExpiry(url) ?: relativeExpiry
                val reason = restriction ?: when {
                    responseDrm -> "Player streaming data declares DRM; encrypted media is unsupported."
                    unresolvedN -> "Manifest URL contains unresolved player throttling parameter n."
                    expiry != null && context.nowEpochMs > 0 && expiry <= context.nowEpochMs -> "The supplied manifest URL has expired."
                    else -> null
                }
                representations += Representation(key, url, TrackRole.MUXED, transport, mimeType = mime,
                    durationMs = durationMs, expiresAtEpochMs = expiry, unavailableReason = reason)
            }
            manifest("hlsManifestUrl", TransportKind.HLS, "application/vnd.apple.mpegurl")
            manifest("dashManifestUrl", TransportKind.DASH, "application/dash+xml")
            if (hasSabr) {
                if (representations.any { it.sabr != null }) diagnostic("SABR_TRANSFER_CONTRACT_PREPARED", "Prepared source-provided SABR endpoint, Ustreamer configuration, client metadata and exact FormatIds. Selection invokes the binary transfer adapter, never a progressive GET. Live acceptance is unverified until a real transfer succeeds.")
                else diagnostic("SABR_UMP_UNSUPPORTED", "No executable SABR contract could be prepared: a matching client, complete player n transform, Ustreamer configuration, FormatId and known byte length are required. The binary endpoint is not a progressive file or manifest.")
                if (representations.any { it.url == null }) diagnostic("URLLESS_SABR_ASSOCIATION_INFERRED", "Inferred: URL-less descriptors occur alongside serverAbrStreamingUrl. The response does not prove an independently usable resource URL for each descriptor.")
            }
            if (representations.none { it.selectable }) diagnostic("NO_SELECTABLE_REPRESENTATION", "No unblocked representation was found in the supplied player response.")
            val thumbnails = details.obj("thumbnail")?.array("thumbnails")
            val thumbnail = thumbnails?.mapNotNull { (it as? JsonObject)?.text("url") }?.lastOrNull()?.let { validMediaUrl(context.responseUrl, it) }
            ExtractedMedia(videoId, id, details.text("title").orEmpty(), context.pageUrl,
                distinguishVariants(representations), thumbnail, durationMs, details.text("author"),
                captions = YouTubeCaptions.catalogue(player, context.responseUrl),
                uploaderChannelId = details.text("channelId")?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) })
        }.groupBy { it.id }.map { (_, variants) ->
            val richest = variants.maxBy { it.representations.size }
            richest.copy(representations = distinguishVariants(variants.flatMap { it.representations }),
                captions = variants.mapNotNull { it.captions }.maxByOrNull { it.tracks.size },
                uploaderChannelId = variants.mapNotNull { it.uploaderChannelId }.distinct().singleOrNull())
        }
        if (media.isEmpty()) diagnostic("PLAYER_DATA_NOT_FOUND", "No matching videoDetails/streamingData player object was found. The reader does not execute page scripts or fabricate formats.")
        diagnostics += ExtractionDiagnostic("youtube-request-plan", "PRIVATE_PLAYER_CONTEXT_NOT_SUPPLIED",
            "This response parser does not send player requests. The optional response orchestrator can use explicitly configured client contracts; no client identity, cookie or integrity token is invented by parsing.")
        if (Regex("(?:ytcfg\\.set\\s*\\(|INNERTUBE_CLIENT_NAME|INNERTUBE_CONTEXT)").containsMatchIn(body)) {
            diagnostic("CLIENT_CONFIGURATION_PRESENT", "Verified: supplied HTML declares YouTube client configuration. Parsing alone does not select or send a client fallback request.")
        }
        if (Regex("(?:PLAYER_JS_URL|jsUrl|/s/player/[^\\s\"']+/[^\\s\"']*base\\.js)").containsMatchIn(body)) {
            diagnostic("PLAYER_JAVASCRIPT_DEPENDENCY_PRESENT", "Verified: supplied response includes a changing player-script reference. Optional shared orchestration loads and compiles the referenced program; unsupported program syntax remains unavailable.")
        }
        return ProviderResult(media, diagnostics.distinct())
    }

    /** Each parsed JSON object is bounded even when the surrounding HTML is larger. */
    private fun assignedJson(body: String, identifier: String): List<JsonElement> =
        Regex("$identifier\\s*[=:]\\s*", RegexOption.IGNORE_CASE).findAll(body).take(32).mapNotNull { match ->
            val start = match.range.last + 1
            if (body.getOrNull(start) != '{') return@mapNotNull null
            val bounded = body.substring(start, minOf(body.length, start + EmbeddedData.MAX_BODY))
            EmbeddedData.balanced(bounded, 0)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        }.toList()

    // Audio tracks commonly share an itag. Preserve actual track IDs; if absent,
    // retain distinct observed descriptors with a display identifier suffix.
    private fun distinguishVariants(items: List<Representation>): List<Representation> {
        val unique = items.distinct()
        val repeated = unique.groupingBy { it.id }.eachCount()
        val indices = mutableMapOf<String, Int>()
        return unique.map { item ->
            if ((repeated[item.id] ?: 0) <= 1) item else {
                val index = (indices[item.id] ?: 0) + 1
                indices[item.id] = index
                item.copy(id = "${item.id}#observed-$index")
            }
        }
    }

    private fun researchOrigin(url: String): String? {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return null
        if (parsed.protocol.name !in setOf("https", "http") || parsed.host.isBlank() || !parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) return null
        return URLBuilder(parsed).apply { encodedPath = "/"; parameters.clear(); fragment = "" }.buildString().trimEnd('/')
    }

    private fun validMediaUrl(base: String, candidate: String): String? = runCatching {
        require(candidate.isNotBlank() && candidate.none { it.isWhitespace() || it.code < 32 || it == '\\' })
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(candidate)?.groupValues?.get(1)
        require(scheme == null || scheme.lowercase() in setOf("http", "https"))
        val absolute = if (scheme != null) candidate.replace("&amp;", "&") else EmbeddedData.absolute(base, candidate)
        val url = Url(absolute)
        require(url.protocol.name in setOf("http", "https") && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
        absolute
    }.getOrNull()

    private fun explicitExpiry(url: String): Long? = Url(url).parameters["expire"]?.toLongOrNull()
        ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)

    private fun positiveInt(number: Long?): Int? = number?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    private fun secondsToMillis(value: String?): Long? = value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }

    companion object {
        const val MAX_PAGE_BODY = 8_000_000
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val CODECS = Regex("codecs\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    }
}
