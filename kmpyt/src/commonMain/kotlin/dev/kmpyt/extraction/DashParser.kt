package dev.kmpyt.extraction

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Static single-period DASH: progressive BaseURL, SegmentList and bounded SegmentTemplate. */
object DashParser : ManifestParser {
    private const val MAX_SEGMENTS = 4096

    override fun parse(url: String, body: String): ManifestResult = try {
        val mpd = ManifestXml.parse(body)
        require(mpd.name == "MPD") { "Not a DASH MPD" }
        require(mpd.attributes["type"].orEmpty() != "dynamic") { "Live DASH is unsupported" }
        require(mpd.attributes["type"] in setOf(null, "static")) { "Unknown DASH presentation type" }
        fun inspect(node: ManifestNode) {
            require(node.name != "ContentProtection") { "Encrypted DASH is unsupported" }
            node.children.forEach(::inspect)
        }
        inspect(mpd)
        val periods = mpd.all("Period")
        require(periods.size == 1) { "Multi-period DASH is unsupported" }
        val period = periods.single()
        val duration = period.attributes["duration"]?.let(::isoDuration)
            ?: mpd.attributes["mediaPresentationDuration"]?.let(::isoDuration)
        val result = mutableListOf<Representation>()
        period.all("AdaptationSet").forEach { adaptation ->
            adaptation.all("Representation").forEach { representation ->
                require(result.size < 128) { "DASH exceeds the representation limit" }
                val nodes = listOf(mpd, period, adaptation, representation)
                var base = url
                var hasBase = false
                nodes.forEach { node -> node.child("BaseURL")?.text?.trim()?.takeIf(String::isNotBlank)?.let {
                    base = EmbeddedData.absolute(base, it); hasBase = true
                } }
                val fields = adaptation.attributes + representation.attributes
                val mime = fields["mimeType"] ?: when (fields["contentType"]) { "audio" -> "audio/mp4"; "video" -> "video/mp4"; else -> "application/octet-stream" }
                val role = when { mime.startsWith("audio/") -> TrackRole.AUDIO; mime.startsWith("video/") -> TrackRole.VIDEO; else -> errorArgument("Unknown DASH track type") }
                val id = fields["id"] ?: "dash-${result.size}"
                val bandwidth = fields["bandwidth"]?.let { positive(it, "bandwidth") }
                val templates = nodes.mapNotNull { it.child("SegmentTemplate") }
                val lists = nodes.mapNotNull { it.child("SegmentList") }
                require(templates.isEmpty() || lists.isEmpty()) { "Mixed DASH segmentation is unsupported" }
                val resources = when {
                    templates.isNotEmpty() -> template(base, templates, id, bandwidth, duration)
                    lists.isNotEmpty() -> segmentList(base, lists, duration)
                    else -> {
                        require(hasBase && base != url) { "DASH has no media resource" }
                        // SegmentBase/indexRange can be served as one complete, validated local file.
                        Resources(emptyList(), null)
                    }
                }
                val transport = if (resources.segments.isEmpty()) TransportKind.PROGRESSIVE else TransportKind.DASH
                result += Representation(id, base, role, transport, mime, fields["codecs"],
                    fields["width"]?.toIntOrNull(), fields["height"]?.toIntOrNull(), bandwidth,
                    durationMs = duration, segments = resources.segments, initialization = resources.initialization,
                    audioLanguage = fields["lang"], manifestUrl = url)
            }
        }
        require(result.isNotEmpty()) { "DASH has no supported representations" }
        ManifestResult(result)
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "Invalid DASH manifest"
        ManifestResult(emptyList(), listOf(ExtractionDiagnostic("dash", when {
            message.contains("Encrypted") -> "encrypted"
            message.contains("Live") -> "live"
            message.contains("unsupported") -> "unsupported"
            else -> "malformed"
        }, message)))
    }

    private data class Resources(val segments: List<SegmentResource>, val initialization: SegmentResource?)
    private data class Timing(val time: Long, val duration: Long)

    private fun template(base: String, templates: List<ManifestNode>, id: String, bandwidth: Long?, durationMs: Long?): Resources {
        val values = templates.fold(emptyMap<String, String>()) { acc, node -> acc + node.attributes }
        val media = requireNotNull(values["media"]) { "DASH template has no media pattern" }
        val scale = positive(values["timescale"] ?: "1", "timescale")
        require(scale <= 1_000_000_000) { "DASH timescale exceeds the limit" }
        val start = nonnegative(values["startNumber"] ?: "1", "startNumber")
        val offset = nonnegative(values["presentationTimeOffset"] ?: "0", "presentationTimeOffset")
        val timeline = templates.lastOrNull { it.child("SegmentTimeline") != null }?.child("SegmentTimeline")
        val timings = if (timeline != null) timeline(timeline, scale, durationMs, offset) else {
            val span = positive(requireNotNull(values["duration"]) { "Unbounded DASH template is unsupported" }, "segment duration")
            val end = durationUnits(requireNotNull(durationMs) { "Unbounded DASH duration is unsupported" }, scale)
            val count = ceil(end.toDouble() / span).toLong()
            require(count in 1..MAX_SEGMENTS) { "DASH exceeds the segment limit" }
            (0 until count.toInt()).map { index -> Timing(multiply(index.toLong(), span), span) }
        }
        val segments = timings.mapIndexed { index, timing ->
            val reference = substitute(media, id, bandwidth, add(start, index.toLong()), timing.time)
            SegmentResource(EmbeddedData.absolute(base, reference), durationMs = milliseconds(timing.duration, scale))
        }
        val initialization = values["initialization"]?.let { pattern ->
            require(!pattern.contains("\$Number") && !pattern.contains("\$Time")) { "Dynamic DASH initialization is unsupported" }
            SegmentResource(EmbeddedData.absolute(base, substitute(pattern, id, bandwidth, start, 0)))
        }
        require(initialization != null) { "Segmented DASH without initialization is unsupported" }
        return Resources(segments, initialization)
    }

    private fun segmentList(base: String, lists: List<ManifestNode>, durationMs: Long?): Resources {
        val values = lists.fold(emptyMap<String, String>()) { acc, node -> acc + node.attributes }
        val scale = positive(values["timescale"] ?: "1", "timescale")
        require(scale <= 1_000_000_000) { "DASH timescale exceeds the limit" }
        val list = lists.lastOrNull { it.all("SegmentURL").isNotEmpty() }
            ?: errorArgument("DASH SegmentList is empty")
        val urls = list.all("SegmentURL")
        require(urls.size in 1..MAX_SEGMENTS) { "DASH exceeds the segment limit" }
        val timelineNode = lists.lastOrNull { it.child("SegmentTimeline") != null }?.child("SegmentTimeline")
        val timings = timelineNode?.let { timeline(it, scale, durationMs, nonnegative(values["presentationTimeOffset"] ?: "0", "presentationTimeOffset")) }
        require(timings == null || timings.size == urls.size) { "DASH timeline and segment list differ" }
        val span = values["duration"]?.let { positive(it, "segment duration") }
        val segments = urls.mapIndexed { index, node ->
            val media = node.attributes["media"] ?: base
            val range = byteRange(node.attributes["mediaRange"])
            SegmentResource(EmbeddedData.absolute(base, media), range?.first, range?.second,
                (timings?.get(index)?.duration ?: span)?.let { milliseconds(it, scale) })
        }
        val init = lists.lastOrNull { it.child("Initialization") != null }?.child("Initialization")
            ?: errorArgument("Segmented DASH without initialization is unsupported")
        val range = byteRange(init.attributes["range"])
        val initialization = SegmentResource(EmbeddedData.absolute(base, init.attributes["sourceURL"] ?: base), range?.first, range?.second)
        return Resources(segments, initialization)
    }

    private fun timeline(node: ManifestNode, scale: Long, durationMs: Long?, offset: Long): List<Timing> {
        val entries = node.all("S")
        require(entries.isNotEmpty() && entries.size <= MAX_SEGMENTS) { "Empty or excessive DASH timeline" }
        val result = mutableListOf<Timing>()
        var time = 0L
        entries.forEachIndexed { index, entry ->
            val declared = entry.attributes["t"]?.let { nonnegative(it, "timeline time") }
            if (declared != null) {
                require(result.isEmpty() || declared == time) { "DASH timeline discontinuities are unsupported" }
                time = declared
            }
            val duration = positive(requireNotNull(entry.attributes["d"]) { "DASH timeline has no duration" }, "timeline duration")
            val repeats = entry.attributes["r"]?.let { requireNotNull(it.toLongOrNull()) { "Invalid DASH timeline repeat" } } ?: 0
            require(repeats >= -1 && duration <= durationUnits(604800000, scale)) { "Invalid DASH timeline repeat or duration" }
            val count = if (repeats == -1L) {
                val bound = entries.getOrNull(index + 1)?.attributes?.get("t")?.let { nonnegative(it, "timeline time") }
                    ?: durationMs?.let { add(durationUnits(it, scale), offset) }
                    ?: errorArgument("Unbounded DASH timeline is unsupported")
                require(bound > time) { "Invalid DASH timeline bound" }
                ceil((bound - time).toDouble() / duration).toLong()
            } else add(repeats, 1)
            require(count in 1..MAX_SEGMENTS && count <= MAX_SEGMENTS - result.size) { "DASH exceeds the segment limit" }
            repeat(count.toInt()) {
                result += Timing(time, duration)
                time = add(time, duration)
            }
        }
        return result
    }

    private fun substitute(pattern: String, id: String, bandwidth: Long?, number: Long, time: Long): String {
        val escaped = pattern.replace("\$\$", "\u0000")
        val replaced = Regex("\\$(RepresentationID|Bandwidth|Number|Time)(%0([1-9][0-9]?)d)?\\$").replace(escaped) { match ->
            val value = when (match.groupValues[1]) { "RepresentationID" -> id; "Bandwidth" -> requireNotNull(bandwidth) { "DASH template requires bandwidth" }.toString(); "Number" -> number.toString(); else -> time.toString() }
            val width = match.groupValues[3].takeIf(String::isNotEmpty)?.toInt()
            require(width == null || (width <= 20 && match.groupValues[1] != "RepresentationID")) { "Unsupported DASH template formatting" }
            if (width == null) value else value.padStart(width, '0')
        }
        require(!replaced.contains('$')) { "Unknown DASH template placeholder" }
        return replaced.replace('\u0000', '$')
    }

    private fun byteRange(text: String?): Pair<Long, Long>? {
        if (text == null) return null
        val pieces = text.split('-')
        require(pieces.size == 2) { "Invalid DASH byte range" }
        val start = nonnegative(pieces[0], "range start")
        val end = nonnegative(pieces[1], "range end")
        require(end >= start && end < Long.MAX_VALUE) { "Invalid DASH byte range bounds" }
        return start to (end - start + 1)
    }
    private fun isoDuration(text: String): Long {
        val match = Regex("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").matchEntire(text)
            ?: errorArgument("Unsupported DASH duration")
        val values = (1..4).map { match.groupValues[it].toDoubleOrNull() ?: 0.0 }
        val seconds = values[0] * 86400 + values[1] * 3600 + values[2] * 60 + values[3]
        require(seconds.isFinite() && seconds > 0 && seconds <= 604800) { "Invalid DASH duration" }
        return (seconds * 1000).roundToLong()
    }
    private fun durationUnits(milliseconds: Long, scale: Long): Long = ceil(milliseconds.toDouble() * scale / 1000).toLong()
    private fun milliseconds(units: Long, scale: Long): Long = (units.toDouble() * 1000 / scale).roundToLong()
    private fun positive(text: String, label: String): Long = nonnegative(text, label).also { require(it > 0) { "Invalid DASH $label" } }
    private fun nonnegative(text: String, label: String): Long = requireNotNull(text.toLongOrNull()?.takeIf { it >= 0 }) { "Invalid DASH $label" }
    private fun add(a: Long, b: Long): Long { require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b) { "DASH numeric overflow" }; return a + b }
    private fun multiply(a: Long, b: Long): Long { require(a >= 0 && b > 0 && a <= Long.MAX_VALUE / b) { "DASH numeric overflow" }; return a * b }
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
}
