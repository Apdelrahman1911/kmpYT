package dev.kmpyt.youtube

import dev.kmpyt.MediaKind
import dev.kmpyt.extraction.*
import kotlin.test.*

class YouTubeSourcePlaybackSelectionTest {
    private val primary = Representation("18", "https://rr1.googlevideo.com/videoplayback", TrackRole.MUXED,
        mimeType = "video/mp4", codecs = "avc1.42001E, mp4a.40.2", sizeBytes = 1024)
    private val media = ExtractedMedia("ABCDEFGHIJK", ProviderId.YOUTUBE, "Actual title",
        "https://youtube.com/watch?v=ABCDEFGHIJK", listOf(primary))
    private val report = ExtractionReport(media.pageUrl, ProviderId.YOUTUBE, listOf(media), emptyList())

    @Test fun progressiveSourceCanPlayWithoutCreatingLibraryMetadata() {
        val request = SourcePlaybackRequest.fromReport(report, media.id, primary.id)
        request.requireSupportedSource()
        assertEquals(MediaKind.VIDEO, request.metadata.kind)
        assertEquals(primary, request.plan.primary)
        assertNull(YouTubeFormatCapabilities.playbackProblem(primary, null, "Android"))
        assertFailsWith<IllegalArgumentException> { request.requireStaticSabr() }
    }

    @Test fun separateProgressiveTracksCanUseNativeMergingWithoutMp4Mux() {
        val video = primary.copy(id = "248", role = TrackRole.VIDEO, mimeType = "video/webm", codecs = "vp9")
        val audio = primary.copy(id = "251", role = TrackRole.AUDIO, mimeType = "audio/webm", codecs = "opus")
        val actual = report.copy(media = listOf(media.copy(representations = listOf(video, audio))))
        val request = SourcePlaybackRequest.fromReport(actual, media.id, video.id, audio.id)
        assertNull(YouTubeFormatCapabilities.playbackProblem(video, audio, "Android"))
        assertEquals(audio.id, request.plan.audio?.id)
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video, audio, "Android"))
    }

    @Test fun rejectsManifestPlaybackAndMismatchedTrackRoles() {
        val hls = primary.copy(transport = TransportKind.HLS)
        val actual = report.copy(media = listOf(media.copy(representations = listOf(hls))))
        assertFailsWith<IllegalArgumentException> { SourcePlaybackRequest.fromReport(actual, media.id, hls.id) }
        assertNotNull(YouTubeFormatCapabilities.playbackProblem(hls, null, "Android"))
        val request = SourcePlaybackRequest.fromReport(report, media.id, primary.id)
        assertFailsWith<IllegalArgumentException> { request.copy(plan = request.plan.copy(audio = primary)).requireSupportedSource() }
    }

    @Test fun acceptsLargePlaybackButRejectsImportedHeadersAndAggregateOverflow() {
        val request = SourcePlaybackRequest.fromReport(report, media.id, primary.id)
        assertFailsWith<IllegalArgumentException> {
            request.copy(plan = request.plan.copy(requestHeaders = mapOf("X-Custom" to "value"))).requireSupportedSource()
        }
        request.copy(plan = request.plan.copy(primary = primary.copy(sizeBytes = 513L * 1024 * 1024))).requireSupportedSource()
        request.copy(plan = request.plan.copy(primary = primary.copy(sizeBytes = Long.MAX_VALUE))).requireSupportedSource()
        val video = primary.copy(role = TrackRole.VIDEO, codecs = "avc1.42001E", sizeBytes = Long.MAX_VALUE)
        val audio = primary.copy(id = "140", role = TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 1)
        assertFailsWith<IllegalArgumentException> {
            request.copy(plan = request.plan.copy(primary = video, audio = audio)).requireSupportedSource()
        }
    }

    @Test fun recoveryUsesOnlyActualEquivalentProgressiveTracksFromTheSameReport() {
        val selected = primary.copy(id = "137", role = TrackRole.VIDEO, transport = TransportKind.SABR,
            width = 1920, height = 1080, frameRate = 30, codecs = "avc1.640028",
            sabr = YouTubeSabrResource(media.id, primary.url!!, "AQID", SabrFormatId(137, 101uL, "source"), SabrClientInfo(1, "source-1")))
        val audio = selected.copy(id = "140", role = TrackRole.AUDIO, width = null, height = null, frameRate = null,
            mimeType = "audio/mp4", codecs = "mp4a.40.2", audioLanguage = "en", audioTrackId = "en.original",
            sabr = selected.sabr!!.copy(formatId = SabrFormatId(140, 102uL, "audio")))
        fun progressive(track: Representation) = track.copy(id = "${track.id}#progressive", transport = TransportKind.PROGRESSIVE,
            sabr = null, url = "${track.url}?itag=${track.id}")
        val videoAlternative = progressive(selected)
        val audioAlternative = progressive(audio)
        fun request(vararg alternatives: Representation) = SourcePlaybackRequest.fromReport(report.copy(media = listOf(
            media.copy(representations = listOf(selected, audio) + alternatives))), media.id, selected.id, audio.id)

        assertNull(request().progressiveFallback)
        assertNull(request(videoAlternative).progressiveFallback)
        assertNull(request(videoAlternative.copy(height = 720), audioAlternative).progressiveFallback)
        assertNull(request(videoAlternative, audioAlternative.copy(audioLanguage = "fr")).progressiveFallback)
        assertNull(request(videoAlternative.copy(codecs = "av01.0.08M.08"), audioAlternative).progressiveFallback)
        val actual = request(videoAlternative, audioAlternative)
        assertEquals(videoAlternative, actual.progressiveFallback!!.primary)
        assertEquals(audioAlternative, actual.progressiveFallback!!.audio)
        actual.requireSupportedSource()
        assertFailsWith<IllegalArgumentException> {
            actual.copy(progressiveFallback = actual.progressiveFallback!!.copy(mediaId = "KJIHGFEDCBA")).requireSupportedSource()
        }
        assertFailsWith<IllegalArgumentException> {
            actual.copy(progressiveFallback = actual.progressiveFallback!!.copy(requestHeaders = mapOf("Cookie" to "imported"))).requireSupportedSource()
        }
    }
}
