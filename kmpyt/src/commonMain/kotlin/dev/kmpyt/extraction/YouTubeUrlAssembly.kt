package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.parseQueryString

/** Only the supplied signature/n parameter changes; other signed query bytes remain verbatim. */
object YouTubeUrlAssembly {
    fun replaceParameter(url: String, name: String, value: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}").matches(name)) { "Invalid player URL parameter name." }
        require(name.lowercase() !in setOf("url", "host", "scheme", "expire", "itag")) { "Player transformation cannot change the resource identity." }
        require(value.isNotEmpty() && value.length <= 8192 && value.none { it.code < 32 }) { "Invalid player transformation output." }
        val parsed = Url(url)
        require(parsed.protocol.name in setOf("https", "http") && parsed.host.isNotBlank() && parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
        val beforeFragment = url.substringBefore('#')
        val fragment = if ('#' in url) "#${url.substringAfter('#')}" else ""
        val base = beforeFragment.substringBefore('?')
        val fields = if ('?' in beforeFragment) beforeFragment.substringAfter('?').split('&') else emptyList()
        val replacement = "${name.encodeURLParameter()}=${value.encodeURLParameter()}"
        var replacements = 0
        val updated = fields.map { field ->
            val key = runCatching { parseQueryString(field).names().singleOrNull() }.getOrNull()
            if (key == name) { replacements++; replacement } else field
        }.toMutableList()
        require(replacements <= 1) { "Ambiguous duplicate player URL parameter." }
        if (replacements == 0) updated += replacement
        return "$base?${updated.joinToString("&")}$fragment"
    }
}
