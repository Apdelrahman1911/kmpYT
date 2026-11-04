package dev.kmpyt.youtube

import dev.kmpyt.extraction.*
import kotlin.test.*

class YouTubeFormatCapabilitiesTest {
    private val video = Representation("137", "https://rr1.googlevideo.com/video", TrackRole.VIDEO,
        mimeType = "video/mp4", codecs = "avc1.640028", height = 1080, sizeBytes = 10_000)
    private val audio = Representation("140", "https://rr1.googlevideo.com/audio", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 2_000, audioIsDefault = true)
    private fun media(vararg formats: Representation) = ExtractedMedia("ABCDEFGHIJK", ProviderId.YOUTUBE,
        "Actual video", "https://youtube.com/watch?v=ABCDEFGHIJK", formats.toList())

    @Test fun rejectsAndroidWebmPairBeforeTransferButRetainsStandaloneAudio() {
        val opus = audio.copy(id = "251", mimeType = "audio/webm", codecs = "opus")
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video, opus, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(opus, null, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(video, opus, "Desktop"))
    }

    @Test fun knownIncompatibleVideoCodecIsNotHiddenByMp4Mime() {
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video.copy(codecs = "av01.0.08M.08"), audio, "Android"))
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video.copy(codecs = "vp09.00.40.08"), audio, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(video.copy(codecs = "hvc1.1.6.L93.B0"), audio, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(video, audio, "Android 15"))
    }

    @Test fun considersCombinedBytesAndAvoidsOverflow() {
        val limit = YouTubeFormatCapabilities.MAX_TRANSFER_BYTES
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video.copy(sizeBytes = limit), audio, "Desktop"))
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(video.copy(sizeBytes = Long.MAX_VALUE), audio, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(video.copy(sizeBytes = limit - 2_000), audio, "Android"))
    }

    @Test fun wholeMediaAbove512MiBAndOneTiBAreNotApplicationPolicyFailures() {
        val large = video.copy(sizeBytes = 2L * 1024 * 1024 * 1024 * 1024)
        val largeAudio = audio.copy(sizeBytes = 700L * 1024 * 1024)
        assertEquals(Long.MAX_VALUE, YouTubeFormatCapabilities.MAX_TRANSFER_BYTES)
        assertNull(YouTubeFormatCapabilities.downloadProblem(large, largeAudio, "Android"))
        assertNull(YouTubeFormatCapabilities.playbackProblem(large, largeAudio, "Android"))
        assertEquals(YouTubeFormatSelection("137", "140"), YouTubeFormatSelection.choose(media(large, largeAudio), "Android"))
    }

    @Test fun anExplicitCallerBudgetStillAppliesWithoutOverflowOrDoubleCounting() {
        val budget = 512L * 1024 * 1024
        val small = video.copy(sizeBytes = budget - 2_000)
        assertNull(YouTubeFormatCapabilities.budgetProblem(listOf(small, audio), budget))
        assertNotNull(YouTubeFormatCapabilities.budgetProblem(listOf(small.copy(sizeBytes = budget - 1_999), audio), budget))
        assertNotNull(YouTubeFormatCapabilities.budgetProblem(listOf(video.copy(sizeBytes = Long.MAX_VALUE), audio)))
        assertNull(YouTubeFormatCapabilities.budgetProblem(listOf(video.copy(sizeBytes = Long.MAX_VALUE))))
    }

    @Test fun includesInitializationAndKnownSegmentRangesWithoutDoubleCounting() {
        val half = YouTubeFormatCapabilities.MAX_TRANSFER_BYTES / 2
        val segmented = video.copy(transport = TransportKind.DASH, sizeBytes = null,
            initialization = SegmentResource("https://example.com/init", 0, half),
            segments = listOf(SegmentResource("https://example.com/media", half, half)))
        assertNotNull(YouTubeFormatCapabilities.downloadProblem(segmented, audio, "Android"))
        assertNull(YouTubeFormatCapabilities.downloadProblem(segmented.copy(sizeBytes = half * 2), null, "Android"))
    }

    @Test fun bestSelectionUsesCompatibleAudioAndRespectsQualityCeiling() {
        val high = video.copy(id = "2160", height = 2160)
        val vp9 = video.copy(id = "248", mimeType = "video/webm", codecs = "vp09.00.40.08", bitrate = 10_000)
        val opus = audio.copy(id = "251", mimeType = "audio/webm", codecs = "opus", bitrate = 200_000)
        val selected = YouTubeFormatSelection.choose(media(high, vp9, video, opus, audio), "Android", 1080)
        assertEquals(YouTubeFormatSelection("137", "140"), selected)
        assertEquals(YouTubeFormatSelection("248", "251"),
            YouTubeFormatSelection.choose(media(vp9, video, opus, audio), "Desktop", 1080))
    }

    @Test fun selectionDoesNotUseUnavailableResourcesOrInventAudio() {
        assertNull(YouTubeFormatSelection.choose(media(video), "Android"))
        val missing = video.copy(url = null)
        val muxed = video.copy(id = "18", role = TrackRole.MUXED, height = 360, codecs = "avc1.42001E, mp4a.40.2")
        assertEquals(YouTubeFormatSelection("18"), YouTubeFormatSelection.choose(media(missing, muxed), "Android"))
    }

    @Test fun defaultAudioLanguageWinsAndVariantGroupsRemainBound() {
        val alternate = audio.copy(id = "alternate", audioIsDefault = false, bitrate = 400_000)
        assertEquals(YouTubeFormatSelection("140"),
            YouTubeFormatSelection.choose(media(alternate, audio), "Android", audioOnly = true))
        assertNull(YouTubeFormatSelection.choose(media(video.copy(audioGroupId = "en"), audio.copy(audioGroupId = "fr")), "Android"))
    }

    @Test fun unrepresentableCombinedSizeFallsBackToActualSmallerRepresentation() {
        val small = video.copy(id = "136", height = 720)
        val large = video.copy(sizeBytes = YouTubeFormatCapabilities.MAX_TRANSFER_BYTES)
        assertEquals(YouTubeFormatSelection("136", "140"),
            YouTubeFormatSelection.choose(media(large, small, audio), "Android"))
    }

    @Test fun unknownHeightCannotSatisfyAnExplicitResolutionCeiling() {
        val unknown = video.copy(id = "unknown", height = null, role = TrackRole.MUXED)
        assertNull(YouTubeFormatSelection.choose(media(unknown), "Android", maxHeight = 720))
        assertEquals(YouTubeFormatSelection("unknown"),
            YouTubeFormatSelection.choose(media(unknown), "Android", maxHeight = null))
        assertEquals(YouTubeFormatSelection("140"),
            YouTubeFormatSelection.choose(media(audio), "Android", maxHeight = 720, audioOnly = true))
    }

    @Test fun pairingChoicesPrioritizeCombinedDownloadsButKeepPlaybackOnlyAudioExplicit() {
        val opus = audio.copy(id = "251", mimeType = "audio/webm", codecs = "opus", bitrate = 256_000)
        val choices = YouTubeFormatSelection.audioChoices(media(video, opus, audio), video, "Android")
        assertEquals(listOf("140", "251"), choices.map { it.audio.id })
        assertNull(choices[0].downloadProblem)
        assertNull(choices[0].playbackProblem)
        assertNotNull(choices[1].downloadProblem)
        assertNull(choices[1].playbackProblem)
        assertNull(YouTubeFormatCapabilities.downloadProblem(opus, null, "Android"))
    }

    @Test fun replacementNamesActualLowerQualityAndNeverRewritesTheChosenFormat() {
        val vp9 = video.copy(id = "248", mimeType = "video/webm", codecs = "vp09.00.40.08", height = 1080)
        val avc720 = video.copy(id = "136", height = 720)
        val avc2160 = video.copy(id = "313", height = 2160)
        val catalogue = media(vp9, avc720, avc2160, audio)
        val replacement = YouTubeFormatSelection.choose(catalogue, "Android", maxHeight = vp9.height,
            preferredPrimaryId = vp9.id)
        assertEquals(YouTubeFormatSelection("136", "140"), replacement)
        assertEquals(720, catalogue.representations.single { it.id == replacement!!.primaryId }.height)
        assertEquals(1080, vp9.height)
        assertEquals("vp09.00.40.08", vp9.codecs)
    }

    @Test fun preferredCompatibleVideoAndAudioRemainSelectedWithinTheirQualityCeiling() {
        val avc720 = video.copy(id = "136", height = 720)
        val translated = audio.copy(id = "141-fr", audioIsDefault = false, audioLanguage = "fr")
        assertEquals(YouTubeFormatSelection("136", "141-fr"),
            YouTubeFormatSelection.choose(media(video, avc720, audio, translated), "Android", maxHeight = 1080,
                preferredPrimaryId = avc720.id, preferredAudioId = translated.id))
    }

    @Test fun playbackOnlyVideoPairDoesNotBecomeACombinedDownloadRecommendation() {
        val vp9 = video.copy(id = "248", mimeType = "video/webm", codecs = "vp9")
        val opus = audio.copy(id = "251", mimeType = "audio/webm", codecs = "opus")
        val catalogue = media(vp9, opus)
        assertNull(YouTubeFormatSelection.choose(catalogue, "Android"))
        val option = YouTubeFormatSelection.audioChoices(catalogue, vp9, "Android").single()
        assertNull(option.playbackProblem)
        assertNotNull(option.downloadProblem)
        assertNull(YouTubeFormatCapabilities.downloadProblem(vp9, null, "Android"))
    }

    @Test fun pairingChoicesExcludeForeignGroupsAndFormatsAbsentFromTheActualCatalogue() {
        val grouped = video.copy(audioGroupId = "en")
        assertTrue(YouTubeFormatSelection.audioChoices(media(grouped, audio.copy(audioGroupId = "fr")), grouped, "Android").isEmpty())
        assertTrue(YouTubeFormatSelection.audioChoices(media(audio), video, "Android").isEmpty())
    }

    @Test fun actualProgressivePairWinsEquivalentQualityDespiteHigherSabrBitrate() {
        val sabrVideo = asSabr(video, 137).copy(bitrate = 9_000_000)
        val sabrAudio = asSabr(audio, 140).copy(bitrate = 256_000)
        val catalogue = media(sabrVideo, sabrAudio, video, audio)
        assertEquals(YouTubeFormatSelection("137", "140"), YouTubeFormatSelection.choose(catalogue, "Android"))
        assertEquals(YouTubeFormatSelection(sabrVideo.id, sabrAudio.id),
            YouTubeFormatSelection.choose(catalogue, "Android", preferredPrimaryId = sabrVideo.id, preferredAudioId = sabrAudio.id))
        assertEquals(YouTubeFormatSelection("137", "140"), YouTubeFormatSelection.equivalentProgressiveDownload(catalogue,
            YouTubeFormatSelection(sabrVideo.id, sabrAudio.id), "Android"))
    }

    @Test fun progressiveRecommendationRequiresTheWholeSelectedPairAndSameAudioLanguage() {
        val sabrVideo = asSabr(video, 137)
        val sabrAudio = asSabr(audio, 140).copy(audioLanguage = "fr")
        val selected = YouTubeFormatSelection(sabrVideo.id, sabrAudio.id)
        assertNull(YouTubeFormatSelection.equivalentProgressiveDownload(media(sabrVideo, sabrAudio, video), selected, "Android"))
        assertNull(YouTubeFormatSelection.equivalentProgressiveDownload(media(sabrVideo, sabrAudio, video, audio), selected, "Android"))
        assertEquals(YouTubeFormatSelection("137", "140"), YouTubeFormatSelection.equivalentProgressiveDownload(
            media(sabrVideo, sabrAudio, video, audio.copy(audioLanguage = "fr")), selected, "Android"))
    }

    @Test fun inspectorOffersLowerProgressiveQualityEvenWhenSelectedSabrPairCanBeMuxed() {
        val sabrVideo = asSabr(video, 137)
        val sabrAudio = asSabr(audio, 140)
        val direct720 = video.copy(id = "136", height = 720)
        val selected = YouTubeFormatSelection(sabrVideo.id, sabrAudio.id)
        val catalogue = media(sabrVideo, sabrAudio, direct720, audio)
        assertNull(YouTubeFormatCapabilities.downloadProblem(sabrVideo, sabrAudio, "Android"))
        assertNull(YouTubeFormatSelection.equivalentProgressiveDownload(catalogue, selected, "Android"))
        assertEquals(YouTubeFormatSelection("136", "140"), YouTubeFormatSelection.recommendedDownload(catalogue, "Android", selected))
        assertEquals(YouTubeFormatSelection("136", "140"), YouTubeFormatSelection.recommendedDownload(catalogue, "Android"))
        assertEquals(YouTubeFormatSelection(sabrVideo.id, sabrAudio.id), selected)
    }

    @Test fun lowerProgressiveRecommendationStillRequiresSoundAndRespectsSelectedHeight() {
        val sabrVideo = asSabr(video.copy(height = 720), 137)
        val sabrAudio = asSabr(audio, 140)
        val selected = YouTubeFormatSelection(sabrVideo.id, sabrAudio.id)
        assertEquals(selected, YouTubeFormatSelection.recommendedDownload(media(sabrVideo, sabrAudio, video, audio), "Android", selected))
        assertEquals(selected, YouTubeFormatSelection.recommendedDownload(media(sabrVideo, sabrAudio, video.copy(height = 360)), "Android", selected))
    }

    private fun asSabr(track: Representation, itag: Int) = track.copy(id = "${track.id}#sabr", transport = TransportKind.SABR,
        url = "https://rr1.googlevideo.com/sabr", sabr = YouTubeSabrResource("ABCDEFGHIJK", "https://rr1.googlevideo.com/sabr",
            "AQID", SabrFormatId(itag, 1uL, null), SabrClientInfo(1, "authored-client")))
}
