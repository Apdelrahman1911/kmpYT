package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.*
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Public YouTube metadata, browsing, captions and explicit native downloads.
 * Inspection never requests media bytes. Watch responses and signed media URLs are
 * fresh for each inspection; only bounded, versioned offline player programs are cached.
 * Native JavaScript executes off the caller's UI thread. Call [close] to release owned resources.
 *
 * Swift callers use `try await` for suspending operations (for example,
 * `let report = try await client.inspect(url: url)`) and `try` for [select] or [playbackPlan].
 * Handle the exported NSError in `do`/`catch`, including cancellation and unavailable
 * formats. Close with `try await client.close()` after operations finish; do not close
 * from an active download's progress callback.
 */
class YouTubeClient(
    private val transport: ResearchTransport,
    playerRuntime: YouTubeJavaScriptRuntime? = null,
    private val downloader: YouTubeDownloader? = null,
    private val onClose: () -> Unit = {},
    val platformName: String = "JVM",
) {
    private val compiler: YouTubePlayerProgramCompiler = playerRuntime?.let(::ContextYouTubePlayerProgramCompiler)
        ?: BoundedYouTubePlayerProgramCompiler()
    private val programs = YouTubePlayerProgramCache(transport, compiler, cacheEntries = 1)
    private val inspector = YouTubeInspectionResolver(transport, compiler = compiler,
        usePublicPlayerClients = true, programCache = programs)
    private val browse = PublicYouTubeBrowseService(transport)
    private val captionService = PublicYouTubeCaptionService(transport)
    val captionState get() = captionService.state

    private val lifecycle = Mutex()
    private var closing = false
    private var active = 0
    private val idle = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()

    @OptIn(ExperimentalTime::class)
    @Throws(Exception::class)
    suspend fun inspect(url: String): ExtractionReport = operation {
        inspector.inspect(url, Clock.System.now().toEpochMilliseconds())
    }

    @Throws(Exception::class)
    suspend fun search(query: String): YouTubeBrowsePage = operation { browse.search(query) }
    @Throws(Exception::class)
    suspend fun home(): YouTubeBrowsePage = operation { browse.home() }
    @Throws(Exception::class)
    suspend fun open(url: String): YouTubeBrowsePage = operation { browse.open(url) }
    @Throws(Exception::class)
    suspend fun more(page: YouTubeBrowsePage): YouTubeBrowsePage = operation { browse.more(page) }

    /** Accepts a public channel URL or an @handle; channelId is populated only by the response. */
    @Throws(Exception::class)
    suspend fun channel(urlOrHandle: String): YouTubeBrowsePage = operation {
        val url = if (urlOrHandle.startsWith('@')) "https://www.youtube.com/$urlOrHandle/videos" else urlOrHandle
        val path = Url(url).encodedPath
        require(path.startsWith("/@") || path.startsWith("/channel/")) { "Use a YouTube @handle or channel URL." }
        browse.open(url)
    }

    @Throws(Exception::class)
    suspend fun playlist(url: String): YouTubeBrowsePage = operation {
        require(Url(url).encodedPath == "/playlist") { "Use a YouTube playlist URL." }
        browse.open(url)
    }

    /**
     * Expands up to eight source-declared HLS/DASH catalogues into at most 1,024 formats.
     * Only manifest text is fetched. IDs are scoped to the original catalogue; repeated
     * calls on the returned report do not refetch it. Select from this returned report,
     * then [plan] resolves only the chosen HLS child playlists. Live/encrypted manifests
     * and nested multivariant playlists remain unsupported.
     */
    @Throws(Exception::class)
    suspend fun expandFormats(report: ExtractionReport,
        mediaId: String = report.media.single().id): ExtractionReport = operation {
        expandFormatsInternal(report, mediaId)
    }

    /** Prefers a usable progressive pair; returned IDs always belong to this exact report. */
    @Throws(Exception::class)
    fun select(report: ExtractionReport, maxHeight: Int? = 1080, audioOnly: Boolean = false,
        mediaId: String = report.media.single().id): YouTubeFormatSelection? {
        val media = requireMedia(report, mediaId)
        return if (audioOnly) YouTubeFormatSelection.choose(media, platformName, maxHeight, audioOnly = true)
            else YouTubeFormatSelection.recommendedDownload(media, platformName, maxHeight = maxHeight)
    }

    /** Creates a portable progressive/static-SABR request; the host supplies the actual player. */
    @Throws(Exception::class)
    fun playbackPlan(report: ExtractionReport, selection: YouTubeFormatSelection,
        mediaId: String = report.media.single().id): SourcePlaybackRequest {
        requireMedia(report, mediaId)
        val request = SourcePlaybackRequest.fromReport(report, mediaId, selection.primaryId, selection.audioId)
        YouTubeFormatCapabilities.playbackProblem(request.plan.primary, request.plan.audio, platformName)
            ?.let { throw IllegalArgumentException(it) }
        return request
    }

    /** Resolves selected manifests only; no media transfer begins until [download] is called. */
    @Throws(Exception::class)
    suspend fun plan(report: ExtractionReport, selection: YouTubeFormatSelection,
        mediaId: String = report.media.single().id): PlannedDownload = operation {
        planInternal(report, selection, mediaId)
    }

    @Throws(Exception::class)
    suspend fun captions(report: ExtractionReport, sourceIndex: Int, translationLanguage: String? = null,
        mediaId: String = report.media.single().id): YouTubeCaptionDocument = operation {
        requireMedia(report, mediaId)
        captionService.loadSelected(report, mediaId, sourceIndex, translationLanguage)
    }

    @Throws(Exception::class)
    suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit = {}): YouTubeDownloadResult = operation {
        validatePlan(plan)
        require(destinationPath.isNotBlank()) { "Choose a destination path." }
        requireNotNull(downloader) { "Supply a native YouTubeDownloader to save media on this platform." }
            .download(plan, destinationPath, onProgress)
    }

    /** Reinspect explicitly after expiry; a different quality, language or transport is never substituted. */
    @OptIn(ExperimentalTime::class)
    @Throws(Exception::class)
    suspend fun refresh(plan: PlannedDownload): PlannedDownload = operation {
        validatePlan(plan)
        val inspected = inspector.inspect(plan.inspectionUrl, Clock.System.now().toEpochMilliseconds())
        val catalogues = listOfNotNull(plan.primary, plan.audio).filter { it.manifestUrl != null && MANIFEST_ID in it.id }
            .map { it.id.substringBefore(MANIFEST_ID) }.toSet()
        val report = if (catalogues.isEmpty()) inspected else expandFormatsInternal(inspected, plan.mediaId, catalogues)
        val media = requireMedia(report, plan.mediaId)
        suspend fun replacement(previous: Representation): Representation {
            val offered = if (previous.transport == TransportKind.PROGRESSIVE &&
                !(previous.manifestUrl != null && MANIFEST_ID !in previous.id))
                refreshedProgressiveRepresentation(previous, media.representations)
            else requireNotNull(media.representations.singleOrNull { it.id == previous.id }) {
                "The original selected format is no longer available. Inspect and select again."
            }
            return resolve(offered, report.input, plan.mediaId).also {
                require(TransferPlanning.sameRepresentation(previous, it)) { "The selected source track changed. Inspect and select again." }
            }
        }
        PlannedDownload(replacement(plan.primary), plan.audio?.let { replacement(it) },
            inspectionUrl = report.input, mediaId = plan.mediaId).also(::validatePlan)
    }

    /** Waits for in-flight operations, disposes offline VMs, and closes the owned transport once. */
    @Throws(Exception::class)
    suspend fun close() {
        check(currentCoroutineContext()[ClientOperation]?.owner !== this) {
            "Close the client after its operation returns, outside transport or progress callbacks."
        }
        withContext(NonCancellable + Dispatchers.Default) {
            val owner = lifecycle.withLock {
                if (closing) false else {
                    closing = true
                    if (active == 0) idle.complete(Unit)
                    true
                }
            }
            if (!owner) { closed.await(); return@withContext }
            try {
                idle.await()
                try { programs.clear() } finally { onClose() }
                closed.complete(Unit)
            } catch (failure: Throwable) {
                closed.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private class ClientOperation(val owner: YouTubeClient) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClientOperation>
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.Default + ClientOperation(this)) {
        lifecycle.withLock { check(!closing) { "This YouTubeClient is closed." }; active++ }
        try { block() } finally {
            withContext(NonCancellable) {
                lifecycle.withLock { active--; if (closing && active == 0) idle.complete(Unit) }
            }
        }
    }

    private fun requireMedia(report: ExtractionReport, mediaId: String): ExtractedMedia {
        require(YouTubeDownloadPolicy.videoId(report.input) == mediaId && report.provider == ProviderId.YOUTUBE) {
            "The report must identify the requested YouTube video."
        }
        return requireNotNull(report.media.singleOrNull { it.id == mediaId && it.provider == ProviderId.YOUTUBE }) {
            "The inspected video is missing or ambiguous."
        }.also { require(YouTubeDownloadPolicy.videoId(it.pageUrl) == mediaId) { "The media source identity does not match." } }
    }

    private suspend fun planInternal(report: ExtractionReport, selection: YouTubeFormatSelection,
        mediaId: String): PlannedDownload {
        val media = requireMedia(report, mediaId)
        fun selected(id: String) = requireNotNull(media.representations.singleOrNull { it.id == id }) {
            "Select a format from this report."
        }
        val primary = selected(selection.primaryId)
        val audio = selection.audioId?.let(::selected)
        YouTubeFormatCapabilities.downloadProblem(primary, audio, platformName)?.let { throw IllegalArgumentException(it) }
        val plan = PlannedDownload(resolve(primary, report.input, mediaId), audio?.let { resolve(it, report.input, mediaId) },
            inspectionUrl = report.input, mediaId = mediaId)
        validatePlan(plan)
        return plan
    }

    private fun validatePlan(plan: PlannedDownload) {
        require(YouTubeDownloadPolicy.videoId(plan.inspectionUrl) == plan.mediaId) { "The plan must match its YouTube video." }
        require(plan.credentialRef == null) { "The public client does not attach account credentials." }
        YouTubeFormatCapabilities.downloadProblem(plan.primary, plan.audio, platformName)?.let { throw IllegalArgumentException(it) }
        listOfNotNull(plan.primary, plan.audio).forEach {
            require(it.transport !in setOf(TransportKind.HLS, TransportKind.DASH) || it.segments.isNotEmpty()) {
                "Resolve the selected media playlist before downloading."
            }
        }
        TransferPlanning.parts(plan)
        TransferPlanning.totalBytes(plan)
    }

    private suspend fun resolve(original: Representation, source: String, mediaId: String): Representation {
        require(original.selectable) { original.unavailableReason ?: "This format is unavailable." }
        if (original.transport in setOf(TransportKind.PROGRESSIVE, TransportKind.SABR) || original.segments.isNotEmpty()) return original
        require(original.transport in setOf(TransportKind.HLS, TransportKind.DASH)) { "This transport cannot be downloaded." }
        var current = original
        val visited = mutableSetOf<String>()
        repeat(3) {
            val url = current.requireResourceUrl()
            require(visited.add(url)) { "The manifest repeats a previously requested resource." }
            val parsed = readManifest(current, source, mediaId)
            val concrete = requireNotNull(parsed.representations.singleOrNull()) {
                "Call expandFormats and select a concrete media playlist or track before downloading."
            }
            require(concrete.selectable) { concrete.unavailableReason ?: "The selected manifest is unavailable." }
            val generic = current.manifestUrl == null && current.mimeType.substringBefore(';').lowercase() in MANIFEST_MIMES
            current = concrete.copy(id = original.id, role = if (generic) concrete.role else current.role,
                mimeType = if (!generic && current.role == TrackRole.AUDIO && concrete.role == TrackRole.MUXED &&
                    concrete.mimeType == "video/mp4") "audio/mp4" else concrete.mimeType,
                codecs = current.codecs ?: concrete.codecs, width = current.width ?: concrete.width,
                height = current.height ?: concrete.height, audioGroupId = current.audioGroupId ?: concrete.audioGroupId,
                audioTrackId = current.audioTrackId ?: concrete.audioTrackId,
                audioLanguage = current.audioLanguage ?: concrete.audioLanguage,
                audioDisplayName = current.audioDisplayName ?: concrete.audioDisplayName,
                audioIsDefault = current.audioIsDefault ?: concrete.audioIsDefault,
                frameRate = current.frameRate ?: concrete.frameRate,
                sizeBytes = concrete.sizeBytes ?: current.sizeBytes,
                durationMs = concrete.durationMs ?: current.durationMs,
                expiresAtEpochMs = current.expiresAtEpochMs ?: concrete.expiresAtEpochMs)
            if (current.segments.isNotEmpty() || current.transport == TransportKind.PROGRESSIVE) return current
        }
        throw IllegalArgumentException("The selected manifest exceeded the resolution depth limit.")
    }

    private suspend fun readManifest(representation: Representation, source: String, mediaId: String): ManifestResult {
        val url = representation.requireResourceUrl()
        UrlPolicy.validate(url)?.let { throw IllegalArgumentException(it) }
        YouTubeDownloadPolicy.requireResource(url, source, mediaId)
        val response = transport.execute(ResearchRequest("GET", url, stage = "selected-manifest"))
        require(response.url == url && response.status in 200..299) { "The selected manifest was not available at its declared URL." }
        val result = (if (representation.transport == TransportKind.HLS) HlsParser else DashParser).parse(url, response.body)
        require(result.representations.isNotEmpty()) {
            result.diagnostics.firstOrNull()?.message ?: "The manifest did not provide supported formats."
        }
        return result
    }

    private suspend fun expandFormatsInternal(report: ExtractionReport, mediaId: String,
        catalogueIds: Set<String>? = null): ExtractionReport {
        val media = requireMedia(report, mediaId)
        val catalogues = media.representations.filter { it.selectable && it.manifestUrl == null &&
            it.transport in setOf(TransportKind.HLS, TransportKind.DASH) && it.segments.isEmpty() &&
            (catalogueIds == null || it.id in catalogueIds) }
        if (catalogues.isEmpty()) return report
        require(catalogues.size <= 8) { "The report exceeds the manifest catalogue request limit." }
        val replacements = mutableMapOf<String, List<Representation>>()
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        val requests = mutableListOf<ResearchRequest>()
        val fetched = mutableMapOf<Pair<TransportKind, String>, ManifestResult>()
        for (catalogue in catalogues) {
            val url = catalogue.requireResourceUrl()
            val key = catalogue.transport to url
            val parsed = fetched[key] ?: readManifest(catalogue, report.input, mediaId).also {
                fetched[key] = it
                requests += ResearchRequest("GET", ResearchRedaction.url(url), stage = "selected-manifest")
                diagnostics += it.diagnostics
            }
            replacements[catalogue.id] = parsed.representations.map { child ->
                require(child.id.length in 1..512) { "A manifest format identifier exceeds the supported length." }
                child.copy(id = "${catalogue.id}$MANIFEST_ID${child.role.name.lowercase()}:${child.id}",
                    durationMs = child.durationMs ?: catalogue.durationMs,
                    expiresAtEpochMs = child.expiresAtEpochMs ?: catalogue.expiresAtEpochMs,
                    manifestUrl = url)
            }
        }
        val expanded = media.representations.flatMap { replacements[it.id] ?: listOf(it) }
        require(expanded.size <= 1024 && expanded.map { it.id }.distinct().size == expanded.size) {
            "The expanded catalogue has excessive or ambiguous format identities."
        }
        return report.copy(media = report.media.map { if (it.id == mediaId) it.copy(representations = expanded) else it },
            diagnostics = report.diagnostics + diagnostics + ExtractionDiagnostic("youtube-manifests", "MANIFEST_FORMATS_EXPANDED",
                "Expanded ${catalogues.size} source catalogues into ${expanded.size} formats without requesting media bytes."),
            requestPlan = report.requestPlan + requests)
    }

    private companion object {
        const val MANIFEST_ID = "#manifest:"
        val MANIFEST_MIMES = setOf("application/dash+xml", "application/vnd.apple.mpegurl", "application/x-mpegurl")
    }
}
