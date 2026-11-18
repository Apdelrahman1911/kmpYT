package dev.kmpyt.extraction

import dev.kmpyt.YouTubeClient
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YouTubeAudioInspectionTest {
    private val watch = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val player = """function ownedSig(a){a=a.split("");a.reverse();return a.join("");}q.set("signature",ownedSig(q.s));"""
    private fun format(id: Int, mime: String, cipher: String): String =
        """{"itag":$id,"mimeType":"$mime","signatureCipher":"url=${"https://owned.test/media-$id".encodeURLParameter()}&s=$cipher&sp=sig"}"""
    private fun page(status: String = "OK"): String = """<script>var ytInitialPlayerResponse={"videoDetails":{"videoId":"ABCDEFGHIJK","title":"Authored media","author":"Fixture owner","thumbnail":{"thumbnails":[{"url":"https://owned.test/image.jpg"}]}},"playabilityStatus":{"status":"$status"},"streamingData":{"adaptiveFormats":[${format(140, "audio/mp4", "audio")},${format(137, "video/mp4", "video")}]}};</script><script src="/s/player/authored/player_ias.vflset/en_US/base.js"></script>"""

    @Test fun audioInspectionExcludesUnneededVideoTransformsWithoutChangingFullInspection() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val client = YouTubeClient(ResearchTransport { request ->
            requests += request
            ResearchResponse(request.url, 200, if (request.stage == "youtube-player-program") player else page())
        })
        try {
            val audio = client.inspectAudio(watch)
            assertEquals(listOf(TrackRole.AUDIO), audio.media.single().representations.map { it.role })
            assertTrue(audio.media.single().representations.single().selectable)
            assertEquals("oidua", Url(requireNotNull(audio.media.single().representations.single().url)).parameters["sig"])
            assertTrue(audio.diagnostics.any { it.code == "AUDIO_FORMAT_SCOPE" })
            val full = client.inspect(watch)
            assertEquals(setOf(TrackRole.AUDIO, TrackRole.VIDEO), full.media.single().representations.map { it.role }.toSet())
            assertTrue(full.media.single().representations.all { it.selectable })
            assertFalse(full.diagnostics.any { it.code == "AUDIO_FORMAT_SCOPE" })
            assertEquals(1, requests.count { it.stage == "youtube-player-program" }, "Share the versioned player program, not source responses")
            assertEquals(2, requests.count { it.stage == "youtube-watch" })
            assertTrue(requests.none { it.url.startsWith("https://owned.test/media") })
        } finally { client.close() }
    }

    @Test fun audioScopePreservesAccountRestrictionsAndDoesNotRequestAlternateClients() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val client = YouTubeClient(ResearchTransport { request ->
            requests += request
            ResearchResponse(request.url, 200, page("LOGIN_REQUIRED"))
        })
        try {
            val result = client.inspectAudio(watch)
            assertTrue(result.media.flatMap { it.representations }.none { it.selectable })
            assertTrue(result.diagnostics.any { it.code == "CLIENT_FALLBACK_AUTHORIZATION_REQUIRED" })
            assertEquals(listOf("youtube-watch"), requests.map { it.stage })
        } finally { client.close() }
    }
}
