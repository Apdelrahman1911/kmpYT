package dev.kmpyt.youtube

import dev.kmpyt.extraction.*
import kotlin.test.*

class YouTubeFormatCapabilitiesTest {
    @Test fun portablePlaybackPlanningIsAvailableAcrossNativePlatformsWithoutClaimingDeviceCodecSupport() {
        for (platform in listOf("Android", "JVM", "iOS")) {
            assertNull(YouTubeFormatCapabilities.playbackProblem(video, audio, platform))
            assertNotNull(YouTubeFormatCapabilities.playbackProblem(video.copy(transport = TransportKind.HLS), audio, platform))
        }
    }
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

    @Test fun sourceOriginalArabicWinsOverDefaultEnglishAutoDubForAudioAndVideo() {
        val dubbed = audio.copy(id = "140@en.dub", audioTrackId = "en.dub", audioDisplayName = "English (US)",
            audioIsAutoDubbed = true, bitrate = 256_000)
        val original = audio.copy(id = "140@ar.source", audioTrackId = "ar.source", audioDisplayName = "Arabic original",
            audioIsDefault = false, bitrate = 96_000)
        for (platform in listOf("Android", "iOS", "JVM")) {
            for (tracks in listOf(listOf(dubbed, original), listOf(original, dubbed))) {
                val catalogue = media(video, *tracks.toTypedArray())
                assertEquals(YouTubeFormatSelection(original.id),
                    YouTubeFormatSelection.choose(catalogue, platform, audioOnly = true))
                assertEquals(YouTubeFormatSelection(video.id, original.id), YouTubeFormatSelection.choose(catalogue, platform))
                assertEquals(original.id, YouTubeFormatSelection.audioChoices(catalogue, video, platform).first().audio.id)
                assertEquals("ABCDEFGHIJK", catalogue.id)
            }
        }
    }

    @Test fun originalPreferenceIsNotAnArabicOrUiLanguagePreference() {
        val arabicDub = audio.copy(id = "ar-dub", audioLanguage = "ar", audioDisplayName = "Arabic",
            audioIsAutoDubbed = true)
        val frenchOriginal = audio.copy(id = "fr-source", audioLanguage = "fr", audioDisplayName = "French (original)",
            audioIsDefault = false)
        assertEquals(YouTubeFormatSelection(frenchOriginal.id),
            YouTubeFormatSelection.choose(media(arabicDub, frenchOriginal), "iOS", audioOnly = true))
    }

    @Test fun absentOriginalEvidenceKeepsDefaultsWithoutGuessingFromLanguageTrackIdOrNonAutoFlag() {
        val unknown = audio.copy(id = "ar.4", audioTrackId = "ar.4", audioLanguage = "ar", audioIsDefault = false,
            audioIsAutoDubbed = false, audioDisplayName = "Arabic", bitrate = 512_000)
        val default = audio.copy(id = "en-default", audioLanguage = "en", audioIsAutoDubbed = true)
        assertTrue(YouTubeFormatSelection.originalAudioTracks(media(default, unknown)).isEmpty())
        assertEquals(YouTubeFormatSelection(default.id),
            YouTubeFormatSelection.choose(media(default, unknown), "Android", audioOnly = true))
        assertEquals(YouTubeFormatSelection(unknown.id), YouTubeFormatSelection.choose(
            media(default.copy(audioIsDefault = null), unknown), "Android", audioOnly = true))
    }

    @Test fun multipleDeclaredOriginalsRemainVisibleAndTiesAreDeterministic() {
        val arabic = audio.copy(id = "original-ar", audioLanguage = "ar", audioDisplayName = "Arabic original",
            audioIsDefault = false, bitrate = 128_000)
        val english = arabic.copy(id = "original-en", audioLanguage = "en", audioDisplayName = "English original")
        val dub = audio.copy(id = "dub-default", audioIsAutoDubbed = true, bitrate = 512_000)
        for (tracks in listOf(listOf(dub, arabic, english), listOf(english, arabic, dub))) {
            val catalogue = media(*tracks.toTypedArray())
            assertEquals(setOf(arabic.id, english.id), YouTubeFormatSelection.originalAudioTracks(catalogue).map { it.id }.toSet())
            assertEquals(YouTubeFormatSelection(arabic.id), YouTubeFormatSelection.choose(catalogue, "iOS", audioOnly = true))
        }
        assertEquals(YouTubeFormatSelection(english.id), YouTubeFormatSelection.choose(
            media(dub, arabic, english.copy(audioIsDefault = true)), "iOS", audioOnly = true))
    }

    @Test fun unselectableOrIncompatibleOriginalDoesNotSilentlyBecomeADub() {
        val original = audio.copy(id = "source", audioDisplayName = "Arabic original", audioIsDefault = false)
        val dub = audio.copy(id = "dub", audioIsAutoDubbed = true)
        for (unavailable in listOf(original.copy(url = null), original.copy(unavailableReason = "Source restriction"),
            original.copy(sizeBytes = -1))) {
            assertNull(YouTubeFormatSelection.choose(media(dub, unavailable), "iOS", audioOnly = true))
        }
        val opus = original.copy(mimeType = "audio/webm", codecs = "opus")
        assertNull(YouTubeFormatSelection.choose(media(video, dub, opus), "iOS"))
        assertEquals(YouTubeFormatSelection(opus.id), YouTubeFormatSelection.choose(media(dub, opus), "iOS", audioOnly = true))
        assertNull(YouTubeFormatSelection.choose(media(video.copy(audioGroupId = "en"), dub,
            original.copy(audioGroupId = "ar")), "Android"))
    }

    @Test fun explicitDubPreferenceRetainsItsContractEvenWhenOriginalExists() {
        val original = audio.copy(id = "source", audioDisplayName = "Arabic original", audioIsDefault = false)
        val dub = audio.copy(id = "dub", audioIsAutoDubbed = true)
        assertEquals(YouTubeFormatSelection(dub.id), YouTubeFormatSelection.choose(
            media(original, dub), "iOS", audioOnly = true, preferredAudioId = dub.id))
        assertEquals(YouTubeFormatSelection(video.id, dub.id), YouTubeFormatSelection.choose(
            media(video, original, dub), "Android", preferredAudioId = dub.id))
    }

    @Test fun unknownMuxedAudioCannotBypassTheDeclaredOriginalDuringAutomaticSelection() {
        val original = audio.copy(id = "source", audioDisplayName = "Arabic original", audioIsDefault = false)
        val muxed = video.copy(id = "muxed", role = TrackRole.MUXED, height = 2160)
        val catalogue = media(muxed, video, original)
        assertEquals(YouTubeFormatSelection(video.id, original.id),
            YouTubeFormatSelection.choose(catalogue, "iOS", maxHeight = null))
        assertEquals(YouTubeFormatSelection(muxed.id), YouTubeFormatSelection.choose(
            catalogue, "iOS", maxHeight = null, preferredPrimaryId = muxed.id))
    }

    @Test fun progressiveRecommendationDoesNotDropSabrOriginalEvidenceAndSelectADub() {
        val original = asSabr(audio.copy(id = "140@ar.source", audioTrackId = "ar.source",
            audioDisplayName = "Arabic original", audioIsDefault = false), 140)
        val sabrVideo = asSabr(video, 137)
        val dubbed = audio.copy(id = "140@en.dub", audioTrackId = "en.dub", audioIsAutoDubbed = true)
        val catalogue = media(sabrVideo, original, video, dubbed)
        assertEquals(YouTubeFormatSelection(sabrVideo.id, original.id),
            YouTubeFormatSelection.recommendedDownload(catalogue, "iOS"))
        assertNull(YouTubeFormatSelection.recommendedDownload(media(original, video, dubbed), "iOS"))
    }

    @Test fun originalFormatsStillRespectDrcTransportBitrateAndQualityRules() {
        val original = audio.copy(id = "original", audioDisplayName = "English original", audioIsDefault = false,
            bitrate = 128_000)
        val drc = original.copy(id = "drc", isDrc = true, bitrate = 256_000)
        val smaller = video.copy(id = "720", height = 720)
        assertEquals(YouTubeFormatSelection(smaller.id, original.id), YouTubeFormatSelection.choose(
            media(video, smaller, drc, original), "Android", maxHeight = 720))
        assertEquals(YouTubeFormatSelection(original.id), YouTubeFormatSelection.choose(
            media(asSabr(original.copy(bitrate = 512_000), 140), original, drc), "Android", audioOnly = true))
        assertEquals(YouTubeFormatSelection("higher-bitrate"), YouTubeFormatSelection.choose(
            media(original, original.copy(id = "higher-bitrate", bitrate = 192_000)), "iOS", audioOnly = true))
    }

    private fun asSabr(track: Representation, itag: Int) = track.copy(id = "${track.id}#sabr", transport = TransportKind.SABR,
        url = "https://rr1.googlevideo.com/sabr", sabr = YouTubeSabrResource("ABCDEFGHIJK", "https://rr1.googlevideo.com/sabr",
            "AQID", SabrFormatId(itag, 1uL, null), SabrClientInfo(1, "authored-client")))
}
