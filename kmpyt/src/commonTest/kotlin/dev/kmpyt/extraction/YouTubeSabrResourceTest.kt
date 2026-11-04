package dev.kmpyt.extraction

import kotlin.test.*

class YouTubeSabrResourceTest {
    private val videoId = "yWvEzBFz44M"
    private fun response(length: String = "123", query: String = "") = """
        {"videoDetails":{"videoId":"$videoId","title":"Controlled media","lengthSeconds":"2"},
        "playabilityStatus":{"status":"OK"},
        "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}},
        "streamingData":{"serverAbrStreamingUrl":"http://127.0.0.1:9876/media$query","adaptiveFormats":[
        {"itag":134,"lastModified":"18446744073709551615","xtags":"owned-test","mimeType":"video/mp4; codecs=\"avc1.42001e\"","contentLength":"$length","height":360}]}}
    """.trimIndent()
    private val context = ExtractionContext("https://www.youtube.com/watch?v=$videoId", "http://127.0.0.1:9876/youtube/player")
    @Test fun preservesSourceProtocolEndpointExactIdentityAndWholeTrackLength() {
        val result = YouTubeProvider(sabrClientInfo = SabrClientInfo(1, "controlled-web-version")).parse(context, response())
        val track = result.media.single().representations.single()
        assertEquals(TransportKind.SABR, track.transport)
        assertTrue(track.selectable)
        assertEquals("http://127.0.0.1:9876/media", track.requireResourceUrl())
        assertEquals(ULong.MAX_VALUE, track.sabr!!.formatId.lastModified)
        assertEquals("owned-test", track.sabr.formatId.xtags)
        assertEquals(123L, track.sizeBytes)
        assertEquals(360, track.height)
    }
    @Test fun missingLengthsAndUnresolvedThrottlingNeverBecomeSelectable() {
        for (body in listOf(response(length = ""), response(query = "?n=unresolved"))) {
            val track = YouTubeProvider(sabrClientInfo = SabrClientInfo(1, "controlled-web-version")).parse(context, body).media.single().representations.single()
            assertFalse(track.selectable)
            assertNull(track.sabr)
        }
    }
    @Test fun planCannotSubstituteAnotherUrlForItsSourceProtocolEndpoint() {
        val track = YouTubeProvider(sabrClientInfo = SabrClientInfo(1, "controlled-web-version")).parse(context, response()).media.single().representations.single()
        val plan = PlannedDownload(track.copy(url = "http://127.0.0.1:9876/another"), inspectionUrl = context.responseUrl, mediaId = videoId)
        assertFailsWith<IllegalArgumentException> { TransferPlanning.parts(plan) }
    }
}
