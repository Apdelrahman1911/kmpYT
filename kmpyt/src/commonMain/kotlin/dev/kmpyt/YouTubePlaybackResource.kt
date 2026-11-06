package dev.kmpyt

import dev.kmpyt.extraction.SourcePlaybackAuthorization
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole

/** An in-memory bearer endpoint. Never persist or log its URL. */
class YouTubePlaybackEndpoint(
    val role: TrackRole,
    val url: String,
    val mimeType: String,
    val lengthBytes: Long,
) {
    init {
        val match = Regex("http://127\\.0\\.0\\.1:([0-9]{1,5})/([0-9a-f]{64})/(audio|video)").matchEntire(url)
        require(match != null && match.groupValues[1].toInt() in 1..65_535)
        require(role in setOf(TrackRole.AUDIO, TrackRole.VIDEO) && match.groupValues[3] == role.name.lowercase())
        require(lengthBytes > 0 && Regex("(?:audio|video)/[a-z0-9.+-]+").matches(mimeType))
    }
    override fun toString() = "YouTubePlaybackEndpoint(role=$role, url=<redacted>)"
}

/**
 * A native decoder may GET only these exact owned loopback endpoints. The library
 * executes and validates SABR/UMP; its signed POST endpoint is never handed to a
 * generic HTTP player. Close is idempotent and cancels readers/ingestion before
 * deleting the private cache. This is remote acquisition, not an offline file.
 */
interface YouTubePlaybackResource {
    val endpoints: List<YouTubePlaybackEndpoint>
    suspend fun close()
}

/** Native implementation supplied by a factory or the host, with no default authorization grant. */
fun interface YouTubePlaybackAdapter {
    suspend fun open(request: SourcePlaybackRequest, authorization: SourcePlaybackAuthorization): YouTubePlaybackResource
}

/** Static whole-track cache bounds; no rolling/live/server-time-seek protocol is implied. */
data class YouTubeNativePlaybackLimits(
    val maxCacheBytes: Long = 1_073_741_824,
    val maxConnections: Int = 4,
    val maxHeaderBytes: Int = 16_384,
    val chunkBytes: Int = 65_536,
    val waitTimeoutMs: Long = 45_000,
    val connectionIdleTimeoutMs: Long = 60_000,
) {
    init {
        require(maxCacheBytes > 0 && maxConnections in 1..16 && maxHeaderBytes in 256..65_536)
        require(chunkBytes in 1..1_048_576 && waitTimeoutMs in 1..120_000)
        require(connectionIdleTimeoutMs in 1_000..300_000 && connectionIdleTimeoutMs >= waitTimeoutMs)
    }
}
