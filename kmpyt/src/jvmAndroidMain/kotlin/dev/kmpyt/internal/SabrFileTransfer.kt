package dev.kmpyt.internal


import dev.kmpyt.UrlPolicy
import dev.kmpyt.extraction.*




import io.ktor.client.HttpClient

import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Native durable-file adapter for the shared continuing SABR/UMP protocol engine.
 * Endpoint POSTs and byte coverage remain shared Kotlin; native adapters supply
 * random-access files and a platform media processor.
 */
internal class SabrFileTransfer(
    private val processor: FileMediaProcessor,
    private val directory: File,
    private val client: HttpClient,
    private val allowHttp: Boolean,
    private val storageCheck: (File, Long) -> Unit = MediaStorageSpace::requireAvailable,
) {
    suspend fun run(input: PlannedDownload, output: File, current: suspend () -> Boolean,
        persist: suspend (PlannedDownload, List<PartState>, Long, Long?) -> Unit): TransferOutcome = coroutineScope {
        YouTubeDownloadPolicy.requirePlan(input)
        var plan = input
        require(plan.credentialRef == null && plan.requestHeaders.isEmpty()) {
            "The SABR transport supports anonymous source-declared clients; credential/header sessions are not implemented"
        }
        val source = plan.sabrSource()
        val selection = plan.sabrSelection()
        val representations = listOfNotNull(plan.primary, plan.audio)
        require(representations.all { it.role in setOf(TrackRole.VIDEO, TrackRole.AUDIO) } &&
            representations.map { it.role }.distinct().size == representations.size) { "Select one exact audio and/or video SABR track" }
        val expected = representations.associate { it.sabr!!.formatId to requireNotNull(it.sizeBytes) }
        val total = expected.values.fold(0L) { sum, length ->
            require(length > 0 && length <= Long.MAX_VALUE - sum) { "SABR transfer sizes overflow supported file offsets" }; sum + length
        }
        requirePrivateCheckpointPath(directory, allowMissing = true)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private SABR track storage" }
        val trackFiles = representations.mapIndexed { index, representation -> representation.role to File(directory, "$index-0") }.toMap()
        trackFiles.values.forEach { requirePrivateCheckpointPath(it, allowMissing = true) }
        if (plan.sabrCheckpoint == null) trackFiles.values.forEach { file ->
            check(!file.exists() || file.delete()) { "Could not reset an uncheckpointed SABR track" }
        }
        val preservedBytes = plan.sabrCheckpoint?.let { checkpoint -> MediaStorageSpace.checkedSum(checkpoint.tracks.map { track ->
            MediaStorageSpace.checkedSum(track.coverage.map { it.length })
        }) } ?: 0L
        require(preservedBytes <= total) { "SABR checkpoint exceeds its declared media sizes" }
        storageCheck(directory, total - preservedBytes)
        val storageFailure = AtomicReference<IOException?>(null)
        val sinks = representations.associate { representation ->
            representation.role to FileSink(trackFiles.getValue(representation.role), expected.getValue(representation.sabr!!.formatId), current,
                storageCheck, storageFailure)
        }
        val transfer = YouTubeSabrTransfer(client, SabrTransferLimits(maxOutputBytes = Long.MAX_VALUE))
        var checkpoint = plan.sabrCheckpoint
        var lastBytes = preservedBytes
        var lastTime = System.nanoTime()
        var speed = 0L
        suspend fun requireCurrent() {
            currentCoroutineContext().ensureActive()
            // A generation change is cancellation, not retryable transport I/O.
            // The outer runner translates this back to its stale-task signal.
            if (!current()) throw CancellationException("SABR task superseded")
        }
        suspend fun persistCurrent(updated: PlannedDownload, parts: List<PartState>, rate: Long) {
            requireCurrent()
            try { persist(updated, parts, rate, total) }
            catch (stale: StaleDownloadException) {
                // Pause can win after the current-task check but before persistence.
                throw CancellationException("SABR task superseded").apply { initCause(stale) }
            }
        }
        fun states(state: SabrTransferCheckpoint): List<PartState> = state.partStates(plan)
        val execution = async(Dispatchers.IO) {
            try {
                val result = transfer.download(source, selection, requireNotNull(plan.primary.sabr).clientInfo,
                    expected, sinks, validateResource = { url ->
                        requireCurrent()
                        UrlPolicy.validate(url, allowHttp)?.let { throw IllegalArgumentException(it) }
                        YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
                    }, checkpoint = checkpoint, onCheckpoint = { state ->
                        requireCurrent()
                        checkpoint = state
                        plan = plan.copy(sabrCheckpoint = state)
                        // Every sink write was fsynced before this durable checkpoint is published.
                        // Sparse file length is never reported as verified downloaded coverage.
                        persistCurrent(plan, states(state), speed)
                    }, onProgress = { progress ->
                        requireCurrent()
                        val now = System.nanoTime()
                        if (progress.bytesWritten != lastBytes && now > lastTime) {
                            speed = ((progress.bytesWritten - lastBytes).coerceAtLeast(0).toDouble() * 1_000_000_000.0 / (now - lastTime)).toLong().coerceAtLeast(0)
                            lastBytes = progress.bytesWritten; lastTime = now
                        }
                        checkpoint?.let { persistCurrent(plan, states(it), speed) }
                    }, nowEpochMs = System::currentTimeMillis, onResponse = { response ->
                        requireCurrent()
                        plan = plan.withSabrDiagnostic(response)
                        checkpoint?.let { persistCurrent(plan, states(it), speed) }
                    })
                requireCurrent()
                plan = plan.copy(sabrCheckpoint = result.checkpoint)
                persistCurrent(plan, result.checkpoint.partStates(plan, complete = true), 0)
                check(result.outputBytesByRole.all { (role, bytes) -> trackFiles.getValue(role).length() == bytes }) {
                    "Completed SABR track file differs from verified coverage"
                }
                requirePrivateCheckpointPath(output, allowMissing = true)
                check(!output.exists() || output.delete()) { "Could not reset private SABR output storage" }
                // Both protocol tracks normally share one endpoint. Actual app-private file
                // URIs distinguish them for the existing processor's callback; no GET or
                // fabricated public media URL is used in this local processing stage.
                fun local(representation: Representation) = representation.copy(
                    url = trackFiles.getValue(representation.role).toURI().toString(), transport = TransportKind.PROGRESSIVE,
                    sabr = null, segments = emptyList(), initialization = null)
                val localFiles = trackFiles.values.associateBy { it.toURI().toString() }
                processor.materialize(local(plan.primary), plan.audio?.let(::local), { resource, target ->
                    requireCurrent()
                    val file = localFiles[resource.url] ?: error("Unplanned private SABR processing resource")
                    requirePrivateCheckpointPath(file)
                    requirePrivateCheckpointPath(target, allowMissing = true)
                    MediaStorageSpace.requireAvailable(target, file.length())
                    file.inputStream().use { input -> target.outputStream().use { destination ->
                        MediaStorageSpace.copy(input, destination, target, ::requireCurrent)
                    } }
                }, output)
                requireCurrent()
                TransferOutcome(output.length(), output.length(), null, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SabrTransferException) {
                storageFailure.get()?.let { throw it }
                if (failure.code in setOf("SABR_SOURCE_URL_EXPIRED", "SABR_REDIRECT_URL_EXPIRED")) throw ExpiredResourceException()
                // Access/protection responses are permanent diagnostics, not expiry guesses.
                // The enclosing planned runner refreshes only an already-known expired URL.
                if (failure.code == "SABR_NETWORK_RECOVERY_EXHAUSTED") throw RetryableDownloadException("SABR network recovery exhausted; verified track checkpoints were retained.")
                throw PermanentDownloadException(failure.safeSummary())
            } catch (failure: Exception) {
                storageFailure.get()?.let { throw it }
                throw failure
            }
        }
        // Caller cancellation also cancels Ktor; a superseded operation closes stalled requests.
        val watcher = launch {
            while (execution.isActive) {
                delay(50)
                if (!current()) { execution.cancel(CancellationException("SABR task superseded")); break }
            }
        }
        try { execution.await() }
        catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && !current()) throw StaleDownloadException()
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                watcher.cancelAndJoin()
                transfer.close()
            }
        }
    }

    private class FileSink(private val file: File, private val expected: Long,
        private val current: suspend () -> Boolean, private val storageCheck: (File, Long) -> Unit,
        private val storageFailure: AtomicReference<IOException?>) : YouTubeSabrTrackSink {
        private suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("SABR task superseded")
        }
        override suspend fun size(): Long = withContext(Dispatchers.IO) {
            checkCurrent()
            requirePrivateCheckpointPath(file, allowMissing = true)
            val size = file.takeIf { it.isFile }?.length() ?: 0L
            require(size <= expected) { "Private SABR track exceeds its source-declared size" }
            size
        }
        override suspend fun read(offset: Long, count: Int): ByteArray = withContext(Dispatchers.IO) {
            checkCurrent()
            requirePrivateCheckpointPath(file)
            require(offset >= 0 && count >= 0 && offset <= expected - count && file.isFile && file.length() >= offset + count)
            ByteArray(count).also { bytes -> RandomAccessFile(file, "r").use { input -> input.seek(offset); input.readFully(bytes) } }
        }
        override suspend fun write(offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
            checkCurrent()
            requirePrivateCheckpointPath(file, allowMissing = true)
            require(offset >= 0 && bytes.isNotEmpty() && offset <= expected - bytes.size)
            try {
                storageCheck(file, bytes.size.toLong())
                RandomAccessFile(file, "rw").use { destination ->
                    destination.seek(offset); destination.write(bytes); destination.fd.sync()
                }
            } catch (failure: IOException) {
                // This catch surrounds only local filesystem writes: never retry it as network I/O.
                storageFailure.compareAndSet(null, failure)
                throw MediaStorageAbort()
            }
            Unit
        }
    }

}
