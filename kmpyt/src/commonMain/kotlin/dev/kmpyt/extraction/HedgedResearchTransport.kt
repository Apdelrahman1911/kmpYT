package dev.kmpyt.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded recovery for an unusually slow, idempotent public read.
 *
 * When a request in one of [hedgeAfterMs]'s stages has not completed within its delay, one
 * identical duplicate is started and whichever completes first is used; the other is cancelled.
 * At most two attempts exist per call. A failure before the delay is returned unchanged (no
 * hidden retry), and a failure of one attempt after hedging waits for the other. Only the
 * caller-chosen read stages are hedged; media transfers never pass through here.
 */
class HedgedResearchTransport(
    private val base: ResearchTransport,
    private val hedgeAfterMs: Map<String, Long> = DEFAULT_STAGES,
) : ResearchTransport {
    override suspend fun execute(request: ResearchRequest): ResearchResponse {
        val delayMs = hedgeAfterMs[request.stage] ?: return base.execute(request)
        return coroutineScope {
            val results = Channel<Result<ResearchResponse>>(2)
            suspend fun attempt() {
                val result = try { Result.success(base.execute(request)) }
                catch (cancelled: CancellationException) {
                    // Our own cancellation ends the attempt; a transport-thrown cancellation is
                    // that attempt's outcome and must reach the caller unchanged.
                    if (!currentCoroutineContext().isActive) throw cancelled
                    Result.failure(cancelled)
                }
                catch (failure: Throwable) { Result.failure(failure) }
                results.send(result)
            }
            val first = launch { attempt() }
            withTimeoutOrNull(delayMs) { results.receive() }?.let { return@coroutineScope it.getOrThrow() }
            val second = launch { attempt() }
            var failure: Throwable? = null
            repeat(2) {
                val result = results.receive()
                if (result.isSuccess) {
                    first.cancel(); second.cancel()
                    return@coroutineScope result.getOrThrow()
                }
                failure = result.exceptionOrNull()
            }
            throw requireNotNull(failure)
        }
    }

    companion object {
        /** Watch pages normally complete in 0.8–1.7 s, player API reads in about 0.25 s (measured). */
        val DEFAULT_STAGES = mapOf("youtube-watch" to 3_500L, "youtube-player-client" to 2_500L, "youtube-oembed" to 2_500L)
    }
}
