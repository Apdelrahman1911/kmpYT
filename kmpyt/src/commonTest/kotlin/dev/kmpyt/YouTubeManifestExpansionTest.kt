package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.YouTubeFormatSelection
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YouTubeManifestExpansionTest {
    private val source = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val mpd = "https://media.example/manifest.mpd"
    private val hls = "https://media.example/master.m3u8"
    private val dashPointer = Representation("dashManifestUrl", mpd, TrackRole.MUXED,
        TransportKind.DASH, "application/dash+xml")
    private val hlsPointer = Representation("hlsManifestUrl", hls, TrackRole.MUXED,
        TransportKind.HLS, "application/vnd.apple.mpegurl")
    private fun report(vararg formats: Representation) = ExtractionReport(source, ProviderId.YOUTUBE,
        listOf(ExtractedMedia("ABCDEFGHIJK", ProviderId.YOUTUBE, "Manifest fixture", source, formats.toList(),
            uploaderChannelId = "UCabcdefghijklmnopqrstuv")), emptyList())
    private fun dash(contents: String) = """<MPD type="static" mediaPresentationDuration="PT3S"><Period>$contents</Period></MPD>"""
    private fun video(id: String, height: Int, extra: String = "") = """
        <Representation id="$id" bandwidth="800000" width="1280" height="$height">
          <BaseURL>$id.mp4</BaseURL>$extra
        </Representation>
    """.trimIndent()

    @Test fun completeDashBaseUrlAndSegmentBaseRequireOnlyTheManifestRequest() = runTest {
        for (segmentBase in listOf("", """<SegmentBase indexRange="100-200"><Initialization range="0-99"/></SegmentBase>""")) {
            val requests = mutableListOf<String>()
            val body = dash("""<AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">${video("v720", 720, segmentBase)}</AdaptationSet>""")
            val client = YouTubeClient(ResearchTransport { request ->
                requests += request.url
                assertEquals(mpd, request.url, "Planning must not GET the BaseURL media file")
                ResearchResponse(request.url, 200, body)
            })
            try {
                val plan = client.plan(report(dashPointer), YouTubeFormatSelection(dashPointer.id))
                assertEquals(listOf(mpd), requests)
                assertEquals(TransportKind.PROGRESSIVE, plan.primary.transport)
                assertEquals(TrackRole.VIDEO, plan.primary.role, "A generic manifest pointer cannot overwrite the parsed track role")
                assertEquals("https://media.example/v720.mp4", plan.primary.url)
                assertTrue(plan.primary.segments.isEmpty())
            } finally { client.close() }
        }
    }

    @Test fun dashExpansionExposesSelectableVideoAudioAndStableScopedIdsWithoutFetchingMedia() = runTest {
        val body = dash("""
            <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">${video("v360", 360)}${video("v720", 720)}</AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2"><Representation id="aac" bandwidth="128000"><BaseURL>audio.m4a</BaseURL></Representation></AdaptationSet>
        """.trimIndent())
        var requests = 0
        val client = YouTubeClient(ResearchTransport {
            assertEquals(mpd, it.url); requests++; ResearchResponse(it.url, 200, body)
        }, platformName = "Android")
        try {
            val initial = report(dashPointer)
            val expanded = client.expandFormats(initial)
            val formats = expanded.media.single().representations
            assertEquals(listOf(TrackRole.VIDEO, TrackRole.VIDEO, TrackRole.AUDIO), formats.map { it.role })
            assertTrue(formats.all { it.transport == TransportKind.PROGRESSIVE && it.manifestUrl == mpd })
            assertEquals(formats.size, formats.map { it.id }.distinct().size)
            assertTrue(formats.all { it.id.startsWith("dashManifestUrl#manifest:") })
            assertEquals(initial.media.single().uploaderChannelId, expanded.media.single().uploaderChannelId)
            val selection = assertNotNull(client.select(expanded, maxHeight = 720))
            val plan = client.plan(expanded, selection)
            assertEquals("https://media.example/v720.mp4", plan.primary.url)
            assertEquals("https://media.example/audio.m4a", plan.audio?.url)
            assertEquals(1, requests)
            assertSame(expanded, client.expandFormats(expanded))
            assertEquals(1, requests, "Already expanded reports must not refetch catalogues or media")
            assertEquals(formats.map { it.id }, client.expandFormats(initial).media.single().representations.map { it.id })
        } finally { client.close() }
    }

    @Test fun hlsExpansionLoadsOnlyTheSelectedVideoAndAudioPlaylistsDuringPlanning() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="sound"
            v360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="sound"
            v720.m3u8
        """.trimIndent()
        fun child(prefix: String) = "#EXTM3U\n#EXT-X-MAP:URI=\"$prefix-init.mp4\"\n#EXTINF:3,\n$prefix-part.m4s\n#EXT-X-ENDLIST"
        val bodies = mapOf(hls to master, "https://media.example/v720.m3u8" to child("video"),
            "https://media.example/audio.m3u8" to child("audio"))
        val requests = mutableListOf<String>()
        val client = YouTubeClient(ResearchTransport {
            requests += it.url
            ResearchResponse(it.url, 200, requireNotNull(bodies[it.url]) { "Unselected playlist or media was requested" })
        }, platformName = "iOS")
        try {
            val expanded = client.expandFormats(report(hlsPointer))
            assertEquals(listOf(hls), requests)
            val formats = expanded.media.single().representations
            assertEquals(listOf(TrackRole.AUDIO, TrackRole.VIDEO, TrackRole.VIDEO), formats.map { it.role })
            assertEquals("en", formats.first().audioLanguage)
            assertEquals(true, formats.first().audioIsDefault)
            val selected = assertNotNull(client.select(expanded, maxHeight = 720))
            val plan = client.plan(expanded, selected)
            assertEquals(listOf(hls, "https://media.example/v720.m3u8", "https://media.example/audio.m3u8"), requests)
            assertEquals(TrackRole.VIDEO, plan.primary.role)
            assertEquals(TrackRole.AUDIO, plan.audio?.role)
            assertEquals("audio/mp4", plan.audio?.mimeType)
            assertEquals("en", plan.audio?.audioLanguage)
            assertEquals("https://media.example/video-part.m4s", plan.primary.segments.single().url)
            assertEquals("https://media.example/audio-part.m4s", plan.audio?.segments?.single()?.url)
        } finally { client.close() }
    }

    @Test fun excessiveCataloguesAreRejectedBeforeRequestsAndEncryptedManifestsDoNotFetchKeys() = runTest {
        var requests = 0
        val client = YouTubeClient(ResearchTransport {
            requests++
            assertEquals(hls, it.url)
            ResearchResponse(it.url, 200, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:3,\npart.ts\n#EXT-X-ENDLIST")
        })
        try {
            assertFailsWith<IllegalArgumentException> {
                client.expandFormats(report(*Array(9) { hlsPointer.copy(id = "manifest-$it") }))
            }
            assertEquals(0, requests)
            assertFailsWith<IllegalArgumentException> { client.expandFormats(report(hlsPointer)) }
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun refreshingDirectAndExpandedDashSelectionsKeepsIdentityAndRefetchesOnlyMetadata() = runTest {
        for (expand in listOf(false, true)) {
            val requests = mutableListOf<String>()
            var generation = 1
            val client = YouTubeClient(ResearchTransport { request ->
                requests += request.url
                val body = if (request.url == mpd) dash("""<AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">${video("v720", 720).replace("v720.mp4", "v720.mp4?generation=$generation")}</AdaptationSet>""")
                else {
                    assertEquals(source, request.url)
                    """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Manifest fixture","author":"Fixture author","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/ABCDEFGHIJK/default.jpg"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"dashManifestUrl":"$mpd"}};</script>"""
                }
                ResearchResponse(request.url, 200, body)
            })
            try {
                val initial = report(dashPointer).let { if (expand) client.expandFormats(it) else it }
                val plan = client.plan(initial, YouTubeFormatSelection(initial.media.single().representations.single().id))
                generation = 2
                val refreshed = client.refresh(plan)
                assertEquals(plan.primary.id, refreshed.primary.id)
                assertEquals("https://media.example/v720.mp4?generation=2", refreshed.primary.url)
                assertEquals(TrackRole.VIDEO, refreshed.primary.role)
                assertEquals(TransportKind.PROGRESSIVE, refreshed.primary.transport)
                assertEquals(listOf(mpd, source, mpd), requests)
            } finally { client.close() }
        }
    }

    @Test fun inspectingManifestPointersDoesNotProbeManifestOrMediaResources() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val client = YouTubeClient(ResearchTransport {
            requests += it
            assertEquals("youtube-watch", it.stage)
            ResearchResponse(it.url, 200, """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Manifest fixture","author":"Fixture author","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/ABCDEFGHIJK/default.jpg"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"dashManifestUrl":"$mpd","hlsManifestUrl":"$hls"}};</script>""")
        })
        try {
            val inspected = client.inspect(source)
            assertEquals(setOf(TransportKind.DASH, TransportKind.HLS), inspected.media.single().representations.map { it.transport }.toSet())
            assertEquals(1, requests.size)
        } finally { client.close() }
    }
}
