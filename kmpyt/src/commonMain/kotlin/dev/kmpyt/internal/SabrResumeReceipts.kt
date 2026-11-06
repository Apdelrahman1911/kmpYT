package dev.kmpyt.internal

import dev.kmpyt.extraction.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Platform SHA-256 state. A snapshot must not consume or modify the running digest. */
internal interface ResumeSha256 {
    fun update(bytes: ByteArray, count: Int = bytes.size)
    fun snapshot(): String
}

/** No source URLs/configuration, request counters, cookies or opaque protocol state are durable. */
@Serializable internal data class SabrSavedTrack(val index: Int, val expectedBytes: Long,
    val coverage: List<SabrTransferByteRange>, val completedSegments: List<SabrTransferSegment>,
    val rangeDigests: List<String> = emptyList())
@Serializable internal data class SabrSavedReceipt(val sourceDigest: String, val tracks: List<SabrSavedTrack>)

/**
 * File adapters persist these receipts rather than serializing a transient protocol checkpoint.
 * Restoring requires an exact inspected plan/generation and SHA-256 of every retained byte range.
 * Session state always starts anonymous and fresh; verified segment receipts may be acknowledged.
 *
 * Digests stream through 64 KiB reads. Within one exclusively owned file lifetime, only newly
 * covered suffixes are hashed; joining disjoint ranges may reread the joined span. Neither sparse
 * holes nor uncommitted file tails become acknowledged coverage. The transfer engine checks any
 * overlap before writing, and callers must keep these files private and exclusively owned.
 */
internal class SabrResumeReceipts(plan: PlannedDownload, digestText: (String) -> String,
    private val newDigest: () -> ResumeSha256) {
    private val source = plan.sabrSource()
    private val selection = plan.sabrSelection()
    private val client = requireNotNull(plan.primary.sabr).clientInfo
    private val representations = listOfNotNull(plan.primary, plan.audio)
    private val generation = YouTubeIntegrityBinding.forSabr(source, client).resourceGeneration
    // Stronger than an endpoint-only digest: selection, client, exact source/configuration,
    // role, format identity, content length and representation metadata must all still match.
    private val sourceDigest = digestText(Json.encodeToString(PlannedDownload.serializer(),
        plan.copy(sabrCheckpoint = null, sabrDiagnostics = emptyList())))
    private class RangeDigest(val start: Long, var end: Long, val digest: ResumeSha256)
    private var ranges = emptyMap<Pair<Int, Long>, RangeDigest>()

    init {
        require(representations.size in 1..2 && representations.map { it.role }.distinct().size == representations.size)
        require(sourceDigest.matches(SHA256))
    }

    suspend fun restore(saved: SabrSavedReceipt?, length: (Int) -> Long?,
        read: suspend (Int, Long, Int) -> ByteArray): SabrTransferCheckpoint? {
        ranges = emptyMap()
        if (saved == null || !valid(saved, length)) return null
        val verified = mutableMapOf<Pair<Int, Long>, RangeDigest>()
        for (track in saved.tracks) for ((number, range) in track.coverage.withIndex()) {
            currentCoroutineContext().ensureActive()
            val receipt = RangeDigest(range.start, range.start, newDigest())
            extend(track.index, receipt, range.endExclusive, read)
            if (receipt.digest.snapshot() != track.rangeDigests[number]) return null
            verified[track.index to range.start] = receipt
        }
        ranges = verified
        return SabrTransferCheckpoint(source.videoId, selection.audio, selection.video, client,
            tracks = saved.tracks.sortedBy { it.index }.map { track ->
                val representation = representations[track.index]
                SabrTransferTrackCheckpoint(representation.role, requireNotNull(representation.sabr).formatId,
                    track.expectedBytes, track.coverage, track.completedSegments)
            }, resourceGeneration = generation)
    }

    suspend fun snapshot(checkpoint: SabrTransferCheckpoint,
        read: suspend (Int, Long, Int) -> ByteArray): SabrSavedReceipt {
        require(checkpoint.videoId == source.videoId && checkpoint.clientInfo == client &&
            checkpoint.selectedAudio == selection.audio && checkpoint.selectedVideo == selection.video &&
            checkpoint.resourceGeneration == generation) { "SABR receipt source identity changed." }
        require(checkpoint.tracks.size == representations.size)
        val next = mutableMapOf<Pair<Int, Long>, RangeDigest>()
        val tracks = representations.mapIndexed { index, representation ->
            val track = checkpoint.tracks.single { it.role == representation.role && it.formatId == representation.sabr!!.formatId }
            require(validTrack(index, track.expectedBytes, track.coverage, track.completedSegments)) { "Invalid SABR byte coverage receipt." }
            val hashes = track.coverage.map { range ->
                val receipt = ranges[index to range.start] ?: RangeDigest(range.start, range.start, newDigest())
                require(receipt.end <= range.endExclusive) { "SABR acknowledged coverage cannot regress." }
                extend(index, receipt, range.endExclusive, read)
                next[index to range.start] = receipt
                receipt.digest.snapshot().also { check(it.matches(SHA256)) { "Invalid SHA-256 receipt." } }
            }
            SabrSavedTrack(index, track.expectedBytes, track.coverage, track.completedSegments, hashes)
        }
        ranges = next
        return SabrSavedReceipt(sourceDigest, tracks)
    }

    private suspend fun extend(index: Int, receipt: RangeDigest, end: Long,
        read: suspend (Int, Long, Int) -> ByteArray) {
        while (receipt.end < end) {
            currentCoroutineContext().ensureActive()
            val count = minOf(64 * 1024L, end - receipt.end).toInt()
            val bytes = read(index, receipt.end, count)
            check(bytes.size == count) { "The private SABR range changed while reading its receipt." }
            receipt.digest.update(bytes)
            receipt.end += count
        }
    }

    private fun valid(saved: SabrSavedReceipt, length: (Int) -> Long?): Boolean {
        if (saved.sourceDigest != sourceDigest || saved.tracks.size != representations.size ||
            saved.tracks.map { it.index }.toSet() != representations.indices.toSet()) return false
        return saved.tracks.all { track ->
            val last = track.coverage.lastOrNull()?.endExclusive ?: 0
            val actualLength = length(track.index)
            validTrack(track.index, track.expectedBytes, track.coverage, track.completedSegments) &&
                track.rangeDigests.size == track.coverage.size && track.rangeDigests.all(SHA256::matches) &&
                (actualLength != null && actualLength >= last && actualLength <= track.expectedBytes || actualLength == null && last == 0L)
        }
    }

    private fun validTrack(index: Int, expected: Long, coverage: List<SabrTransferByteRange>,
        segments: List<SabrTransferSegment>): Boolean = expected > 0 && representations[index].sizeBytes == expected &&
        coverage.size <= MAX_RANGES && segments.size <= MAX_RANGES &&
        coverage.all { it.start >= 0 && it.endExclusive > it.start && it.endExclusive <= expected } &&
        coverage.zipWithNext().all { (a, b) -> a.endExclusive < b.start } &&
        segments.all { segment -> segment.offset >= 0 && segment.length > 0 && segment.length <= expected &&
            segment.offset <= expected - segment.length && segment.sequence >= 0 && segment.startTimeMs >= 0 &&
            segment.durationMs >= 0 && segment.startTimeMs <= Long.MAX_VALUE - segment.durationMs &&
            coverage.any { it.start <= segment.offset && it.endExclusive >= segment.offset + segment.length } } &&
        segments.groupBy { it.sequence to it.initialization }.values.all { it.distinct().size == 1 }

    private companion object {
        const val MAX_RANGES = 8_192
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
