package dev.kmpyt.extraction

import io.ktor.http.Url

/** Exact origins prevent caller-supplied protected headers from crossing a source boundary. */
object ResearchAccess {
    fun origin(input: String): String {
        val url = Url(input)
        require(url.host.isNotBlank() && url.user.isNullOrBlank() && url.password.isNullOrBlank()) { "Invalid resource origin" }
        require(url.protocol.name == "https" || url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1")) {
            "Use HTTPS or an explicitly configured loopback server"
        }
        return "${url.protocol.name}://${url.host.lowercase().trimEnd('.')}:${url.port}"
    }
    fun requireSessionOrigin(url: String, sessionOrigin: String) {
        require(sessionOrigin.isNotBlank() && origin(url) == origin(sessionOrigin)) { "Protected headers must stay on their saved origin" }
        require(!YouTubeDownloadPolicy.isYouTubeResource(url)) { "Public YouTube inspection does not accept account credentials" }
    }
}
