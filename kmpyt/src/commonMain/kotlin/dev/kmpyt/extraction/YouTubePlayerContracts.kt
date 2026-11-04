package dev.kmpyt.extraction

import kotlinx.serialization.json.JsonObject

/** Shared player-program execution; implementations must reject unsupported syntax, never guess. */
interface YouTubeUrlTransforms {
    val signatureAvailable: Boolean
    val throttlingAvailable: Boolean
    val signatureTimestamp: Long?
    @Throws(Exception::class)
    fun signature(input: String): String
    @Throws(Exception::class)
    fun throttling(input: String): String
    /** Release native execution state when a cached compilation is evicted or cleared. */
    @Throws(Exception::class)
    fun close() {}
}

data class YouTubeProgramBinding(val signatureFunction: String? = null, val throttlingFunction: String? = null)

data class YouTubeProgramCompilation(val transforms: YouTubeUrlTransforms?, val diagnostics: List<ExtractionDiagnostic>)

fun interface YouTubePlayerProgramCompiler {
    fun compile(source: String, binding: YouTubeProgramBinding): YouTubeProgramCompilation
}

/** Explicit response/client contract. No private client identities or integrity state are invented. */
data class YouTubeClientContract(
    val name: String,
    val endpoint: String,
    val clientContext: JsonObject,
    val headers: Map<String, String> = emptyMap(),
    /** Response-declared STS, independent of whether the current JS can be compiled. */
    val signatureTimestamp: Long? = null,
    val provenance: YouTubeClientProvenance = YouTubeClientProvenance.EXPLICIT_AUTHORIZED_CONTRACT,
    /** An issuer is used only when this particular contract explicitly requires it. */
    val playerIntegrity: YouTubePlayerIntegrityContract? = null,
) {
    override fun toString() = "YouTubeClientContract(name=$name,endpoint=${ResearchRedaction.url(endpoint)},context=redacted,headers=redacted)"
}

enum class YouTubeClientProvenance { RESPONSE_DECLARED_BROWSER, EXPLICIT_AUTHORIZED_CONTRACT, PINNED_PUBLIC_CLIENT }

/** Opaque source generation prevents sharing a grant between different player contracts. */
class YouTubePlayerIntegrityContract(val issuerId: String, val sourceGeneration: String) {
    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(issuerId))
        require(sourceGeneration.length in 1..65_536 && sourceGeneration.none { it.code < 32 })
    }
    override fun toString() = "YouTubePlayerIntegrityContract(issuer=$issuerId,generation=redacted)"
}

data class YouTubeResponseExtractionOptions(
    val clients: List<YouTubeClientContract> = emptyList(),
    val programBinding: YouTubeProgramBinding = YouTubeProgramBinding(),
    /** Ask the same response-declared WEB client once when only SABR resources were supplied. */
    val requestObservedProgressiveAlternatives: Boolean = false,
    /** Opt-in native integration of the pinned public profiles; account/rate denials still stop requests. */
    val requestPublicProgressiveAlternatives: Boolean = false,
)
