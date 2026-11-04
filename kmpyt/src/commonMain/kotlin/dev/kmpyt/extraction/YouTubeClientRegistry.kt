package dev.kmpyt.extraction

import dev.kmpyt.UrlPolicy

/**
 * A finite registry of response-declared, pinned upstream public or explicitly
 * authorized contracts. Entries are supplied by the caller; the registry never
 * guesses credentials, integrity grants, client versions or device profiles.
 */
class YouTubeClientRegistry(contracts: List<YouTubeClientContract>) {
    val contracts: List<YouTubeClientContract> = contracts.toList()
    init {
        require(contracts.size <= 3) { "Configure no more than three explicit player-client contracts" }
        contracts.forEach(::validate)
        require(contracts.distinctBy { Triple(it.name, it.endpoint, it.clientContext) }.size == contracts.size) {
            "Duplicate player-client contracts would repeat the same request"
        }
    }

    private fun validate(client: YouTubeClientContract) {
        require(client.name.length in 1..80 && client.name.none { it.code < 32 || it.code == 127 }) { "Invalid player-client label" }
        require(UrlPolicy.validate(client.endpoint, allowLocalHttp = true) == null) { "A client endpoint must use HTTPS or loopback HTTP" }
        require(client.clientContext.toString().length <= 64_000) { "Player-client context exceeds its bound" }
        require(client.signatureTimestamp == null || client.signatureTimestamp in 1..Int.MAX_VALUE.toLong()) { "Invalid response-declared signature timestamp" }
        require(client.headers.size <= 16) { "Too many player-client headers" }
        require(client.headers.keys.map(String::lowercase).distinct().size == client.headers.size) { "Duplicate player-client header names" }
        client.headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name) && name.lowercase() !in PROTECTED_HEADERS) { "Protected credentials/framing headers require their native adapter" }
            require(value.length <= 2048 && value.none { it.code < 32 || it.code == 127 }) { "Invalid player-client header value" }
        }
        val suppliedVersion = client.clientContext.text("clientVersion")
        val headerVersion = client.header("X-Youtube-Client-Version")
        require(headerVersion == null || suppliedVersion == headerVersion) { "Player client header and body versions differ" }
        client.header("X-Youtube-Client-Name")?.let { name ->
            require(name.toIntOrNull()?.let { it > 0 } == true) { "Invalid numeric player-client identity" }
        }
        require(client.playerIntegrity == null || client.sabrClientInfoOrNull() != null) {
            "An integrity contract requires the actual numeric client identity and version"
        }
    }

    companion object {
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val PROTECTED_HEADERS = setOf("cookie", "authorization", "set-cookie", "host", "content-length", "transfer-encoding", "connection", "range", "if-range", "accept-encoding")
    }
}

internal fun YouTubeClientContract.header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value

/** Never substitute another contract's identity when parsing a fallback response. */
internal fun YouTubeClientContract.sabrClientInfoOrNull(): SabrClientInfo? {
    val number = header("X-Youtube-Client-Name")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val version = clientContext.text("clientVersion")?.takeIf { it.length in 1..80 && it.none { c -> c.code < 32 || c.code == 127 } } ?: return null
    if (header("X-Youtube-Client-Version")?.let { it != version } == true) return null
    return SabrClientInfo(number, version)
}
