package dev.kmpyt

import io.ktor.http.Url

/** Transport syntax validation; resource authorization remains tied to its inspected source. */
object UrlPolicy {
    fun validate(input: String, allowLocalHttp: Boolean = false): String? = validateInspection(input, allowLocalHttp)
    fun validateInspection(input: String, allowLocalHttp: Boolean = false): String? {
        if (input.isBlank()) return "Enter a media URL."
        if (input.length > 8192) return "The URL is too long."
        if (input.any { it.isWhitespace() || it.code < 32 } || '\\' in input) return "The URL contains invalid characters."
        val url = try { Url(input) } catch (_: Exception) { return "Enter a complete HTTPS URL." }
        if (url.host.isBlank() || "://" !in input) return "Enter a complete HTTPS URL."
        if (!url.user.isNullOrBlank() || !url.password.isNullOrBlank()) return "URLs containing account credentials are not supported."
        val local = allowLocalHttp && url.protocol.name == "http" && url.host in setOf("127.0.0.1", "localhost")
        if (url.protocol.name != "https" && !local) return "Use HTTPS. Other URL schemes are not supported."
        return null
    }
}

enum class MediaKind { VIDEO, AUDIO }
