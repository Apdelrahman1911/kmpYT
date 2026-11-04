package dev.kmpyt.youtube

import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Shape fixtures cover public parsing and transport boundaries, not current server availability. */
class PublicYouTubeBrowseServiceTest {
    private val origin = "https://www.youtube.com"
    private val id = "ABCDEFGHIJK"
    private val secondId = "LMNOPQRSTUV"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val playlistId = "PLabcdefghijklmnopqrstuv"

    private class Transport(val handler: (ResearchRequest) -> ResearchResponse) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse {
            requests += request
            return handler(request)
        }
    }

    private fun video(videoId: String = id, title: String = "A real title", renderer: String = "videoRenderer") =
        """{"$renderer":{"videoId":"$videoId","title":{"runs":[{"text":"$title"}]},"ownerText":{"runs":[{"text":"Creator"}]},"lengthText":{"simpleText":"3:42"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$videoId/default.jpg","width":120},{"url":"https://i.ytimg.com/vi/$videoId/hqdefault.jpg","width":480}]}}}"""

    private fun initial(contents: String, extra: String = "") =
        """<script>window["ytInitialData"] = {"contents":{"sectionListRenderer":{"contents":[$contents]}}$extra};</script>"""

    private fun token(value: String, endpoint: String? = "/youtubei/v1/browse"): String {
        val metadata = endpoint?.let { """"commandMetadata":{"webCommandMetadata":{"apiUrl":"$it"}},""" }.orEmpty()
        return """{"continuationItemRenderer":{"continuationEndpoint":{$metadata"continuationCommand":{"token":"$value"}}}}"""
    }

    private val config = """<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.actual.version","hl":"en","visitorData":"source-visitor","authorization":"must-not-send"},"user":{"loggedIn":true}},"INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_API_KEY":"source-api-key","INNERTUBE_CLIENT_VERSION":"2.actual.version"});</script>"""

    private fun continuation(contents: String) =
        """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$contents]}}]}"""

    @Test fun channelIdentityIsSourceDeclaredAndSurvivesPaginationWithoutGuessingFromHandle() = runTest {
        val transport = Transport { request -> ResearchResponse(request.url, 200,
            if (request.method == "GET") config + initial("${video()},${token("next")}",
                ""","metadata":{"channelMetadataRenderer":{"title":"Fixture channel","externalId":"$channelId"}}""")
            else continuation(video(secondId))) }
        val service = PublicYouTubeBrowseService(transport)
        val first = service.open("$origin/@fixture/videos")
        assertEquals(channelId, first.channelId)
        assertEquals(channelId, service.more(first).channelId)
        val absent = PublicYouTubeBrowseService(Transport { ResearchResponse(it.url, 200, initial(video())) })
        assertNull(absent.open("$origin/@fixture/videos").channelId)
    }

    @Test fun searchEncodesUserTextAndParsesMixedPublicResults() = runTest {
        val channel = """{"channelRenderer":{"channelId":"$channelId","title":{"simpleText":"A channel"},"subscriberCountText":{"simpleText":"25 subscribers"},"navigationEndpoint":{"browseEndpoint":{"browseId":"$channelId","canonicalBaseUrl":"/@actual_creator"}}}}"""
        val playlist = """{"playlistRenderer":{"playlistId":"$playlistId","title":{"simpleText":"Actual playlist"},"videoCountText":{"runs":[{"text":"12 videos"}]}}}"""
        val transport = Transport { ResearchResponse(it.url, 200, initial("${video()},$channel,$playlist")) }
        val page = PublicYouTubeBrowseService(transport).search(" cats & music ")
        assertEquals("cats & music", Url(transport.requests.single().url).parameters["search_query"])
        assertEquals(listOf(YouTubeBrowseKind.VIDEO, YouTubeBrowseKind.CHANNEL, YouTubeBrowseKind.PLAYLIST), page.items.map { it.kind })
        assertEquals("3:42", page.items[0].durationText)
        assertEquals("https://i.ytimg.com/vi/$id/hqdefault.jpg", page.items[0].thumbnailUrl)
        assertEquals("$origin/@actual_creator/videos", page.items[1].url)
        assertEquals("$origin/playlist?list=$playlistId", page.items[2].url)
        assertEquals("Creator", page.items[0].subtitle)
        assertEquals("GET", transport.requests.single().method)
        assertTrue(transport.requests.single().headers.keys.none { it.equals("cookie", true) || it.equals("authorization", true) })
    }

    @Test fun newLockupsAndShortsUseActualIdentifiersAndMetadata() = runTest {
        val lockup = """{"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Current video shape"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Actual author"}},{"text":{"content":"100 views"}}]}]}}}},"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg","width":480}]},"overlays":[{"thumbnailBadgeViewModel":{"text":"12:34"}}]}}}}}}"""
        val shorts = """{"shortsLockupViewModel":{"overlayMetadata":{"primaryText":{"content":"A short"},"secondaryText":{"content":"40 views"}},"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"$secondId"}}},"thumbnail":{"sources":[{"url":"https://i.ytimg.com/vi/$secondId/hqdefault.jpg","width":480}]}}}"""
        val transport = Transport { ResearchResponse(it.url, 200, initial("$lockup,$shorts")) }
        val items = PublicYouTubeBrowseService(transport).home().items
        assertEquals(listOf(id, secondId), items.map { it.id })
        assertEquals("Current video shape", items[0].title)
        assertEquals("Actual author · 100 views", items[0].subtitle)
        assertEquals("12:34", items[0].durationText)
        assertEquals("$origin/watch?v=$secondId", items[1].url)
    }

    @Test fun playlistOrderAndRepeatedOccurrencesSurviveWhilePrivateEntriesAreOmitted() = runTest {
        val hidden = """{"playlistVideoRenderer":{"videoId":"ZZZZZZZZZZZ","title":{"simpleText":"[Private video]"},"isPlayable":false}}"""
        val html = initial("${video(secondId, renderer = "playlistVideoRenderer")},$hidden,${video(renderer = "playlistVideoRenderer")},${video(secondId, renderer = "playlistVideoRenderer")}",
            ""","metadata":{"playlistMetadataRenderer":{"title":"My ordered playlist"}}""")
        val transport = Transport { ResearchResponse(it.url, 200, html) }
        val page = PublicYouTubeBrowseService(transport).open("$origin/playlist?list=$playlistId&si=ignored")
        assertEquals(listOf(secondId, id, secondId), page.items.map { it.id })
        assertEquals("My ordered playlist", page.title)
        assertTrue(page.notice.orEmpty().contains("Unavailable"))
    }

    @Test fun adsAndOtherChannelTabsAreNotFlattenedIntoPublicResults() = runTest {
        val contents = """{"adSlotRenderer":{"content":${video(secondId)}}},{"tabRenderer":{"selected":false,"content":${video(secondId)}}},${video()}"""
        val transport = Transport { ResearchResponse(it.url, 200, initial(contents)) }
        assertEquals(listOf(id), PublicYouTubeBrowseService(transport).home().items.map { it.id })
    }

    @Test fun paginationUsesDeclaredContextAndEndpointAndAccumulatesResults() = runTest {
        var posts = 0
        val transport = Transport { request ->
            if (request.method == "GET") ResearchResponse(request.url, 200, config + initial("${video()},${token("first", "/youtubei/v1/search")}"))
            else {
                posts++
                val body = Json.parseToJsonElement(requireNotNull(request.body)).jsonObject
                assertEquals(if (posts == 1) "first" else "second", body.getValue("continuation").jsonPrimitive.content)
                val client = body.getValue("context").jsonObject.getValue("client").jsonObject
                assertEquals("WEB", client.getValue("clientName").jsonPrimitive.content)
                assertEquals("source-visitor", client.getValue("visitorData").jsonPrimitive.content)
                assertFalse(client.containsKey("authorization"))
                assertFalse(body.getValue("context").jsonObject.containsKey("user"))
                assertEquals("source-api-key", Url(request.url).parameters["key"])
                assertEquals("/youtubei/v1/search", Url(request.url).encodedPath)
                assertEquals("1", request.headers["X-Youtube-Client-Name"])
                ResearchResponse(request.url, 200, if (posts == 1) continuation("${video()},${video(secondId)},${token("second", "/youtubei/v1/search")}")
                    else continuation("${video(secondId)}"))
            }
        }
        val service = PublicYouTubeBrowseService(transport)
        val first = service.search("test")
        assertEquals("first", first.continuation)
        val second = service.more(first)
        assertEquals(listOf(id, secondId), second.items.map { it.id })
        assertEquals("second", second.continuation)
        val third = service.more(second)
        assertEquals(2, third.items.size)
        assertNull(third.continuation)
        assertEquals(3, transport.requests.size)
    }

    @Test fun playlistPaginationPreservesRepeatedVideoAndServerOrder() = runTest {
        val transport = Transport {
            ResearchResponse(it.url, 200, if (it.method == "GET") config + initial("${video()},${token("next")}")
                else continuation("${video(secondId)},${video()}"))
        }
        val service = PublicYouTubeBrowseService(transport)
        val page = service.more(service.open("$origin/playlist?list=$playlistId"))
        assertEquals(listOf(id, secondId, id), page.items.map { it.id })
    }

    @Test fun repeatedContinuationStopsInsteadOfLooping() = runTest {
        val transport = Transport { ResearchResponse(it.url, 200, if (it.method == "GET") config + initial("${video()},${token("same")}")
            else continuation("${video(secondId)},${token("same")}")) }
        val service = PublicYouTubeBrowseService(transport)
        val page = service.more(service.home())
        assertNull(page.continuation)
        assertTrue(page.notice.orEmpty().contains("repeated"))
        assertEquals(page, service.more(page))
        assertEquals(2, transport.requests.size)
    }

    @Test fun missingOrInconsistentClientDeclarationsDoNotInventPaginationContracts() = runTest {
        for (settings in listOf("", config.replace("\"clientName\":\"WEB\"", "\"clientName\":\"ANDROID\""),
            config.replace("\"INNERTUBE_CLIENT_VERSION\":\"2.actual.version\"", "\"INNERTUBE_CLIENT_VERSION\":\"different\""))) {
            val transport = Transport { ResearchResponse(it.url, 200, settings + initial("${video()},${token("next")}")) }
            val page = PublicYouTubeBrowseService(transport).home()
            assertEquals(1, page.items.size)
            assertNull(page.continuation)
            assertTrue(page.notice.orEmpty().contains("continuation"))
        }
    }

    @Test fun untrustedContinuationDestinationsAndCredentialContextsAreRejected() = runTest {
        val transport = Transport { ResearchResponse(it.url, 200, config + initial("${video()},${token("next", "https://attacker.test/youtubei/v1/browse")}")) }
        assertNull(PublicYouTubeBrowseService(transport).home().continuation)
        val validTransport = Transport { ResearchResponse(it.url, 200, config + initial("${video()},${token("next")}")) }
        val service = PublicYouTubeBrowseService(validTransport)
        val page = service.home()
        val context = requireNotNull(page.continuationContext)
        assertFailsWith<IllegalArgumentException> { service.more(page.copy(continuationContext = context.copy(headers = context.headers + ("Cookie" to "sensitive")))) }
        assertFailsWith<IllegalArgumentException> { service.more(page.copy(sourceUrl = "$origin/playlist?list=$playlistId")) }
        assertEquals(1, validTransport.requests.size)
    }

    @Test fun aBadNextPageDestinationStopsPaginationWithoutContactingIt() = runTest {
        val transport = Transport { ResearchResponse(it.url, 200, if (it.method == "GET") config + initial("${video()},${token("first")}")
            else continuation("${video(secondId)},${token("second", "https://attacker.test/youtubei/v1/browse")}")) }
        val service = PublicYouTubeBrowseService(transport)
        val page = service.more(service.home())
        assertEquals(2, page.items.size)
        assertNull(page.continuation)
        assertTrue(page.notice.orEmpty().contains("unsupported"))
        assertTrue(transport.requests.all { Url(it.url).host == "www.youtube.com" })
    }

    @Test fun failedNextPageRetainsResultsAndCanBeRetried() = runTest {
        var posts = 0
        val transport = Transport { request ->
            if (request.method == "GET") ResearchResponse(request.url, 200, config + initial("${video()},${token("next")}"))
            else if (++posts == 1) ResearchResponse(request.url, 429, "rate limited")
            else ResearchResponse(request.url, 200, continuation(video(secondId)))
        }
        val service = PublicYouTubeBrowseService(transport)
        val failed = service.more(service.home())
        assertEquals(listOf(id), failed.items.map { it.id })
        assertEquals("next", failed.continuation)
        assertTrue(failed.notice.orEmpty().contains("limited"))
        assertEquals(2, service.more(failed).items.size)
    }

    @Test fun onlySamePublicPageRedirectsAreFollowed() = runTest {
        val transport = Transport { request ->
            if (Url(request.url).host == "www.youtube.com") ResearchResponse(request.url, 302, "", mapOf("Location" to "https://m.youtube.com/@creator/videos"))
            else ResearchResponse(request.url, 200, initial(video()))
        }
        assertEquals(1, PublicYouTubeBrowseService(transport).open("$origin/@creator").items.size)
        assertEquals(2, transport.requests.size)
        for (location in listOf("https://consent.youtube.com/", "https://accounts.google.com/", "http://www.youtube.com/@creator/videos", "https://attacker.test/@creator/videos", "$origin/@different/videos")) {
            val denied = Transport { ResearchResponse(it.url, 302, "", mapOf("Location" to location)) }
            val page = PublicYouTubeBrowseService(denied).open("$origin/@creator")
            assertTrue(page.items.isEmpty())
            assertNotNull(page.notice)
            assertEquals(1, denied.requests.size)
        }
    }

    @Test fun invalidUrlsAreRejectedBeforeAnyNetworkRequest() = runTest {
        val transport = Transport { error("must not contact network") }
        val service = PublicYouTubeBrowseService(transport)
        for (url in listOf("https://youtube.com.attacker.test/playlist?list=$playlistId", "https://www.youtube.com:444/@creator", "https://user:pass@www.youtube.com/@creator", "$origin/@creator%2fother", "$origin/watch?v=$id", "$origin/playlist?list=$playlistId&list=another")) {
            assertFailsWith<IllegalArgumentException>(url) { service.open(url) }
        }
        assertFailsWith<IllegalArgumentException> { service.search(" ") }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun emptyHomeAndMissingDataHaveActionableNoticesWithoutFakeCards() = runTest {
        val empty = Transport { ResearchResponse(it.url, 200, initial("")) }
        val page = PublicYouTubeBrowseService(empty).home()
        assertTrue(page.items.isEmpty())
        assertTrue(page.notice.orEmpty().contains("Search"))
        val malformed = Transport { ResearchResponse(it.url, 200, "<script>var ytInitialData = {invalid};</script>") }
        assertTrue(PublicYouTubeBrowseService(malformed).search("a query").items.isEmpty())
        val failing = Transport { error("private network details https://private.test/?secret=value") }
        val failure = PublicYouTubeBrowseService(failing).home()
        assertFalse(failure.notice.orEmpty().contains("secret"))
        assertTrue(failure.notice.orEmpty().contains("connection"))
    }

    @Test fun cancellationIsPropagatedAndOversizeBodiesAreNotParsed() = runTest {
        val cancelling = Transport { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { PublicYouTubeBrowseService(cancelling).home() }
        val oversized = Transport { ResearchResponse(it.url, 200, " ".repeat(8_000_001)) }
        val page = PublicYouTubeBrowseService(oversized).home()
        assertTrue(page.items.isEmpty())
        assertTrue(page.notice.orEmpty().contains("size limit"))
    }

    @Test fun unsupportedImageSchemesAndLookalikeHostsNeverReachCards() = runTest {
        val transport = Transport { ResearchResponse(it.url, 200, initial(video().replace("https://i.ytimg.com/", "https://i.ytimg.com.attacker.test/"))) }
        assertNull(PublicYouTubeBrowseService(transport).home().items.single().thumbnailUrl)
    }
}
