package dev.kmpyt.extraction

import io.ktor.http.*
import kotlinx.serialization.json.*

/** Reads JSON data, not executable JavaScript. Bounded scanning avoids evaluating untrusted pages. */
object EmbeddedData {
    const val MAX_BODY = 2_000_000
    fun documents(body: String): List<JsonElement> {
        require(body.length <= MAX_BODY) { "Response exceeds the research parser limit" }
        val found = mutableListOf<JsonElement>()
        fun add(text: String) { runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull()?.let(found::add) }
        add(body)
        findAllAt(Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE), body, listOf("<script"),
            scriptEnd(body)).take(64).forEach { script ->
            val text = script.groupValues[1].trim()
            add(text)
            if (text.startsWith("var ") || text.startsWith("window.") || text.contains("ytInitialPlayerResponse")) {
                val start = text.indexOf('{')
                if (start >= 0) balanced(text, start)?.let(::add)
            }
        }
        return found.distinct()
    }
    /**
     * The same matches as `regex.findAll(text)` for a pattern whose every match starts with one of
     * [prefixes] (compared case-insensitively). The regex runs only at candidate positions, which
     * avoids scanning a large page with a case-insensitive regex (853 ms vs a few ms measured on
     * Kotlin/Native for a 1.3 MB watch page).
     */
    fun findAllAt(regex: Regex, text: String, prefixes: List<String>,
        windowEnd: (start: Int) -> Int = { text.length }): Sequence<MatchResult> = sequence {
        // Anchored, and run on a bounded window: some regex engines search forward from a start
        // index instead of failing fast, which would rescan the rest of the page per candidate.
        val anchored = Regex("^(?:${regex.pattern})", regex.options)
        // One length-preserving lowercase copy; plain indexOf on it is far faster than
        // case-insensitive indexOf, and each prefix's next position is cached, not re-searched.
        val folded = foldedCase(text)
        val keys = prefixes.map { prefix -> foldedCase(prefix) }
        val next = IntArray(keys.size) { -2 }
        var position = 0
        while (position <= text.length) {
            for (i in keys.indices) if (next[i] != -1 && next[i] < position) next[i] = folded.indexOf(keys[i], position)
            val candidate = next.filter { it >= 0 }.minOrNull() ?: break
            val end = windowEnd(candidate).coerceIn(candidate, text.length)
            val local = anchored.find(text.substring(candidate, end))
            if (local != null) {
                val value = local.value
                val range = candidate until candidate + value.length
                val groups = local.groupValues
                yield(object : MatchResult {
                    override val range = range
                    override val value = value
                    override val groupValues = groups
                    override val groups: MatchGroupCollection get() = error("Use groupValues for windowed matches.")
                    override fun next(): MatchResult? = null
                })
                position = if (value.isEmpty()) candidate + 1 else range.last + 1
            } else position = candidate + 1
        }
    }

    private fun foldedCase(text: String): String {
        val chars = CharArray(text.length)
        for (i in text.indices) chars[i] = text[i].lowercaseChar()
        return chars.concatToString()
    }

    /**
     * Bodies of `<script type="application/ld+json">` elements, equivalent to the pattern
     * `<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script\s*>`
     * (case-insensitive), but the regex only inspects each opening tag, never a large script body.
     */
    fun ldJsonScripts(text: String): Sequence<String> = sequence {
        val folded = foldedCase(text)
        val opening = Regex("^<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>", RegexOption.IGNORE_CASE)
        var position = 0
        while (true) {
            val start = folded.indexOf("<script", position)
            if (start < 0) break
            val tagEnd = text.indexOf('>', start)
            if (tagEnd < 0) break
            if (opening.find(text.substring(start, tagEnd + 1)) == null) { position = start + 1; continue }
            val close = closingScript(text, folded, tagEnd + 1) ?: break
            yield(text.substring(tagEnd + 1, close.first))
            position = close.second
        }
    }

    /** The first `</script\s*>` at or after [from]: its start and the index after its `>`. */
    private fun closingScript(text: String, folded: String, from: Int): Pair<Int, Int>? {
        var search = from
        while (true) {
            val close = folded.indexOf("</script", search)
            if (close < 0) return null
            var i = close + 8
            while (i < text.length && text[i] in REGEX_SPACE) i++
            if (i < text.length && text[i] == '>') return close to i + 1
            search = close + 1
        }
    }

    private val REGEX_SPACE = setOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')

    /** A window that ends just after the next closing script tag, for `<script ...>...</script>` patterns. */
    fun scriptEnd(text: String): (Int) -> Int {
        val folded = foldedCase(text)
        return { start -> closingScript(text, folded, start)?.second ?: text.length }
    }

    fun balanced(text: String, start: Int): String? {
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{' -> { depth++; if (depth > 64) return null }; '}' -> if (--depth == 0) return text.substring(start, i + 1) }
        }
        return null
    }
    fun objects(documents: List<JsonElement>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var visited = 0
        fun visit(value: JsonElement, depth: Int) {
            require(depth <= 64 && ++visited <= 30_000) { "Embedded data exceeds traversal limits" }
            when (value) {
                is JsonObject -> { result += value; value.values.forEach { visit(it, depth + 1) } }
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }
        documents.forEach { visit(it, 0) }
        return result
    }
    fun absolute(base: String, reference: String): String {
        val ref = reference.replace("&amp;", "&")
        val b = Url(base)
        val origin = "${b.protocol.name}://${b.host}${if (b.port == b.protocol.defaultPort) "" else ":${b.port}"}"
        val absolute = when {
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(ref) -> ref
            ref.startsWith("//") -> "${b.protocol.name}:$ref"
            ref.startsWith("/") -> origin + ref
            ref.startsWith("?") -> origin + b.encodedPath + ref
            ref.startsWith("#") || ref.isEmpty() -> base.substringBefore('#') + ref
            else -> origin + b.encodedPath.substringBeforeLast('/', "") + "/" + ref
        }
        val u = Url(absolute)
        val path = mutableListOf<String>()
        u.encodedPath.split('/').forEach { segment -> when (segment) { "." -> Unit; ".." -> if (path.isNotEmpty()) path.removeAt(path.lastIndex); else -> path += segment } }
        val normalized = path.joinToString("/").let { if (it.startsWith('/')) it else "/$it" }
        // Construct from a fresh builder: takeFrom on an existing URL can append query parameters.
        return URLBuilder(absolute).apply { encodedPath = normalized }.buildString()
    }
    fun expires(url: String): Long? = runCatching { Url(url).parameters["expire"]?.toLongOrNull()?.times(1000) }.getOrNull()
}
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

object ResearchRedaction {
    fun url(value: String): String = runCatching { val u = Url(value); "${u.protocol.name}://${u.host}${if (u.port != u.protocol.defaultPort) ":${u.port}" else ""}${u.encodedPath}${if (u.parameters.isEmpty()) "" else "?[redacted]"}" }.getOrDefault("[invalid URL]")
    fun headers(values: Map<String, String>): Map<String, String> = values.mapValues { (key, value) ->
        if (key.lowercase() in setOf("authorization", "cookie", "set-cookie", "x-csrf-token", "x-ig-www-claim")) "[redacted]" else value.take(200)
    }
}
