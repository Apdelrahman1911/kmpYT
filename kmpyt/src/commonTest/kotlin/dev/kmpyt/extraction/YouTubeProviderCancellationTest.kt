package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlin.test.*

class YouTubeProviderCancellationTest {
    private val url = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val context = ExtractionContext(url, url)

    @Test fun cancelledSignatureAndThrottlingCallsStopBeforeProcessingMoreFormats() {
        val formats = listOf(
            """{"itag":18,"mimeType":"video/mp4","signatureCipher":"url=https%3A%2F%2Fmedia.example%2Fvideo.mp4&s=abc&sp=sig"}""",
            """{"itag":18,"mimeType":"video/mp4","url":"https://media.example/video.mp4?n=abc"}""",
        )
        formats.forEach { format ->
            assertCancellation(""""formats":[${List(10) { format }.joinToString(",")}]""")
        }
    }

    @Test fun cancelledManifestTransformDoesNotContinueToTheNextManifest() {
        assertCancellation(""""hlsManifestUrl":"https://media.example/master.m3u8?n=abc","dashManifestUrl":"https://media.example/manifest.mpd?n=abc"""")
    }

    @Test fun cancelledSabrEndpointTransformIsNotMisreportedAsAnUnavailableFormat() {
        assertCancellation(""""serverAbrStreamingUrl":"https://media.example/sabr?n=abc","adaptiveFormats":[{"itag":137,"lastModified":"123","contentLength":"100","mimeType":"video/mp4"}]""",
            extra = """, "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQID"}}}""")
    }

    private fun assertCancellation(streaming: String, extra: String = "") {
        val cancellation = CancellationException("Fixture cancellation")
        var calls = 0
        val transforms = object : YouTubeUrlTransforms {
            override val signatureAvailable = true
            override val throttlingAvailable = true
            override val signatureTimestamp: Long? = null
            override fun signature(input: String): String { calls++; throw cancellation }
            override fun throttling(input: String): String { calls++; throw cancellation }
        }
        val provider = YouTubeProvider(transforms, SabrClientInfo(1, "fixture-version"))
        val body = """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Cancellation fixture"},"playabilityStatus":{"status":"OK"},"streamingData":{$streaming}$extra}"""
        assertSame(cancellation, assertFailsWith<CancellationException> { provider.parse(context, body) })
        assertEquals(1, calls, "Cancelled inspection must not keep transforming the remaining catalogue")
    }
}
