package dev.kmpyt.youtube

import dev.kmpyt.extraction.YouTubeProvider
import io.ktor.http.Url
import io.ktor.http.decodeURLPart

/** Extracts one supported public source from bounded Android/browser share text. */
object YouTubeIncomingLinks {
    const val MAX_SHARED_TEXT = 16_384
    private val links = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")

    fun extract(text: String?): String? {
        if (text == null || text.length !in 1..MAX_SHARED_TEXT) return null
        for (match in links.findAll(text).take(16)) {
            val previous = text.getOrNull(match.range.first - 1)
            if (previous != null && (previous.isLetterOrDigit() || previous in "+.-_")) continue
            val candidate = match.value.trimEnd('.', ',', '!', ';', ':', '?', ')', ']', '}').replace("&amp;", "&")
            if (candidate.length !in 1..2048 || candidate.any { it.code < 32 || it.code == 127 ||
                    it in '\u200B'..'\u200F' || it in '\u202A'..'\u202E' || it in '\u2060'..'\u206F' }) continue
            normalized(candidate)?.let { return it }
        }
        return null
    }

    private fun normalized(candidate: String): String? = runCatching {
        val url = Url(candidate)
        if (url.protocol.name != "https" || url.port != 443 || url.host.lowercase() !in hosts ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return@runCatching null
        val parts = url.encodedPath.trim('/').split('/')
        val videoShape = when {
            url.host.equals("youtu.be", true) -> parts.size == 1
            parts.firstOrNull() == "watch" -> parts.size == 1 && url.parameters.getAll("v")?.size == 1
            parts.firstOrNull() in setOf("shorts", "embed", "live", "v") -> parts.size == 2
            else -> false
        }
        if (videoShape) YouTubeProvider().normalize(candidate)?.let { return@runCatching it }
        val firstSegment = parts.firstOrNull()?.decodeURLPart()
        val browseShape = firstSegment?.startsWith('@') == true || firstSegment in setOf("channel", "c", "user", "playlist")
        if (!browseShape) return@runCatching null
        // YouTube Music shares the same public playlist/video identities; browse via the public web host.
        val browseInput = if (url.host.equals("music.youtube.com", true))
            candidate.replaceFirst(Regex("https://music\\.youtube\\.com", RegexOption.IGNORE_CASE), "https://www.youtube.com")
        else candidate
        PublicYouTubeBrowseService.canonicalBrowseUrl(browseInput)
    }.getOrNull()
}
