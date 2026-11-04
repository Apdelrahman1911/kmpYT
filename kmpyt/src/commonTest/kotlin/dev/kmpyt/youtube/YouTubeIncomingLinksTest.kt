package dev.kmpyt.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeIncomingLinksTest {
    private val id = "ABCDEFGHIJK"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test fun extractsTheVideoFromSharedTitleAndPunctuation() {
        assertEquals(canonical, YouTubeIncomingLinks.extract("A useful video\nWatch here: (https://youtu.be/$id?si=shared)."))
        assertEquals(canonical, YouTubeIncomingLinks.extract("<https://www.youtube.com/watch?v=$id&feature=shared>"))
        assertEquals(canonical, YouTubeIncomingLinks.extract("https://www.youtube.com/watch?feature=shared&amp;v=$id"))
    }

    @Test fun supportsActualSingleVideoLinkFormsAndDropsUnrelatedTracking() {
        listOf("https://m.youtube.com/watch?v=$id&t=20", "https://music.youtube.com/watch?v=$id&list=PLpublic",
            "https://www.youtube.com/shorts/$id", "https://www.youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id", "https://www.youtube.com/v/$id").forEach {
            assertEquals(canonical, YouTubeIncomingLinks.extract(it), it)
        }
    }

    @Test fun channelAndPlaylistSharesUseThePublicBrowseValidator() {
        assertEquals("https://www.youtube.com/@creator/videos", YouTubeIncomingLinks.extract("Creator: https://www.youtube.com/@creator?si=share"))
        assertEquals("https://www.youtube.com/@creator/shorts", YouTubeIncomingLinks.extract("https://m.youtube.com/@creator/shorts"))
        assertEquals("https://www.youtube.com/@creator/videos", YouTubeIncomingLinks.extract("https://www.youtube.com/%40creator/videos"))
        assertEquals("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv/videos",
            YouTubeIncomingLinks.extract("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertEquals("https://www.youtube.com/playlist?list=PLabcdefghijklmnop",
            YouTubeIncomingLinks.extract("Listen: https://music.youtube.com/playlist?list=PLabcdefghijklmnop&si=share"))
    }

    @Test fun rejectsCredentialsUnknownHostsAndMalformedOrAmbiguousLinks() {
        listOf("http://www.youtube.com/watch?v=$id", "https://www.youtube.com.attacker.test/watch?v=$id",
            "https://attacker.youtube.com/watch?v=$id", "https://user:secret@www.youtube.com/watch?v=$id",
            "https://www.youtube.com:444/watch?v=$id", "https://youtu.be/$id/extra",
            "https://www.youtube.com/watch?v=$id&v=LMNOPQRSTUV", "https://www.youtube.com/shorts/$id/extra",
            "https://www.youtube.com/@creator%2fother", "https://www.youtube.com/playlist?list=one&list=two",
            "https://www.youtube.com/watch?v=too-short", "nothttps://youtu.be/$id").forEach {
            assertNull(YouTubeIncomingLinks.extract(it), it)
        }
    }

    @Test fun choosesFirstSupportedLinkWithoutExtractingNestedRedirectParameters() {
        assertEquals(canonical, YouTubeIncomingLinks.extract("https://other.test/a https://youtu.be/$id https://youtu.be/LMNOPQRSTUV"))
        assertNull(YouTubeIncomingLinks.extract("https://other.test/redirect?url=https://youtu.be/$id"))
        assertNull(YouTubeIncomingLinks.extract("https://www.youtube.com/redirect?q=https://youtu.be/$id"))
    }

    @Test fun arbitrarySearchAccountAndHomepageLinksAreNotMediaShares() {
        listOf("https://www.youtube.com/", "https://www.youtube.com/results?search_query=music",
            "https://www.youtube.com/feed/subscriptions", "https://accounts.google.com/", "A title without a link").forEach {
            assertNull(YouTubeIncomingLinks.extract(it))
        }
    }

    @Test fun oversizedOrInvisibleControlInputIsRejectedWithoutTruncatingItIntoAValidLink() {
        assertNull(YouTubeIncomingLinks.extract(null))
        assertNull(YouTubeIncomingLinks.extract(""))
        assertNull(YouTubeIncomingLinks.extract("a".repeat(YouTubeIncomingLinks.MAX_SHARED_TEXT) + " https://youtu.be/$id"))
        assertNull(YouTubeIncomingLinks.extract("https://youtu.be/${id}\u202E"))
    }
}
