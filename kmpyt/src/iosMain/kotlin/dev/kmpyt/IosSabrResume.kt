package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.internal.SabrResumeReceipts

/** Verified local range hashes only. A later call starts a fresh anonymous protocol session. */
internal class IosSabrResume(private val store: IosDownloadResumeStore, private val plan: PlannedDownload) {
    private val representations = listOfNotNull(plan.primary, plan.audio)
    private val receipts = SabrResumeReceipts(plan, ::iosDigest, ::IosResumeSha256)
    private var owned = emptyList<IosMediaFile>()

    suspend fun restore(files: MutableList<IosMediaFile>): SabrTransferCheckpoint? {
        val opened = mutableListOf<IosMediaFile>()
        representations.forEachIndexed { index, representation ->
            val path = "${store.directory}/sabr-$index.mp4"
            val file = IosMediaFile(path, requireNotNull(representation.sizeBytes), durableWrites = true,
                existing = store.fileLength(path) != null)
            // Register before any fallible metadata/hash operation; the downloader closes every handle.
            files += file
            opened += file
        }
        owned = opened
        val checkpoint = receipts.restore(store.sabr, { owned[it].length() }, ::read)
        if (checkpoint == null) store.resetSabr(removeFiles = false)
        owned.forEachIndexed { index, file ->
            val committed = checkpoint?.tracks?.get(index)?.coverage?.lastOrNull()?.endExclusive ?: 0L
            if (file.length() != committed) { file.truncate(committed); file.flush() }
        }
        return checkpoint
    }

    suspend fun save(checkpoint: SabrTransferCheckpoint) = store.saveSabr(receipts.snapshot(checkpoint, ::read))

    private suspend fun read(index: Int, offset: Long, count: Int) = owned[index].read(offset, count)
}
