package dev.kmpyt.extraction

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class YouTubePreparedPlayerResponseTest {
    private val watch = "https://www.youtube.com/watch?v=ABCDEFGHIJK"
    private val context = ExtractionContext(watch, watch)
    private val cipher = "url=${"https://authored.example/audio".encodeURLParameter()}&s=authored&sp=sig"
    private fun player(status: String = "OK", title: String = "Authored original") =
        """{"videoDetails":{"videoId":"ABCDEFGHIJK","title":"$title"},"playabilityStatus":{"status":"$status"},"streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","signatureCipher":${JsonPrimitive(cipher)},"audioTrack":{"id":"fr.4","displayName":"French original","audioIsDefault":false,"isAutoDubbed":false}}]}}"""
    private val transforms = object : YouTubeUrlTransforms {
        override val signatureAvailable = true
        override val throttlingAvailable = false
        override val signatureTimestamp: Long? = null
        override fun signature(input: String) = input.reversed()
        override fun throttling(input: String): String = error("Unexpected throttling transform")
    }

    @Test fun onePreparedDocumentGraphSupportsBothUnresolvedAndTransformedPasses() {
        val provider = YouTubeProvider(audioOnly = true)
        val body = "<script>var ytInitialPlayerResponse=${player()};</script>"
        val prepared = provider.prepare(body)
        val documents = prepared.getOrThrow()
        val unresolved = provider.parse(context, prepared, body)
        val resolved = YouTubeProvider(transforms, audioOnly = true).parse(context, prepared, body)
        assertSame(documents, prepared.getOrThrow())
        assertTrue(unresolved.diagnostics.any { it.code == "SIGNATURE_CIPHER_UNRESOLVED" })
        val audio = resolved.media.single().representations.single()
        assertTrue(audio.selectable)
        assertEquals("derohtua", Url(requireNotNull(audio.url)).parameters["sig"])
        assertEquals("fr.4", audio.audioTrackId)
        assertEquals(AudioTrackProvenance.ORIGINAL, audio.audioProvenance)
        assertEquals(resolved, YouTubeProvider(transforms, audioOnly = true).parse(context, player()))
    }

    @Test fun preparedEncodedPlayerStillExpandsItsBoundedNestedResponse() {
        val provider = YouTubeProvider(transforms, audioOnly = true)
        val body = """{"player_response":${JsonPrimitive(player())}}"""
        assertEquals(provider.parse(context, player()), provider.parse(context, provider.prepare(body), body))
    }

    @Test fun preparedAuthorizationAndParserFailuresCannotBecomePlayable() {
        val provider = YouTubeProvider(transforms, audioOnly = true)
        val restrictedBody = player(status = "LOGIN_REQUIRED")
        val restricted = provider.parse(context, provider.prepare(restrictedBody), restrictedBody)
        assertTrue(restricted.media.flatMap { it.representations }.none { it.selectable })
        val oversized = provider.prepare(" ".repeat(YouTubeProvider.MAX_PAGE_BODY + 1))
        assertTrue(oversized.isFailure)
        assertEquals(listOf("PARSER_LIMIT_OR_INVALID_DATA"), provider.parse(context, oversized, "").diagnostics.map { it.code })
    }

    @Test fun preparedDocumentsNeverBecomeACrossInspectionResponseCache() {
        val provider = YouTubeProvider(transforms, audioOnly = true)
        val first = provider.prepare(player(title = "First authored response"))
        val second = provider.prepare(player(title = "Second authored response"))
        assertNotSame(first.getOrThrow(), second.getOrThrow())
        assertEquals("First authored response", provider.parse(context, first, player(title = "First authored response")).media.single().title)
        assertEquals("Second authored response", provider.parse(context, second, player(title = "Second authored response")).media.single().title)
    }
}
