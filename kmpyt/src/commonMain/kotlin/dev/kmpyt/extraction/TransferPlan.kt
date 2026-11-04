package dev.kmpyt.extraction

import kotlinx.serialization.Serializable
import dev.kmpyt.UrlPolicy

// authorizedOrigin retains its serialized key for existing tasks; it now describes optional
// protected-session scope rather than granting or denying permission to transfer media.
@Serializable data class PlannedDownload(val primary: Representation, val audio: Representation? = null,
    val authorizedOrigin: String = "", val inspectionUrl: String, val mediaId: String,
    val requestHeaders: Map<String, String> = emptyMap(), val credentialRef: String? = null,
    val sabrCheckpoint: SabrTransferCheckpoint? = null,
    /** Bounded, payload-free protocol receipts; source/cookie/token values are excluded. */
    val sabrDiagnostics: List<String> = emptyList())
@Serializable data class PartState(val key: String, val bytes: Long = 0, val total: Long? = null,
    val etag: String? = null, val lastModified: String? = null, val complete: Boolean = false)
data class TransferPart(val key: String, val resource: SegmentResource, val mimeType: String,
    val expectedBytes: Long? = null)

object TransferPlanning {
    fun parts(plan: PlannedDownload): List<TransferPart> {
        UrlPolicy.validate(plan.inspectionUrl, allowLocalHttp = true)?.let { throw IllegalArgumentException(it) }
        YouTubeDownloadPolicy.requirePlan(plan)
        if (plan.primary.transport == TransportKind.SABR) plan.sabrSource()
        val result = buildList {
            listOfNotNull(plan.primary, plan.audio).forEachIndexed { track, representation ->
                require(representation.selectable) { representation.unavailableReason.orEmpty() }
                val resources = if (representation.segments.isEmpty()) listOf(SegmentResource(representation.requireResourceUrl()))
                    else listOfNotNull(representation.initialization) + representation.segments
                resources.forEachIndexed { segment, resource ->
                    UrlPolicy.validate(resource.url, allowLocalHttp = true)?.let { throw IllegalArgumentException(it) }
                    require((resource.rangeStart == null) == (resource.rangeLength == null)) { "A byte range needs both offset and length" }
                    val start = resource.rangeStart
                    val length = resource.rangeLength
                    require(start == null || length != null && start >= 0 && length > 0 && start <= Long.MAX_VALUE - length) {
                        "The source byte range exceeds the supported offset range"
                    }
                    val expected = resource.rangeLength ?: representation.sizeBytes.takeIf { resources.size == 1 }
                    require(expected == null || expected >= 0) { "The source reports an invalid media size" }
                    add(TransferPart("$track-$segment", resource, representation.mimeType, expected))
                }
            }
        }
        require(result.size in 1..3000) { "VOD transfer exceeds the segment limit" }
        require(plan.requestHeaders.keys.none { it.lowercase() in setOf("host", "range", "if-range", "accept-encoding", "cookie", "authorization", "set-cookie") }) {
            "Credentials must use a native session adapter; transport framing headers cannot be overridden"
        }
        return result
    }

    /** Complete source-transfer size, including audio and initialization; never a sum of only known parts. */
    fun totalBytes(plan: PlannedDownload): Long? {
        val sizes = listOfNotNull(plan.primary, plan.audio).map(::knownBytes)
        val known = checkedBytes(sizes.filterNotNull())
        return known.takeIf { sizes.all { it != null } }
    }

    internal fun knownBytes(representation: Representation): Long? {
        val declared = representation.sizeBytes
        require(declared == null || declared >= 0) { "The source reports an invalid media size" }
        if (representation.segments.isEmpty()) return declared
        val resources = listOfNotNull(representation.initialization) + representation.segments
        val ranges = resources.map { it.rangeLength }
        val knownRanges = checkedBytes(ranges.filterNotNull())
        return if (ranges.all { it != null }) knownRanges else declared?.takeIf { it >= knownRanges }
    }

    private fun checkedBytes(values: List<Long>): Long = values.fold(0L) { total, value ->
        require(value >= 0 && value <= Long.MAX_VALUE - total) { "Media byte totals exceed the supported range" }
        total + value
    }
    fun sameRepresentation(old: Representation, fresh: Representation): Boolean =
        old.id == fresh.id && old.role == fresh.role && old.transport == fresh.transport &&
            old.codecs == fresh.codecs && old.width == fresh.width && old.height == fresh.height &&
            old.sabr?.formatId == fresh.sabr?.formatId &&
            (old.sizeBytes == null || fresh.sizeBytes == null || old.sizeBytes == fresh.sizeBytes) &&
            old.segments.size == fresh.segments.size && old.initialization?.rangeLength == fresh.initialization?.rangeLength
}

/** Shared orchestration: durable per-part state, aggregate progress and cancellation supplied by platform. */
class TransferCoordinator(private val maximumBytes: Long = Long.MAX_VALUE) {
    init { require(maximumBytes > 0) { "Invalid transfer byte budget" } }

    suspend fun run(plan: PlannedDownload, initial: List<PartState>,
        isCurrent: suspend () -> Boolean,
        transfer: suspend (TransferPart, PartState, suspend (PartState, Long) -> Unit) -> PartState,
        persist: suspend (List<PartState>, Long, Long?) -> Unit) {
        val parts = TransferPlanning.parts(plan)
        val keys = parts.map { it.key }.toSet()
        require(initial.map { it.key }.distinct().size == initial.size && initial.all { it.key in keys }) { "Persisted transfer parts do not match this plan" }
        val restored = initial.associateBy { it.key }
        val states = parts.associate { part ->
            val previous = restored[part.key]
            part.key to (previous?.copy(total = previous.total ?: if (previous.complete) previous.bytes
                else part.expectedBytes?.takeIf { it >= previous.bytes }) ?: PartState(part.key, total = part.expectedBytes))
        }.toMutableMap()
        val trackHints = listOfNotNull(plan.primary, plan.audio).map(TransferPlanning::knownBytes)
        val trackParts = parts.groupBy { it.key.substringBefore('-').toInt() }
        fun validate(state: PartState) {
            require(state.bytes >= 0 && (state.total == null || state.total >= 0 && state.bytes <= state.total)) {
                "Invalid persisted or reported transfer byte counts"
            }
        }
        fun aggregate(values: List<Long>): Long = values.fold(0L) { sum, bytes ->
            require(bytes >= 0 && bytes <= maximumBytes - sum) {
                if (maximumBytes == Long.MAX_VALUE) "Aggregate media size exceeds the supported byte-count range"
                else "Media transfer exceeds the configured byte budget"
            }
            sum + bytes
        }
        fun validateAggregate() {
            states.values.forEach(::validate)
            aggregate(states.values.map { it.bytes })
            // Check known lower bounds even if another part still has an unknown total.
            aggregate(states.values.map { maxOf(it.bytes, it.total ?: 0) })
        }
        fun totalBytes(): Long? {
            val totals = trackHints.indices.map { track ->
                val selected = trackParts.getValue(track).map { states.getValue(it.key) }
                if (selected.all { it.total != null }) aggregate(selected.map { it.total!! })
                else if (selected.size == 1) null
                else trackHints[track]?.takeIf { it >= aggregate(selected.map { state -> maxOf(state.bytes, state.total ?: 0) }) }
            }
            val known = aggregate(totals.filterNotNull())
            return known.takeIf { totals.all { it != null } }
        }
        // Validate restored complete parts too; a fully completed resume skips the transfer loop.
        validateAggregate()
        aggregate(trackHints.filterNotNull())
        var lastPublished: Triple<List<PartState>, Long, Long?>? = null
        suspend fun publish(speed: Long) {
            validateAggregate()
            val ordered = parts.map { states[it.key] ?: PartState(it.key) }
            val total = totalBytes()
            val value = Triple(ordered, speed, total)
            if (value == lastPublished) return
            persist(ordered, speed, total)
            lastPublished = value
        }
        fun retainTotal(update: PartState, previous: PartState, complete: Boolean = update.complete): PartState = update.copy(
            total = update.total ?: if (complete) update.bytes else previous.total?.takeIf { it >= update.bytes }, complete = complete)
        if (totalBytes() != null) {
            check(isCurrent()) { "Transfer superseded or cancelled" }
            publish(0)
        }
        for (part in parts) {
            check(isCurrent()) { "Transfer superseded or cancelled" }
            val old = states[part.key] ?: PartState(part.key, total = part.expectedBytes)
            validate(old)
            if (old.complete) continue
            states[part.key] = transfer(part, old) { update, speed ->
                check(isCurrent()) { "Transfer superseded or cancelled" }
                require(update.key == part.key) { "Transfer update belongs to a different part" }
                states[part.key] = retainTotal(update, states.getValue(part.key)); publish(speed)
            }.also { require(it.key == part.key) { "Transfer completion belongs to a different part" } }
                .let { retainTotal(it, states.getValue(part.key), complete = true) }
            publish(0)
        }
    }
}
