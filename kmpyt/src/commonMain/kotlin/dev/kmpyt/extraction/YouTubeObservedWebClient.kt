package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.*

/**
 * Reads the anonymous WEB contract declared by a real watch page. This is an
 * observable, undocumented browser interface, not an official download API.
 * No alternate client identity, login flags, cookies or integrity state is made up.
 */
object YouTubeObservedWebClient {
    fun discover(context: ExtractionContext, page: String): YouTubeClientContract? {
        if (page.length > YouTubeProvider.MAX_PAGE_BODY) return null
        val source = runCatching { Url(context.responseUrl) }.getOrNull() ?: return null
        if (source.protocol.name != "https" || source.port != 443 ||
            source.host.lowercase().trimEnd('.') !in setOf("www.youtube.com", "youtube.com", "m.youtube.com") ||
            source.encodedPath != "/watch" || !source.user.isNullOrBlank() || !source.password.isNullOrBlank()) return null
        val configs = Regex("ytcfg\\.set\\s*\\(\\s*\\{").findAll(page).take(16).mapNotNull { match ->
            val start = page.indexOf('{', match.range.first)
            // Current pages put large unrelated experiment/UI settings in this same
            // declaration. Bound the whole JSON, then copy only tiny client fields.
            val json = EmbeddedData.balanced(page, start)?.takeIf { it.length <= EmbeddedData.MAX_BODY } ?: return@mapNotNull null
            runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        }.toList()
        return configs.firstNotNullOfOrNull { config -> fromConfig(config, source.host) }
    }

    private fun fromConfig(config: JsonObject, sourceHost: String): YouTubeClientContract? {
        val supplied = config.obj("INNERTUBE_CONTEXT")?.obj("client") ?: return null
        val name = supplied.text("clientName") ?: return null
        val version = (supplied["clientVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.length in 1..80 && it.none { c -> c.code < 32 || c.code == 127 } } ?: return null
        if (name != "WEB" || config.text("INNERTUBE_CLIENT_NAME")?.let { it != name } == true ||
            config.text("INNERTUBE_CLIENT_VERSION")?.let { it != version } == true) return null
        val headerName = config.number("INNERTUBE_CONTEXT_CLIENT_NAME")?.takeIf { it == 1L } ?: return null
        // Allow only public browser client declarations. Account, request/session,
        // serviceIntegrityDimensions and arbitrary fields never cross this boundary.
        val client = buildJsonObject {
            PUBLIC_CLIENT_FIELDS.forEach { key ->
                (supplied[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 2048 && it.content.none { c -> c.code < 32 || c.code == 127 } }
                    ?.let { put(key, it) }
            }
        }
        val apiKey = config.text("INNERTUBE_API_KEY")?.takeIf { it.length in 1..256 && it.none(Char::isWhitespace) }
        val endpoint = "https://$sourceHost/youtubei/v1/player?prettyPrint=false" + (apiKey?.let { "&key=${it.encodeURLParameter()}" } ?: "")
        return YouTubeClientContract("response-declared WEB", endpoint, client,
            headers = mapOf("Origin" to "https://$sourceHost", "X-Youtube-Client-Name" to headerName.toString(),
                "X-Youtube-Client-Version" to version),
            signatureTimestamp = config.number("STS")?.takeIf { it in 1..Int.MAX_VALUE.toLong() },
            provenance = YouTubeClientProvenance.RESPONSE_DECLARED_BROWSER)
    }

    private val PUBLIC_CLIENT_FIELDS = setOf("clientName", "clientVersion", "hl", "gl", "userAgent", "platform",
        "osName", "osVersion", "browserName", "browserVersion", "visitorData")
}
