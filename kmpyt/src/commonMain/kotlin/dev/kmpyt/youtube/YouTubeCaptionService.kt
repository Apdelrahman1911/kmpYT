package dev.kmpyt.youtube

import dev.kmpyt.extraction.*
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class CaptionSelection(val sourceIndex: Int, val trackId: String?,
    val languageCode: String?, val translationLanguage: String? = null)

/** This sidecar contains subtitle text and identity only; source URLs and credentials are never persisted. */
@Serializable data class SavedYouTubeCaptions(val videoId: String, val title: String, val label: String,
    val selection: CaptionSelection, val document: YouTubeCaptionDocument, val enabled: Boolean,
    val schemaVersion: Int = 1)

interface YouTubeCaptionStore {
    suspend fun load(videoId: String): SavedYouTubeCaptions?
    suspend fun save(value: SavedYouTubeCaptions)
}

data class VideoCaptionsState(val videoId: String, val title: String = "YouTube video",
    val catalogue: YouTubeCaptionCatalogue? = null, val selected: CaptionSelection? = null,
    val label: String? = null, val document: YouTubeCaptionDocument? = null,
    val enabled: Boolean = false, val loading: Boolean = false, val error: String? = null,
    val revision: Long = 0, val cached: Map<CaptionSelection, YouTubeCaptionDocument> = emptyMap())

data class CaptionsState(val videos: Map<String, VideoCaptionsState> = emptyMap(),
    val restored: Set<String> = emptySet())

enum class CaptionExportFormat(val extension: String, val mimeType: String) {
    SRT("srt", "application/x-subrip"), WEBVTT("vtt", "text/vtt")
}

interface YouTubeCaptionService {
    val state: StateFlow<CaptionsState>
    fun register(report: ExtractionReport, mediaId: String)
    suspend fun select(videoId: String, sourceIndex: Int, translationLanguage: String? = null)
    suspend fun setEnabled(videoId: String, enabled: Boolean)
    suspend fun restore(videoId: String)
    fun export(videoId: String, format: CaptionExportFormat): String
}

/** User-selected captions use the response's exact timedtext resource and declared translation catalogue. */
class PublicYouTubeCaptionService(private val transport: ResearchTransport,
    private val store: YouTubeCaptionStore? = null) : YouTubeCaptionService {
    private val mutable = MutableStateFlow(CaptionsState())
    override val state: StateFlow<CaptionsState> = mutable.asStateFlow()
    private val persistence = Mutex()

    override fun register(report: ExtractionReport, mediaId: String) {
        require(report.provider == ProviderId.YOUTUBE && captionVideoId(report.input) == mediaId) { "Captions must belong to the inspected YouTube video." }
        val media = report.media.single { it.id == mediaId && it.provider == ProviderId.YOUTUBE }
        require(captionVideoId(media.pageUrl) == mediaId) { "Caption video identity does not match." }
        require((media.captions?.tracks?.size ?: 0) <= 256)
        mutable.update { current ->
            val old = current.videos[mediaId] ?: VideoCaptionsState(mediaId)
            val changed = old.catalogue != media.captions
            current.withVideo(old.copy(title = media.title.take(300), catalogue = media.captions,
                revision = old.revision + if (changed) 1 else 0,
                loading = if (changed) false else old.loading))
        }
    }

    override suspend fun select(videoId: String, sourceIndex: Int, translationLanguage: String?) {
        val existing = requireNotNull(state.value.videos[videoId]) { "Inspect this video to choose subtitles." }
        val catalogue = requireNotNull(existing.catalogue) { "This video did not provide subtitle tracks." }
        val track = catalogue.tracks.singleOrNull { it.sourceIndex == sourceIndex }
            ?: throw IllegalArgumentException("Choose a subtitle track from this video.")
        requirePublicTrack(track, videoId)
        val language = translationLanguage?.let { code ->
            require(track.isTranslatable == true) { "This track does not offer translation." }
            catalogue.translationLanguages.singleOrNull { it.languageCode == code }
                ?: throw IllegalArgumentException("Choose a translation language supplied by this video.")
        }
        val requestTrack = if (language == null) track else track.copy(
            url = YouTubeUrlAssembly.replaceParameter(track.url, "tlang", language.languageCode))
        val selection = CaptionSelection(track.sourceIndex, track.id, track.languageCode, language?.languageCode)
        val label = (language?.let { "${it.name ?: it.languageCode} (translated)" }
            ?: track.name ?: track.languageCode ?: "Track ${track.sourceIndex + 1}").take(160)
        var started = existing
        mutable.update { current ->
            val old = requireNotNull(current.videos[videoId])
            require(old.catalogue == catalogue) { "The subtitle list changed. Select a track again." }
            started = old.copy(selected = selection, label = label, document = old.cached[selection],
                enabled = true, loading = old.cached[selection] == null, error = null, revision = old.revision + 1)
            current.withVideo(started)
        }
        if (started.document != null) { persist(videoId); return }
        try {
            val document = YouTubeCaptionClient(transport) { candidate -> candidate == requestTrack }.load(requestTrack)
            validateCaptionDocument(document)
            mutable.update { current ->
                val old = current.videos[videoId]
                if (old == null || old.revision != started.revision) current else {
                    val cache = if (document.cues.isEmpty()) old.cached - selection else (old.cached - selection) + (selection to document)
                    current.withVideo(old.copy(document = document, loading = false,
                        error = if (document.cues.isEmpty()) "This subtitle track returned no text. Inspect the video again or choose another track." else null,
                        cached = cache.entries.toList().takeLast(4).associate { it.toPair() }))
                }
            }
            persist(videoId)
        } catch (cancelled: CancellationException) {
            mutable.update { current -> current.changeIfCurrent(videoId, started.revision) { it.copy(loading = false) } }
            throw cancelled
        } catch (failure: Exception) {
            val message = captionFailureMessage(failure)
            mutable.update { current -> current.changeIfCurrent(videoId, started.revision) {
                it.copy(document = null, loading = false, error = message)
            } }
        }
    }

    override suspend fun setEnabled(videoId: String, enabled: Boolean) {
        mutable.update { current ->
            val old = current.videos[videoId] ?: return@update current
            current.withVideo(old.copy(enabled = enabled))
        }
        persist(videoId)
    }

    override suspend fun restore(videoId: String) {
        require(VIDEO_ID.matches(videoId))
        var shouldLoad = false
        mutable.update { current ->
            shouldLoad = videoId !in current.restored
            if (shouldLoad) current.copy(restored = (current.restored + videoId).toList().takeLast(128).toSet()) else current
        }
        if (!shouldLoad || store == null) return
        try {
            val saved = store.load(videoId) ?: return
            require(saved.schemaVersion == 1 && saved.videoId == videoId && saved.title.length <= 300 && saved.label.length <= 160)
            require(saved.selection.sourceIndex in 0..255 && (saved.selection.trackId?.length ?: 0) <= 512 &&
                (saved.selection.languageCode?.length ?: 0) <= 128 && (saved.selection.translationLanguage?.length ?: 0) <= 128)
            validateCaptionDocument(saved.document)
            mutable.update { current ->
                val old = current.videos[videoId] ?: VideoCaptionsState(videoId, saved.title)
                // A current user choice always wins over an asynchronous sidecar read.
                if (old.selected != null || old.loading) current else current.withVideo(old.copy(
                    selected = saved.selection, label = saved.label, document = saved.document,
                    enabled = saved.enabled, cached = mapOf(saved.selection to saved.document)))
            }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(restored = it.restored - videoId) }
            throw cancelled
        } catch (_: Exception) {
            mutable.update { current ->
                val old = current.videos[videoId] ?: VideoCaptionsState(videoId)
                if (old.selected != null) current else current.withVideo(old.copy(error = "Saved subtitles could not be read. Inspect this video to download them again."))
            }
        }
    }

    override fun export(videoId: String, format: CaptionExportFormat): String = CaptionDocuments.export(
        requireNotNull(state.value.videos[videoId]?.document) { "Load subtitles before exporting them." }, format)

    private suspend fun persist(videoId: String) {
        if (store == null) return
        persistence.withLock {
            val current = state.value.videos[videoId] ?: return@withLock
            val document = current.document?.takeIf { it.cues.isNotEmpty() } ?: return@withLock
            val selection = current.selected ?: return@withLock
            try { store.save(SavedYouTubeCaptions(videoId, current.title, current.label.orEmpty(), selection, document, current.enabled)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { snapshot -> snapshot.changeIfCurrent(videoId, current.revision) {
                    it.copy(error = "Subtitles are available now, but could not be saved for the next app session.")
                } }
            }
        }
    }

    private fun CaptionsState.withVideo(video: VideoCaptionsState): CaptionsState {
        val updated = (videos - video.videoId) + (video.videoId to video)
        val retained = updated.entries.toList().takeLast(16).associate { it.toPair() }
        return copy(videos = retained, restored = restored - (videos.keys - retained.keys))
    }

    private fun CaptionsState.changeIfCurrent(videoId: String, revision: Long,
        change: (VideoCaptionsState) -> VideoCaptionsState): CaptionsState {
        val current = videos[videoId] ?: return this
        return if (current.revision == revision) withVideo(change(current)) else this
    }

    private fun requirePublicTrack(track: YouTubeCaptionTrack, videoId: String) {
        val parsed = runCatching { Url(track.url) }.getOrNull()
            ?: throw IllegalArgumentException("This subtitle URL is invalid.")
        val host = parsed.host.lowercase()
        require(parsed.protocol.name == "https" && parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty() &&
            (host == "youtube.com" || host.endsWith(".youtube.com")) && parsed.encodedPath == "/api/timedtext") {
            "This subtitle track does not provide a supported YouTube timedtext URL."
        }
        require(parsed.parameters.getAll("v")?.let { it.size == 1 && it.single() == videoId } != false) {
            "The subtitle URL belongs to another video."
        }
    }

    private fun captionFailureMessage(failure: Exception): String = when ((failure as? YouTubeCaptionException)?.code) {
        "CAPTION_HTTP_401", "CAPTION_HTTP_403" -> "YouTube denied this subtitle request. Inspect the video again to refresh its tracks."
        "CAPTION_HTTP_429" -> "YouTube is limiting requests. Wait before trying subtitles again."
        "CAPTION_HTTP_FAILED" -> "Subtitles could not be retrieved. Check your connection and retry."
        "CAPTION_SIZE_LIMIT" -> "This subtitle response was empty or exceeded the supported size. Try another track."
        else -> "This subtitle track could not be read. Inspect the video again or choose another track."
    }

    companion object { private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}") }
}

fun captionVideoId(source: String?): String? = source?.let { YouTubeProvider().normalize(it) }
    ?.let { Url(it).parameters["v"] }

private fun validateCaptionDocument(document: YouTubeCaptionDocument) {
    require(document.cues.size <= YouTubeCaptions.MAX_CUES)
    var characters = 0L
    document.cues.forEach { cue ->
        require(cue.startMs in 0..604_800_000L && cue.endMs in cue.startMs + 1..604_800_000L)
        require(cue.text.length <= YouTubeCaptions.MAX_CUE_CHARACTERS && cue.segments.size <= 4096)
        cue.segments.forEach { segment ->
            require(segment.text.length <= YouTubeCaptions.MAX_CUE_CHARACTERS &&
                (segment.offsetMs == null || segment.offsetMs in 0..cue.endMs - cue.startMs))
        }
        characters += cue.text.length
        require(characters <= YouTubeCaptions.MAX_CHARACTERS)
    }
}

/** Recomputed from media position, so pause, seek and playback speed need no independent subtitle timer. */
class CaptionTimeline(document: YouTubeCaptionDocument) {
    private val cues = document.cues.sortedWith(compareBy<YouTubeCaptionCue> { it.startMs }.thenBy { it.endMs })

    fun textAt(positionMs: Long): String {
        if (positionMs < 0) return ""
        val lines = linkedMapOf<String, String>()
        for ((index, cue) in cues.withIndex()) {
            if (cue.startMs > positionMs) break
            if (positionMs >= cue.endMs) continue
            val text = if (cue.segments.any { it.offsetMs != null }) cue.segments
                .filter { (it.offsetMs ?: 0) <= positionMs - cue.startMs }.joinToString("") { it.text } else cue.text
            if (text.isBlank()) continue
            val key = cue.windowId?.let { "window:$it" } ?: "cue:$index"
            lines[key] = if (cue.append == true) lines[key].orEmpty() + text else text
        }
        return lines.values.joinToString("\n")
    }
}

object CaptionDocuments {
    fun export(document: YouTubeCaptionDocument, format: CaptionExportFormat): String {
        validateCaptionDocument(document)
        require(document.cues.isNotEmpty()) { "This subtitle track contains no cues to export." }
        return buildString {
            if (format == CaptionExportFormat.WEBVTT) append("WEBVTT\n\n")
            document.cues.sortedWith(compareBy<YouTubeCaptionCue> { it.startMs }.thenBy { it.endMs }).forEachIndexed { index, cue ->
                append(index + 1).append('\n')
                append(timestamp(cue.startMs, format)).append(" --> ").append(timestamp(cue.endMs, format)).append('\n')
                append(cue.text.replace("\r\n", "\n").replace('\r', '\n')
                    .filter { it.code >= 32 || it == '\n' || it == '\t' }
                    .replace(Regex("\n[ \\t]*\n+"), "\n")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .trim()).append("\n\n")
            }
        }
    }

    private fun timestamp(milliseconds: Long, format: CaptionExportFormat): String {
        val hours = milliseconds / 3_600_000
        val minutes = milliseconds / 60_000 % 60
        val seconds = milliseconds / 1000 % 60
        val separator = if (format == CaptionExportFormat.SRT) ',' else '.'
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}$separator${(milliseconds % 1000).toString().padStart(3, '0')}"
    }
}
