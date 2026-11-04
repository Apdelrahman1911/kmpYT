package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YouTubeClientTest {
    private val id = "ABCDEFGHIJK"
    private val page = "https://www.youtube.com/watch?v=$id"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val video = Representation("136", "https://media.example/video.mp4", TrackRole.VIDEO,
        mimeType = "video/mp4", codecs = "avc1.4d401f", width = 1280, height = 720, sizeBytes = 100)
    private val audio = Representation("140", "https://media.example/audio.m4a", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 20)
    private fun report(vararg representations: Representation) = ExtractionReport(page, ProviderId.YOUTUBE,
        listOf(ExtractedMedia(id, ProviderId.YOUTUBE, "Fixture video", page, representations.toList())), emptyList())
    private fun watch(generation: Int, program: Boolean = false) = """
        <script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"$id","channelId":"$channelId","title":"Generation $generation","author":"Fixture creator","lengthSeconds":"3","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg"}]}},"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":18,"url":"https://media.example/file.mp4?generation=$generation${if (program) "&n=abc" else ""}","mimeType":"video/mp4","height":360,"contentLength":"100"}]}};</script>
        ${if (program) "<script src='/s/player/fixture/player_ias.vflset/en_US/base.js'></script>" else ""}
    """.trimIndent()

    @Test fun inspectionUsesFreshWatchDataAndActualUploaderWithoutStartingMediaRequests() = runTest {
        var count = 0
        val client = YouTubeClient(ResearchTransport {
            assertEquals("youtube-watch", it.stage)
            ResearchResponse(it.url, 200, watch(++count))
        })
        try {
            val first = client.inspect(page)
            val second = client.inspect(page)
            assertEquals("Generation 1", first.media.single().title)
            assertEquals("Generation 2", second.media.single().title)
            assertEquals(channelId, second.media.single().uploaderChannelId)
            assertTrue(second.inspectedAtEpochMs > 1_700_000_000_000)
            assertEquals(2, count)
        } finally { client.close() }
    }

    @Test fun clientCachesOnlyProgramAndClosesNativeSessionAndTransportOnce() = runTest {
        var watches = 0
        var programs = 0
        var prepares = 0
        var sessionCloses = 0
        var transportCloses = 0
        val source = """var _yt_player={};(function(realm){
            realm.Resource=function(input,enabled){var parsed=new URL(input);this.set=function(k,v){parsed.searchParams.set(k,v);};this.get=function(k){return parsed.searchParams.get(k);};};
            var assemble;assemble=function(uri,key,challenge){uri=new realm.Resource(uri,!0);uri.set("alr","yes");challenge&&uri.set(key,challenge);return uri;};
            var configuration={signatureTimestamp:21002};})(_yt_player);"""
        val runtime = object : YouTubeJavaScriptRuntime {
            override fun prepare(program: String): YouTubeJavaScriptSession {
                prepares++
                assertTrue(program.contains("__kmpyt_transform"))
                return object : YouTubeJavaScriptSession {
                    override val capabilities = YouTubeJavaScriptCapabilities(true, true)
                    override fun call(function: YouTubeJavaScriptFunction, input: String) = input.reversed()
                    override fun close() { sessionCloses++ }
                }
            }
        }
        val client = YouTubeClient(ResearchTransport { request ->
            ResearchResponse(request.url, 200, when (request.stage) {
                "youtube-watch" -> watch(++watches, program = true)
                "youtube-player-program" -> { programs++; source }
                else -> error("Unexpected metadata or media request")
            })
        }, runtime, onClose = { transportCloses++ })
        assertTrue(client.inspect(page).media.single().representations.single().selectable)
        val second = client.inspect(page)
        assertTrue(second.diagnostics.any { it.code == "PLAYER_PROGRAM_CACHE_HIT" })
        assertEquals(2, watches)
        assertEquals(1, programs)
        assertEquals(1, prepares)
        listOf(async { client.close() }, async { client.close() }).awaitAll()
        assertEquals(1, sessionCloses)
        assertEquals(1, transportCloses)
        assertFailsWith<IllegalStateException> { client.inspect(page) }
    }

    @Test fun planningPublishesCompleteSizeAndDownloadRequiresAnExplicitCall() = runTest {
        var downloads = 0
        val client = YouTubeClient(ResearchTransport { error("Typed progressive planning must not fetch media") },
            downloader = YouTubeDownloader { plan, destination, progress ->
                downloads++
                assertEquals(120L, TransferPlanning.totalBytes(plan))
                progress(YouTubeDownloadProgress(120, 120))
                YouTubeDownloadResult(destination, 120, "video/mp4")
            }, platformName = "iOS")
        try {
            val report = report(video, audio)
            val selection = assertNotNull(client.select(report))
            val plan = client.plan(report, selection)
            assertEquals(0, downloads)
            assertEquals(120L, TransferPlanning.totalBytes(plan))
            assertEquals(120L, client.download(plan, "output.mp4").bytesWritten)
            assertEquals(1, downloads)
            assertFailsWith<IllegalArgumentException> { client.download(plan.copy(mediaId = "LMNOPQRSTUV"), "wrong.mp4") }
            assertEquals(1, downloads)
        } finally { client.close() }
    }

    @Test fun concreteVodManifestResolvesBeforeDownloadAndMasterAndEncryptedRemainRejected() = runTest {
        var body = "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\npart.ts\n#EXT-X-ENDLIST"
        val requested = mutableListOf<String>()
        val client = YouTubeClient(ResearchTransport { requested += it.url; ResearchResponse(it.url, 200, body) })
        val manifest = video.copy(id = "hls", role = TrackRole.MUXED, transport = TransportKind.HLS,
            url = "https://media.example/list.m3u8", sizeBytes = null)
        try {
            val resolved = client.plan(report(manifest), YouTubeFormatSelection("hls"))
            assertEquals(listOf("https://media.example/part.ts"), resolved.primary.segments.map { it.url })
            assertEquals(listOf(manifest.url), requested)
            body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=640x360\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=1280x720\nb.m3u8"
            assertFailsWith<IllegalArgumentException> { client.plan(report(manifest), YouTubeFormatSelection("hls")) }
            body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:3,\npart.ts\n#EXT-X-ENDLIST"
            assertFailsWith<IllegalArgumentException> { client.plan(report(manifest), YouTubeFormatSelection("hls")) }
            assertEquals(3, requested.size)
        } finally { client.close() }
    }

    @Test fun closeWaitsForExistingTransferAndRejectsSelfCloseInProgressCallback() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transportClosed = false
        lateinit var client: YouTubeClient
        client = YouTubeClient(ResearchTransport { error("No metadata expected") },
            downloader = YouTubeDownloader { _, path, progress ->
                progress(YouTubeDownloadProgress(0, 100))
                started.complete(Unit)
                release.await()
                assertFalse(transportClosed)
                YouTubeDownloadResult(path, 100, "video/mp4")
            }, onClose = { transportClosed = true })
        val plan = client.plan(report(video), YouTubeFormatSelection(video.id))
        val transfer = async {
            client.download(plan, "output.mp4") {
                assertFailsWith<IllegalStateException> { client.close() }
            }
        }
        started.await()
        val closure = async { client.close() }
        release.complete(Unit)
        transfer.await()
        closure.await()
        assertTrue(transportClosed)
    }

    @Test fun iosRejectsIncompatibleCombinedCodecsBeforeAnyMediaRequest() = runTest {
        val client = YouTubeClient(ResearchTransport { error("Incompatible formats must not request media") }, platformName = "iOS")
        try {
            val webm = video.copy(mimeType = "video/webm", codecs = "vp9")
            assertNull(client.select(report(webm, audio)))
            val failure = assertFailsWith<IllegalArgumentException> {
                client.plan(report(webm, audio), YouTubeFormatSelection(webm.id, audio.id))
            }
            assertTrue(failure.message.orEmpty().startsWith("iOS"))
        } finally { client.close() }
    }

    @Test fun portablePlaybackPlanKeepsSourceTracksAndDoesNotStartANativePlayerOrDownload() = runTest {
        for (platform in listOf("Android", "JVM", "iOS")) {
            val client = YouTubeClient(ResearchTransport { error("Playback planning must not request media") },
                downloader = YouTubeDownloader { _, _, _ -> error("Playback planning must not download") },
                platformName = platform)
            try {
                val request = client.playbackPlan(report(video, audio), YouTubeFormatSelection(video.id, audio.id))
                assertEquals(video, request.plan.primary)
                assertEquals(audio, request.plan.audio)
                assertEquals(id, request.metadata.id)
                assertFailsWith<IllegalArgumentException> {
                    client.playbackPlan(report(video.copy(transport = TransportKind.HLS)), YouTubeFormatSelection(video.id))
                }
            } finally { client.close() }
        }
    }
}
