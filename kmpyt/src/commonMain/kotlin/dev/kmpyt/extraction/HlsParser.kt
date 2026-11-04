package dev.kmpyt.extraction

import kotlin.math.roundToLong

/** Bounded, unencrypted master/VOD parsing. Fetching is deliberately outside this parser. */
object HlsParser : ManifestParser {
    private const val MAX_SEGMENTS = 4096

    override fun parse(url: String, body: String): ManifestResult = try {
        require(body.length <= EmbeddedData.MAX_BODY) { "HLS manifest exceeds the parser limit" }
        val lines = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size <= 16384 && lines.firstOrNull() == "#EXTM3U") { "Not a bounded HLS manifest" }
        lines.filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }.forEach {
            require(attributes(it.substringAfter(':'))["METHOD"] == "NONE") { "Encrypted HLS is unsupported" }
        }
        require(lines.none { it.startsWith("#EXT-X-PART:") || it.startsWith("#EXT-X-PRELOAD-HINT:") || it.startsWith("#EXT-X-SKIP:") || it == "#EXT-X-I-FRAMES-ONLY" }) {
            "Low-latency and I-frame-only HLS are unsupported"
        }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) master(url, lines) else vod(url, lines)
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "Invalid HLS manifest"
        ManifestResult(emptyList(), listOf(ExtractionDiagnostic("hls", when {
            message.contains("Encrypted") -> "encrypted"
            message.contains("Live") -> "live"
            message.contains("unsupported") -> "unsupported"
            else -> "malformed"
        }, message)))
    }

    private fun master(url: String, lines: List<String>): ManifestResult {
        require(lines.none { it.startsWith("#EXTINF:") }) { "Mixed master/media HLS is unsupported" }
        val result = mutableListOf<Representation>()
        val audioGroups = mutableSetOf<String>()
        lines.filter { it.startsWith("#EXT-X-MEDIA:") }.forEach { line ->
            val values = attributes(line.substringAfter(':'))
            if (values["TYPE"] == "AUDIO") {
                val group = requireNotNull(values["GROUP-ID"]) { "Audio group has no GROUP-ID" }
                values["URI"]?.let { uri ->
                    audioGroups += group
                    result += Representation("hls-audio-${result.size}-$group", EmbeddedData.absolute(url, uri),
                        TrackRole.AUDIO, TransportKind.HLS, "audio/mp4", audioGroupId = group)
                }
            }
        }
        var pending: Map<String, String>? = null
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    require(pending == null) { "HLS variant URI is missing" }
                    pending = attributes(line.substringAfter(':'))
                }
                !line.startsWith('#') -> {
                    val values = requireNotNull(pending) { "Unexpected master playlist URI" }
                    val bandwidth = (values["AVERAGE-BANDWIDTH"] ?: values["BANDWIDTH"])?.toLongOrNull()
                    require(bandwidth != null && bandwidth > 0) { "Variant has no valid bandwidth" }
                    val resolution = values["RESOLUTION"]?.split('x')
                    require(resolution == null || resolution.size == 2) { "Invalid variant resolution" }
                    val width = resolution?.get(0)?.toIntOrNull()
                    val height = resolution?.get(1)?.toIntOrNull()
                    require(resolution == null || (width != null && height != null && width > 0 && height > 0)) { "Invalid variant dimensions" }
                    val codecs = values["CODECS"]
                    val audioOnly = resolution == null && codecs != null && codecs.split(',').all {
                        it.trim().startsWith("mp4a") || it.trim().startsWith("ac-3") || it.trim().startsWith("ec-3") || it.trim() == "opus"
                    }
                    val group = values["AUDIO"]
                    val role = when { audioOnly -> TrackRole.AUDIO; group in audioGroups -> TrackRole.VIDEO; else -> TrackRole.MUXED }
                    result += Representation("hls-variant-${result.size}${group?.let { "-$it" }.orEmpty()}",
                        EmbeddedData.absolute(url, line), role, TransportKind.HLS,
                        if (audioOnly) "audio/mp4" else "video/mp4", codecs, width, height, bandwidth, audioGroupId = group)
                    pending = null
                }
            }
        }
        require(pending == null && result.isNotEmpty() && result.size <= 128) { "Missing or excessive HLS variants" }
        return ManifestResult(result, listOf(ExtractionDiagnostic("hls", "master", "Fetch the selected child playlist before materializing media")))
    }

    private fun vod(url: String, lines: List<String>): ManifestResult {
        require("#EXT-X-ENDLIST" in lines) { "Live or unfinished HLS is unsupported" }
        require(lines.none { it == "#EXT-X-DISCONTINUITY" }) { "HLS discontinuities are unsupported" }
        val segments = mutableListOf<SegmentResource>()
        var duration: Long? = null
        var range: String? = null
        var previous: SegmentResource? = null
        var initialization: SegmentResource? = null
        var totalMs = 0L
        lines.forEach { line ->
            when {
                line.startsWith("#EXTINF:") -> {
                    require(duration == null) { "Missing HLS media URI" }
                    val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    require(seconds != null && seconds.isFinite() && seconds > 0 && seconds <= 604800) { "Invalid HLS segment duration" }
                    duration = (seconds * 1000).roundToLong()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    require(range == null) { "Duplicate HLS byte range" }
                    range = line.substringAfter(':')
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val values = attributes(line.substringAfter(':'))
                    val resource = resource(EmbeddedData.absolute(url, requireNotNull(values["URI"]) { "HLS map has no URI" }),
                        values["BYTERANGE"], initialization, null)
                    require(initialization == null || initialization == resource) { "Changing HLS initialization is unsupported" }
                    initialization = resource
                }
                !line.startsWith('#') -> {
                    val milliseconds = requireNotNull(duration) { "HLS segment has no EXTINF" }
                    val resource = resource(EmbeddedData.absolute(url, line), range, previous, milliseconds)
                    segments += resource
                    require(segments.size <= MAX_SEGMENTS) { "HLS exceeds the segment limit" }
                    totalMs += milliseconds
                    require(totalMs <= 604800000) { "HLS exceeds the duration limit" }
                    previous = resource; duration = null; range = null
                }
            }
        }
        require(duration == null && range == null && segments.isNotEmpty()) { "Incomplete HLS VOD playlist" }
        val audio = segments.all { it.url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in setOf("aac", "m4a", "mp3") }
        return ManifestResult(listOf(Representation("hls-vod", url, if (audio) TrackRole.AUDIO else TrackRole.MUXED,
            TransportKind.HLS, if (audio) "audio/mp4" else if (initialization != null) "video/mp4" else "video/mp2t",
            durationMs = totalMs, segments = segments, initialization = initialization)))
    }

    private fun resource(url: String, text: String?, previous: SegmentResource?, duration: Long?): SegmentResource {
        if (text == null) return SegmentResource(url, durationMs = duration)
        val parts = text.split('@')
        require(parts.size in 1..2) { "Invalid HLS byte range" }
        val length = parts[0].toLongOrNull()
        require(length != null && length > 0) { "Invalid HLS byte range length" }
        val start = if (parts.size == 2) parts[1].toLongOrNull() else {
            require(previous?.url == url && previous.rangeStart != null && previous.rangeLength != null) { "Implicit range requires a preceding range on the same resource" }
            previous.rangeStart + previous.rangeLength
        }
        require(start != null && start >= 0 && start <= Long.MAX_VALUE - length) { "Invalid HLS byte range offset" }
        return SegmentResource(url, start, length, duration)
    }

    internal fun attributes(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            val equals = text.indexOf('=', cursor)
            require(equals > cursor) { "Malformed HLS attribute" }
            val name = text.substring(cursor, equals).trim()
            cursor = equals + 1
            val value: String
            if (cursor < text.length && text[cursor] == '"') {
                val end = text.indexOf('"', ++cursor)
                require(end >= cursor) { "Unterminated HLS attribute" }
                value = text.substring(cursor, end); cursor = end + 1
            } else {
                val end = text.indexOf(',', cursor).takeIf { it >= 0 } ?: text.length
                value = text.substring(cursor, end).trim(); cursor = end
            }
            require(name.isNotEmpty() && value.isNotEmpty() && values.put(name, value) == null) { "Empty or duplicate HLS attribute" }
            if (cursor < text.length) { require(text[cursor] == ',') { "Malformed HLS attribute separator" }; cursor++ }
        }
        return values
    }
}
