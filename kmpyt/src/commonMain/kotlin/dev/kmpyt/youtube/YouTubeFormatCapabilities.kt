package dev.kmpyt.youtube

import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.SourcePlaybackRequest

/** Early compatibility checks; native inspection still validates the received container and tracks. */
object YouTubeFormatCapabilities {
    /** Largest representable byte count; no application policy cap is applied to whole media. */
    const val MAX_TRANSFER_BYTES: Long = Long.MAX_VALUE

    /** Source/identity checks shared by playback and saving; they do not imply MP4 mux support. */
    fun pairingProblem(primary: Representation, audio: Representation?): String? {
        val tracks = listOfNotNull(primary, audio)
        tracks.firstOrNull { !it.selectable }?.let { return it.unavailableReason ?: "This format has no usable source resource." }
        if (audio != null && (primary.role != TrackRole.VIDEO || audio.role != TrackRole.AUDIO))
            return "Choose a video-only format and an audio-only format."
        if (audio != null && primary.audioGroupId != null && primary.audioGroupId != audio.audioGroupId)
            return "The audio track belongs to a different video variant."
        if (tracks.any { it.transport == TransportKind.SABR }) {
            val source = primary.sabr ?: return "SABR audio and video must use the same inspected source."
            if (tracks.any { it.transport != TransportKind.SABR || it.sabr?.let { candidate ->
                    candidate.videoId != source.videoId || candidate.endpoint != source.endpoint ||
                        candidate.configuration != source.configuration || candidate.clientInfo != source.clientInfo
                } != false }) return "SABR audio and video must come from the same player response."
            if (audio?.sabr?.formatId == source.formatId) return "Choose distinct video and audio source tracks."
        }
        return budgetProblem(tracks)
    }

    fun downloadProblem(primary: Representation, audio: Representation?, platformName: String): String? {
        pairingProblem(primary, audio)?.let { return it }
        val tracks = listOfNotNull(primary, audio)
        if (isAndroid(platformName) || platformName.equals("iOS", ignoreCase = true)) {
            val platform = if (isAndroid(platformName)) "Android" else "iOS"
            val remux = audio != null || primary.segments.isNotEmpty() || primary.transport in setOf(TransportKind.HLS, TransportKind.DASH)
            if (remux) tracks.forEach { track ->
                // Unknown manifest codecs are checked again after resolution and by MediaExtractor.
                // A known incompatible codec must never consume the full download before failing.
                val unsupported = codecs(track).firstOrNull {
                    if (platform == "iOS") !iosMp4Codec(it) else !androidMp4Codec(it)
                }
                if (unsupported != null) return "$platform cannot combine this format into MP4. Choose AVC or HEVC video with AAC audio."
                if (track.mimeType.substringBefore(';').lowercase().contains("webm"))
                    return "$platform cannot combine WebM tracks into MP4. Choose AVC or HEVC video with AAC audio."
            }
        }
        return null
    }

    fun playbackProblem(primary: Representation, audio: Representation?, platformName: String): String? {
        if (!isAndroid(platformName)) return "Source playback is available on Android. Download this format to play the saved file."
        // Source playback keeps tracks separate; it does not inherit the MP4 muxer's codec restriction.
        pairingProblem(primary, audio)?.let { return it }
        val tracks = listOfNotNull(primary, audio)
        if (!(tracks.all { it.transport == TransportKind.SABR } || tracks.all { it.transport == TransportKind.PROGRESSIVE }) ||
            tracks.any { it.segments.isNotEmpty() || it.initialization != null })
            return "Source playback supports static SABR or progressive media. Download this format to play the saved file."
        return null
    }

    /** Checked lower bound from declared sizes/ranges; callers may explicitly request a smaller budget. */
    fun budgetProblem(tracks: List<Representation>, maximumBytes: Long = MAX_TRANSFER_BYTES): String? {
        if (maximumBytes <= 0) return "The media size limit is invalid."
        var total = 0L
        for (track in tracks) {
            val declared = track.sizeBytes
            if (declared != null && declared <= 0) return "The source reports an invalid media size."
            var ranges = 0L
            for (resource in listOfNotNull(track.initialization) + track.segments) {
                val bytes = resource.rangeLength ?: continue
                if (bytes <= 0) return "The source reports an invalid media range."
                if (bytes > maximumBytes - ranges) return sizeProblem(maximumBytes)
                ranges += bytes
            }
            val minimum = maxOf(declared ?: 0L, ranges)
            if (minimum > maximumBytes - total) return sizeProblem(maximumBytes)
            total += minimum
        }
        return null
    }

    private fun sizeProblem(maximumBytes: Long) = if (maximumBytes == Long.MAX_VALUE)
        "The selected media size exceeds the supported byte-count range."
    else "The selected media exceeds the configured byte budget."

    private fun isAndroid(platformName: String) = platformName.contains("android", ignoreCase = true)
    private fun codecs(track: Representation): List<String> {
        val declared = track.codecs ?: Regex("codecs\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(track.mimeType)?.groupValues?.get(1)
        return declared.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
    private fun androidMp4Codec(codec: String) = codec.startsWith("avc1") || codec.startsWith("avc3") ||
        codec.startsWith("hvc1") || codec.startsWith("hev1") || codec.startsWith("mp4a") ||
        codec.startsWith("mp4v") || codec in setOf("h264", "h265", "hevc", "aac", "samr", "sawb", "s263")
    private fun iosMp4Codec(codec: String) = codec.startsWith("avc1") || codec.startsWith("avc3") ||
        codec.startsWith("hvc1") || codec.startsWith("hev1") || codec.startsWith("mp4a") ||
        codec in setOf("h264", "h265", "hevc", "aac")
}

/** IDs always reference the inspected catalogue; this selector never creates a quality or resource. */
data class YouTubeFormatSelection(val primaryId: String, val audioId: String? = null) {
    companion object {
        fun choose(media: ExtractedMedia, platformName: String, maxHeight: Int? = 1080,
            audioOnly: Boolean = false, preferredPrimaryId: String? = null,
            preferredAudioId: String? = null): YouTubeFormatSelection? {
            require(maxHeight == null || maxHeight > 0) { "Choose a positive video height." }
            val audio = media.representations.filter { it.role == TrackRole.AUDIO && it.selectable }
                .sortedWith(compareByDescending<Representation> { it.id == preferredAudioId }
                    .thenByDescending { it.audioIsDefault == true }
                    .thenBy { it.isDrc == true }
                    .thenByDescending { it.transport == TransportKind.PROGRESSIVE }
                    .thenByDescending { it.bitrate ?: 0L })
            if (audioOnly) return audio.firstOrNull {
                YouTubeFormatCapabilities.downloadProblem(it, null, platformName) == null
            }?.let { YouTubeFormatSelection(it.id) }
            val video = media.representations.filter { it.selectable && it.role != TrackRole.AUDIO &&
                (maxHeight == null || it.height?.let { height -> height <= maxHeight } == true) }
                .sortedWith(compareByDescending<Representation> { it.id == preferredPrimaryId }
                    .thenByDescending { it.height ?: 0 }
                    .thenByDescending { it.frameRate ?: 0 }
                    .thenByDescending { it.transport == TransportKind.PROGRESSIVE }
                    .thenByDescending { it.bitrate ?: 0L })
            for (primary in video) {
                if (primary.role == TrackRole.MUXED && YouTubeFormatCapabilities.downloadProblem(primary, null, platformName) == null)
                    return YouTubeFormatSelection(primary.id)
                if (primary.role == TrackRole.VIDEO) audio.firstOrNull {
                    YouTubeFormatCapabilities.downloadProblem(primary, it, platformName) == null
                }?.let { return YouTubeFormatSelection(primary.id, it.id) }
            }
            return null
        }

        /** A separately reviewable alternative, never a mutation of the user's selected tracks. */
        fun equivalentProgressiveDownload(media: ExtractedMedia, selection: YouTubeFormatSelection,
            platformName: String): YouTubeFormatSelection? {
            val primary = media.representations.singleOrNull { it.id == selection.primaryId } ?: return null
            val replacement = media.representations.firstOrNull { SourcePlaybackRequest.sameProgressiveFormat(primary, it) } ?: return null
            val audio = selection.audioId?.let { id ->
                val selected = media.representations.singleOrNull { it.id == id } ?: return null
                media.representations.firstOrNull { SourcePlaybackRequest.sameProgressiveFormat(selected, it) } ?: return null
            }
            if (YouTubeFormatCapabilities.downloadProblem(replacement, audio, platformName) != null) return null
            return YouTubeFormatSelection(replacement.id, audio?.id)
        }

        /** The inspector offers this choice for review; it never changes or starts the current selection. */
        fun recommendedDownload(media: ExtractedMedia, platformName: String,
            selected: YouTubeFormatSelection? = null, maxHeight: Int? = 1080): YouTubeFormatSelection? {
            val current = selected?.let { selection ->
                media.representations.singleOrNull { it.id == selection.primaryId } ?: return null
            }
            if (current?.role == TrackRole.AUDIO) return null
            val ceiling = current?.height ?: maxHeight
            val equivalent = selected?.let { equivalentProgressiveDownload(media, it, platformName) }
            if (current == null || current.transport == TransportKind.SABR) {
                val progressive = media.copy(representations = media.representations.filter { it.transport == TransportKind.PROGRESSIVE })
                choose(progressive, platformName, maxHeight = ceiling,
                    preferredPrimaryId = equivalent?.primaryId, preferredAudioId = equivalent?.audioId ?: selected?.audioId)
                    ?.let { return it }
            }
            return choose(media, platformName, maxHeight = ceiling,
                preferredPrimaryId = current?.id, preferredAudioId = selected?.audioId)
        }

        /** Compatible download audio wins; playback-only choices remain explicitly available. */
        fun audioChoices(media: ExtractedMedia, video: Representation, platformName: String): List<YouTubeAudioChoice> {
            if (video.role != TrackRole.VIDEO || video !in media.representations) return emptyList()
            return media.representations.filter { it.role == TrackRole.AUDIO &&
                YouTubeFormatCapabilities.pairingProblem(video, it) == null }
                .map { YouTubeAudioChoice(it,
                    YouTubeFormatCapabilities.downloadProblem(video, it, platformName),
                    YouTubeFormatCapabilities.playbackProblem(video, it, platformName)) }
                .sortedWith(compareBy<YouTubeAudioChoice> { it.downloadProblem != null }
                    .thenBy { it.playbackProblem != null }
                    .thenByDescending { it.audio.audioIsDefault == true }
                    .thenBy { it.audio.isDrc == true }
                    .thenByDescending { it.audio.transport == TransportKind.PROGRESSIVE }
                    .thenByDescending { it.audio.bitrate ?: 0L })
        }
    }
}

data class YouTubeAudioChoice(val audio: Representation, val downloadProblem: String?, val playbackProblem: String?)
