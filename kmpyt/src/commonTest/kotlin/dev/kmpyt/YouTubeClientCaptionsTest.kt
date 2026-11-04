package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class YouTubeClientCaptionsTest {
    @Test fun concurrentSelectionsReturnTheirOwnDocumentsWhenTheSecondFinishesFirst() = exercise(failFirst = false)

    @Test fun failedOlderSelectionCannotReturnTheNewerSelectionsSuccessfulDocument() = exercise(failFirst = true)

    private fun exercise(failFirst: Boolean) = runTest {
        val videoId = "ABCDEFGHIJK"
        val source = "https://www.youtube.com/watch?v=$videoId"
        val catalogue = YouTubeCaptionCatalogue(listOf("en", "fr").mapIndexed { index, language ->
            YouTubeCaptionTrack(index, ".$language", "https://www.youtube.com/api/timedtext?v=$videoId&lang=$language",
                language, language, null, false, false)
        }, emptyList(), emptyList(), null)
        val report = ExtractionReport(source, ProviderId.YOUTUBE,
            listOf(ExtractedMedia(videoId, ProviderId.YOUTUBE, "Concurrent captions", source, emptyList(), captions = catalogue)),
            emptyList())
        val started = List(2) { CompletableDeferred<Unit>() }
        val release = List(2) { CompletableDeferred<Unit>() }
        var requests = 0
        val client = YouTubeClient(ResearchTransport { request ->
            val index = if (Url(request.url).parameters["lang"] == "en") 0 else 1
            requests++
            started[index].complete(Unit)
            release[index].await()
            if (index == 0 && failFirst) ResearchResponse(request.url, 503, "Unavailable")
            else ResearchResponse(request.url, 200,
                "WEBVTT\n\n00:01.000 --> 00:02.000\n${if (index == 0) "English document" else "French document"}\n",
                mapOf("content-type" to "text/vtt"))
        })
        try {
            val first = async { runCatching { client.captions(report, 0) } }
            started[0].await()
            val second = async { client.captions(report, 1) }
            started[1].await()
            release[1].complete(Unit)
            assertEquals("French document", second.await().cues.single().text)
            release[0].complete(Unit)
            val result = first.await()
            if (failFirst) assertIs<IllegalStateException>(result.exceptionOrNull())
            else assertEquals("English document", result.getOrThrow().cues.single().text)
            // Stale completion never overwrites the user's latest selection.
            val latest = client.captionState.value.videos.getValue(videoId)
            assertEquals(1, latest.selected?.sourceIndex)
            assertEquals("French document", latest.document?.cues?.single()?.text)
            assertNull(latest.error)
            assertEquals("French document", client.captions(report, 1).cues.single().text)
            assertEquals(2, requests, "The latest successfully loaded document remains cached")
        } finally {
            release.forEach { it.complete(Unit) }
            client.close()
        }
    }
}
