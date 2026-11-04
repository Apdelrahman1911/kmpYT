package dev.kmpyt.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.*

/** Unfinished channels prove limits are enforced before Ktor attempts to save a whole body. */
class KtorResearchTransportTest {
    private val endpoint = "https://owned.example/response"

    @Test fun requestMethodHeadersBodyAndActualUtf8ResponseArePreserved() = runTest {
        val body = "{\"title\":\"مقطع\"}"
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("kmpYT-Research/1.0", request.headers[HttpHeaders.UserAgent])
            // Ktor can move entity headers into OutgoingContent before engine dispatch.
            assertEquals("application/json", request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString())
            assertEquals("owned-session", request.headers["X-Owned-Test"])
            assertEquals("{\"videoId\":\"owned\"}", (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
        }) { followRedirects = false }
        try {
            val transport = KtorResearchTransport(client, maximumBytes = 128) { mapOf("X-Owned-Test" to "owned-session") }
            val result = transport.execute(ResearchRequest("POST", endpoint, mapOf(HttpHeaders.ContentType to "application/json"), "{\"videoId\":\"owned\"}"))
            assertEquals(200, result.status)
            assertEquals(body, result.body)
            assertEquals("application/json; charset=utf-8", result.headers["content-type"])
        } finally { client.close() }
    }

    @Test fun invalidLimitMethodAndOriginAreRejectedBeforeHttp() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("No request allowed") }) { followRedirects = false }
        try {
            listOf(0, 8_000_001).forEach { limit ->
                assertFailsWith<IllegalArgumentException> { KtorResearchTransport(client, limit).execute(ResearchRequest("GET", endpoint)) }
            }
            assertFailsWith<IllegalArgumentException> { KtorResearchTransport(client).execute(ResearchRequest("PUT", endpoint)) }
            assertFailsWith<IllegalArgumentException> { KtorResearchTransport(client).execute(ResearchRequest("GET", "http://owned.example/response")) }
            assertEquals(0, requests)
        } finally { client.close() }
    }

    @Test fun oversizedHeaderRejectsAndClosesUnfinishedBodyWithoutWaitingForEof() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) }) { followRedirects = false }
        try {
            val error = withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    assertFailsWith<IllegalArgumentException> { KtorResearchTransport(client, 32).execute(ResearchRequest("GET", endpoint)) }
                }
            }
            assertTrue(error.message.orEmpty().contains("byte limit"))
            assertNotNull(channel.closedCause)
            assertTrue(channel.isClosedForRead)
        } finally { channel.cancel(); client.close() }
    }

    @Test fun unknownLengthOverflowRejectsAndClosesBodyWithoutWaitingForEof() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully(ByteArray(64))
        channel.flush()
        val client = HttpClient(MockEngine { respond(channel, HttpStatusCode.OK) }) { followRedirects = false }
        try {
            val error = withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    assertFailsWith<IllegalArgumentException> { KtorResearchTransport(client, 32).execute(ResearchRequest("GET", endpoint)) }
                }
            }
            assertTrue(error.message.orEmpty().contains("byte limit"))
            assertNotNull(channel.closedCause)
            assertTrue(channel.isClosedForRead)
        } finally { channel.cancel(); client.close() }
    }

    @Test fun cancellationWhileReadingClosesTheStreamingResponse() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val started = CompletableDeferred<Unit>()
        val observed = object : ByteReadChannel by channel {
            override suspend fun awaitContent(min: Int): Boolean {
                started.complete(Unit)
                return channel.awaitContent(min)
            }
        }
        val client = HttpClient(MockEngine { respond(observed, HttpStatusCode.OK) }) { followRedirects = false }
        try {
            val reading = async { KtorResearchTransport(client, 32).execute(ResearchRequest("GET", endpoint)) }
            started.await()
            reading.cancelAndJoin()
            assertNotNull(channel.closedCause)
            assertTrue(channel.isClosedForRead)
        } finally { channel.cancel(); client.close() }
    }

    @Test fun headAcceptsLargeEntityLengthWithoutDownloadingItsBody() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Head, request.method)
            respond(ByteReadChannel.Empty, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000000000"))
        }) { followRedirects = false }
        try {
            val response = KtorResearchTransport(client, 32).execute(ResearchRequest("HEAD", endpoint))
            assertEquals("", response.body)
            assertEquals("1000000000", response.headers["content-length"])
        } finally { client.close() }
    }
}
