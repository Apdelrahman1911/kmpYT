package dev.kmpyt.extraction

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Isolated lifecycle invariants; actual HTTP credential issuance is tested separately on Desktop. */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeIntegrityContextTest {
    private val media = YouTubeIntegrityScope.MEDIA_RESOURCE
    private val player = YouTubeIntegrityScope.PLAYER_REQUEST
    private val binding = YouTubeIntegrityBinding("ABCDEFGHIJK", SabrClientInfo(1, "owned-client"), "actual-resource-generation", "owned-visitor")
    private val policy = YouTubeIntegrityCachePolicy(retryDelayMs = 0)
    private class Issuer(val produce: suspend (YouTubeIntegrityIssueRequest) -> YouTubeIssuedIntegrityContext,
        override val capabilities: YouTubeIntegrityCapabilities = YouTubeIntegrityCapabilities(YouTubeIntegrityScope.entries.toSet()),
        override val id: String = "owned-issuer") : YouTubeIntegrityIssuer {
        var calls = 0
        override suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext { calls++; return produce(request) }
    }
    private fun issued(request: YouTubeIntegrityIssueRequest, value: Int = 1, now: Long = 0, expiry: Long? = 1_000) =
        YouTubeIssuedIntegrityContext("owned-issuer", request.scope, request.binding, byteArrayOf(value.toByte()), now, expiry)
    private fun manager(scope: CoroutineScope, issuer: Issuer, clock: () -> Long = { 0 }) =
        YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(listOf(issuer)), scope, clock, policy)

    @Test fun concurrentSameBindingAcquisitionsExecuteOneIssuerCall() = runTest {
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> delay(50); issued(request) })
        val manager = manager(this, issuer)
        try {
            val results = (1..20).map { async { manager.acquire(issuer.id, media, binding) } }.awaitAll()
            assertEquals(1, issuer.calls); assertTrue(results.all { it === results.first() })
            assertSame(results.first(), manager.acquire(issuer.id, media, binding))
        } finally { manager.close() }
    }

    @Test fun scopeVideoClientVisitorAndResourceGenerationNeverShareCredentials() = runTest {
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> issued(request) })
        val manager = manager(this, issuer)
        try {
            val variants = listOf(binding, binding.copy(videoId = "ZYXWVUTSRQP"),
                binding.copy(clientInfo = SabrClientInfo(2, "owned-client")),
                binding.copy(clientInfo = SabrClientInfo(1, "new-version")),
                binding.copy(visitorBinding = "other-owned-visitor"), binding.copy(resourceGeneration = "new-source-generation"))
            for (variant in variants) manager.acquire(issuer.id, media, variant)
            manager.acquire(issuer.id, player, binding)
            assertEquals(7, issuer.calls)
        } finally { manager.close() }
    }

    @Test fun knownExpiryRefreshAndOldRejectionCannotEvictNewerCredential() = runTest {
        var now = 10L
        val reasons = mutableListOf<YouTubeIntegrityIssueReason>()
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> reasons += request.reason; issued(request, reasons.size, now, now + 10) })
        val manager = manager(this, issuer) { now }
        try {
            val old = manager.acquire(issuer.id, media, binding)
            assertTrue(manager.isUsable(old))
            now = 21
            assertFalse(manager.isUsable(old))
            val newer = manager.acquire(issuer.id, media, binding)
            assertEquals(listOf(YouTubeIntegrityIssueReason.FIRST_USE, YouTubeIntegrityIssueReason.KNOWN_EXPIRY), reasons)
            manager.invalidate(old)
            assertTrue(manager.isUsable(newer))
            assertSame(newer, manager.acquire(issuer.id, media, binding))
            val refreshed = manager.refresh(issuer.id, media, binding, newer)
            assertEquals(YouTubeIntegrityIssueReason.REJECTED_CONTEXT, reasons.last())
            assertNotSame(newer, refreshed); assertEquals(3, issuer.calls)
            assertFalse(manager.isUsable(newer)); assertTrue(manager.isUsable(refreshed))
        } finally { manager.close() }
    }

    @Test fun unknownIssuerExpiryIsNotInventedOrCachedAcrossCalls() = runTest {
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> issued(request, expiry = null) })
        val manager = manager(this, issuer)
        try {
            val unknown = manager.acquire(issuer.id, media, binding)
            assertNull(unknown.expiresAtEpochMs); assertFalse(manager.isUsable(unknown))
            manager.acquire(issuer.id, media, binding)
            assertEquals(2, issuer.calls)
        } finally { manager.close() }
    }

    @Test fun transientFailuresRetryOnlyExplicitlyAndDenialsDoNotRetry() = runTest {
        var attempts = 0
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest ->
            if (attempts++ == 0) throw YouTubeIntegrityException(YouTubeIntegrityError.ISSUER_TEMPORARILY_UNAVAILABLE)
            assertEquals(1, request.attempt); issued(request)
        })
        val manager = manager(this, issuer)
        try { manager.acquire(issuer.id, media, binding); assertEquals(2, issuer.calls) } finally { manager.close() }
        val denied = Issuer({ throw YouTubeIntegrityException(YouTubeIntegrityError.ISSUER_DENIED) })
        val deniedManager = manager(this, denied)
        try {
            val failure = assertFailsWith<YouTubeIntegrityException> { deniedManager.acquire(denied.id, media, binding) }
            assertEquals(YouTubeIntegrityError.ISSUER_DENIED, failure.reason); assertEquals(1, denied.calls)
        } finally { deniedManager.close() }
    }

    @Test fun mismatchedExpiredOversizedOrFutureContextsAreRejected() = runTest {
        val cases = listOf<Pair<(YouTubeIntegrityIssueRequest) -> YouTubeIssuedIntegrityContext, YouTubeIntegrityError>>(
            ({ request: YouTubeIntegrityIssueRequest -> issued(request).let { YouTubeIssuedIntegrityContext(it.issuerId, player, it.binding, it.copyValue(), 0, 100) } }) to YouTubeIntegrityError.BINDING_MISMATCH,
            ({ request: YouTubeIntegrityIssueRequest -> YouTubeIssuedIntegrityContext("owned-issuer", request.scope, binding.copy(resourceGeneration = "wrong"), byteArrayOf(1), 0, 100) }) to YouTubeIntegrityError.BINDING_MISMATCH,
            ({ request: YouTubeIntegrityIssueRequest -> issued(request, now = 0, expiry = 5) }) to YouTubeIntegrityError.EXPIRED_CONTEXT,
            ({ request: YouTubeIntegrityIssueRequest -> YouTubeIssuedIntegrityContext("owned-issuer", request.scope, request.binding, ByteArray(5), 0, 100) }) to YouTubeIntegrityError.INVALID_CONTEXT,
            ({ request: YouTubeIntegrityIssueRequest -> issued(request, now = 20, expiry = 100) }) to YouTubeIntegrityError.INVALID_CONTEXT)
        for ((result, expected) in cases) {
            val issuer = Issuer({ result(it) })
            val manager = YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(listOf(issuer)), this, { 10 },
                policy.copy(maxTokenBytes = 4))
            try {
                val failure = assertFailsWith<YouTubeIntegrityException> { manager.acquire(issuer.id, media, binding) }
                assertEquals(expected, failure.reason); assertEquals(1, issuer.calls)
            } finally { manager.close() }
        }
    }

    @Test fun cancellingOneAwaitDoesNotCancelAnotherCallersSingleflightIssuance() = runTest {
        val gate = CompletableDeferred<Unit>()
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> gate.await(); issued(request) })
        val manager = manager(this, issuer)
        try {
            val first = async { manager.acquire(issuer.id, media, binding) }
            val second = async { manager.acquire(issuer.id, media, binding) }
            runCurrent(); assertEquals(1, issuer.calls)
            first.cancelAndJoin()
            gate.complete(Unit)
            assertContentEquals(byteArrayOf(1), second.await().copyValue()); assertEquals(1, issuer.calls)
        } finally { manager.close() }
    }

    @Test fun clearRevokesPendingWorkAndStaleIssuerResponseCannotRefillCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val issuer = Issuer({ request: YouTubeIntegrityIssueRequest -> gate.await(); issued(request) })
        val manager = manager(this, issuer)
        try {
            val first = async { runCatching { manager.acquire(issuer.id, media, binding) } }
            runCurrent(); manager.clear(); gate.complete(Unit)
            val failure = first.await().exceptionOrNull() as YouTubeIntegrityException
            assertEquals(YouTubeIntegrityError.INVALIDATED, failure.reason)
            manager.acquire(issuer.id, media, binding); assertEquals(2, issuer.calls)
        } finally { manager.close() }
        assertEquals(YouTubeIntegrityError.MANAGER_CLOSED,
            assertFailsWith<YouTubeIntegrityException> { manager.acquire(issuer.id, media, binding) }.reason)
    }

    @Test fun unavailableScopesAndRefreshCapabilitiesAreExplicitWithoutFakeIssuer() = runTest {
        val empty = YouTubeIntegrityContextManager(YouTubeIntegrityIssuerRegistry(), this, { 0 })
        try {
            assertEquals(YouTubeIntegrityError.ISSUER_UNAVAILABLE,
                assertFailsWith<YouTubeIntegrityException> { empty.acquire("missing", media, binding) }.reason)
        } finally { empty.close() }
        val issuer = Issuer({ issued(it) }, YouTubeIntegrityCapabilities(setOf(player), canRefresh = false))
        val manager = manager(this, issuer)
        try {
            assertEquals(YouTubeIntegrityError.SCOPE_UNSUPPORTED,
                assertFailsWith<YouTubeIntegrityException> { manager.acquire(issuer.id, media, binding) }.reason)
            manager.acquire(issuer.id, player, binding)
            assertEquals(YouTubeIntegrityError.REFRESH_UNSUPPORTED,
                assertFailsWith<YouTubeIntegrityException> { manager.refresh(issuer.id, player, binding) }.reason)
            assertEquals(1, issuer.calls)
        } finally { manager.close() }
    }

    @Test fun exactSabrSourceGenerationAndOpaqueCredentialsStayRedacted() {
        val source = YouTubeSabrSource("ABCDEFGHIJK", "https://owned.example/media?secret=private-value", byteArrayOf(1, 2),
            emptyList(), null)
        val actual = YouTubeIntegrityBinding.forSabr(source, binding.clientInfo, "private-visitor")
        val changed = YouTubeIntegrityBinding.forSabr(YouTubeSabrSource(source.videoId, source.endpoint, byteArrayOf(2, 1), emptyList(), null), binding.clientInfo)
        assertNotEquals(actual.resourceGeneration, changed.resourceGeneration)
        val credential = YouTubeIssuedIntegrityContext("owned-issuer", media, actual, "private-token".encodeToByteArray(), 0, 100)
        val input = credential.copyValue(); input[0] = 0
        assertEquals('p'.code.toByte(), credential.copyValue()[0])
        assertFalse(actual.toString().contains("private-value")); assertFalse(actual.toString().contains("private-visitor"))
        assertFalse(credential.toString().contains("private-token"))
        assertFalse(YouTubeIntegrityIssueRequest(media, actual, YouTubeIntegrityIssueReason.REJECTED_CONTEXT, previous = credential).toString().contains("private-token"))
    }
}
