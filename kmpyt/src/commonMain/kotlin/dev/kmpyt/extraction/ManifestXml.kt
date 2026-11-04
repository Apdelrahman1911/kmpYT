package dev.kmpyt.extraction

internal class ManifestNode(val name: String, val attributes: Map<String, String>,
    val children: MutableList<ManifestNode> = mutableListOf()) {
    private val textBuffer = StringBuilder()
    val text: String get() = textBuffer.toString()
    fun appendText(value: String) { textBuffer.append(value) }
    fun child(name: String): ManifestNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<ManifestNode> = children.filter { it.name == name }
}

/** A small non-validating XML reader. DTDs, external entities and external nodes are rejected. */
internal object ManifestXml {
    fun parse(input: String): ManifestNode {
        val body = input.removePrefix("\uFEFF")
        require(body.length <= EmbeddedData.MAX_BODY) { "DASH manifest exceeds the parser limit" }
        require(!body.contains("<!DOCTYPE", true) && !body.contains("<!ENTITY", true)) { "DASH DTD/entities are unsupported" }
        val stack = mutableListOf<ManifestNode>()
        var root: ManifestNode? = null
        var cursor = 0
        var count = 0
        while (cursor < body.length) {
            if (body[cursor] != '<') {
                val end = body.indexOf('<', cursor).takeIf { it >= 0 } ?: body.length
                val text = decode(body.substring(cursor, end))
                if (stack.isNotEmpty()) stack.last().appendText(text) else require(text.isBlank()) { "Text outside DASH root" }
                cursor = end; continue
            }
            if (body.startsWith("<!--", cursor)) {
                val end = body.indexOf("-->", cursor + 4); require(end >= 0) { "Unclosed DASH comment" }
                cursor = end + 3; continue
            }
            if (body.startsWith("<?", cursor)) {
                val end = body.indexOf("?>", cursor + 2); require(end >= 0) { "Unclosed DASH processing instruction" }
                cursor = end + 2; continue
            }
            if (body.startsWith("<![CDATA[", cursor)) {
                val end = body.indexOf("]]>", cursor + 9)
                require(end >= 0 && stack.isNotEmpty()) { "Invalid DASH CDATA" }
                stack.last().appendText(body.substring(cursor + 9, end)); cursor = end + 3; continue
            }
            require(!body.startsWith("<!", cursor)) { "Unsupported DASH declaration" }
            var end = cursor + 1
            var quote: Char? = null
            while (end < body.length) {
                val c = body[end]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '\'' || c == '"') quote = c else if (c == '>') break
                end++
            }
            require(end < body.length && quote == null) { "Unclosed DASH tag" }
            val tag = body.substring(cursor + 1, end).trim()
            cursor = end + 1
            if (tag.startsWith('/')) {
                val name = tag.drop(1).trim().substringAfter(':')
                require(stack.isNotEmpty() && stack.last().name == name) { "Mismatched DASH tag" }
                stack.removeAt(stack.lastIndex); continue
            }
            val selfClosing = tag.endsWith('/')
            val content = if (selfClosing) tag.dropLast(1).trimEnd() else tag
            val nameEnd = content.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: content.length
            val name = content.substring(0, nameEnd).substringAfter(':')
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) { "Invalid DASH element name" }
            val attributes = attributes(content.substring(nameEnd))
            require(attributes.keys.none { it.startsWith("xlink:") }) { "External DASH nodes are unsupported" }
            val node = ManifestNode(name, attributes)
            if (stack.isEmpty()) { require(root == null) { "Multiple DASH roots" }; root = node }
            else stack.last().children += node
            require(++count <= 16000 && stack.size < 32) { "DASH XML exceeds traversal limits" }
            if (!selfClosing) stack += node
        }
        require(stack.isEmpty()) { "Incomplete DASH XML" }
        return requireNotNull(root) { "Missing DASH root" }
    }

    private fun attributes(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor == text.length) break
            val start = cursor
            while (cursor < text.length && !text[cursor].isWhitespace() && text[cursor] != '=') cursor++
            val name = text.substring(start, cursor)
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            require(name.isNotEmpty() && cursor < text.length && text[cursor++] == '=') { "Malformed DASH attribute" }
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            require(cursor < text.length && text[cursor] in setOf('\'', '"')) { "Unquoted DASH attribute" }
            val quote = text[cursor++]
            val end = text.indexOf(quote, cursor)
            require(end >= cursor) { "Unclosed DASH attribute" }
            val value = decode(text.substring(cursor, end)); cursor = end + 1
            require(result.put(name, value) == null) { "Duplicate DASH attribute" }
        }
        return result
    }

    private fun decode(text: String): String = Regex("&([^;]{1,16});").replace(text) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
                    else if (entity.startsWith('#')) entity.drop(1).toIntOrNull() else null
                require(code != null && code in 1..0x10ffff && code !in 0xd800..0xdfff) { "Unknown DASH entity" }
                if (code <= 0xffff) code.toChar().toString()
                else { val value = code - 0x10000; "${(0xd800 + (value shr 10)).toChar()}${(0xdc00 + (value and 1023)).toChar()}" }
            }
        }
    }
}
