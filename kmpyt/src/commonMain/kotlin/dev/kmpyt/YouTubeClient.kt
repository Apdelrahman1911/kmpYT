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
 * `let report = try await client.inspect(url: url)`) and `try` for [select].
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

    /** Prefers a usable progressive pair; returned IDs always belong to this exact report. */
    @Throws(Exception::class)
    fun select(report: ExtractionReport, maxHeight: Int? = 1080, audioOnly: Boolean = false,
        mediaId: String = report.media.single().id): YouTubeFormatSelection? {
        val media = requireMedia(report, mediaId)
        return if (audioOnly) YouTubeFormatSelection.choose(media, platformName, maxHeight, audioOnly = true)
            else YouTubeFormatSelection.recommendedDownload(media, platformName, maxHeight = maxHeight)
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
        captionService.register(report, mediaId)
        captionService.select(mediaId, sourceIndex, translationLanguage)
        val state = requireNotNull(captionService.state.value.videos[mediaId])
        check(state.error == null) { state.error.orEmpty() }
        requireNotNull(state.document) { "This subtitle track returned no document." }
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
        val report = inspector.inspect(plan.inspectionUrl, Clock.System.now().toEpochMilliseconds())
        val media = requireMedia(report, plan.mediaId)
        suspend fun replacement(previous: Representation): Representation {
            val offered = if (previous.transport == TransportKind.PROGRESSIVE)
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
            UrlPolicy.validate(url)?.let { throw IllegalArgumentException(it) }
            YouTubeDownloadPolicy.requireResource(url, source, mediaId)
            val response = transport.execute(ResearchRequest("GET", url, stage = "selected-manifest"))
            require(response.url == url && response.status in 200..299) { "The selected manifest was not available at its declared URL." }
            val parsed = (if (current.transport == TransportKind.HLS) HlsParser else DashParser).parse(url, response.body)
            val concrete = requireNotNull(parsed.representations.singleOrNull()) {
                "Select a concrete media playlist or track before downloading."
            }
            require(concrete.selectable) { concrete.unavailableReason ?: "The selected manifest is unavailable." }
            current = concrete.copy(id = original.id, role = original.role,
                codecs = original.codecs ?: concrete.codecs, width = original.width ?: concrete.width,
                height = original.height ?: concrete.height, audioGroupId = original.audioGroupId ?: concrete.audioGroupId,
                audioTrackId = original.audioTrackId ?: concrete.audioTrackId,
                audioLanguage = original.audioLanguage ?: concrete.audioLanguage,
                frameRate = original.frameRate ?: concrete.frameRate,
                sizeBytes = concrete.sizeBytes ?: original.sizeBytes,
                durationMs = concrete.durationMs ?: original.durationMs,
                expiresAtEpochMs = original.expiresAtEpochMs ?: concrete.expiresAtEpochMs)
            if (current.segments.isNotEmpty() || current.transport == TransportKind.PROGRESSIVE) return current
        }
        throw IllegalArgumentException("The selected manifest exceeded the resolution depth limit.")
    }
}
