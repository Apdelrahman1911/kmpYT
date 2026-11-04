package dev.kmpyt.extraction

import kotlin.test.*

class YouTubeRepresentationRefreshTest {
    private val previous = Representation("136#progressive-2", "https://media.example/old", TrackRole.VIDEO,
        mimeType = "video/mp4", codecs = "avc1.4d401f", width = 1280, height = 720, sizeBytes = 4096)

    @Test fun aliasesPreserveOriginalSelectionIdentityAndRejectChangedOrAmbiguousSources() {
        val plain = previous.copy(id = "136", url = "https://media.example/fresh")
        assertEquals(previous.id, refreshedProgressiveRepresentation(previous, listOf(plain)).id)
        assertEquals("136", refreshedProgressiveRepresentation(plain, listOf(previous)).id)
        listOf(plain.copy(height = 1080), plain.copy(codecs = "vp9"), plain.copy(mimeType = "video/webm"),
            plain.copy(sizeBytes = 4097), plain.copy(audioLanguage = "fr"), plain.copy(transport = TransportKind.SABR),
            plain.copy(id = "137"), plain.copy(id = "136#another-alias"), plain.copy(audioSampleRate = 48000)).forEach {
            assertFailsWith<IllegalArgumentException> { refreshedProgressiveRepresentation(previous, listOf(it)) }
        }
        assertFailsWith<IllegalArgumentException> {
            refreshedProgressiveRepresentation(previous, listOf(plain, plain.copy(id = "136#progressive", url = "https://media.example/other")))
        }
    }
}
