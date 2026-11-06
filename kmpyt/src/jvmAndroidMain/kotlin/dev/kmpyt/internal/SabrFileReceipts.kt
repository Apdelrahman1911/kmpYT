package dev.kmpyt.internal

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.SabrTransferCheckpoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class JvmResumeSha256 : ResumeSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, count: Int) { require(count in 0..bytes.size); digest.update(bytes, 0, count) }
    override fun snapshot(): String = (digest.clone() as MessageDigest).digest().resumeHex()
}

internal class SabrFileReceipts(private val directory: File, plan: PlannedDownload) {
    private val receipts = SabrResumeReceipts(plan, ::resumeDigest, ::JvmResumeSha256)
    private fun file(index: Int) = File(directory, "$index-0").also {
        requirePrivateCheckpointPath(it, allowMissing = true)
    }

    suspend fun restore(saved: SabrSavedReceipt?): SabrTransferCheckpoint? {
        val checkpoint = receipts.restore(saved, { file(it).takeIf(File::isFile)?.length() }, ::read) ?: return null
        checkpoint.tracks.forEachIndexed { index, track ->
            val file = file(index)
            val committedEnd = track.coverage.lastOrNull()?.endExclusive ?: 0L
            if (file.exists() && file.length() > committedEnd) RandomAccessFile(file, "rw").use {
                currentCoroutineContext().ensureActive()
                it.setLength(committedEnd); it.fd.sync()
            }
        }
        return checkpoint
    }

    suspend fun snapshot(checkpoint: SabrTransferCheckpoint): SabrSavedReceipt = receipts.snapshot(checkpoint, ::read)

    private suspend fun read(index: Int, offset: Long, count: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        return ByteArray(count).also { bytes -> RandomAccessFile(file(index), "r").use { file ->
            file.seek(offset); file.readFully(bytes)
        } }
    }
}
