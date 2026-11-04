package dev.kmpyt.extraction

import kotlinx.serialization.Serializable

@Serializable enum class ProviderId { YOUTUBE }
@Serializable enum class TrackRole { MUXED, VIDEO, AUDIO }
@Serializable enum class TransportKind { PROGRESSIVE, HLS, DASH, SABR, UNRESOLVED }

@Serializable data class SegmentResource(val url: String, val rangeStart: Long? = null,
    val rangeLength: Long? = null, val durationMs: Long? = null)

/** Source-declared color metadata; absent fields are not guessed from a quality label. */
@Serializable data class MediaColorInfo(val primaries: String? = null,
    val transferCharacteristics: String? = null, val matrixCoefficients: String? = null)

/** A representation is a track or complete file, never an invented quality/download URL. */
@Serializable data class Representation(
    val id: String, val url: String?, val role: TrackRole,
    val transport: TransportKind = TransportKind.PROGRESSIVE,
    val mimeType: String = "application/octet-stream", val codecs: String? = null,
    val width: Int? = null, val height: Int? = null, val bitrate: Long? = null,
    val sizeBytes: Long? = null, val durationMs: Long? = null,
    val segments: List<SegmentResource> = emptyList(), val initialization: SegmentResource? = null,
    val expiresAtEpochMs: Long? = null, val unavailableReason: String? = null,
    val manifestBody: String? = null,
    val audioGroupId: String? = null,
    val frameRate: Int? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioLanguage: String? = null,
    val qualityLabel: String? = null,
    val adaptive: Boolean? = null,
    val audioTrackId: String? = null,
    val sabr: YouTubeSabrResource? = null,
    val audioIsDefault: Boolean? = null,
    val audioDisplayName: String? = null,
    val audioQuality: String? = null,
    val isDrc: Boolean? = null,
    val loudnessDb: Double? = null,
    val colorInfo: MediaColorInfo? = null,
    val isHdr: Boolean? = null,
    /** Manifest that actually supplied this child track; null for an unexpanded catalogue pointer. */
    val manifestUrl: String? = null,
) {
    val selectable: Boolean get() = unavailableReason == null && !url.isNullOrBlank() &&
        (transport in setOf(TransportKind.PROGRESSIVE, TransportKind.HLS, TransportKind.DASH) || transport == TransportKind.SABR && sabr != null && sizeBytes != null && sizeBytes > 0)
}

/** A catalogue descriptor may have no resource URL. It must never become a fabricated task URL. */
fun Representation.requireResourceUrl(): String = url?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("The source did not provide a media resource URL for this representation")

data class ExtractionDiagnostic(val stage: String, val code: String, val message: String)
data class ExtractedMedia(val id: String, val provider: ProviderId, val title: String,
    val pageUrl: String, val representations: List<Representation>,
    val thumbnailUrl: String? = null, val durationMs: Long? = null, val artist: String? = null,
    val captions: YouTubeCaptionCatalogue? = null,
    /** Actual videoDetails.channelId; absent or malformed identities are never inferred. */
    val uploaderChannelId: String? = null)
data class ExtractionContext(val pageUrl: String, val responseUrl: String, val nowEpochMs: Long = 0)
data class ProviderResult(val media: List<ExtractedMedia>, val diagnostics: List<ExtractionDiagnostic> = emptyList())
data class ResearchRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(),
    val body: String? = null, val stage: String = "page")
data class ResearchResponse(val url: String, val status: Int, val body: String,
    val headers: Map<String, String> = emptyMap())

interface ProviderAdapter {
    val id: ProviderId
    fun normalize(url: String): String?
    fun parse(context: ExtractionContext, body: String): ProviderResult
    /** Request plans are routed to an explicitly authorized research origin, not platform production. */
    fun requestPlan(context: ExtractionContext): List<ResearchRequest>
}

fun interface ResearchTransport { suspend fun execute(request: ResearchRequest): ResearchResponse }
data class ManifestResult(val representations: List<Representation>, val diagnostics: List<ExtractionDiagnostic> = emptyList())
interface ManifestParser {
    fun parse(url: String, body: String): ManifestResult
}

data class ExtractionReport(val input: String, val provider: ProviderId,
    val media: List<ExtractedMedia>, val diagnostics: List<ExtractionDiagnostic>,
    val requestPlan: List<ResearchRequest> = emptyList(), val inspectedAtEpochMs: Long = 0)
