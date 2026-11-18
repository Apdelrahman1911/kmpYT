package dev.kmpyt.extraction

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anonymous compatibility profiles documented by yt-dlp 2026.10.26:
 * https://github.com/yt-dlp/yt-dlp/blob/2026.10.26/yt_dlp/extractor/youtube/_base.py#L295-L381
 * These are pinned upstream profiles, not declarations attributed to the watch page.
 * Only its actual anonymous visitor context and signature timestamp are reused.
 * No cookies, account flags, integrity grants or replacement signatures are generated.
 */
object YouTubePublicPlayerClients {
    const val SOURCE_VERSION = "yt-dlp 2026.10.26"

    /** Pinned public profile names, in kmpYT's default request order. */
    val DEFAULT_ORDER = listOf("TVHTML5", "VISIONOS")

    /**
     * The pinned profiles in [order] (client names; unknown names are ignored and omitted
     * profiles keep their default relative order after the named ones).
     */
    fun fromObservedWeb(web: YouTubeClientContract, order: List<String>): List<YouTubeClientContract> {
        val clients = fromObservedWeb(web)
        val rank = order.withIndex().associate { (index, name) -> name to index }
        return clients.withIndex().sortedWith(compareBy({ rank[it.value.clientContext.text("clientName")] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    fun fromObservedWeb(web: YouTubeClientContract): List<YouTubeClientContract> {
        require(web.provenance == YouTubeClientProvenance.RESPONSE_DECLARED_BROWSER && web.clientContext.text("clientName") == "WEB")
        val visitor = web.clientContext.text("visitorData")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val tvAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
        val visionAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        fun client(label: String, name: String, version: String, number: Int, make: String, model: String,
            agent: String, os: String, osVersion: String) = YouTubeClientContract(
            name = "$SOURCE_VERSION $label",
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientContext = buildJsonObject {
                put("clientName", name); put("clientVersion", version)
                put("deviceMake", make); put("deviceModel", model); put("userAgent", agent)
                put("osName", os); put("osVersion", osVersion)
                put("visitorData", visitor)
                put("hl", web.clientContext.text("hl") ?: "en")
                web.clientContext.text("gl")?.let { put("gl", it) }
                put("timeZone", "UTC"); put("utcOffsetMinutes", 0)
            },
            headers = mapOf("Origin" to "https://www.youtube.com", "User-Agent" to agent,
                "X-Youtube-Client-Name" to number.toString(), "X-Youtube-Client-Version" to version,
                "X-Goog-Visitor-Id" to visitor),
            signatureTimestamp = web.signatureTimestamp,
            provenance = YouTubeClientProvenance.PINNED_PUBLIC_CLIENT,
        )
        return listOf(
            client("tv_samsung", "TVHTML5", "5.20260707", 7, "Samsung", "UKS9800", tvAgent, "Tizen", "2.4.0"),
            client("visionos", "VISIONOS", "1.02", 101, "Apple", "RealityDevice17,1", visionAgent, "visionOS", "26.5.23O471"),
        )
    }
}
