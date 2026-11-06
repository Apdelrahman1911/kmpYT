package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.YouTubeDownloadPolicy
import io.ktor.http.Url
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.security.MessageDigest

internal fun ByteArray.resumeHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun resumeDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).resumeHex()

/** Only standalone progressive resources gain refresh-stable resumability in schema 2. */
internal fun progressiveSelectionDigest(plan: PlannedDownload): String? {
    val track = plan.primary
    if (plan.audio != null || track.transport != TransportKind.PROGRESSIVE || track.segments.isNotEmpty() ||
        track.initialization != null || track.manifestUrl != null || track.sabr != null) return null
    val fields = Json.encodeToJsonElement(Representation.serializer(), track).jsonObject.toMutableMap()
    listOf("url", "expiresAtEpochMs", "manifestBody", "unavailableReason").forEach(fields::remove)
    fields["id"] = JsonPrimitive(track.id.replace(Regex("#progressive(?:-[0-9]+)?$"), ""))
    return resumeDigest(buildJsonObject {
        put("schema", "single-progressive-v1")
        put("media", plan.mediaId)
        put("source", YouTubeDownloadPolicy.videoId(plan.inspectionUrl) ?: plan.inspectionUrl)
        put("track", JsonObject(fields))
        put("origin", plan.authorizedOrigin)
        put("headers", JsonObject(plan.requestHeaders.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) }))
    }.toString())
}

/** ETags are resource-scoped: changing host, port, path or content query invalidates reuse. */
internal fun progressiveResourceScope(resource: String, mediaId: String, allowLocalHttp: Boolean): String {
    val (url, uri) = try { Url(resource) to URI(resource) }
    catch (_: Exception) { error("The media resource does not have a valid URI identity.") }
    val host = url.host.lowercase()
    val googleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    // Explicit loopback transports exercise the same policy using authored resources.
    val loopbackFixture = allowLocalHttp && url.protocol.name == "http" && host in setOf("127.0.0.1", "localhost", "::1", "[::1]")
    val signing = if (googleVideo || loopbackFixture) setOf(
        "sig", "signature", "lsig", "expire", "expires", "sparams", "lsparams", "n", "ei", "ip", "ipbits", "spc", "pot",
    ) else emptySet()
    return resumeDigest(buildJsonObject {
        put("media", mediaId)
        put("origin", "${url.protocol.name}://$host:${url.port}")
        put("path", uri.rawPath)
        put("fragment", uri.rawFragment)
        // A server may distinguish pair order, duplicates, empty pairs, casing,
        // '+' and percent escapes. Do not parse/group/sort the content query.
        // Only the documented exact lowercase signing names may disappear;
        // encoded aliases conservatively require a restart as well.
        put("query", uri.rawQuery?.let { query ->
            JsonArray(query.split('&').filterNot { it.substringBefore('=') in signing }.map(::JsonPrimitive))
        } ?: JsonNull)
    }.toString())
}

/** Do not promote a weak ETag or an unqualified Last-Modified date to a strong validator. */
internal fun strongResumeEtag(value: String?): String? = value?.trim()?.takeIf {
    it.length in 2..8192 && it.startsWith('"') && it.endsWith('"') &&
        it.substring(1, it.lastIndex).all { character -> character.code == 0x21 || character.code in 0x23..0x7e || character.code in 0x80..0xff }
}

internal suspend fun updateResumeDigest(digest: MessageDigest, file: File, bytes: Long) {
    require(bytes >= 0)
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count > 0) { "The private media prefix changed while reading its receipt." }
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }
}

internal suspend fun prefixDigest(file: File, bytes: Long): String = MessageDigest.getInstance("SHA-256").let {
    updateResumeDigest(it, file, bytes)
    it.digest().resumeHex()
}

/** SHA-256 snapshot is constant cost; an exotic non-cloneable provider safely disables fresh-URL reuse. */
internal fun MessageDigest.resumeSnapshot(): String? = try { (clone() as MessageDigest).digest().resumeHex() }
catch (_: CloneNotSupportedException) { null }
