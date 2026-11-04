package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException

/**
 * Keeps the downloaded player closure intact rather than approximating dynamic JavaScript.
 * Shared code proves the URL-helper binding and adds a small, independently written export;
 * a native, offline runtime executes the actual program with its original dependencies.
 * This does not acquire integrity tokens, execute challenges, or authorize media downloads.
 */
class ContextYouTubePlayerProgramCompiler(
    private val runtime: YouTubeJavaScriptRuntime,
    private val boundedCompiler: YouTubePlayerProgramCompiler = BoundedYouTubePlayerProgramCompiler(),
) : YouTubePlayerProgramCompiler {
    override fun compile(source: String, binding: YouTubeProgramBinding): YouTubeProgramCompilation {
        if (source.length !in 1..MAX_SOURCE) return rejected("PLAYER_CONTEXT_SOURCE_LIMIT",
            "The full-context player program is empty or exceeds 5,000,000 characters.")
        val classic = boundedCompiler.compile(source, binding)
        if (classic.transforms?.let { it.signatureAvailable && it.throttlingAvailable } == true) return classic
        val model = try { inspect(source) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_STRUCTURE_UNSUPPORTED",
                "No single supported player closure and unambiguous URL-helper binding could be proved within lexical limits."))
        }
        if (binding.signatureFunction?.let { it != model.signature } == true || binding.throttlingFunction != null)
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_EXPLICIT_BINDING_UNSUPPORTED",
                "The requested explicit function binding does not match the observed full-context URL-helper contract."))
        val program = source.substring(0, model.closeOffset) + exports(model) + source.substring(model.closeOffset)
        val session = try { runtime.prepare(program) } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: YouTubeJavaScriptFailure) {
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_RUNTIME_UNAVAILABLE",
                "The offline full player could not initialize (${failure.code}). Only a bounded symbolic code is retained; source values and stacks are omitted."))
        }
        catch (_: Exception) {
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_RUNTIME_UNAVAILABLE",
                "The isolated runtime could not initialize the complete player program. Raw JavaScript errors and values are not exposed."))
        }
        val capabilities = try { session.capabilities } catch (cancelled: CancellationException) {
            session.close(); throw cancelled
        } catch (_: Exception) {
            session.close()
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_CAPABILITIES_UNAVAILABLE",
                "The isolated player export did not supply readable transformation capabilities."))
        }
        if (!capabilities.signature && !capabilities.throttling) {
            session.close()
            return classic.copy(diagnostics = classic.diagnostics + diagnostic("PLAYER_CONTEXT_EXPORT_UNAVAILABLE",
                "The complete player program did not expose a usable URL-helper export."))
        }
        val transforms = ContextTransforms(classic.transforms, session, capabilities, model.timestamp)
        return YouTubeProgramCompilation(transforms, classic.diagnostics + diagnostic("PLAYER_CONTEXT_RUNTIME_PREPARED",
            "Preserved the complete player closure and initialized the observed three-argument URL-helper and constructor in an isolated runtime. Individual transformations still require actual input/output verification."))
    }

    private class ContextTransforms(
        private val classic: YouTubeUrlTransforms?, private val session: YouTubeJavaScriptSession,
        private val capabilities: YouTubeJavaScriptCapabilities,
        override val signatureTimestamp: Long?,
    ) : YouTubeUrlTransforms {
        private var closed = false
        override val signatureAvailable = classic?.signatureAvailable == true || capabilities.signature
        override val throttlingAvailable = classic?.throttlingAvailable == true || capabilities.throttling
        override fun signature(input: String): String = if (classic?.signatureAvailable == true) classic.signature(input)
            else invoke(YouTubeJavaScriptFunction.SIGNATURE, input)
        override fun throttling(input: String): String = if (classic?.throttlingAvailable == true) classic.throttling(input)
            else invoke(YouTubeJavaScriptFunction.THROTTLING, input)
        override fun close() {
            if (closed) return
            closed = true
            try { session.close() } finally { classic?.close() }
        }
        private fun invoke(function: YouTubeJavaScriptFunction, input: String): String {
            check(!closed) { "The isolated player session has been closed." }
            require(input.length in 1..MAX_INPUT) { "Player transform input is empty or exceeds 8192 UTF-16 units." }
            check(if (function == YouTubeJavaScriptFunction.SIGNATURE) capabilities.signature else capabilities.throttling) {
                "The requested isolated player transformation is unavailable."
            }
            val result = try { session.call(function, input) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw IllegalStateException("The isolated ${function.name.lowercase()} transformation failed; sensitive runtime details are omitted.") }
            check(result.length in 1..MAX_INPUT && result != input &&
                !result.startsWith("enhanced_except_") && !result.startsWith("undefined") && !result.startsWith("null")) {
                "The isolated ${function.name.lowercase()} transformation returned an unchanged, empty, oversized or error result."
            }
            return result
        }
    }

    private data class Model(val signature: String, val constructor: String, val closeOffset: Int, val timestamp: Long?)
    private data class Candidate(val name: String, val parameters: List<String>, val openOffset: Int)
    private data class Frame(val delimiter: String, val candidate: Candidate? = null)
    private data class Token(val text: String, val kind: Kind, val start: Int, val end: Int)
    private enum class Kind { IDENTIFIER, STRING, NUMBER, SYMBOL, OPAQUE }

    /** One streaming lexical pass; large recommendation/string tables are never retained as tokens. */
    private fun inspect(source: String): Model {
        val lexer = Lexer(source)
        fun expected(value: String) = require(lexer.next()?.syntax(value) == true)
        expected("var"); expected("_yt_player"); expected("="); expected("{"); expected("}"); expected(";")
        expected("("); expected("function"); expected("(")
        val namespace = requireNotNull(lexer.next()).also { require(it.kind == Kind.IDENTIFIER && IDENTIFIER.matches(it.text)) }.text
        expected(")"); expected("{")
        val stack = mutableListOf(Frame("{"))
        val recent = mutableListOf<Token>()
        val candidates = mutableListOf<Pair<String, String>>()
        val assignments = mutableMapOf<String, Int>()
        val constructors = mutableMapOf<String, Int>()
        val timestamps = mutableSetOf<Long>()
        var closeOffset = -1
        fun count(target: MutableMap<String, Int>, name: String) {
            require(target.size < 25_000 || name in target)
            target[name] = (target[name] ?: 0) + 1
        }
        while (stack.isNotEmpty()) {
            val token = requireNotNull(lexer.next())
            if (token.kind == Kind.NUMBER && recent.takeLast(2).let { it.size == 2 && it[0].text == "signatureTimestamp" && it[1].text in setOf(":", "=") }) {
                token.text.toLongOrNull()?.takeIf { it >= 0 }?.let { timestamps += it }
                require(timestamps.size <= 16)
            }
            if (token.kind == Kind.SYMBOL && token.text in setOf("=", "+=", "-=", "%=", "++", "--") && stack.size == 1) {
                val tail = recent.takeLast(3)
                if (tail.size == 3 && tail[0].text == namespace && tail[1].text == "." && tail[2].kind == Kind.IDENTIFIER)
                    count(assignments, "$namespace.${tail[2].text}")
                else recent.lastOrNull()?.takeIf { it.kind == Kind.IDENTIFIER && recent.getOrNull(recent.lastIndex - 1)?.text != "." }
                    ?.let { count(assignments, it.text) }
            }
            if (token.kind == Kind.SYMBOL) when (token.text) {
                "{" -> {
                    var candidate: Candidate? = null
                    if (stack.size == 1) {
                        val tail = recent.takeLast(10)
                        if (tail.size == 10 && tail[0].kind == Kind.IDENTIFIER && tail[1].syntax("=") && tail[2].syntax("function") &&
                            tail[3].syntax("(") && tail[4].kind == Kind.IDENTIFIER && tail[5].syntax(",") &&
                            tail[6].kind == Kind.IDENTIFIER && tail[7].syntax(",") && tail[8].kind == Kind.IDENTIFIER && tail[9].syntax(")")) {
                            val preceding = recent.getOrNull(recent.size - 11)?.text
                            val parameters = listOf(tail[4].text, tail[6].text, tail[8].text)
                            if (preceding in setOf(null, ";", "}", "var", "let", "const", ",") &&
                                parameters.distinct().size == 3 && namespace !in parameters && IDENTIFIER.matches(tail[0].text))
                                candidate = Candidate(tail[0].text, parameters, token.end)
                        }
                        if (tail.size == 10 && tail[0].syntax(namespace) && tail[1].syntax(".") && tail[2].kind == Kind.IDENTIFIER &&
                            tail[3].syntax("=") && tail[4].syntax("function") && tail[5].syntax("(") &&
                            tail[6].kind == Kind.IDENTIFIER && tail[7].syntax(",") && tail[8].kind == Kind.IDENTIFIER && tail[9].syntax(")"))
                            count(constructors, "$namespace.${tail[2].text}")
                    }
                    require(stack.size < MAX_DEPTH); stack += Frame("{", candidate)
                }
                "(", "[" -> { require(stack.size < MAX_DEPTH); stack += Frame(token.text) }
                "}", ")", "]" -> {
                    // List.removeLast() binds to the Java 21 member on recent Android SDKs.
                    // Indexed removal also works on the supported older Android versions.
                    val frame = stack.removeAt(stack.lastIndex)
                    require(frame.delimiter == when (token.text) { "}" -> "{"; ")" -> "("; else -> "[" })
                    frame.candidate?.takeIf { token.start - it.openOffset <= MAX_HELPER_BODY }?.let { candidate ->
                        recognize(source.substring(candidate.openOffset, token.start), candidate.parameters, namespace)?.let { constructor ->
                            require(candidates.size < 8)
                            candidates += candidate.name to constructor
                        }
                    }
                    if (stack.isEmpty()) closeOffset = token.start
                }
            }
            recent += token
            if (recent.size > 14) recent.removeAt(0)
        }
        expected(")"); expected("("); expected("_yt_player"); expected(")"); expected(";")
        require(lexer.next() == null)
        val candidate = candidates.single()
        require(assignments[candidate.first] == 1 && assignments[candidate.second] == 1 && constructors[candidate.second] == 1)
        require(assignments[namespace] == null)
        return Model(candidate.first, candidate.second, closeOffset, timestamps.singleOrNull())
    }

    private fun recognize(body: String, parameters: List<String>, namespace: String): String? {
        val lexer = Lexer(body)
        val tokens = buildList { while (true) { val token = lexer.next() ?: break; require(size < 4096); add(token) } }
        val url = parameters[0]
        // Namespace shadowing or arbitrary aliases cannot silently select a different constructor.
        if (tokens.count { it.kind == Kind.IDENTIFIER && it.text == namespace } != 1) return null
        val constructors = tokens.indices.mapNotNull { i ->
            val part = tokens.subList(i, minOf(tokens.size, i + 13))
            if (part.size != 13 || !part[0].syntax(url) || !part[1].syntax("=") || !part[2].syntax("new") ||
                !part[3].syntax(namespace) || !part[4].syntax(".") || part[5].kind != Kind.IDENTIFIER ||
                !IDENTIFIER.matches(part[5].text) || !part[6].syntax("(") || !part[7].syntax(url) || !part[8].syntax(",") ||
                !part[9].syntax("!") || part[10].kind != Kind.NUMBER || part[10].text != "0" || !part[11].syntax(")") || !part[12].syntax(";")) null
            else "$namespace.${part[5].text}"
        }
        val hasMarker = tokens.windowed(9).any { part ->
            part[0].syntax(url) && part[1].syntax(".") && part[2].syntax("set") && part[3].syntax("(") &&
                part[4].kind == Kind.STRING && part[4].text == "alr" && part[5].syntax(",") &&
                part[6].kind == Kind.STRING && part[6].text == "yes" && part[7].syntax(")") && part[8].syntax(";")
        }
        // Minified players commonly omit the final semicolon before the closing brace.
        // A line terminator after `return` would instead invoke JavaScript ASI and return
        // undefined, so proving two adjacent lexical tokens alone is insufficient.
        val ending = (if (tokens.lastOrNull()?.syntax(";") == true) tokens.dropLast(1) else tokens).takeLast(2)
        val returnsHelper = ending.size == 2 && ending[0].syntax("return") && ending[1].syntax(url) &&
            body.substring(ending[0].end, ending[1].start).none { it in "\r\n\u2028\u2029" }
        return constructors.singleOrNull()?.takeIf { hasMarker && returnsHelper }
    }

    private fun Token.syntax(value: String): Boolean = text == value && kind ==
        if (value.first().isLetter() || value.first() in "_$") Kind.IDENTIFIER else Kind.SYMBOL

    private fun exports(model: Model): String = """
        ;globalThis.__kmpyt_capabilities={signature:typeof ${model.signature}==="function"&&typeof ${model.constructor}==="function",throttling:typeof ${model.constructor}==="function"};
        globalThis.__kmpyt_transform=function(kind,input){
          if(typeof input!=="string"||input.length===0||input.length>8192)throw new Error("kmpYT invalid transform input");
          var neutral="https://rr0.googlevideo.com/videoplayback",result;
          // The observed URL helper accepts and returns URI-component encoded signatures.
          // Keep the public transform raw so URL assembly performs exactly one encoding.
          if(kind==="signature"){
            result=${model.signature}(neutral,"signature",globalThis.encodeURIComponent(input)).get("signature");
            if(typeof result==="string")result=globalThis.decodeURIComponent(result);
          }
          else if(kind==="throttling")result=(new ${model.constructor}(neutral+"?n="+globalThis.encodeURIComponent(input),true)).get("n");
          else throw new Error("kmpYT unsupported transform kind");
          if(typeof result!=="string"||result.length===0||result.length>8192||result===input||result.indexOf("enhanced_except_")===0||result.indexOf("undefined")===0||result.indexOf("null")===0)throw new Error("kmpYT invalid transform result");
          return result;
        };
    """.trimIndent()

    /** Strings, comments and regexes are opaque to binding discovery; interpolated templates fail closed. */
    private class Lexer(private val source: String) {
        private var position = 0
        private var count = 0
        private var previous = ""
        fun next(): Token? {
            while (position < source.length) {
                if (source[position].isWhitespace()) { position++; continue }
                if (source.startsWith("//", position)) { position = source.indexOf('\n', position + 2).takeIf { it >= 0 } ?: source.length; continue }
                if (source.startsWith("/*", position)) { position = source.indexOf("*/", position + 2).also { require(it >= 0) } + 2; continue }
                break
            }
            if (position == source.length) return null
            require(++count <= MAX_TOKENS)
            val start = position
            val first = source[position++]
            var kind = Kind.SYMBOL
            var value = first.toString()
            if (first in "\"'`") {
                val text = StringBuilder(); var closed = false; var overflow = false
                while (position < source.length) {
                    var c = source[position++]
                    if (c == first) { closed = true; break }
                    require(first == '`' || c !in "\r\n")
                    require(first != '`' || !(c == '$' && source.getOrNull(position) == '{'))
                    if (c == '\\') {
                        require(position < source.length); c = source[position++]
                        c = when (c) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'; 'v' -> '\u000b'
                            'x', 'u' -> {
                                val length = if (c == 'x') 2 else 4
                                require(position + length <= source.length)
                                val decoded = requireNotNull(source.substring(position, position + length).toIntOrNull(16)).toChar()
                                position += length; decoded
                            }
                            '\n', '\r' -> { if (c == '\r' && source.getOrNull(position) == '\n') position++; continue }
                            else -> c
                        }
                    }
                    if (text.length < 128) text.append(c) else overflow = true
                }
                require(closed)
                kind = if (first == '`') Kind.OPAQUE else Kind.STRING
                value = if (overflow) "<long-literal>" else text.toString()
            } else if (first.isLetter() || first in "_$") {
                while (source.getOrNull(position)?.let { it.isLetterOrDigit() || it in "_$" } == true) position++
                kind = Kind.IDENTIFIER; value = if (position - start <= 128) source.substring(start, position) else "<long-identifier>"
            } else if (first.isDigit()) {
                while (source.getOrNull(position)?.let { it.isLetterOrDigit() || it == '.' } == true) position++
                kind = Kind.NUMBER; value = if (position - start <= 128) source.substring(start, position) else "<long-number>"
            } else if (first == '/' && previous in REGEX_PREFIX) {
                var escaped = false; var characterClass = false; var closed = false
                while (position < source.length) {
                    val c = source[position++]
                    require(c !in "\r\n")
                    if (escaped) { escaped = false; continue }
                    if (c == '\\') { escaped = true; continue }
                    if (c == '[') characterClass = true else if (c == ']') characterClass = false
                    if (c == '/' && !characterClass) { closed = true; break }
                }
                require(closed)
                while (source.getOrNull(position)?.isLetter() == true) position++
                kind = Kind.OPAQUE; value = "<regex>"
            } else {
                val pair = source.substring(start, minOf(source.length, start + 2))
                if (pair in PAIRS) { value = pair; position++ }
            }
            previous = value
            return Token(value, kind, start, position)
        }
    }

    private fun rejected(code: String, message: String) = YouTubeProgramCompilation(null, listOf(diagnostic(code, message)))
    private fun diagnostic(code: String, message: String) = ExtractionDiagnostic("youtube-player-program", code, message)

    companion object {
        const val MAX_SOURCE = 5_000_000
        private const val MAX_INPUT = 8192
        private const val MAX_HELPER_BODY = 8192
        private const val MAX_DEPTH = 256
        private const val MAX_TOKENS = 2_000_000
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val REGEX_PREFIX = setOf("", "=", "(", "[", ":", ",", "return", "throw", "case", "!", "&&", "||", "?", ";", "{")
        private val PAIRS = setOf("=>", "++", "--", "+=", "-=", "%=", "==", "!=", "&&", "||", "<=", ">=")
    }
}
