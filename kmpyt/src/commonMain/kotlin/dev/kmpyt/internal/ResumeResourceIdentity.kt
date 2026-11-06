package dev.kmpyt.internal

import io.ktor.http.Url
import kotlinx.serialization.json.*

/** Raw content identity; never decode/sort query pairs or treat CDN hosts as interchangeable. */
internal fun resumeResourceIdentity(resource: String, mediaId: String, allowLocalHttp: Boolean): String {
    require(resource.none { it.code <= 32 || it.code == 127 } && !BAD_ESCAPE.containsMatchIn(resource)) {
        "The media resource does not have a valid URI identity."
    }
    val raw = ABSOLUTE_URL.matchEntire(resource) ?: error("The media resource does not have an absolute URI identity.")
    val url = Url(resource)
    require(url.protocol.name in setOf("https", "http") && url.host.isNotEmpty())
    val host = url.host.lowercase()
    val googleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    val loopbackFixture = allowLocalHttp && url.protocol.name == "http" && host in setOf("127.0.0.1", "localhost", "::1", "[::1]")
    val signing = if (googleVideo || loopbackFixture) SIGNING_KEYS else emptySet()
    return buildJsonObject {
        put("media", mediaId)
        put("origin", "${url.protocol.name}://$host:${url.port}")
        put("path", raw.groupValues[3])
        put("fragment", raw.groups[5]?.value)
        put("query", raw.groups[4]?.value?.let { query ->
            JsonArray(query.split('&').filterNot { it.substringBefore('=') in signing }.map(::JsonPrimitive))
        } ?: JsonNull)
    }.toString()
}

private val ABSOLUTE_URL = Regex("([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#(.*))?")
private val BAD_ESCAPE = Regex("%(?![0-9a-fA-F]{2})")
private val SIGNING_KEYS = setOf("sig", "signature", "lsig", "expire", "expires", "sparams", "lsparams",
    "n", "ei", "ip", "ipbits", "spc", "pot")
