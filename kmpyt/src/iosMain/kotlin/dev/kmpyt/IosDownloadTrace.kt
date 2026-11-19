package dev.kmpyt

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

enum class IosDownloadStage { CHECKPOINT_RESTORE, TRANSFER, CHECKPOINT_FINISH, ASSEMBLY, EXPORT, STAGING_MOVE, STAGING_CLEANUP }
enum class IosDownloadStageOutcome { START, SUCCESS, FAILED, CANCELLED }

data class IosDownloadStageEvent(
    val stage: IosDownloadStage,
    val outcome: IosDownloadStageOutcome,
    val durationMs: Long?,
    val processingFailure: DownloadProcessingException.Reason? = null,
    val restartRequired: Boolean = false,
    val operationId: String? = null,
)

internal suspend inline fun <T> traceIosDownload(
    observer: ((IosDownloadStageEvent) -> Unit)?,
    stage: IosDownloadStage,
    block: () -> T,
): T {
    if (observer == null) return block()
    val mark = TimeSource.Monotonic.markNow()
    runCatching { observer(IosDownloadStageEvent(stage, IosDownloadStageOutcome.START, null)) }
    try {
        val value = block()
        runCatching { observer(IosDownloadStageEvent(stage, IosDownloadStageOutcome.SUCCESS, mark.elapsedNow().inWholeMilliseconds)) }
        return value
    } catch (error: Exception) {
        val outcome = if (error is CancellationException) IosDownloadStageOutcome.CANCELLED else IosDownloadStageOutcome.FAILED
        val processing = error as? DownloadProcessingException
        runCatching { observer(IosDownloadStageEvent(stage, outcome, mark.elapsedNow().inWholeMilliseconds,
            processing?.reason, processing?.restartRequired == true)) }
        throw error
    }
}
