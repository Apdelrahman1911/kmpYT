package dev.kmpyt.extraction

import io.ktor.util.encodeBase64
import io.ktor.util.decodeBase64Bytes
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Player-request and media-resource contexts are separate contracts, not interchangeable strings. */
@Serializable enum class YouTubeIntegrityScope { PLAYER_REQUEST, MEDIA_RESOURCE }

@Serializable
data class YouTubeIntegrityBinding(val videoId: String, val clientInfo: SabrClientInfo,
    val resourceGeneration: String, val visitorBinding: String? = null) {
    init {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        require(resourceGeneration.length in 1..262_144 && resourceGeneration.none { it.code < 32 })
        require(visitorBinding == null || visitorBinding.length in 1..8_192 && visitorBinding.none { it.code < 32 })
    }
    override fun toString() = "YouTubeIntegrityBinding(videoId=$videoId, client=${clientInfo.clientName}, visitor=redacted, generation=redacted)"
    companion object {
        /** Exact length-prefixed source identity, not an invented nonce or guessed platform hash. */
        fun forSabr(source: YouTubeSabrSource, clientInfo: SabrClientInfo, visitorBinding: String? = null): YouTubeIntegrityBinding {
            require(source.endpoint.length <= 65_536 && source.ustreamerConfig.size in 1..98_304)
            val generation = "sabr:${source.endpoint.length}:${source.endpoint}:${source.ustreamerConfig.size}:${source.ustreamerConfig.encodeBase64()}"
            return YouTubeIntegrityBinding(source.videoId, clientInfo, generation, visitorBinding)
        }
    }
}

/**
 * Only an explicitly authorized source/issuer supplies these opaque bytes.
 * No conversion to base64/query/header/protobuf is inferred here: the consuming
 * contract chooses its documented/observed representation. Tokens remain in
 * memory and are never automatically persisted or included in diagnostics.
 */
class YouTubeIssuedIntegrityContext(val issuerId: String, val scope: YouTubeIntegrityScope,
    val binding: YouTubeIntegrityBinding, value: ByteArray,
    val issuedAtEpochMs: Long? = null, val expiresAtEpochMs: Long? = null) {
    private val bytes = value.copyOf()
    init {
        require(ISSUER_ID.matches(issuerId) && bytes.size in 1..262_144)
        require(issuedAtEpochMs == null || issuedAtEpochMs >= 0)
        require(expiresAtEpochMs == null || expiresAtEpochMs >= 0)
        require(issuedAtEpochMs == null || expiresAtEpochMs == null || expiresAtEpochMs > issuedAtEpochMs)
    }
    val byteCount: Int get() = bytes.size
    fun copyValue(): ByteArray = bytes.copyOf()
    override fun toString() = "YouTubeIssuedIntegrityContext(issuer=$issuerId, scope=$scope, videoId=${binding.videoId}, bytes=$byteCount, payload=redacted)"
}

data class YouTubeIntegrityCapabilities(val scopes: Set<YouTubeIntegrityScope>, val canRefresh: Boolean = true) {
    init { require(scopes.isNotEmpty()) }
}
enum class YouTubeIntegrityIssueReason { FIRST_USE, KNOWN_EXPIRY, EXPLICIT_REFRESH, REJECTED_CONTEXT }
class YouTubeIntegrityIssueRequest(val scope: YouTubeIntegrityScope, val binding: YouTubeIntegrityBinding,
    val reason: YouTubeIntegrityIssueReason, val attempt: Int = 0, val previous: YouTubeIssuedIntegrityContext? = null) {
    override fun toString() = "YouTubeIntegrityIssueRequest(scope=$scope, videoId=${binding.videoId}, reason=$reason, attempt=$attempt, previous=redacted)"
}
interface YouTubeIntegrityIssuer {
    val id: String
    val capabilities: YouTubeIntegrityCapabilities
    suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext
}

/** Default is EMPTY. No JNN/BotGuard/PoToken production issuer or fake generator is installed. */
class YouTubeIntegrityIssuerRegistry(issuers: List<YouTubeIntegrityIssuer> = emptyList()) {
    private val byId = issuers.associateBy { it.id }
    init { require(byId.size == issuers.size && byId.keys.all(ISSUER_ID::matches)) }
    fun find(id: String): YouTubeIntegrityIssuer? = byId[id]
    fun capabilities(): Map<String, YouTubeIntegrityCapabilities> = byId.mapValues { it.value.capabilities }
}

enum class YouTubeIntegrityError {
    ISSUER_UNAVAILABLE, SCOPE_UNSUPPORTED, REFRESH_UNSUPPORTED, BINDING_MISMATCH,
    ISSUER_DENIED, ISSUER_TEMPORARILY_UNAVAILABLE, ISSUER_FAILURE, INVALID_CONTEXT,
    EXPIRED_CONTEXT, INVALIDATED, MANAGER_CLOSED, CAPACITY_LIMIT, CLOCK_UNAVAILABLE,
}
class YouTubeIntegrityException(val reason: YouTubeIntegrityError) : Exception(reason.name) {
    val code: String get() = reason.name
    override fun toString() = "YouTubeIntegrityException(code=$code, payload=redacted)"
}
data class YouTubeIntegrityCachePolicy(val maxCachedContexts: Int = 32, val maxConcurrentIssuances: Int = 8,
    val maxTokenBytes: Int = 16_384, val maxRetries: Int = 1, val retryDelayMs: Long = 100,
    val issuanceTimeoutMs: Long = 30_000, val minimumRemainingLifetimeMs: Long = 0) {
    init {
        require(maxCachedContexts in 1..256 && maxConcurrentIssuances in 1..64 && maxTokenBytes in 1..262_144)
        require(maxRetries in 0..4 && retryDelayMs in 0..30_000 && issuanceTimeoutMs in 1..120_000)
        require(minimumRemainingLifetimeMs in 0..120_000)
    }
}

/**
 * Shared singleflight lifecycle for authorized issuer-provided contexts.
 *
 * Cache keys include issuer, purpose, video, actual client, visitor binding and
 * resource generation. Known issuer expiry controls reuse; unknown expiry is
 * never invented and therefore never cached across subsequent acquisitions.
 * One caller cancelling its await cannot cancel another caller's issuance.
 * clear/close revoke pending work and prevent a stale response repopulating cache.
 */
class YouTubeIntegrityContextManager(private val registry: YouTubeIntegrityIssuerRegistry,
    scope: CoroutineScope, private val clock: () -> Long,
    private val policy: YouTubeIntegrityCachePolicy = YouTubeIntegrityCachePolicy()) {
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + supervisor)
    private val mutex = Mutex()
    private val cache = linkedMapOf<Key, YouTubeIssuedIntegrityContext>()
    private val flights = mutableMapOf<Key, Flight>()
    private var closed = false

    suspend fun acquire(issuerId: String, scope: YouTubeIntegrityScope, binding: YouTubeIntegrityBinding,
        forceRefresh: Boolean = false): YouTubeIssuedIntegrityContext =
        obtain(issuerId, scope, binding, forceRefresh, null)

    suspend fun refresh(issuerId: String, scope: YouTubeIntegrityScope, binding: YouTubeIntegrityBinding,
        rejected: YouTubeIssuedIntegrityContext? = null): YouTubeIssuedIntegrityContext {
        if (rejected != null) {
            if (rejected.issuerId != issuerId || rejected.scope != scope || rejected.binding != binding)
                fail(YouTubeIntegrityError.BINDING_MISMATCH)
            invalidate(rejected)
        }
        return obtain(issuerId, scope, binding, true, rejected)
    }

    /** An old rejection cannot evict a newer successful issuance for the same binding. */
    suspend fun invalidate(context: YouTubeIssuedIntegrityContext) {
        val key = Key(context.issuerId, context.scope, context.binding)
        mutex.withLock { if (cache[key] === context) cache.remove(key) }
    }

    /** Unknown expiry or a revoked/evicted/foreign context is not established reusable. */
    suspend fun isUsable(context: YouTubeIssuedIntegrityContext): Boolean {
        val issuer = registry.find(context.issuerId) ?: return false
        if (context.scope !in issuer.capabilities.scopes) return false
        val key = Key(context.issuerId, context.scope, context.binding)
        return mutex.withLock {
            !closed && supervisor.isActive && cache[key] === context &&
                runCatching { reusable(context, now()) }.getOrDefault(false)
        }
    }

    suspend fun clear() {
        val active = mutex.withLock { cache.clear(); flights.values.toList().also { flights.clear() } }
        active.forEach { it.result.completeExceptionally(YouTubeIntegrityException(YouTubeIntegrityError.INVALIDATED)); it.job.cancel() }
    }

    suspend fun close() {
        val active = mutex.withLock { closed = true; cache.clear(); flights.values.toList().also { flights.clear() } }
        active.forEach { it.result.completeExceptionally(YouTubeIntegrityException(YouTubeIntegrityError.MANAGER_CLOSED)); it.job.cancel() }
        supervisor.cancel()
    }

    private suspend fun obtain(issuerId: String, scope: YouTubeIntegrityScope, binding: YouTubeIntegrityBinding,
        force: Boolean, rejected: YouTubeIssuedIntegrityContext?): YouTubeIssuedIntegrityContext {
        val issuer = registry.find(issuerId) ?: fail(YouTubeIntegrityError.ISSUER_UNAVAILABLE)
        if (scope !in issuer.capabilities.scopes) fail(YouTubeIntegrityError.SCOPE_UNSUPPORTED)
        val key = Key(issuerId, scope, binding)
        var immediate: YouTubeIssuedIntegrityContext? = null
        var launch: Flight? = null
        val flight = mutex.withLock {
            if (closed || !supervisor.isActive) fail(YouTubeIntegrityError.MANAGER_CLOSED)
            val now = now()
            val old = cache[key]
            if (!force && old != null && reusable(old, now)) {
                // Move this actual cache hit to the end of the bounded LRU.
                cache.remove(key); cache[key] = old; immediate = old
                return@withLock null
            }
            flights[key]?.let { return@withLock it }
            val reason = when {
                rejected != null -> YouTubeIntegrityIssueReason.REJECTED_CONTEXT
                force -> YouTubeIntegrityIssueReason.EXPLICIT_REFRESH
                old != null -> YouTubeIntegrityIssueReason.KNOWN_EXPIRY
                else -> YouTubeIntegrityIssueReason.FIRST_USE
            }
            if (reason != YouTubeIntegrityIssueReason.FIRST_USE && !issuer.capabilities.canRefresh)
                fail(YouTubeIntegrityError.REFRESH_UNSUPPORTED)
            if (flights.size >= policy.maxConcurrentIssuances) fail(YouTubeIntegrityError.CAPACITY_LIMIT)
            val created = Flight()
            flights[key] = created
            created.job = work.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = issue(issuer, scope, binding, reason, rejected ?: old)
                    mutex.withLock {
                        if (closed) fail(YouTubeIntegrityError.MANAGER_CLOSED)
                        if (flights[key] !== created) fail(YouTubeIntegrityError.INVALIDATED)
                        flights.remove(key)
                        cache.remove(key)
                        if (reusable(result, now())) {
                            while (cache.size >= policy.maxCachedContexts) cache.remove(cache.keys.first())
                            cache[key] = result
                        }
                    }
                    created.result.complete(result)
                } catch (cancelled: CancellationException) {
                    created.result.completeExceptionally(cancelled)
                } catch (failure: YouTubeIntegrityException) {
                    created.result.completeExceptionally(failure)
                } catch (_: Exception) {
                    created.result.completeExceptionally(YouTubeIntegrityException(YouTubeIntegrityError.ISSUER_FAILURE))
                } finally {
                    withContext(NonCancellable) { mutex.withLock { if (flights[key] === created) flights.remove(key) } }
                }
            }
            // A cancelled parent can prevent a lazy coroutine body from ever starting.
            created.job.invokeOnCompletion { failure ->
                if (failure != null) created.result.completeExceptionally(
                    YouTubeIntegrityException(YouTubeIntegrityError.MANAGER_CLOSED))
            }
            launch = created
            created
        }
        immediate?.let { return it }
        launch?.job?.start()
        return requireNotNull(flight).result.await()
    }

    private suspend fun issue(issuer: YouTubeIntegrityIssuer, scope: YouTubeIntegrityScope, binding: YouTubeIntegrityBinding,
        reason: YouTubeIntegrityIssueReason, previous: YouTubeIssuedIntegrityContext?): YouTubeIssuedIntegrityContext {
        for (attempt in 0..policy.maxRetries) {
            try {
                val supplied = withTimeoutOrNull(policy.issuanceTimeoutMs) {
                    issuer.issue(YouTubeIntegrityIssueRequest(scope, binding, reason, attempt, previous))
                } ?: fail(YouTubeIntegrityError.ISSUER_TEMPORARILY_UNAVAILABLE)
                if (supplied.issuerId != issuer.id || supplied.scope != scope || supplied.binding != binding)
                    fail(YouTubeIntegrityError.BINDING_MISMATCH)
                if (supplied.byteCount > policy.maxTokenBytes) fail(YouTubeIntegrityError.INVALID_CONTEXT)
                val now = now()
                if (supplied.issuedAtEpochMs?.let { it > now } == true) fail(YouTubeIntegrityError.INVALID_CONTEXT)
                if (supplied.expiresAtEpochMs?.let { it <= now } == true) fail(YouTubeIntegrityError.EXPIRED_CONTEXT)
                // Copy even when an issuer reuses its object. Reference identity then
                // differentiates an old rejection from a newer validated lease.
                return YouTubeIssuedIntegrityContext(supplied.issuerId, supplied.scope, supplied.binding,
                    supplied.copyValue(), supplied.issuedAtEpochMs, supplied.expiresAtEpochMs)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: YouTubeIntegrityException) {
                if (failure.reason != YouTubeIntegrityError.ISSUER_TEMPORARILY_UNAVAILABLE || attempt == policy.maxRetries) throw failure
                if (policy.retryDelayMs > 0) delay(policy.retryDelayMs)
            } catch (_: Exception) { fail(YouTubeIntegrityError.ISSUER_FAILURE) }
        }
        fail(YouTubeIntegrityError.ISSUER_FAILURE)
    }

    private fun reusable(context: YouTubeIssuedIntegrityContext, now: Long): Boolean {
        val expiry = context.expiresAtEpochMs ?: return false
        return expiry > now && expiry - now > policy.minimumRemainingLifetimeMs
    }
    private fun now(): Long = try { clock().also { if (it < 0) fail(YouTubeIntegrityError.CLOCK_UNAVAILABLE) } }
        catch (failure: YouTubeIntegrityException) { throw failure }
        catch (_: Exception) { fail(YouTubeIntegrityError.CLOCK_UNAVAILABLE) }
    private data class Key(val issuerId: String, val scope: YouTubeIntegrityScope, val binding: YouTubeIntegrityBinding)
    private class Flight {
        val result = CompletableDeferred<YouTubeIssuedIntegrityContext>()
        lateinit var job: Job
    }
}

/**
 * Actual KMP HTTP adapter for an explicitly provided owned/authorized issuer.
 * This JSON exchange is kmpYT's research contract, NOT YouTube's JNN,
 * BotGuard, account OAuth or any undocumented production token generator.
 *
 * POST: scope,binding,reason,attempt,refresh. Response: issuerId,scope,binding,
 * tokenBase64,optional issuedAtEpochMs/expiresAtEpochMs. Opaque bytes come from
 * the issuer; this client neither creates nor derives a token. authorize is
 * mandatory before transport. No inherited credentials or redirects are used.
 */
class AuthorizedHttpYouTubeIntegrityIssuer(client: HttpClient, override val id: String,
    private val endpoint: String, override val capabilities: YouTubeIntegrityCapabilities,
    private val authorize: suspend (YouTubeIntegrityIssueRequest, String) -> Unit,
    headers: Map<String, String> = emptyMap(), private val maxResponseBytes: Int = 524_288) : YouTubeIntegrityIssuer {
    private val explicitHeaders = headers.toMap()
    private val http = HttpClient(client.engine) { followRedirects = false; expectSuccess = false }
    init {
        require(ISSUER_ID.matches(id) && maxResponseBytes in 1..1_048_576 && endpoint.length <= 65_536)
        val url = Url(endpoint)
        require(url.protocol.name in setOf("http", "https") && url.host.isNotBlank() &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty())
        require(explicitHeaders.size <= 32 && explicitHeaders.all { (name, value) ->
            Regex("[A-Za-z0-9-]{1,64}").matches(name) && value.length <= 8_192 && value.none { it.code < 32 } &&
                name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "accept-encoding", "content-type", "connection")
        })
    }
    fun close() = http.close()
    override suspend fun issue(request: YouTubeIntegrityIssueRequest): YouTubeIssuedIntegrityContext {
        if (request.scope !in capabilities.scopes) fail(YouTubeIntegrityError.SCOPE_UNSUPPORTED)
        try { authorize(request, endpoint) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(YouTubeIntegrityError.ISSUER_DENIED) }
        val json = Json
        val body = buildJsonObject {
            put("scope", request.scope.name)
            put("binding", json.encodeToJsonElement(YouTubeIntegrityBinding.serializer(), request.binding))
            put("reason", request.reason.name); put("attempt", request.attempt)
            put("refresh", request.previous != null)
        }.toString()
        try {
            return http.prepareRequest(endpoint) {
                method = HttpMethod.Post
                explicitHeaders.forEach { (name, value) -> header(name, value) }
                header(HttpHeaders.Accept, "application/json"); header(HttpHeaders.AcceptEncoding, "identity")
                contentType(ContentType.Application.Json); setBody(body)
            }.execute { response ->
                when (response.status.value) {
                    401, 403, 429 -> fail(YouTubeIntegrityError.ISSUER_DENIED)
                    408, in 500..599 -> fail(YouTubeIntegrityError.ISSUER_TEMPORARILY_UNAVAILABLE)
                }
                if (response.status.value !in 200..299) fail(YouTubeIntegrityError.ISSUER_FAILURE)
                val mime = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                if (mime != "application/json") fail(YouTubeIntegrityError.INVALID_CONTEXT)
                val encoding = response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
                if (!encoding.isNullOrEmpty() && encoding != "identity") fail(YouTubeIntegrityError.INVALID_CONTEXT)
                response.headers[HttpHeaders.ContentLength]?.let {
                    val length = it.toLongOrNull() ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                    if (length < 0 || length > maxResponseBytes) fail(YouTubeIntegrityError.INVALID_CONTEXT)
                }
                val channel = response.bodyAsChannel()
                val output = ByteArray(maxResponseBytes)
                var received = 0
                val buffer = ByteArray(8_192)
                try {
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > maxResponseBytes - received) fail(YouTubeIntegrityError.INVALID_CONTEXT)
                        buffer.copyInto(output, received, 0, count); received += count
                    }
                } finally { channel.cancel() }
                val payload = json.parseToJsonElement(output.copyOf(received).decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                    ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                fun string(name: String): String? = (payload[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun timestamp(name: String): Long? {
                    if (name !in payload || payload[name] == JsonNull) return null
                    return (payload[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                        ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                }
                val replyIssuer = string("issuerId") ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                val replyScope = string("scope")?.let { value -> YouTubeIntegrityScope.entries.firstOrNull { it.name == value } }
                    ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                val binding = payload["binding"]?.let { json.decodeFromJsonElement(YouTubeIntegrityBinding.serializer(), it) }
                    ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                if (replyIssuer != id || replyScope != request.scope || binding != request.binding)
                    fail(YouTubeIntegrityError.BINDING_MISMATCH)
                val encoded = string("tokenBase64")?.takeIf { it.length in 1..349_528 }
                    ?: fail(YouTubeIntegrityError.INVALID_CONTEXT)
                val token = encoded.decodeBase64Bytes()
                if (token.isEmpty() || token.size > 262_144 || token.encodeBase64() != encoded) fail(YouTubeIntegrityError.INVALID_CONTEXT)
                YouTubeIssuedIntegrityContext(id, replyScope, binding, token, timestamp("issuedAtEpochMs"), timestamp("expiresAtEpochMs"))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: YouTubeIntegrityException) { throw failure
        } catch (_: io.ktor.utils.io.errors.IOException) { fail(YouTubeIntegrityError.ISSUER_TEMPORARILY_UNAVAILABLE)
        } catch (_: Exception) { fail(YouTubeIntegrityError.INVALID_CONTEXT) }
    }
}

private val ISSUER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private fun fail(reason: YouTubeIntegrityError): Nothing = throw YouTubeIntegrityException(reason)
