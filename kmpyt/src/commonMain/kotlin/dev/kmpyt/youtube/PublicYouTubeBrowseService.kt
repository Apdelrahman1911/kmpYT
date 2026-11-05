package dev.kmpyt.youtube

import dev.kmpyt.extraction.EmbeddedData
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.kmpyt.extraction.array
import dev.kmpyt.extraction.number
import dev.kmpyt.extraction.obj
import dev.kmpyt.extraction.text
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Public page data only. No browser cookies, account headers or invented client profiles. */
class PublicYouTubeBrowseService(private val transport: ResearchTransport) : YouTubeBrowseService {
    override suspend fun search(query: String): YouTubeBrowsePage {
        val text = query.trim()
        require(text.length in 1..256 && text.none { it.code < 32 }) { "Enter a search phrase of 1–256 characters." }
        return open("$ORIGIN/results?search_query=${text.encodeURLParameter()}")
    }

    override suspend fun home(): YouTubeBrowsePage = open("$ORIGIN/")

    override suspend fun open(url: String): YouTubeBrowsePage {
        val source = canonicalBrowseUrl(url)
        val title = defaultTitle(source)
        val reply = fetchPage(source)
        if (reply.response == null) return YouTubeBrowsePage(title, emptyList(), sourceUrl = source, notice = reply.notice)
        val response = reply.response
        val data = initialData(response.body)
            ?: return YouTubeBrowsePage(title, emptyList(), sourceUrl = source,
                notice = "YouTube did not return readable public results. Try searching, or open this page in YouTube.")
        val parsed = parse(data, source)
        val context = parsed.token?.let {
            declaredContext(response.body, source, parsed.endpoint)
        }
        return assemble(source, pageTitle(data) ?: title, parsed, context).copy(
            channelId = data.obj("metadata")?.obj("channelMetadataRenderer")?.text("externalId")
                ?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) })
    }

    override suspend fun more(page: YouTubeBrowsePage): YouTubeBrowsePage {
        val token = page.continuation ?: return page
        val source = canonicalBrowseUrl(page.sourceUrl)
        val previous = requireNotNull(page.continuationContext) { "This page has no usable continuation context. Reload it first." }
        require(source == previous.sourceUrl && source == page.sourceUrl) { "Continuation belongs to another page." }
        require(token.length in 1..MAX_TOKEN && token.none { it.code < 32 }) { "Invalid continuation token." }
        validateContext(previous)
        if (previous.pagesLoaded >= MAX_PAGES || page.items.size >= MAX_TOTAL_ITEMS || token in previous.visitedTokens) {
            return page.copy(continuation = null, continuationContext = null, notice = "The page limit was reached. Refine your search or open the full list in YouTube.")
        }
        val body = buildJsonObject {
            putJsonObject("context") { put("client", previous.clientContext) }
            put("continuation", token)
        }.toString()
        val reply = safeExecute(ResearchRequest("POST", previous.endpoint,
            previous.headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json"), body, "youtube-browse-more"))
        if (reply.response == null) return page.copy(notice = reply.notice)
        val response = reply.response
        if (response.url != previous.endpoint || response.status !in 200..299) {
            return page.copy(notice = httpNotice(response.status))
        }
        val data = parseObject(response.body)
            ?: return page.copy(notice = "YouTube returned an unreadable next page. Reload the list or try again.")
        val parsed = parse(data, source)
        val consumed = previous.visitedTokens + token
        val declaredNextEndpoint = parsed.endpoint?.let { runCatching { continuationEndpoint(it, source) }.getOrNull() }
        val invalidEndpoint = parsed.endpoint != null && declaredNextEndpoint == null
        val nextEndpoint = declaredNextEndpoint?.let { attachKey(it, Url(previous.endpoint).parameters["key"]) } ?: previous.endpoint
        val next = previous.copy(endpoint = nextEndpoint,
            visitedTokens = consumed, pagesLoaded = previous.pagesLoaded + 1)
        val combined = if (isPlaylist(source)) page.items + parsed.items
            else (page.items + parsed.items).distinctBy { it.kind to it.id }
        val limitReached = combined.size >= MAX_TOTAL_ITEMS || next.pagesLoaded >= MAX_PAGES
        val repeated = parsed.token != null && parsed.token in consumed
        val usableNext = parsed.token != null && !limitReached && !repeated && !invalidEndpoint && !parsed.truncated
        val notice = when {
            parsed.truncated -> PAGE_TRUNCATED_NOTICE
            limitReached -> "The page limit was reached. Open the full list in YouTube to continue."
            invalidEndpoint -> "YouTube returned an unsupported next-page destination. Open the full list in YouTube."
            repeated -> "YouTube repeated its continuation. Reload the list to check for more results."
            parsed.items.isEmpty() && parsed.token == null -> "No more public results were returned."
            parsed.unavailable > 0 -> "Unavailable entries were omitted from this public list."
            else -> null
        }
        return page.copy(items = combined.take(MAX_TOTAL_ITEMS), continuation = parsed.token.takeIf { usableNext },
            continuationContext = next.takeIf { usableNext }, notice = notice)
    }

    private fun assemble(source: String, title: String, parsed: Parsed, context: YouTubeBrowseContinuation?): YouTubeBrowsePage {
        val items = if (isPlaylist(source)) parsed.items else parsed.items.distinctBy { it.kind to it.id }
        val notice = when {
            parsed.truncated -> PAGE_TRUNCATED_NOTICE
            items.isEmpty() && Url(source).encodedPath == "/" ->
                "YouTube returned no public recommendations. Search for a video, or open YouTube in the browser to sign in."
            items.isEmpty() -> "No public videos, channels or playlists were returned. Search again or open this page in YouTube."
            parsed.token != null && context == null -> "More results are available in YouTube. This page did not provide a usable public continuation."
            parsed.unavailable > 0 -> "Unavailable entries were omitted from this public list."
            else -> null
        }
        return YouTubeBrowsePage(title.take(300), items, parsed.token.takeIf { context != null && !parsed.truncated },
            source, notice, context.takeUnless { parsed.truncated })
    }

    private data class Fetch(val response: ResearchResponse? = null, val notice: String? = null)

    private suspend fun safeExecute(request: ResearchRequest): Fetch = try {
        val response = transport.execute(request)
        if (response.body.length > MAX_PAGE) Fetch(notice = "YouTube's response exceeded the page size limit. Try a narrower search.")
        else Fetch(response)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { Fetch(notice = "YouTube could not be reached. Check your connection and try again.") }

    private suspend fun fetchPage(source: String): Fetch {
        var current = source
        repeat(4) { attempt ->
            val reply = safeExecute(ResearchRequest("GET", current, mapOf("Accept" to "text/html"), stage = "youtube-browse"))
            val response = reply.response ?: return reply
            // A transport that automatically followed a redirect cannot silently change page identity.
            if (response.url != current) return Fetch(notice = "YouTube redirected this request. Open the page in YouTube to continue.")
            if (response.status in 200..299) return reply
            if (response.status !in 300..399) return Fetch(notice = httpNotice(response.status))
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
            val target = location?.let { runCatching { EmbeddedData.absolute(current, it) }.getOrNull() }
            val normalized = target?.let { runCatching { canonicalBrowseUrl(it) }.getOrNull() }
            if (normalized != source || attempt == 3) return Fetch(notice =
                "YouTube asks for consent, sign-in or another page. Open this page in YouTube to continue.")
            // Preserve an actual same-page host redirect, while rechecking its secure origin.
            val parsed = target?.let { runCatching { Url(it) }.getOrNull() }
            if (parsed == null || !publicOrigin(parsed)) return Fetch(notice = "YouTube returned an unsupported redirect.")
            current = requireNotNull(target)
        }
        return Fetch(notice = "YouTube returned too many redirects. Try again later.")
    }

    private fun httpNotice(status: Int): String = when (status) {
        401, 403 -> "YouTube requires access or sign-in for this page. Open it in YouTube."
        404 -> "This YouTube page was not found. Check the link or try searching."
        429 -> "YouTube temporarily limited requests. Wait a little and try again."
        in 300..399 -> "YouTube redirected the next-page request. Reload the list or open it in YouTube."
        else -> "YouTube could not load this page (HTTP $status). Try again later."
    }

    private fun initialData(body: String): JsonObject? {
        if (body.trimStart().startsWith('{')) return parseObject(body)
        val assignment = Regex("(?:\\b(?:var\\s+)?ytInitialData|window\\s*\\[\\s*[\"']ytInitialData[\"']\\s*]|window\\.ytInitialData)\\s*=\\s*\\{")
        assignment.findAll(body).take(4).forEach { match ->
            val start = body.indexOf('{', match.range.first)
            EmbeddedData.balanced(body, start)?.let(::parseObject)?.let { return it }
        }
        Regex("<script\\b[^>]*\\bid\\s*=\\s*[\"']ytInitialData[\"'][^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
            .findAll(body).take(4).forEach { parseObject(it.groupValues[1])?.let { data -> return data } }
        return null
    }

    private fun parseObject(value: String): JsonObject? = value.takeIf { it.length <= MAX_PAGE }
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private data class Parsed(val items: List<YouTubeBrowseItem>, val token: String?, val endpoint: String?,
        val unavailable: Int, val truncated: Boolean)

    private fun parse(data: JsonObject, source: String): Parsed {
        val items = mutableListOf<YouTubeBrowseItem>()
        var token: String? = null
        var endpoint: String? = null
        var visited = 0
        var unavailable = 0
        var truncated = false
        fun continuation(value: JsonObject) {
            val command = value.obj("continuationEndpoint") ?: value
            val actual = command.obj("continuationCommand")?.text("token")
                ?: command.obj("nextContinuationData")?.text("continuation") ?: return
            if (actual.length !in 1..MAX_TOKEN || actual.any { it.code < 32 }) return
            token = actual
            endpoint = command.obj("commandMetadata")?.obj("webCommandMetadata")?.text("apiUrl")
        }
        fun visit(value: JsonElement, depth: Int) {
            if (depth > 64 || ++visited > MAX_NODES) { truncated = true; return }
            when (value) {
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                is JsonObject -> {
                    if (value.keys.any { it in AD_RENDERERS }) return
                    value.obj("continuationItemRenderer")?.let { continuation(it); return }
                    if (value.containsKey("nextContinuationData")) { continuation(value); return }
                    for ((key, child) in value) {
                        if (key in ITEM_RENDERERS && child is JsonObject) {
                            if (items.size >= MAX_PAGE_ITEMS) { truncated = true; return }
                            val result = readItem(key, child, source)
                            if (result != null) items += result else unavailable++
                            return
                        }
                    }
                    val tab = value.obj("tabRenderer")
                    if (tab != null && tab["selected"] == JsonPrimitive(false)) return
                    value.values.forEach { visit(it, depth + 1) }
                }
                else -> Unit
            }
        }
        // Skip headers, menus and account actions; only actual content/continuation regions are result lists.
        listOf("contents", "continuationContents", "onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints")
            .mapNotNull { data[it] }.forEach { visit(it, 0) }
        return Parsed(items, token, endpoint, unavailable, truncated)
    }

    private fun readItem(renderer: String, value: JsonObject, source: String): YouTubeBrowseItem? {
        if (value["isPlayable"] == JsonPrimitive(false) || value["unplayableText"] != null) return null
        val metadata = value.obj("metadata")?.obj("lockupMetadataViewModel")
        val overlay = value.obj("overlayMetadata")
        val title = displayText(value["title"]) ?: displayText(value["headline"])
            ?: displayText(metadata?.get("title")) ?: displayText(overlay?.get("primaryText")) ?: return null
        if (title.trim().lowercase() in setOf("[private video]", "[deleted video]", "private video", "deleted video")) return null
        val nav = value.obj("navigationEndpoint") ?: value.obj("onTap")?.obj("innertubeCommand")
            ?: value.obj("rendererContext")?.obj("commandContext")?.obj("onTap")?.obj("innertubeCommand")
        val contentType = value.text("contentType").orEmpty()
        val contentId = value.text("contentId")
        val videoId = value.text("videoId") ?: nav?.obj("watchEndpoint")?.text("videoId")
            ?: nav?.obj("reelWatchEndpoint")?.text("videoId")
            ?: contentId?.takeIf { contentType.endsWith("_VIDEO") || VIDEO_ID.matches(it) }
        val channelId = value.text("channelId") ?: nav?.obj("browseEndpoint")?.text("browseId")?.takeIf { it.startsWith("UC") }
        val playlistId = value.text("playlistId") ?: nav?.obj("watchPlaylistEndpoint")?.text("playlistId")
            ?: contentId?.takeIf { contentType.endsWith("_PLAYLIST") }
            ?: nav?.obj("browseEndpoint")?.text("browseId")?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
        val kind = when {
            renderer in CHANNEL_RENDERERS -> YouTubeBrowseKind.CHANNEL
            renderer in PLAYLIST_RENDERERS || contentType.endsWith("_PLAYLIST") -> YouTubeBrowseKind.PLAYLIST
            videoId != null -> YouTubeBrowseKind.VIDEO
            playlistId != null -> YouTubeBrowseKind.PLAYLIST
            channelId != null -> YouTubeBrowseKind.CHANNEL
            else -> return null
        }
        val id: String
        val url: String
        when (kind) {
            YouTubeBrowseKind.VIDEO -> {
                id = videoId?.takeIf(VIDEO_ID::matches) ?: return null
                url = "$ORIGIN/watch?v=$id"
            }
            YouTubeBrowseKind.PLAYLIST -> {
                id = (playlistId ?: nav?.obj("watchEndpoint")?.text("playlistId"))?.takeIf(PLAYLIST_ID::matches) ?: return null
                url = "$ORIGIN/playlist?list=$id"
            }
            YouTubeBrowseKind.CHANNEL -> {
                id = channelId?.takeIf(CHANNEL_ID::matches) ?: return null
                val supplied = nav?.obj("browseEndpoint")?.text("canonicalBaseUrl")
                    ?: nav?.obj("commandMetadata")?.obj("webCommandMetadata")?.text("url")
                val channelUrl = supplied?.let { runCatching { canonicalBrowseUrl(EmbeddedData.absolute(source, it)) }.getOrNull() }
                url = channelUrl?.takeIf { Url(it).encodedPath.let { path -> path.startsWith("/@") || path.startsWith("/channel/") } }
                    ?: "$ORIGIN/channel/$id/videos"
            }
        }
        val subtitleParts = listOfNotNull(displayText(value["ownerText"]), displayText(value["shortBylineText"]),
            displayText(value["longBylineText"]), displayText(value["viewCountText"]), displayText(value["publishedTimeText"]),
            displayText(value["subscriberCountText"]), displayText(value["videoCountText"]), displayText(overlay?.get("secondaryText")))
            .distinct().toMutableList()
        metadata?.obj("metadata")?.obj("contentMetadataViewModel")?.array("metadataRows")?.take(3)?.forEach { row ->
            (row as? JsonObject)?.array("metadataParts")?.take(4)?.forEach { part ->
                displayText((part as? JsonObject)?.get("text"))?.let(subtitleParts::add)
            }
        }
        val duration = displayText(value["lengthText"]) ?: value.array("thumbnailOverlays")?.firstNotNullOfOrNull { element ->
            val status = (element as? JsonObject)?.obj("thumbnailOverlayTimeStatusRenderer")
            displayText(status?.get("text"))
        } ?: nestedText(value.obj("contentImage"), "thumbnailBadgeViewModel", "text")
        return YouTubeBrowseItem(id, title.take(500), url, kind,
            thumbnail(value["thumbnail"] ?: value["thumbnails"] ?: value["contentImage"]),
            subtitleParts.distinct().joinToString(" · ").take(700).ifBlank { null }, duration?.take(80))
    }

    private fun displayText(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.takeIf { it.isString }?.content
        is JsonObject -> value.text("simpleText") ?: value.text("content")
            ?: value.array("runs")?.take(50)?.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4000 && it.none { c -> c.code == 0 } }

    private fun nestedText(value: JsonElement?, renderer: String, key: String): String? {
        fun visit(node: JsonElement?, depth: Int): String? {
            if (depth > 8) return null
            return when (node) {
                is JsonObject -> displayText(node.obj(renderer)?.get(key)) ?: node.values.firstNotNullOfOrNull { visit(it, depth + 1) }
                is JsonArray -> node.take(20).firstNotNullOfOrNull { visit(it, depth + 1) }
                else -> null
            }
        }
        return visit(value, 0)
    }

    private fun thumbnail(value: JsonElement?): String? {
        val candidates = mutableListOf<Pair<Long, String>>()
        fun visit(node: JsonElement?, depth: Int) {
            if (depth > 8 || candidates.size >= 32) return
            when (node) {
                is JsonObject -> {
                    node.text("url")?.let { raw ->
                        val expanded = if (raw.startsWith("//")) "https:$raw" else raw
                        val u = runCatching { Url(expanded) }.getOrNull()
                        if (u != null && u.protocol.name == "https" && u.port == 443 && u.user.isNullOrEmpty() && u.password.isNullOrEmpty() &&
                            IMAGE_HOSTS.any { u.host.lowercase() == it || u.host.lowercase().endsWith(".$it") }) {
                            candidates += (node.number("width")?.coerceIn(0, 10000) ?: 0) to expanded
                        }
                    }
                    node.values.forEach { visit(it, depth + 1) }
                }
                is JsonArray -> node.take(32).forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }
        visit(value, 0)
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun pageTitle(data: JsonObject): String? {
        val metadata = data.obj("metadata")
        return displayText(metadata?.obj("playlistMetadataRenderer")?.get("title"))
            ?: displayText(metadata?.obj("channelMetadataRenderer")?.get("title"))
            ?: displayText(data.obj("header")?.obj("pageHeaderRenderer")?.get("pageTitle"))
    }

    private fun declaredContext(body: String, source: String, endpoint: String?): YouTubeBrowseContinuation? {
        val actualEndpoint = endpoint?.let { runCatching { continuationEndpoint(it, source) }.getOrNull() } ?: return null
        val configs = linkedMapOf<String, JsonElement>()
        Regex("ytcfg\\.set\\s*\\(\\s*\\{").findAll(body).take(16).forEach { match ->
            val start = body.indexOf('{', match.range.first)
            val config = EmbeddedData.balanced(body, start)?.takeIf { it.length <= EmbeddedData.MAX_BODY }?.let(::parseObject)
            config?.forEach { (key, value) -> if (key in CONFIG_FIELDS) configs[key] = value }
        }
        val config = JsonObject(configs)
        val supplied = config.obj("INNERTUBE_CONTEXT")?.obj("client") ?: return null
        val version = supplied.text("clientVersion")?.takeIf { safeText(it, 80) } ?: return null
        if (supplied.text("clientName") != "WEB" || config.text("INNERTUBE_CLIENT_NAME")?.let { it != "WEB" } == true ||
            config.text("INNERTUBE_CLIENT_VERSION")?.let { it != version } == true) return null
        val client = buildJsonObject {
            PUBLIC_CLIENT_FIELDS.forEach { key ->
                (supplied[key] as? JsonPrimitive)?.takeIf { it.isString && safeText(it.content, 2048) }?.let { put(key, it) }
            }
        }
        val headers = buildMap {
            put("Origin", ORIGIN)
            put("X-Youtube-Client-Version", version)
            config.number("INNERTUBE_CONTEXT_CLIENT_NAME")?.takeIf { it == 1L }?.let { put("X-Youtube-Client-Name", it.toString()) }
        }
        val key = config.text("INNERTUBE_API_KEY")?.takeIf { safeText(it, 256) && it.none(Char::isWhitespace) }
        val withKey = attachKey(actualEndpoint, key)
        return YouTubeBrowseContinuation(source, withKey, client, headers)
    }

    private fun attachKey(endpoint: String, key: String?): String = endpoint +
        (key?.takeIf { Url(endpoint).parameters["key"] == null }?.let {
            "${if ('?' in endpoint) "&" else "?"}key=${it.encodeURLParameter()}"
        } ?: "")

    private fun validateContext(context: YouTubeBrowseContinuation) {
        require(context.pagesLoaded in 1..MAX_PAGES && context.visitedTokens.size <= MAX_PAGES)
        continuationEndpoint(context.endpoint, context.sourceUrl)
        require(context.clientContext.text("clientName") == "WEB" &&
            context.clientContext.text("clientVersion")?.let { safeText(it, 80) } == true)
        require(context.clientContext.keys.all { it in PUBLIC_CLIENT_FIELDS })
        context.clientContext.values.forEach { require(it is JsonPrimitive && it.isString && safeText(it.content, 2048)) }
        require(context.headers.keys.all { it in setOf("Origin", "X-Youtube-Client-Version", "X-Youtube-Client-Name") })
        require(context.headers["Origin"] == ORIGIN && context.headers["X-Youtube-Client-Version"] == context.clientContext.text("clientVersion"))
        require(context.headers["X-Youtube-Client-Name"]?.let { it == "1" } != false)
    }

    private fun continuationEndpoint(value: String, source: String): String {
        val absolute = EmbeddedData.absolute(source, value)
        val url = Url(absolute)
        require(publicOrigin(url) && url.host.lowercase() == Url(source).host.lowercase()) { "Unsupported continuation origin." }
        require(url.encodedPath in setOf("/youtubei/v1/browse", "/youtubei/v1/search")) { "Unsupported continuation endpoint." }
        require(url.parameters.names().all { it in setOf("key", "prettyPrint") } && url.fragment.isEmpty()) { "Unsupported continuation parameters." }
        require(url.parameters.names().all { url.parameters.getAll(it)?.size == 1 }) { "Duplicate continuation parameters." }
        require(absolute.length <= 1000) { "Continuation endpoint exceeds the limit." }
        return absolute
    }

    companion object {
        private const val ORIGIN = "https://www.youtube.com"
        private const val MAX_PAGE = 8_000_000
        private const val MAX_NODES = 80_000
        private const val MAX_PAGE_ITEMS = 500
        private const val MAX_TOTAL_ITEMS = 5_000
        private const val MAX_PAGES = 100
        private const val MAX_TOKEN = 16_384
        private const val PAGE_TRUNCATED_NOTICE = "YouTube returned more results than this page limit. Some entries were omitted; refine your search or open the full list in YouTube."
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,200}")
        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{10,64}")
        private val PUBLIC_HOSTS = setOf("www.youtube.com", "youtube.com", "m.youtube.com")
        private val IMAGE_HOSTS = setOf("ytimg.com", "ggpht.com", "googleusercontent.com", "youtube.com")
        private val CHANNEL_RENDERERS = setOf("channelRenderer", "gridChannelRenderer")
        private val PLAYLIST_RENDERERS = setOf("playlistRenderer", "gridPlaylistRenderer", "radioRenderer", "compactRadioRenderer")
        private val ITEM_RENDERERS = CHANNEL_RENDERERS + PLAYLIST_RENDERERS + setOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
            "playlistVideoRenderer", "playlistPanelVideoRenderer", "reelItemRenderer", "shortsLockupViewModel", "lockupViewModel", "videoWithContextRenderer")
        private val AD_RENDERERS = setOf("adSlotRenderer", "adPlacementRenderer", "inFeedAdLayoutRenderer", "promotedVideoRenderer", "promotedSparklesWebRenderer", "displayAdRenderer")
        private val PUBLIC_CLIENT_FIELDS = setOf("clientName", "clientVersion", "hl", "gl", "userAgent", "platform", "osName", "osVersion", "browserName", "browserVersion", "visitorData")
        private val CONFIG_FIELDS = setOf("INNERTUBE_CONTEXT", "INNERTUBE_CLIENT_NAME", "INNERTUBE_CLIENT_VERSION", "INNERTUBE_CONTEXT_CLIENT_NAME", "INNERTUBE_API_KEY")

        private fun safeText(value: String, maximum: Int) = value.length in 1..maximum && value.none { it.code < 32 || it.code == 127 }
        private fun publicOrigin(url: Url) = url.protocol.name == "https" && url.port == 443 &&
            url.host.lowercase() in PUBLIC_HOSTS && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()

        private fun isPlaylist(source: String) = Url(source).encodedPath == "/playlist"

        private fun defaultTitle(source: String): String {
            val url = Url(source)
            return when (url.encodedPath) {
                "/" -> "YouTube"
                "/results" -> "Results for ${url.parameters["search_query"].orEmpty()}"
                "/playlist" -> "YouTube playlist"
                else -> "YouTube channel"
            }
        }

        /** Canonicalizes only public browsing pages; watch links belong to source inspection. */
        fun canonicalBrowseUrl(input: String): String {
            require(input.length <= 2000) { "YouTube link is too long." }
            val url = runCatching { Url(input.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("Enter a YouTube channel, playlist or search link.")
            require(publicOrigin(url)) { "Use an HTTPS YouTube channel, playlist or search link." }
            val path = url.encodedPath.trimEnd('/').ifEmpty { "/" }
            return when (path) {
                "/" -> "$ORIGIN/"
                "/results" -> {
                    val query = url.parameters.getAll("search_query")?.singleOrNull()?.trim()
                    require(query != null && safeText(query, 256)) { "The YouTube search link has no valid query." }
                    "$ORIGIN/results?search_query=${query.encodeURLParameter()}"
                }
                "/playlist" -> {
                    val id = url.parameters.getAll("list")?.singleOrNull()
                    require(id != null && PLAYLIST_ID.matches(id)) { "The YouTube playlist link has no valid list ID." }
                    "$ORIGIN/playlist?list=$id"
                }
                else -> {
                    val parts = path.trim('/').split('/').map { it.decodeURLPart() }
                    require(parts.none { part -> part.isEmpty() || part.length > 120 || part.any { it.code < 32 || it in "/\\?#" } }) { "Invalid YouTube channel path." }
                    val base: String
                    val tab: String?
                    when {
                        parts.first().startsWith('@') && parts.first().length > 1 && parts.size <= 2 -> {
                            val handle = parts.first().removePrefix("@")
                            require(handle.none { it == '@' || it.isWhitespace() }) { "Invalid YouTube handle." }
                            base = "@${handle.encodeURLParameter()}"; tab = parts.getOrNull(1)
                        }
                        parts.first() == "channel" && parts.size in 2..3 && CHANNEL_ID.matches(parts[1]) -> {
                            base = "channel/${parts[1]}"; tab = parts.getOrNull(2)
                        }
                        parts.first() in setOf("c", "user") && parts.size in 2..3 && parts[1].none(Char::isWhitespace) -> {
                            base = "${parts[0]}/${parts[1].encodeURLParameter()}"; tab = parts.getOrNull(2)
                        }
                        else -> throw IllegalArgumentException("Open a YouTube channel or playlist, or use Search for videos.")
                    }
                    require(tab == null || tab in setOf("videos", "shorts", "streams", "playlists", "featured")) { "This YouTube channel tab is not supported." }
                    "$ORIGIN/$base/${tab ?: "videos"}"
                }
            }
        }
    }
}
