package dev.kmpyt.youtube

import kotlinx.serialization.json.JsonObject

enum class YouTubeBrowseKind { VIDEO, CHANNEL, PLAYLIST }

data class YouTubeBrowseItem(
    val id: String,
    val title: String,
    val url: String,
    val kind: YouTubeBrowseKind,
    val thumbnailUrl: String? = null,
    val subtitle: String? = null,
    val durationText: String? = null,
)

data class YouTubeBrowsePage(
    val title: String,
    val items: List<YouTubeBrowseItem>,
    val continuation: String? = null,
    val sourceUrl: String,
    val notice: String? = null,
    val continuationContext: YouTubeBrowseContinuation? = null,
    /** Actual channelMetadataRenderer.externalId, not an identity inferred from a handle. */
    val channelId: String? = null,
)

/** Values declared by the actual public page, retained only for its continuation. */
data class YouTubeBrowseContinuation(
    val sourceUrl: String,
    val endpoint: String,
    val clientContext: JsonObject,
    val headers: Map<String, String> = emptyMap(),
    val visitedTokens: Set<String> = emptySet(),
    val pagesLoaded: Int = 1,
) {
    override fun toString() = "YouTubeBrowseContinuation(pagesLoaded=$pagesLoaded,context=redacted)"
}

interface YouTubeBrowseService {
    suspend fun search(query: String): YouTubeBrowsePage
    suspend fun home(): YouTubeBrowsePage
    suspend fun open(url: String): YouTubeBrowsePage

    /** Returns the accumulated page, preserving a playlist's original ordering and occurrences. */
    suspend fun more(page: YouTubeBrowsePage): YouTubeBrowsePage
}
