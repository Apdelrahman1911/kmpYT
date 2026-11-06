package dev.kmpyt.internal

import dev.kmpyt.YouTubePlaybackEndpoint

internal data class PlaybackHttpResponse(
    val status: Int,
    val endpoint: YouTubePlaybackEndpoint? = null,
    val offset: Long = 0,
    val length: Long = 0,
    val sendBody: Boolean = false,
) {
    fun headers(): ByteArray = buildString {
        append("HTTP/1.1 $status ")
        append(when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"; else -> "Service Unavailable"
        })
        append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
        append("Content-Length: ${this@PlaybackHttpResponse.length}\r\n")
        if (status == 405) append("Allow: GET, HEAD\r\n")
        endpoint?.let { track ->
            if (status in setOf(200, 206)) append("Content-Type: ${track.mimeType}\r\nAccept-Ranges: bytes\r\n")
            if (status == 206) append("Content-Range: bytes $offset-${offset + this@PlaybackHttpResponse.length - 1}/${track.lengthBytes}\r\n")
            if (status == 416) append("Content-Range: bytes */${track.lengthBytes}\r\n")
        }
        append("\r\n")
    }.encodeToByteArray()
}

/** A deliberately small one-request, single-range, exact-origin HTTP/1.x surface. */
internal object PlaybackHttpProtocol {
    fun response(headerBytes: ByteArray, endpoints: List<YouTubePlaybackEndpoint>, maximumBytes: Int): PlaybackHttpResponse {
        if (headerBytes.size > maximumBytes) return PlaybackHttpResponse(431)
        if (headerBytes.any { it.toInt() !in 0x20..0x7e && it != 13.toByte() && it != 10.toByte() })
            return PlaybackHttpResponse(400)
        val header = headerBytes.decodeToString()
        if (!header.endsWith("\r\n\r\n") || "\r\n\r\n" in header.dropLast(4)) return PlaybackHttpResponse(400)
        val lines = header.dropLast(4).split("\r\n")
        if (lines.size !in 1..65 || lines.any { '\r' in it || '\n' in it }) return PlaybackHttpResponse(400)
        val request = lines.first().split(' ')
        if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return PlaybackHttpResponse(400)
        val method = request[0]
        if (method !in setOf("GET", "HEAD")) return PlaybackHttpResponse(405)
        val fields = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val name = line.substringBefore(':')
            if (':' !in line || !Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name)) return PlaybackHttpResponse(400)
            val key = name.lowercase()
            if (key in fields) return PlaybackHttpResponse(400)
            fields[key] = line.substringAfter(':').trim()
        }
        if (fields["content-length"]?.let { it != "0" } == true || "transfer-encoding" in fields || "expect" in fields)
            return PlaybackHttpResponse(400)
        // No browser/CORS surface and no DNS-rebinding Host alias. The capability
        // route alone never creates permission to access another local path.
        if ("origin" in fields || fields["sec-fetch-site"]?.let { it != "none" } == true) return PlaybackHttpResponse(403)
        val endpoint = endpoints.singleOrNull { candidate ->
            fields["host"] == candidate.url.removePrefix("http://").substringBefore('/') &&
                request[1] == "/" + candidate.url.removePrefix("http://").substringAfter('/')
        } ?: return PlaybackHttpResponse(404)
        val total = endpoint.lengthBytes
        val range = fields["range"].takeIf { method == "GET" && "if-range" !in fields }
            ?: return PlaybackHttpResponse(200, endpoint, length = total, sendBody = method == "GET")
        val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(range) ?: return PlaybackHttpResponse(416, endpoint)
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        val start: Long
        val end: Long
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull()?.takeIf { it > 0 } ?: return PlaybackHttpResponse(416, endpoint)
            start = total - minOf(total, suffix)
            end = total - 1
        } else {
            start = startText.toLongOrNull()?.takeIf { it < total } ?: return PlaybackHttpResponse(416, endpoint)
            end = if (endText.isEmpty()) total - 1 else
                endText.toLongOrNull()?.takeIf { it >= start }?.coerceAtMost(total - 1) ?: return PlaybackHttpResponse(416, endpoint)
        }
        return PlaybackHttpResponse(206, endpoint, start, end - start + 1, sendBody = true)
    }
}
