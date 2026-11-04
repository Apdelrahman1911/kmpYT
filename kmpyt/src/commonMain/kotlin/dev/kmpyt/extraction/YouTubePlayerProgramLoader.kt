package dev.kmpyt.extraction

import dev.kmpyt.UrlPolicy
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

data class LoadedYouTubePlayerProgram(
    val scriptUrl: String?,
    val compilation: YouTubeProgramCompilation?,
    val diagnostics: List<ExtractionDiagnostic>,
    val cacheHit: Boolean = false,
)

/**
 * Loads only a response-declared player program. The cache is versioned by its actual
 * URL and the requested function binding; a different player cannot reuse its analysis.
 * No iframe lookup, invented hash, integrity program or extractor plugin is downloaded.
 */
class YouTubePlayerProgramLoader(
    private val transport: ResearchTransport,
    private val compiler: YouTubePlayerProgramCompiler = BoundedYouTubePlayerProgramCompiler(),
    private val cacheEntries: Int = 8,
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<ProgramKey, YouTubeProgramCompilation>()
    private data class ProgramKey(val url: String, val binding: YouTubeProgramBinding)

    init { require(cacheEntries in 1..16) { "Invalid player-program cache bound" } }

    fun discover(context: ExtractionContext, pageBody: String): String? {
        if (pageBody.length > YouTubeProvider.MAX_PAGE_BODY) return null
        val candidates = buildList {
            JSON_REFERENCE.findAll(pageBody).take(16).forEach { match ->
                val decoded = runCatching { (Json.parseToJsonElement(match.groupValues[1]) as? JsonPrimitive)?.content }.getOrNull()
                if (decoded != null) add(decoded)
            }
            SCRIPT_REFERENCE.findAll(pageBody).take(32).forEach { add(it.groupValues[2]) }
        }
        return candidates.firstNotNullOfOrNull { candidate -> permittedReference(context.responseUrl, candidate) }
    }

    suspend fun load(context: ExtractionContext, pageBody: String,
        binding: YouTubeProgramBinding = YouTubeProgramBinding(),
        requestTransport: ResearchTransport = transport): LoadedYouTubePlayerProgram {
        val url = discover(context, pageBody) ?: return LoadedYouTubePlayerProgram(null, null,
            listOf(diagnostic("PLAYER_PROGRAM_REFERENCE_MISSING", "No permitted response-declared player program was found. A player hash or program URL was not invented.")))
        val key = ProgramKey(url, binding)
        // Holding the small cache lock also deduplicates simultaneous loads of the same
        // program. The supplied transport remains responsible for cancellation/timeouts.
        return mutex.withLock {
            cache.remove(key)?.let { found ->
                cache[key] = found
                return@withLock LoadedYouTubePlayerProgram(url, found,
                    listOf(diagnostic("PLAYER_PROGRAM_CACHE_HIT", "Reused the previously prepared player program and runtime for ${ResearchRedaction.url(url)}.")) +
                        found.diagnostics.filterNot { it.code in setOf("PLAYER_CONTEXT_RUNTIME_PREPARED",
                            "SIGNATURE_PROGRAM_COMPILED", "THROTTLING_PROGRAM_COMPILED") },
                    cacheHit = true)
            }
            val response = try {
                requestTransport.execute(ResearchRequest("GET", url, mapOf("Accept" to "application/javascript, text/javascript"), stage = "youtube-player-program"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return@withLock LoadedYouTubePlayerProgram(url, null,
                    listOf(diagnostic("PLAYER_PROGRAM_REQUEST_FAILED", "The player-program request failed or exceeded the transport limit. Exception payloads and tokens are not logged.")))
            }
            val receipt = diagnostic("PLAYER_PROGRAM_HTTP_RESPONSE", "GET ${ResearchRedaction.url(url)} returned HTTP ${response.status}; ${response.body.length} characters.")
            if (response.status !in 200..299) return@withLock LoadedYouTubePlayerProgram(url, null,
                listOf(receipt, diagnostic("PLAYER_PROGRAM_HTTP_UNAVAILABLE", "The declared program did not return a successful response. Redirect/account/challenge destinations were not followed.")))
            val contentType = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
                ?.substringBefore(';')?.trim()?.lowercase()
            if (!contentType.isNullOrBlank() && contentType !in setOf("application/javascript", "text/javascript", "application/x-javascript", "text/plain"))
                return@withLock LoadedYouTubePlayerProgram(url, null,
                    listOf(receipt, diagnostic("PLAYER_PROGRAM_CONTENT_TYPE_UNSUPPORTED", "The declared program returned a different content type, such as an HTML access page. Its body was not compiled.")))
            if (response.body.length !in 1..MAX_PROGRAM_CHARS) return@withLock LoadedYouTubePlayerProgram(url, null,
                listOf(receipt, diagnostic("PLAYER_PROGRAM_SIZE_LIMIT", "The declared player program is empty or exceeds the five-million-character compilation bound.")))
            // A custom transport cannot silently replace the requested program with a
            // redirect result on another URL. HTTP status and declared identity both matter.
            if (response.url.substringBefore('#') != url) return@withLock LoadedYouTubePlayerProgram(url, null,
                listOf(receipt, diagnostic("PLAYER_PROGRAM_RESPONSE_MISMATCH", "The returned program URL differs from the declared request. Its code was not compiled.")))
            val compiled = try { compiler.compile(response.body, binding) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return@withLock LoadedYouTubePlayerProgram(url, null,
                    listOf(receipt, diagnostic("PLAYER_PROGRAM_COMPILATION_FAILED", "The declared program could not be analyzed within the supported syntax and bounds. No transform result was guessed.")))
            }
            // A native VM startup failure may be transient. Persist only usable
            // programs so a later inspection can retry the actual declared source.
            if (compiled.transforms != null) cache[key] = compiled
            while (cache.size > cacheEntries) cache.remove(cache.keys.first())?.transforms?.let { discarded ->
                runCatching { discarded.close() }
            }
            LoadedYouTubePlayerProgram(url, compiled, listOf(receipt) + compiled.diagnostics)
        }
    }

    suspend fun clear() = mutex.withLock {
        val discarded = cache.values.mapNotNull { it.transforms }
        cache.clear()
        // Cleanup is best effort for every entry; one native shutdown failure must not
        // retain the other sessions or expose runtime exception payloads to diagnostics.
        discarded.forEach { runCatching { it.close() } }
    }

    suspend fun close() = clear()

    private fun permittedReference(responseUrl: String, raw: String): String? = runCatching {
        require(raw.length in 1..4096 && raw.none { it.isWhitespace() || it.code < 32 || it == '\\' })
        val absolute = EmbeddedData.absolute(responseUrl, raw).substringBefore('#')
        require(UrlPolicy.validate(absolute, allowLocalHttp = true) == null)
        val url = Url(absolute)
        val host = url.host.lowercase().trimEnd('.')
        val isPublicPlayer = host in setOf("youtube.com", "www.youtube.com", "m.youtube.com") &&
            url.protocol.name == "https" && url.port == 443 && PUBLIC_PLAYER_PATH.matches(url.encodedPath)
        val isOwnedProgram = ResearchAccess.origin(absolute) == ResearchAccess.origin(responseUrl) &&
            !isPlatformHost(host) && url.encodedPath.lowercase().endsWith(".js")
        require(isPublicPlayer || isOwnedProgram)
        absolute
    }.getOrNull()

    private fun isPlatformHost(host: String) = host == "youtube.com" || host.endsWith(".youtube.com") ||
        host == "youtu.be" || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
        host == "googlevideo.com" || host.endsWith(".googlevideo.com")

    private fun diagnostic(code: String, message: String) = ExtractionDiagnostic("youtube-player-program", code, message)

    companion object {
        const val MAX_PROGRAM_CHARS = 5_000_000
        private val JSON_REFERENCE = Regex("\"(?:PLAYER_JS_URL|jsUrl)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")")
        private val SCRIPT_REFERENCE = Regex("<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val PUBLIC_PLAYER_PATH = Regex("/s/player/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_./-]{1,256}/base\\.js")
    }
}
