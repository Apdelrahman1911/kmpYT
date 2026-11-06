package dev.kmpyt.internal

import dev.kmpyt.YouTubePlaybackEndpoint
import dev.kmpyt.extraction.TrackRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHttpProtocolTest {
    private val route = "/${"a".repeat(64)}/audio"
    private val endpoint = YouTubePlaybackEndpoint(TrackRole.AUDIO, "http://127.0.0.1:32123$route", "audio/mp4", 100)
    private fun response(method: String = "GET", path: String = route, host: String = "127.0.0.1:32123", vararg fields: String) =
        PlaybackHttpProtocol.response(("$method $path HTTP/1.1\r\nHost: $host\r\n" +
            fields.joinToString("") { "$it\r\n" } + "\r\n").encodeToByteArray(), listOf(endpoint), 16_384)

    @Test fun fullGetAndHeadHaveExactDeclaredLengthWithoutCachingTheBearerUrl() {
        val get = response()
        assertEquals(200, get.status); assertEquals(100, get.length); assertTrue(get.sendBody)
        val head = response("HEAD", fields = arrayOf("Range: bytes=20-30"))
        assertEquals(200, head.status); assertEquals(100, head.length); assertFalse(head.sendBody)
        val headers = get.headers().decodeToString()
        assertTrue("Content-Length: 100\r\n" in headers)
        assertTrue("Cache-Control: no-store\r\n" in headers)
        assertFalse(route in headers); assertFalse(route in endpoint.toString())
    }

    @Test fun exactClosedOpenAndSuffixRangesClampOnlyAtTheActualTrackEnd() {
        for ((range, start, length) in listOf(
            Triple("bytes=10-19", 10L, 10L), Triple("bytes=98-", 98L, 2L),
            Triple("bytes=-4", 96L, 4L), Triple("bytes=0-999", 0L, 100L), Triple("bytes=-999", 0L, 100L),
        )) {
            val result = response(fields = arrayOf("Range: $range"))
            assertEquals(206, result.status); assertEquals(start, result.offset); assertEquals(length, result.length)
            assertTrue("Content-Range: bytes $start-${start + length - 1}/100\r\n" in result.headers().decodeToString())
        }
    }

    @Test fun multipleUnsatisfiableAndOverflowRangesAreNeverSilentlyChanged() {
        for (range in listOf("bytes=100-", "bytes=5-4", "bytes=-0", "bytes=-", "bytes=0-1,3-4", "units=0-1",
            "bytes=9999999999999999999999-", "bytes=0-9999999999999999999999")) {
            val result = response(fields = arrayOf("Range: $range"))
            assertEquals(416, result.status)
            assertEquals(0, result.length)
            assertFalse(result.sendBody)
            assertTrue("Content-Range: bytes */100\r\n" in result.headers().decodeToString())
        }
    }

    @Test fun unknownIfRangeNeverBorrowsAValidatorFromAnotherCacheGeneration() {
        val result = response(fields = arrayOf("Range: bytes=20-30", "If-Range: \"old\""))
        assertEquals(200, result.status); assertEquals(0, result.offset); assertEquals(100, result.length)
    }

    @Test fun targetAndHostMustMatchTheExactOwnedNumericLoopbackCapability() {
        for (path in listOf("/", "$route?copy=1", "$route/../video", "http://127.0.0.1:32123$route", route.replace('a', 'b'),
            route.replace("/audio", "/%61udio"))) assertEquals(404, response(path = path).status)
        for (host in listOf("localhost:32123", "attacker.example:32123", "127.0.0.1:32124", "127.0.0.1"))
            assertEquals(404, response(host = host).status)
    }

    @Test fun browserOriginsDuplicateHeadersBodiesAndUnsupportedMethodsAreRejected() {
        assertEquals(403, response(fields = arrayOf("Origin: https://example.test")).status)
        assertEquals(403, response(fields = arrayOf("Sec-Fetch-Site: cross-site")).status)
        for (fields in listOf(arrayOf("Content-Length: 1"), arrayOf("Transfer-Encoding: chunked"),
            arrayOf("Host: 127.0.0.1:32123"), arrayOf("Range: bytes=0-1", "range: bytes=3-4"),
            arrayOf("Expect: 100-continue"), arrayOf("Bad Name: value"), arrayOf(" Folded: value")))
            assertEquals(400, response(fields = fields).status)
        assertEquals(405, response("POST").status)
        assertEquals(405, response("CONNECT").status)
        assertEquals(200, response(fields = arrayOf("Content-Length: 0")).status)
    }

    @Test fun malformedNonAsciiIncompleteAndOversizedHeadersAreBounded() {
        for (header in listOf("GET $route HTTP/1.1\nHost: 127.0.0.1:32123\n\n",
            "GET $route HTTP/1.1\r\nHost: 127.0.0.1:32123\r\nX: \u0000\r\n\r\n",
            "GET $route HTTP/2\r\nHost: 127.0.0.1:32123\r\n\r\n",
            "GET $route HTTP/1.1\r\nHost: 127.0.0.1:32123\r\nX: عربي\r\n\r\n",
            "GET $route HTTP/1.1\r\nHost: 127.0.0.1:32123\r\n"))
            assertEquals(400, PlaybackHttpProtocol.response(header.encodeToByteArray(), listOf(endpoint), 16_384).status)
        assertEquals(431, PlaybackHttpProtocol.response(ByteArray(257), listOf(endpoint), 256).status)
    }
}
