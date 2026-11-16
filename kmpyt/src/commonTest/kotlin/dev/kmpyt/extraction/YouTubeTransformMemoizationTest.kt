package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class YouTubeTransformMemoizationTest {
    private val context = ExtractionContext("https://www.youtube.com/watch?v=ABCDEFGHIJK", "https://owned.test/watch", 10_000)

    @Test fun repeatedFormatSignaturesCrossTheRuntimeOnlyOncePerDistinctInput() = runTest {
        var signatures = 0
        var throttles = 0
        val transforms = object : YouTubeUrlTransforms {
            override val signatureAvailable = true
            override val throttlingAvailable = true
            override val signatureTimestamp = 12345L
            override fun signature(input: String): String { signatures++; return "sig-$input" }
            override fun throttling(input: String): String { throttles++; return "n-$input" }
        }
        val transport = ResearchTransport { ResearchResponse(it.url, 200, "authored player fixture") }
        val extractor = YouTubeResponseExtractor(transport, compiler = YouTubePlayerProgramCompiler { _, _ ->
            YouTubeProgramCompilation(transforms, emptyList())
        })
        val player = buildJsonObject {
            putJsonObject("videoDetails") { put("videoId", "ABCDEFGHIJK"); put("title", "Authored formats") }
            putJsonObject("playabilityStatus") { put("status", "OK") }
            putJsonObject("streamingData") { putJsonArray("formats") {
                repeat(12) { index -> add(buildJsonObject {
                    put("itag", 18 + index)
                    put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                    val input = if (index % 2 == 0) "same-input" else "other-input"
                    val url = "https://owned.test/media-$index.mp4?n=$input"
                    put("signatureCipher", "url=${url.encodeURLParameter()}&s=$input&sp=sig")
                }) }
            } }
        }
        val body = "<script>var ytInitialPlayerResponse=$player;</script><script src='/player.js'></script>"
        try {
            repeat(2) { inspection ->
                val result = extractor.extract(context, ResearchResponse(context.responseUrl, 200, body))
                val formats = result.media.single().representations
                assertEquals(12, formats.size)
                assertTrue(formats.all { it.selectable })
                formats.forEachIndexed { index, rep ->
                    val input = if (index % 2 == 0) "same-input" else "other-input"
                    val url = Url(requireNotNull(rep.url))
                    assertEquals("sig-$input", url.parameters["sig"])
                    assertEquals("n-$input", url.parameters["n"])
                }
                assertEquals(2 * (inspection + 1), signatures, "Reuse signatures within an inspection; keep fresh inspection scope")
                assertEquals(2 * (inspection + 1), throttles, "Signature and throttling results must remain separate")
            }
        } finally { extractor.close() }
    }
    @Test fun cancellationStopsRemainingNativeTransforms() = runTest {
        val operation = Job()
        var calls = 0
        val transforms = object : YouTubeUrlTransforms {
            override val signatureAvailable = true
            override val throttlingAvailable = false
            override val signatureTimestamp: Long? = null
            override fun signature(input: String): String {
                calls++
                operation.cancel()
                return "transformed-$input"
            }
            override fun throttling(input: String): String = error("Not offered")
        }
        val transport = ResearchTransport { ResearchResponse(it.url, 200, "authored player fixture") }
        val extractor = YouTubeResponseExtractor(transport, compiler = YouTubePlayerProgramCompiler { _, _ ->
            YouTubeProgramCompilation(transforms, emptyList())
        })
        val formats = (1..12).joinToString(",") { index ->
            """{"itag":$index,"mimeType":"audio/mp4","signatureCipher":"url=${"https://owned.test/media-$index".encodeURLParameter()}&s=input-$index&sp=sig"}"""
        }
        val body = """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Authored formats"},"streamingData":{"adaptiveFormats":[$formats]}};</script><script src='/player.js'></script>"""
        try {
            assertFailsWith<CancellationException> {
                withContext(operation) { extractor.extract(context, ResearchResponse(context.responseUrl, 200, body)) }
            }
            assertEquals(1, calls, "Do not keep invoking the native runtime after cancellation")
        } finally { operation.cancel(); extractor.close() }
    }

}
