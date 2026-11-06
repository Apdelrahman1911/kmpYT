package dev.kmpyt.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independently authored response shapes; none of these are live YouTube success evidence. */
class YouTubeFirstResultTest {
    private val firstId = "ABCDEFGHIJK"
    private val laterId = "LMNOPQRSTUV"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val playlistId = "PLabcdefghijklmnopqrstuv"
    private val origin = "https://www.youtube.com"

    private class Transport(val body: String) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse {
            requests += request
            return ResearchResponse(request.url, 200, body)
        }
    }

    private fun video(id: String = firstId, title: JsonElement = JsonPrimitive("Complete first title"),
        unavailable: Boolean = false) = buildJsonObject {
        putJsonObject("videoRenderer") {
            put("videoId", id); put("title", title)
            if (unavailable) put("isPlayable", false)
        }
    }

    private fun body(vararg entries: JsonElement) = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("sectionListRenderer") {
                put("contents", JsonArray(entries.toList()))
            }
        }
    }.toString()

    @Test fun publicClientSubmitsEveryCodeUnitOfCompleteUnicodeTitleWithoutTrimming() = runTest {
        val query = " " + "العربية🎵 & ".repeat(1000) + "the final words "
        assertTrue(query.length > 4_000)
        val transport = Transport(body(video()))
        val client = YouTubeClient(transport)
        try {
            val page = client.search(query)
            assertEquals(query, page.query)
            assertEquals(query, Url(transport.requests.single().url).parameters["search_query"])
            assertEquals(query, Url(page.sourceUrl).parameters["search_query"])
            assertTrue(page.sourceUrl.length > 2_000)
            assertEquals(firstId, page.firstEntry?.id)
        } finally { client.close() }
    }

    @Test fun exactUnicodeBoundPassesAndOversizedOrMalformedQueriesAreRejectedBeforeTransport() = runTest {
        val maximum = "界".repeat(YouTubeBrowseText.MAX_TITLE_LENGTH)
        val transport = Transport(body())
        val service = PublicYouTubeBrowseService(transport)
        assertEquals(maximum, service.search(maximum).query)
        assertEquals(maximum, Url(transport.requests.single().url).parameters["search_query"])
        for (invalid in listOf(maximum + "x", "", "   ", "a\ncomplete", "a\u0000b", "a\u007fb", "a\u0085b", "\uD800", "a\uDC00", "\uD800x")) {
            assertFailsWith<IllegalArgumentException> { service.search(invalid) }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun completeTitlesRetainAllRunsAndSurrogatePairsRatherThanOldDisplayPrefixes() = runTest {
        val parts = List(80) { index -> " $index " + "لغة🎵".repeat(20) }
        val title = parts.joinToString("")
        assertTrue(title.length > 4_000)
        val runs = buildJsonObject { put("runs", JsonArray(parts.map { buildJsonObject { put("text", it) } })) }
        val page = PublicYouTubeBrowseService(Transport(body(video(title = runs)))).search("Complete title")
        assertEquals(title, page.items.single().title)
        assertEquals(title, page.firstEntry?.title)
        assertNull(page.firstEntry?.issue)
    }

    @Test fun unavailableFirstVideoRemainsFirstEvenThoughPresentationItemsOmitIt() = runTest {
        val page = PublicYouTubeBrowseService(Transport(body(video(title = JsonPrimitive("[Private video]"), unavailable = true), video(laterId)))).search("Title")
        assertEquals(listOf(laterId), page.items.map { it.id })
        val first = assertNotNull(page.firstEntry)
        assertEquals(firstId, first.id)
        assertEquals(YouTubeBrowseKind.VIDEO, first.kind)
        assertEquals("[Private video]", first.title)
        assertEquals(YouTubeBrowseEntryIssue.UNAVAILABLE, first.issue)
        assertEquals(0, first.ordinal)
    }

    @Test fun firstChannelOrAlbumPlaylistNeverBecomesALaterVideo() = runTest {
        val channel = buildJsonObject { putJsonObject("channelRenderer") {
            put("channelId", channelId); put("title", "Official channel")
            putJsonObject("navigationEndpoint") { putJsonObject("browseEndpoint") { put("canonicalBaseUrl", "/@a_handle") } }
        } }
        val album = buildJsonObject { putJsonObject("lockupViewModel") {
            put("contentType", "LOCKUP_CONTENT_TYPE_PLAYLIST"); put("contentId", playlistId)
            putJsonObject("metadata") { putJsonObject("lockupMetadataViewModel") { putJsonObject("title") { put("content", "Complete album title") } } }
        } }
        for ((card, kind, id) in listOf(Triple(channel, YouTubeBrowseKind.CHANNEL, channelId), Triple(album, YouTubeBrowseKind.PLAYLIST, playlistId))) {
            val page = PublicYouTubeBrowseService(Transport(body(card, video(laterId)))).search("Album title")
            assertEquals(kind, page.firstEntry?.kind)
            assertEquals(id, page.firstEntry?.id)
            assertEquals(kind, page.items.first().kind)
            assertEquals(0, page.firstEntry?.ordinal)
        }
    }

    @Test fun firstNavigableAlbumOrChannelShelfIsTheCollectionNotItsChildVideo() = runTest {
        for ((browseId, kind, id) in listOf(
            Triple("VL$playlistId", YouTubeBrowseKind.PLAYLIST, playlistId),
            Triple(channelId, YouTubeBrowseKind.CHANNEL, channelId),
        )) {
            val shelf = buildJsonObject { putJsonObject("shelfRenderer") {
                put("title", "Actual first collection title")
                putJsonObject("endpoint") { putJsonObject("browseEndpoint") { put("browseId", browseId) } }
                putJsonObject("content") { putJsonObject("horizontalListRenderer") { put("items", JsonArray(listOf(video()))) } }
            } }
            val page = PublicYouTubeBrowseService(Transport(body(shelf, video(laterId)))).search("Complete album title")
            assertEquals(kind, page.firstEntry?.kind); assertEquals(id, page.firstEntry?.id)
            assertEquals("Actual first collection title", page.firstEntry?.title)
            assertEquals(listOf(id, laterId), page.items.map { it.id })
            assertTrue(page.items.none { it.id == firstId })
        }
        val unknownShelf = buildJsonObject { putJsonObject("reelShelfRenderer") {
            put("title", "Shorts shelf"); put("items", JsonArray(listOf(video())))
        } }
        val unknown = PublicYouTubeBrowseService(Transport(body(unknownShelf, video(laterId)))).search("Title")
        assertNull(unknown.firstEntry?.kind); assertNull(unknown.firstEntry?.id)
        assertEquals(YouTubeBrowseEntryIssue.UNSUPPORTED, unknown.firstEntry?.issue)
        assertEquals(listOf(firstId, laterId), unknown.items.map { it.id })
    }

    @Test fun firstOversizedMissingOrMalformedTitleIsNotShortenedOrSkipped() = runTest {
        val malformedRuns = buildJsonObject { put("runs", buildJsonArray {
            add(buildJsonObject { put("text", "A complete beginning") }); add(buildJsonObject { put("text", 42) })
        }) }
        for ((title, issue) in listOf(
            JsonPrimitive("x".repeat(16_385)) to YouTubeBrowseEntryIssue.TEXT_LIMIT,
            JsonNull to YouTubeBrowseEntryIssue.INVALID_METADATA,
            JsonPrimitive("\uD800") to YouTubeBrowseEntryIssue.INVALID_METADATA,
            malformedRuns to YouTubeBrowseEntryIssue.INVALID_METADATA,
        )) {
            val page = PublicYouTubeBrowseService(Transport(body(video(title = title), video(laterId)))).search("Title")
            assertEquals(listOf(laterId), page.items.map { it.id })
            assertEquals(firstId, page.firstEntry?.id)
            assertNull(page.firstEntry?.title)
            assertEquals(issue, page.firstEntry?.issue)
        }
    }

    @Test fun adsUnknownCardsAndMalformedKnownCardsCannotSilentlySelectTheSecondResult() = runTest {
        for (first in listOf(
            buildJsonObject { put("adSlotRenderer", buildJsonObject { put("content", video()) }) },
            buildJsonObject { put("futureMediaRenderer", buildJsonObject { put("content", video()) }) },
            buildJsonObject { put("unknownCard", buildJsonObject { put("title", "Unrecognized") }) },
            buildJsonObject { put("videoRenderer", 42) },
        )) {
            val page = PublicYouTubeBrowseService(Transport(body(first, video(laterId)))).search("Title")
            assertEquals(listOf(laterId), page.items.map { it.id })
            assertNull(page.firstEntry?.kind)
            assertNull(page.firstEntry?.id)
            assertNotNull(page.firstEntry?.issue)
        }
    }

    @Test fun selectedPrimaryContentsDetermineFirstNotSidebarOrUnselectedTabMetadata() = runTest {
        val data = buildJsonObject { putJsonObject("contents") { putJsonObject("twoColumnSearchResultsRenderer") {
            put("secondaryContents", video(laterId)) // Deliberately serialized before primary.
            put("primaryContents", buildJsonObject { putJsonObject("sectionListRenderer") { put("contents", JsonArray(listOf(video()))) } })
        } } }
        val page = PublicYouTubeBrowseService(Transport(data.toString())).search("Title")
        assertEquals(firstId, page.firstEntry?.id)
        assertEquals(listOf(firstId), page.items.map { it.id })
        val tabs = buildJsonObject { put("tabRenderer", buildJsonObject { put("selected", false); put("content", video(laterId)) }) }
        assertEquals(firstId, PublicYouTubeBrowseService(Transport(body(tabs, video()))).home().firstEntry?.id)
    }

    @Test fun parserLimitBeforeAUsableVideoIsAnUnknownFirstReceiptNotPermissionToSkipIt() = runTest {
        var nested: JsonElement = video()
        repeat(70) { nested = buildJsonObject { put("richItemRenderer", buildJsonObject { put("content", nested) }) } }
        val page = PublicYouTubeBrowseService(Transport(body(nested, video(laterId)))).search("Title")
        assertEquals(YouTubeBrowseEntryIssue.PARSE_LIMIT, page.firstEntry?.issue)
        assertNull(page.firstEntry?.id)
        assertEquals(listOf(laterId), page.items.map { it.id })
    }

    @Test fun trulyEmptyResultsHaveNoEntryButUnrecognizedResponseShapeIsNotEmptySuccess() = runTest {
        assertNull(PublicYouTubeBrowseService(Transport(body())).search("Title").firstEntry)
        val unreadable = PublicYouTubeBrowseService(Transport("{}")).search("Title")
        assertEquals(YouTubeBrowseEntryIssue.INVALID_METADATA, unreadable.firstEntry?.issue)
        assertTrue(unreadable.items.isEmpty())
    }

    @Test fun collectionPageTitleIsCompleteAndInvalidTitleIsRejectedNotReplaced() = runTest {
        val title = "  " + "An album title العربية ".repeat(40) + "  "
        fun document(titleValue: String) = buildJsonObject {
            put("contents", JsonArray(listOf(video())))
            putJsonObject("metadata") { putJsonObject("playlistMetadataRenderer") { put("title", titleValue) } }
        }.toString()
        val service = PublicYouTubeBrowseService(Transport(document(title)))
        assertEquals(title, service.open("$origin/playlist?list=$playlistId").title)
        assertFailsWith<IllegalArgumentException> {
            PublicYouTubeBrowseService(Transport(document("x".repeat(16_385)))).open("$origin/playlist?list=$playlistId")
        }
    }
}
