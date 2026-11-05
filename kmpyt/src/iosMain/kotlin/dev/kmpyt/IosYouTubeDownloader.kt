@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import platform.Foundation.*

/**
 * Darwin streaming with durable private checkpoints and local AVFoundation muxing.
 * Retry the same destination and inspected selection to resume validated media. HTTP
 * sources need strong validators; SABR resumes byte receipts with a fresh anonymous
 * session and restarts if its source generation changes. No signed URL/session data
 * is persisted. Existing destination files are never replaced.
 */
class IosYouTubeDownloader(private val http: HttpClient, private val allowLocalHttp: Boolean = false) : YouTubeDownloader {
    @Throws(Exception::class)
    override suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult = withContext(Dispatchers.Default) {
        YouTubeDownloadPolicy.requirePlan(plan)
        require(plan.credentialRef == null) { "The iOS adapter accepts anonymous inspected resources only." }
        require(plan.sabrCheckpoint == null) { "An external SABR checkpoint has no owned iOS track files. Retry the original destination instead." }
        YouTubeFormatCapabilities.downloadProblem(plan.primary, plan.audio, "iOS")?.let { error(it) }
        val parts = TransferPlanning.parts(plan)
        val representations = listOfNotNull(plan.primary, plan.audio)
        representations.forEach { representation ->
            require(representation.transport != TransportKind.UNRESOLVED)
            require(representation.transport !in setOf(TransportKind.HLS, TransportKind.DASH) || representation.segments.isNotEmpty()) {
                "Resolve the static media manifest before downloading."
            }
            require(representation.segments.isEmpty() || representation.mimeType.substringBefore(';') in setOf("video/mp4", "audio/mp4")) {
                "iOS currently assembles static fragmented MP4 manifests. MPEG-TS/WebM segments are not supported."
            }
        }
        val manager = NSFileManager.defaultManager
        require(!manager.fileExistsAtPath(destinationPath)) { "The destination already exists; choose another file." }
        val store = IosDownloadResumeStore.open(destinationPath, iosSelectionDigest(plan))
        val files = mutableListOf<IosMediaFile>()
        var published = false
        try {
            require(!manager.fileExistsAtPath(store.destination)) { "The destination already exists; choose another file." }
            store.load(plan, parts)
            val paths: List<String>
            if (plan.primary.transport == TransportKind.SABR) {
                require(plan.requestHeaders.isEmpty()) { "SABR uses its source-declared anonymous client headers." }
                val resume = IosSabrResume(store, plan)
                val checkpoint = resume.restore(files)
                val retained = checkedIosSum(checkpoint?.tracks.orEmpty().flatMap { it.coverage }.map { it.length })
                val total = requireNotNull(TransferPlanning.totalBytes(plan))
                requireIosStorage(store.directory, total - retained)
                onProgress(YouTubeDownloadProgress(retained, total))
                val transfer = YouTubeSabrTransfer(http)
                try {
                    val sizes = representations.associate { requireNotNull(it.sabr).formatId to requireNotNull(it.sizeBytes) }
                    val sinks = representations.mapIndexed { index, representation -> representation.role to files[index] }.toMap()
                    transfer.download(plan.sabrSource(), plan.sabrSelection(), requireNotNull(plan.primary.sabr).clientInfo,
                        sizes, sinks, validateResource = { validate(it, plan) }, checkpoint = checkpoint,
                        onCheckpoint = resume::save,
                        onProgress = { onProgress(YouTubeDownloadProgress(it.bytesWritten, it.totalBytes)) },
                        nowEpochMs = { (NSDate().timeIntervalSince1970 * 1000).toLong() })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    files.firstNotNullOfOrNull { it.storageFailure }?.let { throw it }
                    throw failure
                } finally { transfer.close() }
                files.forEach { it.flush(); it.close() }
                paths = files.map { it.path }
            } else {
                val initial = store.parts.map { PartState(it.key, it.bytes, it.total, complete = false) }
                val transfer = IosHttpPartTransfer(http, store, plan, allowLocalHttp)
                var lastProgress: YouTubeDownloadProgress? = null
                suspend fun publish(speed: Long) {
                    // A validator-driven restart can invalidate a formerly known response size.
                    // Compute from current receipts rather than the coordinator's old hint.
                    val states = parts.map { part -> store.parts.singleOrNull { it.key == part.key }
                        ?: IosSavedPart(part.key, "", "", total = part.expectedBytes) }
                    val totals = representations.indices.map { track ->
                        val selected = states.filter { it.key.substringBefore('-').toInt() == track }
                        if (selected.all { it.total != null }) checkedIosSum(selected.map { requireNotNull(it.total) })
                        else TransferPlanning.totalBytes(plan.copy(primary = representations[track], audio = null))
                            ?.takeIf { it >= checkedIosSum(selected.map { maxOf(it.bytes, it.total ?: 0) }) }
                    }
                    val known = checkedIosSum(totals.filterNotNull())
                    val update = YouTubeDownloadProgress(checkedIosSum(states.map { it.bytes }),
                        known.takeIf { totals.all { it != null } }, speed)
                    if (update != lastProgress) { onProgress(update); lastProgress = update }
                }
                publish(0)
                TransferCoordinator().run(plan, initial, isCurrent = {
                    currentCoroutineContext().ensureActive(); true
                }, transfer = { part, _, progress -> transfer.fetch(part, progress) },
                    persist = { _, speed, _ -> publish(speed) })
                check(parts.all { part -> store.parts.any { it.key == part.key && it.complete && it.bytes == it.total } }) {
                    "The selected media has incomplete private byte coverage."
                }
                paths = representations.indices.map { track ->
                    assemble(store, track, parts.filter { it.key.substringBefore('-').toInt() == track })
                }
            }
            currentCoroutineContext().ensureActive()
            val remux = plan.audio != null || plan.primary.segments.isNotEmpty()
            val output = if (remux) "${store.directory}/output.mp4" else paths.single()
            if (remux) {
                requireIosStorage(store.directory, checkedIosSum(paths.map { requireNotNull(store.fileLength(it)) }))
                IosMediaMuxer.mux(paths[0], plan.primary.role, paths.getOrNull(1), output)
                IosMediaFile(output, existing = true).let { file -> try { file.flush() } finally { file.close() } }
            }
            currentCoroutineContext().ensureActive()
            val written = store.fileLength(output) ?: 0
            check(written > 0) { "The downloaded media is empty." }
            require(!manager.fileExistsAtPath(store.destination)) { "The destination was created by another writer." }
            check(manager.moveItemAtPath(output, toPath = store.destination, error = null)) { "Could not publish the complete media file." }
            published = true
            YouTubeDownloadResult(destinationPath, written,
                if (remux) if (plan.primary.role == TrackRole.AUDIO) "audio/mp4" else "video/mp4"
                else plan.primary.mimeType.substringBefore(';'))
        } finally {
            files.forEach { it.close() }
            store.close(completed = published)
        }
    }

    private fun validate(url: String, plan: PlannedDownload) {
        UrlPolicy.validate(url, allowLocalHttp)?.let { throw IllegalArgumentException(it) }
        YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
    }

    private suspend fun assemble(store: IosDownloadResumeStore, track: Int, parts: List<TransferPart>): String {
        if (parts.size == 1) return store.partPath(parts.single().key)
        val sizes = parts.map { requireNotNull(store.fileLength(store.partPath(it.key))) }
        val total = checkedIosSum(sizes)
        requireIosStorage(store.directory, total)
        val destination = IosMediaFile("${store.directory}/assembled-$track.mp4", total)
        try {
            var position = 0L
            parts.forEachIndexed { index, part ->
                val source = IosMediaFile(store.partPath(part.key), existing = true)
                try {
                    var offset = 0L
                    while (offset < sizes[index]) {
                        val bytes = source.read(offset, minOf(64 * 1024L, sizes[index] - offset).toInt())
                        destination.write(position, bytes)
                        offset += bytes.size; position += bytes.size
                    }
                } finally { source.close() }
            }
            check(position == total)
            destination.flush()
            return destination.path
        } finally { destination.close() }
    }
}
