package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Shared extraction stages: actual response parsing, declared-program loading and
 * explicit client-contract fallback. An unconfigured production page never results
 * in an invented client identity, private player POST, integrity token or media GET.
 */
class YouTubeResponseExtractor(
    private val transport: ResearchTransport,
    compiler: YouTubePlayerProgramCompiler = BoundedYouTubePlayerProgramCompiler(),
    private val options: YouTubeResponseExtractionOptions = YouTubeResponseExtractionOptions(),
    programLoader: YouTubePlayerProgramLoader? = null,
    private val integrity: YouTubeIntegrityContextManager? = null,
) {
    private val ownsProgramLoader = programLoader == null
    private val programLoader = programLoader ?: YouTubePlayerProgramLoader(transport, compiler)
    private val clients = YouTubeClientRegistry(options.clients).contracts
    suspend fun close() { if (ownsProgramLoader) programLoader.close() }

    suspend fun extract(context: ExtractionContext, response: ResearchResponse): ProviderResult {
        require(response.body.length <= YouTubeProvider.MAX_PAGE_BODY) { "Player response exceeds the page bound" }
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        val initialReference = programLoader.discover(context, response.body)
        var loaded: LoadedYouTubePlayerProgram? = null
        var transformedBy: YouTubeUrlTransforms? = null
        var memoizedTransforms: YouTubeUrlTransforms? = null

        suspend fun parsed(candidate: ResearchResponse, client: YouTubeClientContract?): ProviderResult {
            val current = context.copy(responseUrl = candidate.url)
            var result = YouTubeProvider(sabrClientInfo = client?.sabrClientInfoOrNull()).parse(current, candidate.body)
            if (requiresAuthorization(result) || !needsTransforms(result, candidate.body)) return result
            val candidateReference = programLoader.discover(current, candidate.body)
            val wantedReference = candidateReference ?: initialReference
            if (loaded == null || wantedReference != null && loaded?.scriptUrl != wantedReference) {
                loaded = if (candidateReference != null) programLoader.load(current, candidate.body, options.programBinding, transport)
                    else programLoader.load(context, response.body, options.programBinding, transport)
                diagnostics += requireNotNull(loaded).diagnostics
            }
            val transforms = loaded?.compilation?.transforms
            if (transforms !== transformedBy) {
                transformedBy = transforms
                memoizedTransforms = transforms?.let(::MemoizedThrottling)
            }
            if (memoizedTransforms != null) result = YouTubeProvider(memoizedTransforms, client?.sabrClientInfoOrNull()).parse(current, candidate.body)
            return result
        }

        // Only a declaration on this response can bind its initial formats. A
        // configured fallback contract has not supplied that response yet.
        val initialClient = YouTubeObservedWebClient.discover(context.copy(responseUrl = response.url), response.body)
        var best = if (response.status in 200..299) parsed(response, initialClient) else ProviderResult(emptyList(),
            listOf(diagnostic("INITIAL_PLAYER_HTTP_UNAVAILABLE", "The initial player response returned HTTP ${response.status}.")))
        if (response.status in setOf(401, 403, 429)) return best.copy(diagnostics = (best.diagnostics +
            diagnostic("INITIAL_PLAYER_ACCESS_OR_RATE_LIMIT", "The initial response requires access or is rate limited. No alternate client request was attempted.")).distinct())
        if (requiresAuthorization(best)) return best.copy(diagnostics = (best.diagnostics +
            diagnostic("CLIENT_FALLBACK_AUTHORIZATION_REQUIRED", "The supplied player response requires account/age authorization. No alternate client or transform was used to bypass it.")).distinct())
        val usable = best.media.flatMap { it.representations }.filter(Representation::selectable)
        val initialCatalogue = best.media.flatMap { it.representations }
        val declaredAudioOnly = initialCatalogue.isNotEmpty() && initialCatalogue.all { it.role == TrackRole.AUDIO }
        val publicAlternatives = if (options.requestPublicProgressiveAlternatives)
            clients.filter { it.provenance == YouTubeClientProvenance.PINNED_PUBLIC_CLIENT } else emptyList()
        val observedAlternative = initialClient?.takeIf { options.requestObservedProgressiveAlternatives && it in clients }
        val seekProgressive = usable.any { it.transport == TransportKind.SABR || it.transport == TransportKind.PROGRESSIVE } &&
            !hasCompleteProgressiveResources(best) &&
            (publicAlternatives.isNotEmpty() || observedAlternative != null)
        if (hasUsableResources(best) && !seekProgressive)
            return best.copy(diagnostics = (best.diagnostics + diagnostics).distinct())
        // Each configured profile is requested at most once. Only the actual
        // returned catalogue is accepted; media bytes and protection grants are not probed.
        val requestClients = if (seekProgressive) publicAlternatives + listOfNotNull(observedAlternative) else clients
        if (seekProgressive) diagnostics += diagnostic(if (publicAlternatives.isEmpty()) "OBSERVED_WEB_PROGRESSIVE_LOOKUP" else "PUBLIC_PLAYER_PROGRESSIVE_LOOKUP",
            if (publicAlternatives.isEmpty()) "The watch page does not supply a complete progressive media selection. Asking its declared WEB player once for independently supplied alternatives; no media transfer is started."
            else "The watch page does not supply a complete progressive media selection. Checking the configured, pinned upstream public profiles for independently supplied progressive URLs, then the declared WEB client if needed. No media request or integrity token is generated.")

        val normalized = YouTubeProvider().normalize(context.pageUrl)
        val videoId = normalized?.let { Url(it).parameters["v"] } ?: best.media.firstOrNull()?.id
            ?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        if (clients.isEmpty()) return best.copy(diagnostics = (best.diagnostics + diagnostics +
            diagnostic("PLAYER_CLIENT_CONTRACT_NOT_CONFIGURED", "No explicit player-client contract is configured. Missing media resources were not replaced with guessed URLs or client profiles.")).distinct())
        if (videoId == null) return best.copy(diagnostics = (best.diagnostics + diagnostics +
            diagnostic("PLAYER_CLIENT_VIDEO_ID_UNAVAILABLE", "Client fallback needs a recognizable source video ID; no ID was invented.")).distinct())

        for (client in requestClients) {
            val grant = client.playerIntegrity?.let { requirement ->
                val manager = integrity
                if (manager == null) {
                    diagnostics += diagnostic("PLAYER_INTEGRITY_ISSUER_UNAVAILABLE", "This contract requires an authorized player-context issuer; none is configured. No context was fabricated.")
                    null
                } else try {
                    manager.acquire(requirement.issuerId, YouTubeIntegrityScope.PLAYER_REQUEST,
                        YouTubeIntegrityBinding(videoId, requireNotNull(client.sabrClientInfoOrNull()),
                            requirement.sourceGeneration, client.clientContext.text("visitorData")))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: YouTubeIntegrityException) {
                    diagnostics += diagnostic("PLAYER_INTEGRITY_${failure.code}", "The authorized player-context issuer could not supply a valid scoped context (${failure.code}). Payloads are redacted.")
                    null
                }
            }
            if (client.playerIntegrity != null && grant == null) continue
            val signatureTimestamp = client.signatureTimestamp ?: loaded?.compilation?.transforms?.signatureTimestamp
            val timestampOrigin = when {
                client.signatureTimestamp != null && client.provenance != YouTubeClientProvenance.EXPLICIT_AUTHORIZED_CONTRACT -> "observed-config"
                client.signatureTimestamp != null -> "explicit-contract"
                signatureTimestamp != null -> "compiled-player"
                else -> "absent"
            }
            val body = buildJsonObject {
                put("videoId", videoId)
                putJsonObject("context") { put("client", client.clientContext) }
                // This field is supported only for the explicit contract above.
                // Player and media-resource grants are separate; never append a
                // player grant to a media URL or use it as a session credential.
                grant?.let { putJsonObject("serviceIntegrityDimensions") { put("poToken", it.copyValue().encodeBase64()) } }
                signatureTimestamp?.let { timestamp ->
                    putJsonObject("playbackContext") { putJsonObject("contentPlaybackContext") { put("signatureTimestamp", timestamp) } }
                }
            }.toString()
            val request = ResearchRequest("POST", client.endpoint,
                client.headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) } + ("Content-Type" to "application/json"), body, "youtube-player-client")
            diagnostics += diagnostic("PLAYER_CLIENT_REQUEST", "Executing explicit client contract '${client.name}' at ${ResearchRedaction.url(client.endpoint)}. Context values and headers are not logged.")
            diagnostics += diagnostic("PLAYER_CLIENT_SIGNATURE_TIMESTAMP", "Client '${client.name}' signatureTimestamp=${signatureTimestamp ?: "absent"}; origin=$timestampOrigin; declared-player=${initialReference?.let(ResearchRedaction::url) ?: "absent"}; compiled-player=${loaded?.scriptUrl?.let(ResearchRedaction::url) ?: "absent"}.")
            val reply = try { transport.execute(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                diagnostics += diagnostic("PLAYER_CLIENT_TRANSPORT_FAILURE", "The explicit client request failed or exceeded its response bound. The next configured contract may be tried; exception payloads are not logged.")
                continue
            }
            diagnostics += diagnostic("PLAYER_CLIENT_HTTP_RESPONSE", "Explicit client '${client.name}' returned HTTP ${reply.status}; ${reply.body.length} characters.")
            if (reply.status in setOf(401, 403, 429)) {
                diagnostics += diagnostic("PLAYER_CLIENT_ACCESS_OR_RATE_LIMIT", "The endpoint requires access or is rate limited. Client fallback stopped; no authentication/challenge workaround was attempted.")
                break
            }
            if (reply.status !in 200..299) continue
            if (reply.url != client.endpoint || reply.body.length > EmbeddedData.MAX_BODY) {
                diagnostics += diagnostic("PLAYER_CLIENT_RESPONSE_REJECTED", "The client response changed endpoint identity or exceeded the two-million-character player bound; its formats were not accepted.")
                continue
            }
            val candidate = parsed(reply, client)
            val sameVideo = candidate.media.filter { it.id == videoId }
            if (sameVideo.isEmpty()) {
                diagnostics += candidate.diagnostics
                diagnostics += diagnostic("PLAYER_CLIENT_VIDEO_MISMATCH", "The configured client did not return matching video data; its formats were not merged.")
                continue
            }
            val matching = candidate.copy(media = sameVideo)
            best = if (seekProgressive) mergeProgressiveAlternatives(best, matching) else merge(best, matching)
            if (requiresAuthorization(matching)) {
                diagnostics += diagnostic("CLIENT_FALLBACK_AUTHORIZATION_REQUIRED", "The client reports account/age authorization. No further client was tried to bypass that status.")
                break
            }
            if (seekProgressive) {
                val found = matching.media.any { media -> media.representations.any { it.selectable && it.transport == TransportKind.PROGRESSIVE } }
                val stage = if (client.provenance == YouTubeClientProvenance.PINNED_PUBLIC_CLIENT) "PUBLIC_PLAYER" else "OBSERVED_WEB"
                diagnostics += diagnostic(if (found) "${stage}_PROGRESSIVE_RESOURCES" else "${stage}_PROGRESSIVE_UNAVAILABLE",
                    if (found) "The configured player returned independent progressive resources. Existing SABR choices are retained; actual media-server acceptance is checked only during playback or download."
                    else "The configured player did not supply usable progressive alternatives. The original SABR catalogue is retained; a later protection requirement still needs an actual authorized context.")
                if (hasCompleteProgressiveResources(best)) break
                continue
            }
            if (hasUsableResources(matching)) {
                diagnostics += diagnostic("PLAYER_CLIENT_RESOURCES_DISCOVERED", "The configured client supplied usable representation URLs for the requested video. No media transfer was started by extraction.")
                // An empty watch catalogue does not establish an audio-only source.
                // When progressive lookup is requested, keep checking the bounded
                // client list until the accumulated responses provide a complete selection.
                if (!(options.requestObservedProgressiveAlternatives || options.requestPublicProgressiveAlternatives) ||
                    hasCompleteProgressiveResources(best, allowAudioOnly = declaredAudioOnly)) break
            }
        }
        return best.copy(diagnostics = (best.diagnostics + diagnostics).distinct())
    }

    private fun needsTransforms(result: ProviderResult, body: String): Boolean = result.diagnostics.any {
        it.code in setOf("SIGNATURE_CIPHER_UNRESOLVED", "THROTTLING_PARAMETER_UNRESOLVED")
    } || result.media.any { media -> media.representations.any { representation ->
        representation.url?.let { runCatching { Url(it).parameters["n"] != null }.getOrDefault(false) } == true &&
            representation.unavailableReason?.contains("n", ignoreCase = true) == true
    } } || Regex("\"serverAbrStreamingUrl\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").findAll(body).take(4).any { match ->
        // SABR also carries n on its shared endpoint, even when individual format
        // descriptors have no URL. Analyze the actual declared JS; never invent n.
        runCatching { Url(Json.parseToJsonElement(match.groupValues[1]).jsonPrimitive.content).parameters["n"] != null }.getOrDefault(false)
    }

    private fun hasUsableResources(result: ProviderResult) = result.media.any { it.representations.any(Representation::selectable) }

    /** Many formats share one n value; do not cross the native VM bridge for each copy. */
    private class MemoizedThrottling(private val source: YouTubeUrlTransforms) : YouTubeUrlTransforms by source {
        private val results = linkedMapOf<String, String>()
        override fun throttling(input: String): String {
            results.remove(input)?.let { found -> results[input] = found; return found }
            val transformed = source.throttling(input)
            if (input.length in 1..8192 && transformed.length in 1..8192) {
                results[input] = transformed
                if (results.size > 32) results.remove(results.keys.first())
            }
            return transformed
        }
        // The loader/cache owns the VM. This wrapper lasts only for this inspection.
        override fun close() = Unit
    }

    /** Audio alone must not end a video lookup; partial results can complete each other across replies. */
    private fun hasCompleteProgressiveResources(result: ProviderResult, allowAudioOnly: Boolean = true): Boolean = result.media.any { media ->
        val progressive = media.representations.filter { it.selectable && it.transport == TransportKind.PROGRESSIVE &&
            it.initialization == null && it.segments.isEmpty() }
        val audio = progressive.filter { it.role == TrackRole.AUDIO }
        progressive.any { it.role == TrackRole.MUXED &&
            dev.kmpyt.youtube.YouTubeFormatCapabilities.pairingProblem(it, null) == null } ||
            progressive.filter { it.role == TrackRole.VIDEO }.any { video -> audio.any { track ->
                dev.kmpyt.youtube.YouTubeFormatCapabilities.pairingProblem(video, track) == null
            } } || allowAudioOnly && media.representations.all { it.role == TrackRole.AUDIO } && audio.any {
                dev.kmpyt.youtube.YouTubeFormatCapabilities.pairingProblem(it, null) == null
            }
    }
    private fun requiresAuthorization(result: ProviderResult) = result.diagnostics.any {
        it.code in setOf("AUTHORIZATION_REQUIRED", "ENCRYPTED_FORMAT_UNSUPPORTED")
    }

    private fun merge(original: ProviderResult, incoming: ProviderResult): ProviderResult {
        val media = original.media.toMutableList()
        incoming.media.forEach { fresh ->
            val index = media.indexOfFirst { it.id == fresh.id }
            if (index < 0) media += fresh
            else {
                val old = media[index]
                val representations = old.representations.associateByTo(linkedMapOf()) { it.id }
                fresh.representations.forEach { offered ->
                    val previous = representations[offered.id]
                    if (previous == null || offered.selectable || !previous.selectable && fields(offered) > fields(previous)) representations[offered.id] = offered
                }
                media[index] = old.copy(title = old.title.ifBlank { fresh.title }, thumbnailUrl = old.thumbnailUrl ?: fresh.thumbnailUrl,
                    durationMs = old.durationMs ?: fresh.durationMs, artist = old.artist?.takeIf(String::isNotBlank) ?: fresh.artist,
                    captions = fresh.captions ?: old.captions,
                    uploaderChannelId = old.uploaderChannelId ?: fresh.uploaderChannelId,
                    representations = representations.values.toList())
            }
        }
        return ProviderResult(media, original.diagnostics + incoming.diagnostics)
    }

    /** Preserve complete SABR pairs even if the extra response only exposes one progressive track. */
    private fun mergeProgressiveAlternatives(original: ProviderResult, incoming: ProviderResult): ProviderResult {
        val media = original.media.map { old ->
            val fresh = incoming.media.firstOrNull { it.id == old.id } ?: return@map old
            val retained = old.representations.toMutableList()
            val alternatives = mutableListOf<Representation>()
            val usedIds = retained.mapTo(mutableSetOf()) { it.id }
            fresh.representations.filter { it.selectable && it.transport == TransportKind.PROGRESSIVE }.forEach { offered ->
                if ((retained + alternatives).any { existing ->
                        existing.id.substringBefore('#') == offered.id.substringBefore('#') && existing.copy(id = offered.id) == offered
                    }) return@forEach
                val previous = retained.firstOrNull { it.id == offered.id }
                if (previous != null && !previous.selectable) {
                    retained.remove(previous)
                    usedIds.remove(previous.id)
                }
                var id = offered.id
                if (id in usedIds) {
                    val base = "${offered.id}#progressive"
                    id = base
                    var suffix = 2
                    while (id in usedIds) { id = "$base-$suffix"; suffix++ }
                }
                usedIds += id
                alternatives += offered.copy(id = id)
            }
            old.copy(representations = alternatives + retained,
                thumbnailUrl = old.thumbnailUrl ?: fresh.thumbnailUrl,
                durationMs = old.durationMs ?: fresh.durationMs,
                artist = old.artist?.takeIf(String::isNotBlank) ?: fresh.artist,
                captions = fresh.captions ?: old.captions,
                uploaderChannelId = old.uploaderChannelId ?: fresh.uploaderChannelId)
        }
        return ProviderResult(media, original.diagnostics + incoming.diagnostics)
    }

    private fun fields(rep: Representation) = listOf(rep.url, rep.codecs, rep.width, rep.height, rep.bitrate, rep.sizeBytes, rep.qualityLabel).count { it != null }

    private fun diagnostic(code: String, message: String) = ExtractionDiagnostic("youtube-player-client", code, message)

}
