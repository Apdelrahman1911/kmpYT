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

/** A fixed, non-sensitive reason why the actual leading result cannot be selected. */
enum class YouTubeBrowseEntryIssue { UNAVAILABLE, UNSUPPORTED, INVALID_METADATA, TEXT_LIMIT, PARSE_LIMIT }

/**
 * The first content entry in response order, before unavailable/unsupported entries are omitted
 * from [YouTubeBrowsePage.items]. A channel or playlist is a collection, not an audio/video plan.
 * An unknown entry keeps a null kind/identity rather than borrowing a later result's identity.
 * A title is complete or null; an oversized/malformed title is never replaced with a prefix.
 */
data class YouTubeBrowseEntry(
    val kind: YouTubeBrowseKind?,
    val id: String?,
    val title: String?,
    val url: String?,
    val issue: YouTubeBrowseEntryIssue? = null,
    val ordinal: Int = 0,
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
    /** Never use items.first() when the original leading result is semantically significant. */
    val firstEntry: YouTubeBrowseEntry? = null,
    /** The exact complete search query, including surrounding spaces; null on non-search pages. */
    val query: String? = null,
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
