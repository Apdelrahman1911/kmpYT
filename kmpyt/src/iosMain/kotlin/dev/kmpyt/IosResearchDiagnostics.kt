package dev.kmpyt

import dev.kmpyt.extraction.ResearchTransport
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

enum class IosResearchStage { PAGE, WATCH, PLAYER, PLAYER_PROGRAM, BROWSE, SEARCH, CAPTIONS, OTHER }
enum class IosResearchOutcome { STARTED, SUCCEEDED, HTTP_FAILURE, TIMEOUT, CANCELLED, FAILED }

data class IosResearchEvent(
    val stage: IosResearchStage,
    val outcome: IosResearchOutcome,
    val requestId: Long,
    val correlationId: Long?,
    val durationMs: Long? = null,
    val status: Int? = null,
)

/** Records fixed stage names and transport receipts without serializing request or response data. */
internal fun observeIosResearch(delegate: ResearchTransport, observer: ((IosResearchEvent) -> Unit)?,
    enabled: () -> Boolean): ResearchTransport {
    val ids = MutableStateFlow(0L)
    return ResearchTransport { request ->
        if (observer == null || !enabled()) return@ResearchTransport delegate.execute(request)
        val stage = when (request.stage) {
            "page", "youtube-page" -> IosResearchStage.PAGE
            "youtube-watch" -> IosResearchStage.WATCH
            "youtube-player", "youtube-player-client" -> IosResearchStage.PLAYER
            "youtube-player-program" -> IosResearchStage.PLAYER_PROGRAM
            "browse", "youtube-browse" -> IosResearchStage.BROWSE
            "search", "youtube-search" -> IosResearchStage.SEARCH
            "youtube-captions" -> IosResearchStage.CAPTIONS
            else -> IosResearchStage.OTHER
        }
        val id = ids.updateAndGet { it + 1 }
        val correlation = currentCoroutineContext()[ResearchTraceContext]?.correlationId
        val mark = TimeSource.Monotonic.markNow()
        fun emit(outcome: IosResearchOutcome, duration: Long? = null, status: Int? = null) {
            runCatching { observer(IosResearchEvent(stage, outcome, id, correlation, duration, status)) }
        }
        emit(IosResearchOutcome.STARTED)
        try {
            val response = delegate.execute(request)
            emit(if (response.status >= 400) IosResearchOutcome.HTTP_FAILURE else IosResearchOutcome.SUCCEEDED,
                mark.elapsedNow().inWholeMilliseconds, response.status)
            response
        } catch (error: Exception) {
            val outcome = when (error) {
                is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException, is TimeoutCancellationException -> IosResearchOutcome.TIMEOUT
                is CancellationException -> IosResearchOutcome.CANCELLED
                else -> IosResearchOutcome.FAILED
            }
            emit(outcome, mark.elapsedNow().inWholeMilliseconds)
            throw error
        }
    }
}
