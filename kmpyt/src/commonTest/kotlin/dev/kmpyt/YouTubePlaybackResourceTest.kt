package dev.kmpyt

import dev.kmpyt.extraction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Resource-ownership tests only; these authored adapters make no media request. */
class YouTubePlaybackResourceTest {
    private val videoId = "ABCDEFGHIJK"
    private val source = "https://rr1.googlevideo.com/videoplayback?id=authored"
    private val request = SourcePlaybackRequest(SourcePlaybackMetadata(videoId, "Authored", MediaKind.AUDIO),
        PlannedDownload(Representation("140", source, TrackRole.AUDIO, TransportKind.SABR,
            "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 100,
            sabr = YouTubeSabrResource(videoId, source, "AQID", SabrFormatId(140, 1uL, "authored"), SabrClientInfo(1, "authored-test"))),
            inspectionUrl = "https://www.youtube.com/watch?v=$videoId", mediaId = videoId))
    private val authorization = SourcePlaybackAuthorization { _, _ -> error("These ownership fixtures do not authorize network access") }
    private fun client(adapter: YouTubePlaybackAdapter? = null, onClose: () -> Unit = {}) =
        YouTubeClient(ResearchTransport { error("These ownership fixtures do not request metadata or media") },
            onClose = onClose, playbackAdapter = adapter)
    private class Resource(length: Long = 100, private val failClose: Boolean = false,
        private val onClose: () -> Unit = {}) : YouTubePlaybackResource {
        override val endpoints = listOf(YouTubePlaybackEndpoint(TrackRole.AUDIO,
            "http://127.0.0.1:32123/${"a".repeat(64)}/audio", "audio/mp4", length))
        var closes = 0
        override suspend fun close() { closes++; onClose(); check(!failClose) { "Authored unknown native release" } }
    }

    @Test fun nativeAdapterIsExplicitAndPlanningAloneDoesNotAcquireResources() = runTest {
        val client = client()
        try {
            assertFalse(client.supportsSabrPlayback)
            assertFailsWith<IllegalArgumentException> { client.openPlayback(request, authorization) }
        } finally { client.close() }
    }

    @Test fun resourcesCloseExactlyOnceBeforeTheOwnedTransport() = runTest {
        val events = mutableListOf<String>()
        val first = Resource(onClose = { events += "first" })
        val second = Resource(onClose = { events += "second" })
        var opens = 0
        val client = client(YouTubePlaybackAdapter { selected, policy ->
            assertSame(request, selected); assertSame(authorization, policy)
            if (opens++ == 0) first else second
        }, onClose = { events += "transport" })
        assertTrue(client.supportsSabrPlayback)
        val one = client.openPlayback(request, authorization)
        val two = client.openPlayback(request, authorization)
        one.close(); one.close()
        client.close(); client.close(); two.close()
        assertEquals(listOf("first", "second", "transport"), events)
        assertEquals(1, first.closes); assertEquals(1, second.closes)
        assertFailsWith<IllegalStateException> { client.openPlayback(request, authorization) }
    }

    @Test fun failedReleaseRemainsTrackedAndCannotTurnIntoASuccessfulDuplicateClose() = runTest {
        val failed = Resource(failClose = true)
        var opens = 0
        var transportClosed = false
        val client = client(YouTubePlaybackAdapter { _, _ -> if (opens++ == 0) failed else Resource() },
            onClose = { transportClosed = true })
        val resources = List(8) { client.openPlayback(request, authorization) }
        repeat(2) { assertFailsWith<IllegalStateException> { resources.first().close() } }
        assertEquals(1, failed.closes)
        assertFailsWith<IllegalStateException> { client.openPlayback(request, authorization) }
        assertEquals(8, opens)
        resources[1].close()
        client.openPlayback(request, authorization)
        assertEquals(9, opens)
        repeat(2) { assertFailsWith<IllegalStateException> { client.close() } }
        assertEquals(1, failed.closes)
        assertTrue(transportClosed)
    }

    @Test fun changedTrackMetadataIsRejectedAndItsUnpublishedResourceIsClosed() = runTest {
        val invalid = Resource(length = 99)
        val client = client(YouTubePlaybackAdapter { _, _ -> invalid })
        try {
            assertFailsWith<IllegalArgumentException> { client.openPlayback(request, authorization) }
            assertEquals(1, invalid.closes)
        } finally { client.close() }
        assertEquals(1, invalid.closes)
    }

    @Test fun failedCleanupOfMalformedAdapterMetadataIsStillOwnedByTheClient() = runTest {
        val invalid = Resource(length = 99, failClose = true)
        val client = client(YouTubePlaybackAdapter { _, _ -> invalid })
        assertFailsWith<IllegalArgumentException> { client.openPlayback(request, authorization) }
        repeat(2) { assertFailsWith<IllegalStateException> { client.close() } }
        assertEquals(1, invalid.closes)
    }

    @Test fun failedAdapterOpenReleasesItsReservation() = runTest {
        var fail = true
        val client = client(YouTubePlaybackAdapter { _, _ -> check(!fail) { "Authored failed open" }; Resource() })
        try {
            repeat(12) { assertFailsWith<IllegalStateException> { client.openPlayback(request, authorization) } }
            fail = false
            client.openPlayback(request, authorization).close()
        } finally { client.close() }
    }

    @Test fun simultaneousUnfinishedOpensCountAgainstTheResourceBound() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutex = Mutex()
        var opens = 0
        val client = client(YouTubePlaybackAdapter { _, _ ->
            mutex.withLock { if (++opens == 8) entered.complete(Unit) }
            release.await()
            Resource()
        })
        val pending = List(8) { async { client.openPlayback(request, authorization) } }
        try {
            entered.await()
            assertFailsWith<IllegalStateException> { client.openPlayback(request, authorization) }
            assertEquals(8, mutex.withLock { opens })
        } finally { release.complete(Unit) }
        pending.awaitAll()
        client.close()
    }

    @Test fun cancellationBeforeResultDeliveryClosesTheAcquiredNativeResource() = runTest {
        val resource = Resource()
        val client = client(YouTubePlaybackAdapter { _, _ ->
            currentCoroutineContext().cancel(CancellationException("Authored cancellation before delivery"))
            resource
        })
        try {
            assertFailsWith<CancellationException> { client.openPlayback(request, authorization) }
            assertEquals(1, resource.closes)
        } finally { client.close() }
        assertEquals(1, resource.closes)
    }

    @Test fun closeWaitsForAnOpeningResourceThenClosesItBeforeTheTransport() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transportClosed = false
        val resource = Resource(onClose = { assertFalse(transportClosed) })
        val client = client(YouTubePlaybackAdapter { _, _ -> entered.complete(Unit); release.await(); resource },
            onClose = { transportClosed = true })
        val opening = async { client.openPlayback(request, authorization) }
        entered.await()
        val closing = async { client.close() }
        release.complete(Unit)
        val owned = opening.await()
        closing.await()
        assertEquals(1, resource.closes); assertTrue(transportClosed)
        owned.close(); assertEquals(1, resource.closes)
    }
}
