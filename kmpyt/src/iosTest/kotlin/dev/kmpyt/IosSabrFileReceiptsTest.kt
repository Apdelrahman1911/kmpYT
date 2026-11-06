@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Native file/hash ownership checks; authored bytes are not a playable-media or device proof. */
class IosSabrFileReceiptsTest {
    private val bytes = ByteArray(200_000) { (it * 31).toByte() }
    private val client = SabrClientInfo(1, "authored")
    private val primary = Representation("136", "https://fixture.invalid/sabr", TrackRole.VIDEO, TransportKind.SABR,
        "video/mp4", codecs = "avc1.42c00a", sizeBytes = bytes.size.toLong(),
        sabr = YouTubeSabrResource("AUTHORED001", "https://fixture.invalid/sabr", "AQID", SabrFormatId(136, 1uL, "authored"), client))
    private val audio = primary.copy(id = "140", role = TrackRole.AUDIO, mimeType = "audio/mp4", codecs = "mp4a.40.2",
        sabr = primary.sabr!!.copy(formatId = SabrFormatId(140, 1uL, "authored")))
    private val plan = PlannedDownload(primary, audio, inspectionUrl = "https://fixture.invalid/watch", mediaId = "AUTHORED001")
    private val tracks = listOf(primary, audio).mapIndexed { index, representation ->
        val offset = 17L + index * 1000
        SabrTransferTrackCheckpoint(representation.role, representation.sabr!!.formatId, bytes.size.toLong(),
            listOf(SabrTransferByteRange(offset, 150_000)), listOf(SabrTransferSegment(offset, 150_000 - offset, 0, 0, 300, false)))
    }
    private val checkpoint = SabrTransferCheckpoint(plan.mediaId, audio.sabr!!.formatId, primary.sabr!!.formatId, client,
        tracks, resourceGeneration = YouTubeIntegrityBinding.forSabr(plan.sabrSource(), client).resourceGeneration)

    @Test fun bothNativeTrackRangesAreVerifiedAndAnyChangedTrackRestartsBothFiles() = iosTest {
        inIosTestDirectory { directory ->
            val destination = "$directory/output.mp4"
            val working = createReceipt(destination)
            val restore = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan))
            val files = mutableListOf<IosMediaFile>()
            try {
                restore.load(plan, TransferPlanning.parts(plan))
                val verified = assertNotNull(IosSabrResume(restore, plan).restore(files))
                assertEquals(tracks, verified.tracks)
                assertEquals(1, verified.nextRequestNumber)
                assertNull(verified.playbackCookie)
                assertTrue(verified.contexts.isEmpty())
            } finally { iosCleanup(null, *files.map<IosMediaFile, () -> Unit> { file -> { file.close() } }.toTypedArray(), { restore.close() }) }
            val changed = IosMediaFile("$working/sabr-1.mp4", existing = true)
            try {
                val offset = tracks[1].coverage.single().start
                val original = changed.read(offset, 1).single()
                changed.write(offset, byteArrayOf((original.toInt() xor 1).toByte())); changed.flush()
            } finally { changed.close() }
            val reset = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan))
            val opened = mutableListOf<IosMediaFile>()
            try {
                reset.load(plan, TransferPlanning.parts(plan))
                assertNull(IosSabrResume(reset, plan).restore(opened))
                assertNull(reset.sabr)
                assertEquals(listOf(0L, 0L), opened.map { it.length() })
            } finally { iosCleanup(null, *opened.map<IosMediaFile, () -> Unit> { file -> { file.close() } }.toTypedArray(), { reset.close() }) }
            assertIosResumeResidue(directory)
        }
    }

    @Test fun cancellationAtRangeValidationRegistersEveryHandleAndPreservesThePriorReceipt() = iosTest {
        inIosTestDirectory { directory ->
            val destination = "$directory/output.mp4"
            val working = createReceipt(destination)
            val before = read("$working/checkpoint.json")
            val store = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan))
            val files = mutableListOf<IosMediaFile>()
            try {
                store.load(plan, TransferPlanning.parts(plan))
                coroutineScope {
                    launch {
                        currentCoroutineContext().cancel()
                        assertFailsWith<CancellationException> { IosSabrResume(store, plan).restore(files) }
                    }.join()
                }
                assertEquals(2, files.size, "Every native handle must be registered before hashing can fail or be cancelled.")
                assertEquals(listOf(150_000L, 150_000L), files.map { it.length() })
            } finally { iosCleanup(null, *files.map<IosMediaFile, () -> Unit> { file -> { file.close() } }.toTypedArray(), { store.close() }) }
            assertContentEquals(before, read("$working/checkpoint.json"))
            val retry = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan))
            val reopened = mutableListOf<IosMediaFile>()
            try {
                retry.load(plan, TransferPlanning.parts(plan))
                assertEquals(tracks, assertNotNull(IosSabrResume(retry, plan).restore(reopened)).tracks)
            } finally { iosCleanup(null, *reopened.map<IosMediaFile, () -> Unit> { file -> { file.close() } }.toTypedArray(), { retry.close(discard = true) }) }
            assertIosResumeResidue(directory)
        }
    }

    private suspend fun createReceipt(destination: String): String {
        val store = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan))
        val files = mutableListOf<IosMediaFile>()
        try {
            store.load(plan, TransferPlanning.parts(plan))
            val resume = IosSabrResume(store, plan)
            assertNull(resume.restore(files))
            tracks.forEachIndexed { index, track ->
                val range = track.coverage.single()
                files[index].write(range.start, bytes.copyOfRange(range.start.toInt(), range.endExclusive.toInt()))
            }
            resume.save(checkpoint)
            return store.directory
        } finally { iosCleanup(null, *files.map<IosMediaFile, () -> Unit> { file -> { file.close() } }.toTypedArray(), { store.close() }) }
    }

    private suspend fun read(path: String): ByteArray {
        val file = IosMediaFile(path, existing = true)
        return try { file.read(0, file.length().toInt()) } finally { file.close() }
    }
}
