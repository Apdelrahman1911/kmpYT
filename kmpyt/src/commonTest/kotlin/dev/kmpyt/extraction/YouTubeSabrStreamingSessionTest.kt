package dev.kmpyt.extraction

import dev.kmpyt.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

/** Isolated range/authorization invariants. Actual HTTP and codec tests live in platform/androidTest. */
class YouTubeSabrStreamingSessionTest {
    private val id = SabrFormatId(137, 1uL, "controlled")
    private val clientInfo = SabrClientInfo(1, "controlled-test")
    private val endpoint = "http://localhost/sabr"
    private fun request(): SourcePlaybackRequest {
        val representation = Representation("video", endpoint, TrackRole.VIDEO, TransportKind.SABR,
            "video/mp4", sizeBytes = 10, sabr = YouTubeSabrResource("ABCDEFGHIJK", endpoint, "AQID", id, clientInfo))
        return SourcePlaybackRequest(SourcePlaybackMetadata("ABCDEFGHIJK", "Controlled", MediaKind.VIDEO),
            PlannedDownload(representation, inspectionUrl = "http://localhost/capture", mediaId = "ABCDEFGHIJK"))
    }
    private class Cache : YouTubeSabrPlaybackCache {
        val bytes = ByteArray(10)
        var length = 0L
        var closed = false
        var reads = 0
        override suspend fun size() = length
        override suspend fun read(offset: Long, count: Int): ByteArray {
            check(!closed); reads++; return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
        override suspend fun write(offset: Long, bytes: ByteArray) {
            check(!closed); bytes.copyInto(this.bytes, offset.toInt()); length = maxOf(length, offset + bytes.size)
        }
        override suspend fun close() { closed = true }
    }
    private fun packet(offset: Int, content: ByteArray, sequence: Int): ByteArray {
        fun number(tag: Int, value: Long) = SabrWireField(tag, SabrWireValue.Varint(value.toULong()))
        fun binary(tag: Int, value: ByteArray) = SabrWireField(tag, SabrWireValue.Bytes(value))
        fun record(kind: Int, body: ByteArray) = YouTubeUmpFraming.encodeInteger(kind) + YouTubeUmpFraming.encodeInteger(body.size) + body
        val header = YouTubeProtobufWire.encode(listOf(number(1, 1), binary(2, "ABCDEFGHIJK".encodeToByteArray()),
            number(6, offset.toLong()), number(7, 0), number(8, 0), number(9, sequence.toLong()),
            number(11, sequence * 100L), number(12, 100), binary(13, YouTubeSabrProtocol.encodeFormatId(id)), number(14, content.size.toLong())))
        return record(20, header) + record(21, byteArrayOf(1) + content) + record(22, byteArrayOf(1))
    }

    private fun protection(status: Int): ByteArray = YouTubeUmpFraming.encodePart(58,
        YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(status.toULong())))))

    private fun suppliedMediaContext(body: OutgoingContent): ByteArray? {
        val request = YouTubeProtobufWire.decode((body as OutgoingContent.ByteArrayContent).bytes())
        val streamer = request.single { it.number == 19 }.value as SabrWireValue.Bytes
        return (YouTubeProtobufWire.decode(streamer.value).singleOrNull { it.number == 2 }?.value as? SabrWireValue.Bytes)?.value
    }

    @Test fun explicitScopedMediaIssuerRefreshFlowsThroughPlaybackWithoutUsingCachedPlayerContext() = runTest {
        val selected = request()
        val binding = YouTubeIntegrityBinding.forSabr(selected.plan.sabrSource(), clientInfo, "authored-visitor-binding")
        val issueRequests = mutableListOf<YouTubeIntegrityIssueRequest>()
        val playerGrant = byteArrayOf(9, 9)
        val firstMediaGrant = byteArrayOf(1, 2)
        val refreshedMediaGrant = byteArrayOf(3, 4)
        val issuer = object : YouTubeIntegrityIssuer {
            override val id = "authored-playback-issuer"
            override val capabilities = YouTubeIntegrityCapabilities(YouTubeIntegrityScope.entries.toSet())
            override suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext {
                issueRequests += request
                val value = when {
                    request.scope == YouTubeIntegrityScope.PLAYER_REQUEST -> playerGrant
                    request.reason == YouTubeIntegrityIssueReason.FIRST_USE -> firstMediaGrant
                    else -> refreshedMediaGrant
                }
                return YouTubeIssuedIntegrityContext(id, request.scope, request.binding, value, 1_000, 60_000)
            }
        }
        val manager = YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(listOf(issuer)), this, { 1_000 })
        manager.acquire(issuer.id, YouTubeIntegrityScope.PLAYER_REQUEST, binding)
        var calls = 0
        val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                calls++
                assertContentEquals(if (calls == 1) firstMediaGrant else refreshedMediaGrant, suppliedMediaContext(request.body))
                respond(if (calls == 1) protection(3) else packet(0, payload, 0),
                    headers = headersOf("Content-Type", "application/vnd.yt-ump"))
            }
        } }
        val cache = Cache()
        val session = YouTubeSabrStreamingSession(client, selected, mapOf(TrackRole.VIDEO to cache), this,
            SourcePlaybackAuthorization { actual, url -> assertSame(selected, actual); require(url.substringBefore('?') == endpoint) },
            integrityAccess = YouTubeSabrIntegrityAccess(manager, issuer.id, binding))
        try {
            session.start()
            val terminal = session.state.first { it.status in setOf(SabrStreamingStatus.COMPLETE, SabrStreamingStatus.FAILED) }
            assertEquals(SabrStreamingStatus.COMPLETE, terminal.status, "Source session: $terminal")
            assertEquals(2, calls); assertContentEquals(payload, session.read(TrackRole.VIDEO, 0, 10))
            assertEquals(listOf(YouTubeIntegrityScope.PLAYER_REQUEST, YouTubeIntegrityScope.MEDIA_RESOURCE,
                YouTubeIntegrityScope.MEDIA_RESOURCE), issueRequests.map { it.scope })
            assertEquals(YouTubeIntegrityIssueReason.REJECTED_CONTEXT, issueRequests.last().reason)
            assertTrue(issueRequests.all { it.binding == binding })
            assertContentEquals(firstMediaGrant, issueRequests.last().previous!!.copyValue())
            assertEquals(listOf(3), terminal.responseDiagnostics.first().protectionStatuses)
            assertEquals(SabrResponseProtectionAction.REQUIRED_REFRESH_EMPTY, terminal.responseDiagnostics.first().action)
            assertEquals(0L, terminal.responseDiagnostics.first().committedBytes)
        } finally { session.close(); manager.close(); client.close() }
    }

    @Test fun deniedMandatoryMediaContextRefreshStopsPlaybackWithoutPublishingBytesOrRetryingTheSource() = runTest {
        val selected = request()
        val binding = YouTubeIntegrityBinding.forSabr(selected.plan.sabrSource(), clientInfo)
        val issueRequests = mutableListOf<YouTubeIntegrityIssueRequest>()
        val actualGrant = byteArrayOf(5, 6)
        val issuer = object : YouTubeIntegrityIssuer {
            override val id = "authored-denying-playback-issuer"
            override val capabilities = YouTubeIntegrityCapabilities(setOf(YouTubeIntegrityScope.MEDIA_RESOURCE))
            override suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext {
                issueRequests += request
                if (request.reason == YouTubeIntegrityIssueReason.REJECTED_CONTEXT)
                    throw YouTubeIntegrityException(YouTubeIntegrityError.ISSUER_DENIED)
                return YouTubeIssuedIntegrityContext(id, request.scope, request.binding, actualGrant, 1_000, 60_000)
            }
        }
        val manager = YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(listOf(issuer)), this, { 1_000 })
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                calls++; assertContentEquals(actualGrant, suppliedMediaContext(request.body))
                respond(protection(3), headers = headersOf("Content-Type", "application/vnd.yt-ump"))
            }
        } }
        val cache = Cache()
        val session = YouTubeSabrStreamingSession(client, selected, mapOf(TrackRole.VIDEO to cache), this,
            SourcePlaybackAuthorization { _, url -> require(url.substringBefore('?') == endpoint) },
            integrityAccess = YouTubeSabrIntegrityAccess(manager, issuer.id, binding))
        try {
            session.start()
            val stopped = session.state.first { it.status in setOf(SabrStreamingStatus.COMPLETE, SabrStreamingStatus.FAILED) }
            assertEquals(SabrStreamingStatus.FAILED, stopped.status)
            assertEquals("SABR_PROTECTION_REFRESH_FAILED", stopped.errorCode)
            assertEquals(1, calls); assertEquals(0L, stopped.bytesWritten); assertEquals(0L, cache.length)
            assertTrue(issueRequests.all { it.scope == YouTubeIntegrityScope.MEDIA_RESOURCE && it.binding == binding })
            assertEquals(listOf(YouTubeIntegrityIssueReason.FIRST_USE, YouTubeIntegrityIssueReason.REJECTED_CONTEXT),
                issueRequests.map { it.reason })
            val receipt = stopped.responseDiagnostics.single()
            assertEquals(listOf(3), receipt.protectionStatuses)
            assertEquals("ISSUER_DENIED", receipt.integrityCode); assertEquals(0L, receipt.committedBytes)
            assertEquals("SABR_PROTECTION_REFRESH_FAILED",
                assertFailsWith<SabrStreamingException> { session.read(TrackRole.VIDEO, 0, 1) }.code)
        } finally { session.close(); manager.close(); client.close() }
    }

    @Test fun absentAuthorizationNeverRequestsOrReadsCache() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; error("Authorization must stop this request.") })
        val cache = Cache()
        val session = YouTubeSabrStreamingSession(client, request(), mapOf(TrackRole.VIDEO to cache), this)
        try {
            assertEquals("SABR_PLAYBACK_AUTHORIZATION_REQUIRED", assertFailsWith<SabrStreamingException> { session.start() }.code)
            assertEquals(0, calls); assertEquals(0, cache.reads)
        } finally { session.close(); client.close() }
    }

    @Test fun sparseLengthDoesNotExposeHolesAndByteSeekWaitsForVerifiedCoverage() = runTest {
        val releaseHead = CompletableDeferred<Unit>()
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            // Both mock HTTP and the session use one virtual scheduler. Otherwise
            // virtual request timeouts can run before an external engine thread responds.
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                if (calls == 1) respond(packet(5, byteArrayOf(5, 6, 7, 8, 9), 1), headers = headersOf("Content-Type", "application/vnd.yt-ump"))
                else { releaseHead.await(); respond(packet(0, byteArrayOf(0, 1, 2, 3, 4), 0), headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
            }
        } }
        val cache = Cache()
        val session = YouTubeSabrStreamingSession(client, request(), mapOf(TrackRole.VIDEO to cache), this,
            SourcePlaybackAuthorization { _, _ -> })
        try {
            session.start()
            val firstCoverage = session.state.first { it.bytesWritten == 5L || it.status in setOf(SabrStreamingStatus.FAILED, SabrStreamingStatus.CLOSED) }
            assertEquals(5L, firstCoverage.bytesWritten, "First range: $firstCoverage")
            assertEquals(10L, cache.length)
            assertContentEquals(byteArrayOf(5, 6, 7), session.read(TrackRole.VIDEO, 5, 3))
            val head = async { session.read(TrackRole.VIDEO, 0, 4) }
            yield(); assertFalse(head.isCompleted)
            releaseHead.complete(Unit)
            assertContentEquals(byteArrayOf(0, 1, 2, 3), head.await())
            val terminal = session.state.first { it.status in setOf(SabrStreamingStatus.COMPLETE, SabrStreamingStatus.FAILED, SabrStreamingStatus.CLOSED) }
            assertEquals(SabrStreamingStatus.COMPLETE, terminal.status, "Final range: $terminal")
            assertContentEquals(byteArrayOf(2, 3, 4, 5), session.read(TrackRole.VIDEO, 2, 4))
            assertTrue(session.read(TrackRole.VIDEO, 10, 4).isEmpty())
            assertFailsWith<IllegalArgumentException> { session.read(TrackRole.VIDEO, 11, 1) }
            assertEquals(10L, session.state.value.bytesWritten)
            val receipts = session.state.value.responseDiagnostics
            assertEquals(2, receipts.size)
            assertTrue(receipts.all { it.httpStatus == 200 && it.completeFraming && it.committedBytes == 5L })
            assertEquals(listOf(5uL, 0uL), receipts.flatMap { it.mediaHeaders }.map { it.offset })
            assertFalse(receipts.toString().contains(endpoint))
        } finally { releaseHead.complete(Unit); session.close(); client.close() }
    }

    @Test fun authorizationIsRecheckedBeforeTheSecondActualProtocolRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; respond(packet(0, byteArrayOf(0, 1, 2, 3, 4), 0), headers = headersOf("Content-Type", "application/vnd.yt-ump")) }
        } }
        val session = YouTubeSabrStreamingSession(client, request(), mapOf(TrackRole.VIDEO to Cache()), this,
            SourcePlaybackAuthorization { _, url -> require(!url.contains("rn=2")) })
        try {
            session.start()
            val stopped = session.state.first { it.status == SabrStreamingStatus.FAILED }
            assertEquals("SABR_RESOURCE_NOT_AUTHORIZED", stopped.errorCode); assertEquals(1, calls)
            assertEquals(1, stopped.responseDiagnostics.size)
            assertEquals(5L, stopped.responseDiagnostics.single().committedBytes)
            assertEquals("SABR_RESOURCE_NOT_AUTHORIZED", assertFailsWith<SabrStreamingException> { session.read(TrackRole.VIDEO, 5, 1) }.code)
        } finally { session.close(); client.close() }
    }

    @Test fun closeUnblocksARealWaitingRangeAndDisposesCache() = runTest {
        val entered = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { entered.complete(Unit); awaitCancellation() }
        } }
        val cache = Cache()
        val session = YouTubeSabrStreamingSession(client, request(), mapOf(TrackRole.VIDEO to cache), this,
            SourcePlaybackAuthorization { _, _ -> })
        try {
            session.start(); entered.await()
            val read = async { runCatching { session.read(TrackRole.VIDEO, 0, 1) } }
            yield(); session.close()
            assertEquals("SABR_PLAYBACK_CLOSED", (read.await().exceptionOrNull() as SabrStreamingException).code)
            assertTrue(cache.closed)
        } finally { session.close(); client.close() }
    }

    @Test fun reportSelectionRejectsMissingOrCrossSourceRepresentations() {
        val original = request()
        val media = ExtractedMedia(original.metadata.id, ProviderId.YOUTUBE, "Actual title", "http://localhost/capture",
            listOf(original.plan.primary), thumbnailUrl = "http://localhost/thumbnail", durationMs = 1000)
        val report = ExtractionReport(media.pageUrl, ProviderId.YOUTUBE, listOf(media), emptyList())
        val selected = SourcePlaybackRequest.fromReport(report, media.id, "video")
        assertEquals(media.title, selected.metadata.title); assertEquals(media.thumbnailUrl, selected.metadata.thumbnailUrl)
        assertFailsWith<NoSuchElementException> { SourcePlaybackRequest.fromReport(report, media.id, "invented-quality") }
        assertFailsWith<IllegalArgumentException> { selected.copy(metadata = selected.metadata.copy(id = "ZYXWVUTSRQP")).requireStaticSabr() }
        assertFalse(selected.toString().contains(endpoint))
    }

    @Test fun failedCacheCleanupStillClosesOtherOwnedCachesAndRedactsTheFailure() = runTest {
        val original = request()
        val audio = original.plan.primary.copy(id = "audio", role = TrackRole.AUDIO, mimeType = "audio/mp4",
            sabr = original.plan.primary.sabr!!.copy(formatId = SabrFormatId(140, 2uL, "controlled")))
        val selected = original.copy(plan = original.plan.copy(audio = audio))
        val client = HttpClient(MockEngine { error("A closed idle session does not request media.") })
        val remaining = Cache()
        val broken = object : YouTubeSabrPlaybackCache by Cache() {
            override suspend fun close() { throw IllegalStateException("signed-url-private-payload") }
        }
        val session = YouTubeSabrStreamingSession(client, selected, mapOf(TrackRole.VIDEO to broken, TrackRole.AUDIO to remaining), this)
        try {
            val failure = assertFailsWith<SabrStreamingException> { session.close() }
            assertEquals("SABR_PLAYBACK_CLEANUP_FAILED", failure.code)
            assertFalse(failure.toString().contains("signed-url-private-payload")); assertTrue(remaining.closed)
        } finally { client.close() }
    }
}
