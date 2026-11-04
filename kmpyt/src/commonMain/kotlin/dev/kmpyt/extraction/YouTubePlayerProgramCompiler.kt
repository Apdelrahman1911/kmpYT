package dev.kmpyt.extraction

/**
 * Compiles a deliberately small, verified JavaScript subset into Kotlin array operations.
 * It does not execute browser code, regex programs, dynamic dispatch or a partial function.
 * A changed player using an unsupported operation stays unavailable instead of guessing.
 */
class BoundedYouTubePlayerProgramCompiler : YouTubePlayerProgramCompiler {
    override fun compile(source: String, binding: YouTubeProgramBinding): YouTubeProgramCompilation {
        val diagnostics = mutableListOf<ExtractionDiagnostic>()
        fun diagnostic(code: String, message: String) { diagnostics += ExtractionDiagnostic("youtube-player-program", code, message) }
        if (source.length > MAX_SOURCE) {
            diagnostic("PLAYER_PROGRAM_SOURCE_LIMIT", "The supplied program exceeds the 5,000,000-character shared compiler source limit.")
            return YouTubeProgramCompilation(null, diagnostics)
        }
        val tokens = try {
            lex(source)
        } catch (_: TokenLimit) {
            diagnostic("PLAYER_PROGRAM_TOKEN_LIMIT", "The supplied program exceeds the 200,000-token shared compiler limit. No partial program was compiled.")
            return YouTubeProgramCompilation(null, diagnostics)
        } catch (_: Exception) {
            diagnostic("PLAYER_PROGRAM_LEXING_UNSUPPORTED", "The supplied program exceeds bounded lexical limits or contains unsupported syntax.")
            return YouTubeProgramCompilation(null, diagnostics)
        }
        val depths = try { lexicalDepths(tokens) } catch (_: Exception) {
            diagnostic("PLAYER_PROGRAM_SCOPE_UNSUPPORTED", "The supplied program has unsupported or unbalanced lexical containers; no binding visibility was inferred.")
            return YouTubeProgramCompilation(null, diagnostics)
        }
        fun selected(explicit: String?, key: String): String? {
            if (explicit != null) return explicit.takeIf { IDENTIFIER.matches(it) }
            // Bind the call site, not whichever split/join function happens to occur first.
            val names = tokens.indices.mapNotNull { index ->
                if (tokens[index].kind != Kind.ID || tokens[index].value != "set" || tokens.getOrNull(index + 1)?.value != "(" ||
                    tokens.getOrNull(index + 2)?.kind != Kind.STRING || tokens[index + 2].value != key ||
                    tokens.getOrNull(index + 3)?.value != ",") return@mapNotNull null
                val candidate = tokens.getOrNull(index + 4) ?: return@mapNotNull null
                if (candidate.kind == Kind.ID && tokens.getOrNull(index + 5)?.value == "(") candidate.value else null
            }.distinct()
            return names.singleOrNull()
        }
        val signatureName = selected(binding.signatureFunction, "signature") ?: if (binding.signatureFunction == null) selected(null, "sig") else null
        val throttlingName = selected(binding.throttlingFunction, "n")
        val functions = try {
            functions(tokens, depths, listOfNotNull(signatureName, throttlingName).toSet())
        } catch (_: Exception) {
            diagnostic("PLAYER_PROGRAM_SCAN_LIMIT", "Function binding scan exceeds bounded candidate limits; no partial program was compiled.")
            return YouTubeProgramCompilation(null, diagnostics)
        }
        fun program(name: String?, role: String): List<Operation>? {
            if (name == null) { diagnostic("${role}_FUNCTION_NOT_IDENTIFIED", "No unambiguous $role function binding was found in the supplied program."); return null }
            return try {
                val function = functions[name]?.singleOrNull() ?: error("Ambiguous or absent function")
                // A later assignment would make a static definition an unreliable binding.
                require(tokens.indices.none { i ->
                    tokens[i].kind == Kind.ID && tokens[i].value == name &&
                        tokens.getOrNull(i + 1)?.value == "=" && tokens.getOrNull(i + 2)?.value != "function"
                })
                compileFunction(function, tokens, depths)
            } catch (_: Exception) {
                diagnostic("${role}_PROGRAM_UNSUPPORTED", "The bound $role function is not a complete supported single-argument array program; dynamic/global state and additional parameters are not inferred.")
                null
            }
        }
        val signature = program(signatureName, "SIGNATURE")
        val throttling = program(throttlingName, "THROTTLING")
        val timestamps = tokens.indices.mapNotNull { i ->
            if (tokens[i].value != "signatureTimestamp" || tokens.getOrNull(i + 1)?.value !in setOf(":", "=")) null
            else tokens.getOrNull(i + 2)?.takeIf { it.kind == Kind.NUMBER }?.value?.toLongOrNull()?.takeIf { it >= 0 }
        }.distinct()
        val timestamp = timestamps.singleOrNull()
        if (timestamps.size > 1) diagnostic("SIGNATURE_TIMESTAMP_AMBIGUOUS", "Multiple different timestamp literals were supplied; none was selected.")
        if (signature == null && throttling == null) return YouTubeProgramCompilation(null, diagnostics)
        if (signature != null) diagnostic("SIGNATURE_PROGRAM_COMPILED", "A complete supported signature function was compiled to bounded shared Kotlin operations.")
        if (throttling != null) diagnostic("THROTTLING_PROGRAM_COMPILED", "A complete supported n function was compiled to bounded shared Kotlin operations.")
        return YouTubeProgramCompilation(Compiled(signature, throttling, timestamp), diagnostics)
    }

    private class Compiled(private val sig: List<Operation>?, private val n: List<Operation>?, override val signatureTimestamp: Long?) : YouTubeUrlTransforms {
        override val signatureAvailable = sig != null
        override val throttlingAvailable = n != null
        override fun signature(input: String): String = execute(requireNotNull(sig) { "Signature program unavailable." }, input)
        override fun throttling(input: String): String = execute(requireNotNull(n) { "Throttling program unavailable." }, input)
        private fun execute(operations: List<Operation>, input: String): String {
            require(input.isNotEmpty() && input.length <= MAX_INPUT) { "Player transform input is empty or exceeds 8192 UTF-16 units." }
            // JavaScript split("") operates on UTF-16 code units, including surrogate halves.
            var chars: MutableList<Char?> = input.map { it }.toMutableList()
            for (operation in operations) {
                chars = operation.apply(chars)
                require(chars.size <= MAX_INPUT) { "Player transform output exceeds the bounded array limit." }
            }
            val output = chars.filterNotNull().joinToString("")
            require(output.isNotEmpty()) { "Player transform produced an empty string." }
            return output
        }
    }

    private sealed interface Operation {
        fun apply(chars: MutableList<Char?>): MutableList<Char?>
        data object Reverse : Operation { override fun apply(chars: MutableList<Char?>) = chars.apply { reverse() } }
        data class Slice(val start: Int, val end: Int? = null) : Operation {
            override fun apply(chars: MutableList<Char?>): MutableList<Char?> {
                fun index(value: Int) = if (value < 0) (chars.size + value).coerceAtLeast(0) else value.coerceAtMost(chars.size)
                val first = index(start); val last = index(end ?: chars.size).coerceAtLeast(first)
                return chars.subList(first, last).toMutableList()
            }
        }
        data class Splice(val count: Int) : Operation {
            override fun apply(chars: MutableList<Char?>) = chars.apply { repeat(count.coerceAtLeast(0).coerceAtMost(size)) { removeAt(0) } }
        }
        data class Swap(val index: Int, val moduloDestination: Boolean) : Operation {
            override fun apply(chars: MutableList<Char?>) = chars.apply {
                val originalLength = size
                val target = if (originalLength == 0) null else index % originalLength
                val value = getOrNull(0)
                if (isEmpty()) add(null)
                this[0] = target?.let { getOrNull(it) }
                val destination = if (moduloDestination) target else index
                if (destination != null) {
                    require(destination in 0 until MAX_INPUT)
                    while (size <= destination) add(null)
                    this[destination] = value
                }
            }
        }
        data object RotateLeft : Operation { override fun apply(chars: MutableList<Char?>) = chars.apply { add(if (isEmpty()) null else removeAt(0)) } }
        data object RotateRight : Operation { override fun apply(chars: MutableList<Char?>) = chars.apply { add(0, if (isEmpty()) null else removeAt(lastIndex)) } }
    }

    private data class Function(val params: List<String>, val body: List<Token>, val bodyStartIndex: Int, val endIndex: Int,
        val globallyVisible: Boolean = false)
    private enum class Kind { ID, NUMBER, STRING, SYMBOL, OPAQUE }
    private data class Token(val value: String, val kind: Kind)
    private class TokenLimit : IllegalArgumentException()

    private fun functions(tokens: List<Token>, depths: IntArray, wanted: Set<String>): Map<String, List<Function>> {
        val found = mutableMapOf<String, MutableList<Function>>()
        var candidates = 0
        tokens.indices.forEach { i ->
            if (tokens[i].kind != Kind.ID || tokens[i].value != "function") return@forEach
            val named = tokens.getOrNull(i + 1)?.kind == Kind.ID
            val name = if (named) tokens[i + 1].value else if (tokens.getOrNull(i - 1)?.value == "=" && tokens.getOrNull(i - 2)?.kind == Kind.ID) tokens[i - 2].value else null
            if (name == null || name !in wanted) return@forEach
            val boundary = if (named) i - 1 else i - 3
            val previous = tokens.getOrNull(boundary)?.value
            // Retain invisible same-name definitions as ambiguity instead of quietly
            // substituting a global definition for a shadowed call-site binding.
            val visible = depths[i] == 0 && (previous == null || previous in setOf(";", "}", "var", "let", "const")) &&
                !(named && previous in setOf("var", "let", "const"))
            require(++candidates <= 8)
            val open = i + if (named) 2 else 1
            readFunction(tokens, open)?.let { function ->
                val wrapped = !named && tokens.getOrNull(function.endIndex + 1)?.value in setOf(".", "[", "(")
                found.getOrPut(name) { mutableListOf() } += function.copy(globallyVisible = visible && !wrapped)
            }
        }
        return found
    }

    private fun readFunction(tokens: List<Token>, open: Int): Function? {
        if (tokens.getOrNull(open)?.kind != Kind.SYMBOL || tokens.getOrNull(open)?.value != "(") return null
        val close = matching(tokens, open) ?: return null
        val parameterTokens = tokens.subList(open + 1, close)
        if (parameterTokens.isNotEmpty() && (parameterTokens.size % 2 != 1 || parameterTokens.withIndex().any { (i, t) -> if (i % 2 == 0) t.kind != Kind.ID else t.value != "," })) return null
        val start = close + 1
        if (tokens.getOrNull(start)?.kind != Kind.SYMBOL || tokens.getOrNull(start)?.value != "{") return null
        val end = matching(tokens, start) ?: return null
        return Function(parameterTokens.filter { it.kind == Kind.ID }.map { it.value }, tokens.subList(start + 1, end), start + 1, end)
    }

    private fun compileFunction(function: Function, source: List<Token>, depths: IntArray): List<Operation> {
        require(function.globallyVisible) { "The function binding is not a proven top-level declaration." }
        require(function.params.size == 1)
        require(function.body.size <= 8192)
        val statements = statements(function.body).toMutableList()
        while (statements.firstOrNull()?.let { it.size == 1 && it.single().kind == Kind.STRING && it.single().value == "use strict" } == true) statements.removeAt(0)
        require(statements.size in 3..66)
        val parameter = function.params.single()
        val first = statements.removeAt(0).toMutableList()
        val declaration = first.firstOrNull()?.takeIf { it.kind == Kind.ID && it.value in setOf("var", "let", "const") }?.value
        if (first.firstOrNull()?.kind == Kind.ID && first.firstOrNull()?.value in setOf("var", "let", "const")) first.removeAt(0)
        require(first.size == 8 && first[0].kind == Kind.ID && first.map { it.value }.drop(1) == listOf("=", parameter, ".", "split", "(", "", ")") && first[6].kind == Kind.STRING && first.withIndex().all { (i, t) -> i == 6 || t.kind !in setOf(Kind.STRING, Kind.OPAQUE) })
        val array = first[0].value
        require(array == parameter || declaration != null) { "Split array must be a parameter or declared local binding." }
        require(declaration !in setOf("let", "const") || array != parameter)
        val returnTokens = statements.removeAt(statements.lastIndex)
        val last = returnTokens.map { it.value }
        require(last == listOf("return", array, ".", "join", "(", "", ")") && returnTokens[5].kind == Kind.STRING && returnTokens.withIndex().all { (i, t) -> i == 5 || t.kind !in setOf(Kind.STRING, Kind.OPAQUE) })
        val operations = statements.map { statement -> operation(statement, array, parameter, function, source, depths) }
        require(operations.isNotEmpty() && operations.size <= MAX_OPERATIONS)
        require(declaration != "const" || operations.none { it is Operation.Slice })
        return operations
    }

    private fun operation(statement: List<Token>, array: String, parameter: String, owner: Function, source: List<Token>, depths: IntArray): Operation {
        val values = statement.map { it.value }
        require(statement.none { it.kind == Kind.OPAQUE })
        require(statement.withIndex().all { (i, t) -> t.kind != Kind.STRING || i == 2 && values.getOrNull(1) == "[" && values.getOrNull(3) == "]" })
        if (values == listOf(array, ".", "reverse", "(", ")")) return Operation.Reverse
        if (values == listOf(array, ".", "push", "(", array, ".", "shift", "(", ")", ")")) return Operation.RotateLeft
        if (values == listOf(array, ".", "unshift", "(", array, ".", "pop", "(", ")", ")")) return Operation.RotateRight
        if (values.take(5) == listOf(array, "=", array, ".", "slice") && values.getOrNull(5) == "(" && values.lastOrNull() == ")") {
            val args = arguments(statement.subList(6, statement.lastIndex))
            require(args.size in 1..2)
            return Operation.Slice(integer(args[0]), args.getOrNull(1)?.let(::integer))
        }
        if (values.take(4) == listOf(array, ".", "splice", "(") && values.lastOrNull() == ")") {
            val args = arguments(statement.subList(4, statement.lastIndex)); require(args.size == 2 && integer(args[0]) == 0)
            return Operation.Splice(integer(args[1]))
        }
        val method: String
        val objectName: String
        val open: Int
        if (statement.getOrNull(0)?.kind == Kind.ID && values.getOrNull(1) == "." && statement.getOrNull(2)?.kind == Kind.ID && values.getOrNull(3) == "(") {
            objectName = values[0]; method = values[2]; open = 3
        } else if (statement.getOrNull(0)?.kind == Kind.ID && values.getOrNull(1) == "[" && statement.getOrNull(2)?.kind == Kind.STRING && values.getOrNull(3) == "]" && values.getOrNull(4) == "(") {
            objectName = values[0]; method = values[2]; open = 4
        } else error("Unsupported array statement")
        require(values.last() == ")")
        require(objectName != array && objectName != parameter) { "Local binding shadows helper object." }
        val args = arguments(statement.subList(open + 1, statement.lastIndex))
        require(args.size in 1..2 && args[0].map { it.value } == listOf(array))
        val number = args.getOrNull(1)?.let(::integer)
        return helper(objectName, method, number, owner, source, depths)
    }

    private fun helper(name: String, method: String, argument: Int?, owner: Function, source: List<Token>, depths: IntArray): Operation {
        require(source.count { it.kind == Kind.ID && it.value == name } <= 256)
        val objects = source.indices.filter { i ->
            source[i].kind == Kind.ID && source[i].value == name && depths[i] == 0 &&
                source.getOrNull(i + 1)?.value == "=" && source.getOrNull(i + 2)?.value == "{" &&
                (source.getOrNull(i - 1)?.value == null || source[i - 1].value in setOf(";", "}", "var", "let", "const"))
        }
        require(objects.size == 1)
        require(source.indices.none { i ->
            if (source[i].kind != Kind.ID || source[i].value != name) false
            else if (source.getOrNull(i + 1)?.value == "=") source.getOrNull(i + 2)?.value != "{"
            else {
                val memberEnd = when (source.getOrNull(i + 1)?.value) {
                    "." -> i + 3
                    "[" -> matching(source, i + 1)?.plus(1)
                    else -> null
                }
                memberEnd != null && source.getOrNull(memberEnd)?.value in setOf("=", "+=", "-=", "%=", "++", "--")
            }
        })
        val open = objects.single() + 2
        val close = matching(source, open) ?: error("Unclosed helper object")
        require(close - open <= 8192)
        // A helper passed to other code or referenced in a different function cannot
        // be assumed immutable. Its only uses may be its declaration and this body.
        require(source.indices.none { i -> source[i].kind == Kind.ID && source[i].value == name &&
            i !in objects.single()..close && i !in owner.bodyStartIndex until owner.endIndex })
        val entries = splitAtTopLevel(source.subList(open + 1, close), ",")
        val entry = entries.single { it.firstOrNull()?.value == method && it.firstOrNull()?.kind in setOf(Kind.ID, Kind.STRING) }
        val function = when {
            entry.getOrNull(1)?.value == ":" && entry.getOrNull(2)?.kind == Kind.ID && entry.getOrNull(2)?.value == "function" -> readFunction(entry, 3)
            entry.getOrNull(1)?.value == "(" -> readFunction(entry, 1)
            else -> null
        } ?: error("Unsupported helper syntax")
        require(function.endIndex == entry.lastIndex)
        require(function.params.size in 1..2)
        require(function.params.distinct().size == function.params.size)
        require(function.body.size <= 256)
        val array = function.params[0]
        val number = function.params.getOrNull(1)
        val body = statements(function.body)
        require(body.flatten().none { it.kind in setOf(Kind.STRING, Kind.OPAQUE) })
        // Compare complete tokens using actual parameter names. Concatenating normalized
        // tokens would mistake a global identifier returnA for return + parameter A.
        val lines = body.map { line -> line.map { it.value } }
        val reverse = listOf(array, ".", "reverse", "(", ")")
        if (lines == listOf(reverse) || lines == listOf(listOf("return") + reverse)) return Operation.Reverse
        val splice = listOf(array, ".", "splice", "(", "0", ",", number, ")")
        if (number != null && (lines == listOf(splice) || lines == listOf(listOf("return") + splice))) return Operation.Splice(requireNotNull(argument))
        if (lines == listOf(listOf(array, ".", "push", "(", array, ".", "shift", "(", ")", ")"))) return Operation.RotateLeft
        if (lines == listOf(listOf(array, ".", "unshift", "(", array, ".", "pop", "(", ")", ")"))) return Operation.RotateRight
        if (number != null && body.size == 3 && body[0].size == 7 && body[0][0].value in setOf("var", "let", "const") && body[0][1].kind == Kind.ID) {
            val temp = body[0][1].value
            require(temp != array && temp != number)
            val declaration = listOf(body[0][0].value, temp, "=", array, "[", "0", "]")
            val read = listOf(array, "[", "0", "]", "=", array, "[", number, "%", array, ".", "length", "]")
            val moduloWrite = listOf(array, "[", number, "%", array, ".", "length", "]", "=", temp)
            val rawWrite = listOf(array, "[", number, "]", "=", temp)
            if (lines[0] == declaration && lines[1] == read && lines[2] in setOf(moduloWrite, rawWrite)) {
                require(argument != null && argument >= 0)
                return Operation.Swap(argument, lines[2] == moduloWrite)
            }
        }
        error("Helper body is not a complete supported operation")
    }

    private fun statements(tokens: List<Token>): List<List<Token>> = splitAtTopLevel(tokens, ";").filter { it.isNotEmpty() }

    private fun lexicalDepths(tokens: List<Token>): IntArray {
        val depths = IntArray(tokens.size)
        val stack = mutableListOf<String>()
        tokens.forEachIndexed { i, token ->
            depths[i] = stack.size
            if (token.kind == Kind.SYMBOL) when (token.value) {
                "(", "[", "{" -> { require(stack.size < 256); stack += token.value }
                ")", "]", "}" -> require(stack.removeLastOrNull() == when (token.value) { ")" -> "("; "]" -> "["; else -> "{" })
            }
        }
        require(stack.isEmpty())
        return depths
    }
    private fun arguments(tokens: List<Token>) = splitAtTopLevel(tokens, ",")
    private fun splitAtTopLevel(tokens: List<Token>, separator: String): List<List<Token>> {
        val result = mutableListOf<List<Token>>()
        var start = 0; val stack = mutableListOf<String>()
        tokens.forEachIndexed { index, token ->
            if (token.kind == Kind.SYMBOL) when (token.value) {
                "(", "[", "{" -> { require(stack.size < 256); stack += token.value }
                ")", "]", "}" -> require(stack.removeLastOrNull() == when (token.value) { ")" -> "("; "]" -> "["; else -> "{" })
                separator -> if (stack.isEmpty()) { result += tokens.subList(start, index); start = index + 1 }
            }
        }
        require(stack.isEmpty())
        result += tokens.subList(start, tokens.size)
        return result
    }

    private fun integer(tokens: List<Token>): Int {
        val text = tokens.joinToString("") { it.value }
        require(tokens.all { it.kind == Kind.NUMBER || it.value in setOf("-", "+") } && tokens.size <= 2)
        val value = if (text.startsWith("0x", true)) text.drop(2).toIntOrNull(16) else text.toIntOrNull()
        return requireNotNull(value).also { require(it in -MAX_INPUT..MAX_INPUT) }
    }

    private fun matching(tokens: List<Token>, open: Int): Int? {
        val expected = when (tokens.getOrNull(open)?.value) { "(" -> ")"; "[" -> "]"; "{" -> "}"; else -> return null }
        var depth = 0
        for (i in open until tokens.size) {
            if (tokens[i].kind != Kind.SYMBOL) continue
            if (tokens[i].value == tokens[open].value) depth++
            if (tokens[i].value == expected) { depth--; if (depth == 0) return i }
        }
        return null
    }

    private fun lex(source: String): List<Token> {
        val result = mutableListOf<Token>(); var i = 0
        fun add(value: String, kind: Kind) { if (result.size >= MAX_TOKENS) throw TokenLimit(); result += Token(value, kind) }
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace()) { i++; continue }
            if (source.startsWith("//", i)) { i = source.indexOf('\n', i + 2).takeIf { it >= 0 } ?: source.length; continue }
            if (source.startsWith("/*", i)) { val end = source.indexOf("*/", i + 2); require(end >= 0); i = end + 2; continue }
            if (c == '\'' || c == '"' || c == '`') {
                val quote = c; val text = StringBuilder(); i++; var closed = false
                while (i < source.length) {
                    val next = source[i++]
                    if (next == quote) { closed = true; break }
                    if (next == '\\') {
                        require(i < source.length); val escape = source[i++]
                        when (escape) {
                            '\\', '\'', '"', '`' -> text.append(escape)
                            'n' -> text.append('\n'); 'r' -> text.append('\r'); 't' -> text.append('\t')
                            'b' -> text.append('\b'); 'f' -> text.append('\u000c'); 'v' -> text.append('\u000b')
                            '0' -> { require(source.getOrNull(i)?.isDigit() != true); text.append('\u0000') }
                            in '1'..'9' -> error("Legacy numeric string escapes are unsupported")
                            '\n' -> Unit
                            '\r' -> if (source.getOrNull(i) == '\n') i++
                            'x', 'u' -> { val count = if (escape == 'x') 2 else 4; require(i + count <= source.length); text.append(requireNotNull(source.substring(i, i + count).toIntOrNull(16)).toChar()); i += count }
                            else -> text.append(escape)
                        }
                    } else { require(quote == '`' || next !in "\r\n"); text.append(next) }
                }
                require(closed); add(text.toString(), if (quote == '`') Kind.OPAQUE else Kind.STRING); continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                val start = i++; while (i < source.length && (source[i].isLetterOrDigit() || source[i] in "_$")) i++
                add(source.substring(start, i), Kind.ID); continue
            }
            if (c.isDigit()) {
                val start = i++; while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '.')) i++
                add(source.substring(start, i), Kind.NUMBER); continue
            }
            // A regex literal is opaque. It cannot participate in a compiled array function.
            val regexStart = c == '/' && (result.isEmpty() || result.last().value in setOf("=", "(", "[", ":", ",", "return", "!", "&&", "||", "?"))
            if (regexStart) {
                i++; var escaped = false; var characterClass = false; var closed = false
                while (i < source.length) {
                    val next = source[i++]
                    if (escaped) { escaped = false; continue }
                    if (next == '\\') { escaped = true; continue }
                    if (next == '[') characterClass = true else if (next == ']') characterClass = false
                    if (next == '/' && !characterClass) { closed = true; break }
                }
                require(closed); while (i < source.length && source[i].isLetter()) i++
                add("<regex>", Kind.OPAQUE); continue
            }
            val pair = source.substring(i, minOf(source.length, i + 2))
            if (pair in setOf("=>", "++", "--", "+=", "-=", "%=", "==", "!=", "&&", "||", "<=", ">=")) { add(pair, Kind.SYMBOL); i += 2 }
            else { add(c.toString(), Kind.SYMBOL); i++ }
        }
        return result
    }

    companion object {
        const val MAX_SOURCE = 5_000_000
        private const val MAX_TOKENS = 200_000
        private const val MAX_INPUT = 8192
        private const val MAX_OPERATIONS = 64
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    }
}
