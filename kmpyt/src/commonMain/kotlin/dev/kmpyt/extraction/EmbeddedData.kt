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
        Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(body).take(64).forEach { script ->
            val text = script.groupValues[1].trim()
            add(text)
            if (text.startsWith("var ") || text.startsWith("window.") || text.contains("ytInitialPlayerResponse")) {
                val start = text.indexOf('{')
                if (start >= 0) balanced(text, start)?.let(::add)
            }
        }
        return found.distinct()
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
