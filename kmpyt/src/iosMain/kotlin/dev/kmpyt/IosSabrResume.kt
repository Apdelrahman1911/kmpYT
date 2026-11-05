package dev.kmpyt

import dev.kmpyt.extraction.*

/** Persist byte receipts only. A later call starts a fresh anonymous protocol session. */
internal class IosSabrResume(private val store: IosDownloadResumeStore, private val plan: PlannedDownload) {
    private val representations = listOfNotNull(plan.primary, plan.audio)
    private val source = plan.sabrSource()
    private val selection = plan.sabrSelection()
    private val client = requireNotNull(plan.primary.sabr).clientInfo
    private val generation = YouTubeIntegrityBinding.forSabr(source, client).resourceGeneration
    private val sourceDigest = iosDigest(generation)

    suspend fun restore(files: MutableList<IosMediaFile>): SabrTransferCheckpoint? {
        var saved = store.sabr
        if (saved == null || !valid(saved)) {
            store.resetSabr()
            saved = null
        }
        representations.forEachIndexed { index, representation ->
            val path = "${store.directory}/sabr-$index.mp4"
            val retained = saved?.tracks?.single { it.index == index }?.coverage?.lastOrNull()?.endExclusive ?: 0
            val expected = requireNotNull(representation.sizeBytes)
            val file = IosMediaFile(path, expected, durableWrites = true, existing = store.fileLength(path) != null)
            files += file
            if (file.length() != retained) { file.truncate(retained); file.flush() }
        }
        return saved?.let {
            SabrTransferCheckpoint(source.videoId, selection.audio, selection.video, client,
                tracks = it.tracks.sortedBy { track -> track.index }.map { track ->
                    val representation = representations[track.index]
                    SabrTransferTrackCheckpoint(representation.role, requireNotNull(representation.sabr).formatId,
                        track.expectedBytes, track.coverage, track.completedSegments)
                }, resourceGeneration = generation)
        }
    }

    suspend fun save(checkpoint: SabrTransferCheckpoint) {
        check(checkpoint.resourceGeneration == generation && checkpoint.videoId == source.videoId)
        store.saveSabr(IosSavedSabr(sourceDigest, checkpoint.tracks.map { track ->
            val index = representations.indexOfFirst { it.sabr?.formatId == track.formatId && it.role == track.role }
            check(index >= 0)
            IosSavedSabrTrack(index, track.expectedBytes, track.coverage, track.completedSegments)
        }))
    }

    private fun valid(saved: IosSavedSabr): Boolean {
        if (saved.sourceDigest != sourceDigest || saved.tracks.size != representations.size ||
            saved.tracks.map { it.index }.toSet() != representations.indices.toSet()) return false
        return saved.tracks.all { track ->
            val expected = representations[track.index].sizeBytes
            val ranges = track.coverage
            val segments = track.completedSegments
            val last = ranges.lastOrNull()?.endExclusive ?: 0
            val length = store.fileLength("${store.directory}/sabr-${track.index}.mp4")
            expected != null && expected > 0 && track.expectedBytes == expected &&
                ranges.size <= 8_192 && segments.size <= 8_192 &&
                ranges.all { it.endExclusive <= expected } &&
                ranges.zipWithNext().all { (a, b) -> a.endExclusive < b.start } &&
                (length != null && length >= last && length <= expected || length == null && last == 0L) &&
                segments.all { segment -> segment.length <= expected && segment.offset <= expected - segment.length &&
                    ranges.any { it.start <= segment.offset && it.endExclusive >= segment.offset + segment.length } } &&
                segments.groupBy { it.sequence to it.initialization }.values.all { it.distinct().size == 1 }
        }
    }
}
