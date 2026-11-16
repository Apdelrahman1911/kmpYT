package dev.kmpyt

import dev.kmpyt.extraction.KtorResearchTransport
import dev.kmpyt.extraction.ResearchTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/** Owns its Darwin client and offline WebKit sessions; call the returned client's suspend close(). */
@Throws(Exception::class)
fun createIosYouTubeClient(): YouTubeClient {
    val http = HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 45_000
        }
    }
    return YouTubeClient(
        transport = measuredIosTransport(KtorResearchTransport(http, maximumBytes = 8_000_000)),
        playerRuntime = IosYouTubeJavaScriptRuntime(),
        downloader = IosYouTubeDownloader(http),
        onClose = { http.close() },
        platformName = "iOS",
        playbackAdapter = createIosYouTubeSabrPlaybackAdapter(http),
        programStore = IosYouTubePlayerProgramStore(iosPlayerProgramDirectory()),
    )
}

/** The application's private Caches subfolder for exact player programs. */
private fun iosPlayerProgramDirectory(): String {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: NSTemporaryDirectory()
    return caches.trimEnd('/') + "/kmpyt-player-programs"
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun measuredIosTransport(delegate: ResearchTransport): ResearchTransport {
    if (!kotlin.native.Platform.isDebugBinary) return delegate
    return ResearchTransport { request ->
        val stage = when (request.stage) {
            "youtube-watch", "youtube-page" -> "WATCH"
            "youtube-player-program" -> "PLAYER_PROGRAM"
            "youtube-browse" -> "BROWSE"
            else -> "OTHER"
        }
        val started = NSProcessInfo.processInfo.systemUptime
        println("VEYRA_SOURCE_TIMING stage=$stage outcome=START uptimeMs=${(started * 1_000).toLong()}")
        try {
            val response = delegate.execute(request)
            val elapsed = ((NSProcessInfo.processInfo.systemUptime - started) * 1_000).toLong()
            println("VEYRA_SOURCE_TIMING stage=$stage outcome=END elapsedMs=$elapsed status=${response.status} characters=${response.body.length} uptimeMs=${(NSProcessInfo.processInfo.systemUptime * 1_000).toLong()}")
            if (request.stage == "youtube-player-client") debugIosAvailability(request, response.body)
            response
        } catch (error: Throwable) {
            val elapsed = ((NSProcessInfo.processInfo.systemUptime - started) * 1_000).toLong()
            println("VEYRA_SOURCE_TIMING stage=$stage outcome=FAILED elapsedMs=$elapsed")
            throw error
        }
    }
}

/** Debug binaries only: redacted shape of source-declared pre-playback declarations (names and counts, no URLs). */
private fun debugIosAvailability(request: dev.kmpyt.extraction.ResearchRequest, body: String) {
    runCatching {
        val player = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return
        fun kotlinx.serialization.json.JsonElement?.obj() = this as? kotlinx.serialization.json.JsonObject
        fun kotlinx.serialization.json.JsonElement?.arr() = this as? kotlinx.serialization.json.JsonArray
        fun kotlinx.serialization.json.JsonElement?.str() = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val client = request.body?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).obj()?.get("context").obj()?.get("client").obj()?.get("clientName").str() }.getOrNull() }
        val placements = player["adPlacements"].arr().orEmpty().mapNotNull { entry ->
            val renderer = entry.obj()?.get("adPlacementRenderer").obj() ?: return@mapNotNull "other:" + entry.obj()?.keys?.joinToString("+")
            val kind = renderer["config"].obj()?.get("adPlacementConfig").obj()?.get("kind").str()
            val inner = renderer["renderer"].obj()?.keys?.joinToString("+")
            val skip = renderer["renderer"].obj()?.get("instreamVideoAdRenderer").obj()?.get("skipOffsetMilliseconds").str()
            "$kind/$inner/skip=$skip"
        }
        val slots = player["adSlots"].arr().orEmpty().mapNotNull { entry ->
            val slot = entry.obj()?.get("adSlotRenderer").obj() ?: return@mapNotNull "other:" + entry.obj()?.keys?.joinToString("+")
            val trigger = slot["adSlotMetadata"].obj()?.get("triggerEvent").str()
            val layout = slot["fulfillmentContent"].obj()?.get("fulfilledLayout").obj()?.keys?.joinToString("+")
            val content = slot["fulfillmentContent"].obj()?.get("fulfilledLayout").obj()?.get("playerBytesAdLayoutRenderer").obj()
                ?.get("renderingContent").obj()?.keys?.joinToString("+")
            "$trigger/$layout/$content"
        }
        val status = player["playabilityStatus"].obj()?.get("status").str()
        val waitMs = dev.kmpyt.extraction.SourceAvailability.waitMillis(player)
        val topKeys = player.keys.filter { it.contains("ad", ignoreCase = true) }
        println("VEYRA_SOURCE_AVAILABILITY client=$client status=$status waitMs=$waitMs adKeys=$topKeys placements=$placements slots=$slots")
    }
}
