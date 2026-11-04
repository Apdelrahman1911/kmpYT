package dev.kmpyt.extraction

import kotlin.test.*

class HlsParserTest {
    private val url = "https://fixtures.example/media/master.m3u8"

    @Test fun masterPreservesAudioGroupingCodecsAndVariantDimensions() {
        val result = HlsParser.parse(url, """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="sound"
            video/index.m3u8
        """.trimIndent())
        assertEquals(2, result.representations.size)
        val audio = result.representations.first()
        val video = result.representations.last()
        assertEquals(TrackRole.AUDIO, audio.role)
        assertEquals(TrackRole.VIDEO, video.role)
        assertEquals("sound", audio.audioGroupId)
        assertEquals(audio.audioGroupId, video.audioGroupId)
        assertEquals("https://fixtures.example/media/video/index.m3u8", video.url)
        assertEquals(640, video.width)
        assertEquals("avc1.4d401e,mp4a.40.2", video.codecs)
        assertTrue(video.segments.isEmpty())
        assertEquals("master", result.diagnostics.single().code)
    }

    @Test fun vodResolvesInitializationAndExplicitThenImplicitRanges() {
        val result = HlsParser.parse(url, """
            #EXTM3U
            #EXT-X-MAP:URI="../shared/movie.mp4",BYTERANGE="20@0"
            #EXTINF:1.5,
            #EXT-X-BYTERANGE:100@20
            ../shared/movie.mp4
            #EXTINF:2.25,
            #EXT-X-BYTERANGE:70
            ../shared/movie.mp4
            #EXT-X-ENDLIST
        """.trimIndent())
        val representation = result.representations.single()
        assertEquals("https://fixtures.example/shared/movie.mp4", representation.initialization!!.url)
        assertEquals(20L, representation.initialization!!.rangeLength)
        assertEquals(listOf(20L, 120L), representation.segments.map { it.rangeStart })
        assertEquals(listOf(1500L, 2250L), representation.segments.map { it.durationMs })
        assertEquals(3750L, representation.durationMs)
    }

    @Test fun encryptionAndLiveManifestsReturnNoSelectableRepresentations() {
        val encrypted = HlsParser.parse(url, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST")
        assertTrue(encrypted.representations.isEmpty())
        assertEquals("encrypted", encrypted.diagnostics.single().code)
        val live = HlsParser.parse(url, "#EXTM3U\n#EXTINF:1,\nsegment.ts")
        assertEquals("live", live.diagnostics.single().code)
    }

    @Test fun unsupportedDiscontinuityAndMalformedImplicitRangeAreReported() {
        assertTrue(HlsParser.parse(url, "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST").representations.isEmpty())
        val range = HlsParser.parse(url, "#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:50\nfile.mp4\n#EXT-X-ENDLIST")
        assertTrue(range.representations.isEmpty())
        assertTrue(range.diagnostics.single().message.contains("preceding range"))
    }

    @Test fun missingVariantAndOversizedBodyFailWithoutInventedUrls() {
        assertTrue(HlsParser.parse(url, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100").representations.isEmpty())
        assertTrue(HlsParser.parse(url, "x".repeat(EmbeddedData.MAX_BODY + 1)).representations.isEmpty())
    }
}
