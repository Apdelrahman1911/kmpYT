package dev.kmpyt.extraction

import io.ktor.http.parseQueryString
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Source-declared pre-playback timing; it does not supply credentials or fetch advertising media. */
internal object SourceAvailability {
    const val MAX_WAIT_MS = 300_000L

    fun waitMillis(player: JsonObject): Long {
        var unsupported = (player.array("adPlacements")?.size ?: 0) > 64 || (player.array("adSlots")?.size ?: 0) > 64
        val renderers = buildList {
            player.array("adPlacements")?.take(64)?.forEach { entry ->
                val placement = (entry as? JsonObject)?.obj("adPlacementRenderer") ?: return@forEach
                if (placement.obj("config")?.obj("adPlacementConfig")?.text("kind") == "AD_PLACEMENT_KIND_START")
                    placement.obj("renderer")?.obj("instreamVideoAdRenderer")?.let(::add)
            }
            player.array("adSlots")?.take(64)?.forEach { entry ->
                val slot = (entry as? JsonObject)?.obj("adSlotRenderer") ?: return@forEach
                if (slot.obj("adSlotMetadata")?.text("triggerEvent") != "SLOT_TRIGGER_EVENT_BEFORE_CONTENT") return@forEach
                val content = slot.obj("fulfillmentContent")?.obj("fulfilledLayout")?.obj("playerBytesAdLayoutRenderer")
                    ?.obj("renderingContent") ?: return@forEach
                content.obj("instreamVideoAdRenderer")?.let(::add)
                val sequence = content.obj("playerBytesSequentialLayoutRenderer")?.array("sequentialLayouts")
                if ((sequence?.size ?: 0) > 64) unsupported = true
                sequence?.take(64)?.forEach { layout ->
                    (layout as? JsonObject)?.obj("playerBytesAdLayoutRenderer")?.obj("renderingContent")
                        ?.obj("instreamVideoAdRenderer")?.let(::add)
                }
            }
        }
        if (unsupported) return MAX_WAIT_MS + 1
        var total = 0L
        for (renderer in renderers) {
            val duration = renderer.text("playerVars")?.takeIf { it.length <= 16_384 }?.let { variables ->
                runCatching { parseQueryString(variables).getAll("length_seconds")?.singleOrNull() }.getOrNull()
            }?.let { millis(it, 1000.0) }
            val skip = renderer.text("skipOffsetMilliseconds")?.let { millis(it, 1.0) }
            val wait = if (skip != null) minOf(skip, duration ?: skip) else duration ?: continue
            if (wait > MAX_WAIT_MS || total > MAX_WAIT_MS - wait) return MAX_WAIT_MS + 1
            total += wait
        }
        return total
    }

    private fun millis(text: String, scale: Double): Long? {
        if (text.length !in 1..32) return null
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val milliseconds = ceil(value * scale)
        return if (milliseconds > MAX_WAIT_MS) MAX_WAIT_MS + 1 else milliseconds.toLong()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun await(plan: PlannedDownload, nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
        val deadlines = listOfNotNull(plan.primary.availableAtEpochMs, plan.audio?.availableAtEpochMs)
        require(deadlines.all { it >= 0 }) { "Invalid source availability timestamp." }
        val deadline = deadlines.maxOrNull() ?: return
        val now = nowEpochMs()
        require(now >= 0) { "Invalid source availability clock." }
        if (deadline <= now) return
        val remaining = deadline - now
        require(remaining <= MAX_WAIT_MS) { "Source playback wait exceeds the supported bound." }
        // One cancellable, monotonic delay preserves the declared wait across wall-clock changes.
        delay(remaining)
    }
}
