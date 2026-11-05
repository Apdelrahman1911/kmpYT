package dev.kmpyt.extraction

import io.ktor.http.Url
import dev.kmpyt.UrlPolicy

/** Exact origins prevent caller-supplied protected headers from crossing a source boundary. */
object ResearchAccess {
    fun origin(input: String): String {
        val url = Url(input)
        require(url.host.isNotBlank() && url.user.isNullOrBlank() && url.password.isNullOrBlank()) { "Invalid resource origin" }
        require(url.protocol.name == "https" || url.protocol.name == "http" && UrlPolicy.isLoopbackHost(url.host)) {
            "Use HTTPS or an explicitly configured loopback server"
        }
        val host = url.host.lowercase().trimEnd('.')
        val authorityHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        return "${url.protocol.name}://$authorityHost:${url.port}"
    }
    fun requireSessionOrigin(url: String, sessionOrigin: String) {
        require(sessionOrigin.isNotBlank() && origin(url) == origin(sessionOrigin)) { "Protected headers must stay on their saved origin" }
        require(!YouTubeDownloadPolicy.isYouTubeResource(url)) { "Public YouTube inspection does not accept account credentials" }
    }
}
