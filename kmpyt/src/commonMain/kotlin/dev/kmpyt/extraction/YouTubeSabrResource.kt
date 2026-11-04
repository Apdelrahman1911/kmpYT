package dev.kmpyt.extraction

import io.ktor.util.decodeBase64Bytes
import kotlinx.serialization.Serializable

/** A shared protocol endpoint plus exact source identity, never an invented per-format MP4 URL. */
@Serializable data class YouTubeSabrResource(val videoId: String, val endpoint: String,
    val configuration: String, val formatId: SabrFormatId, val clientInfo: SabrClientInfo) {
    init {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        require(configuration.length in 1..131072)
        require(configuration.decodeBase64Bytes().size in 1..98304)
    }
    override fun toString() = "YouTubeSabrResource(videoId=$videoId,itag=${formatId.itag},endpoint=${ResearchRedaction.url(endpoint)},configuration=redacted)"
}

fun PlannedDownload.sabrSource(): YouTubeSabrSource {
    val tracks = listOfNotNull(primary, audio)
    require(tracks.all { it.transport == TransportKind.SABR && it.sabr != null && it.url == it.sabr.endpoint && it.sizeBytes != null && it.sizeBytes > 0 })
    val first = requireNotNull(primary.sabr)
    require(tracks.all { it.sabr!!.let { resource -> resource.videoId == first.videoId && resource.endpoint == first.endpoint && resource.configuration == first.configuration && resource.clientInfo == first.clientInfo } }) {
        "SABR tracks must come from the same actual player response and client. Inspect the source again."
    }
    require(mediaId == first.videoId)
    return YouTubeSabrSource(first.videoId, first.endpoint, first.configuration.decodeBase64Bytes(), tracks.map { track ->
        YouTubeSabrSourceFormat(track.sabr!!.formatId, track.role, track.mimeType, track.width, track.height,
            track.bitrate, track.audioTrackId, null)
    }, primary.expiresAtEpochMs)
}

fun PlannedDownload.sabrSelection() = YouTubeSabrSelection(
    listOfNotNull(primary, audio).firstOrNull { it.role == TrackRole.AUDIO }?.sabr?.formatId,
    listOfNotNull(primary, audio).firstOrNull { it.role == TrackRole.VIDEO }?.sabr?.formatId)

/** Sparse-file length is not transferred-byte progress; only verified coverage counts. */
fun SabrTransferCheckpoint.partStates(plan: PlannedDownload, complete: Boolean = false): List<PartState> =
    listOfNotNull(plan.primary, plan.audio).mapIndexed { index, rep ->
        val track = tracks.single { it.formatId == requireNotNull(rep.sabr).formatId && it.role == rep.role }
        PartState("$index-0", track.coverage.sumOf { it.length }, track.expectedBytes, complete = complete)
    }
