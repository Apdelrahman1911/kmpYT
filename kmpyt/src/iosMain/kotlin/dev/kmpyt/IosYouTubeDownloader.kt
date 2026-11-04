@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import dev.kmpyt.downloads.DownloadProtocol
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import platform.Foundation.*

/** Real Darwin streaming and AVFoundation muxing. Failed/cancelled staging files are removed; no cross-call resume yet. */
class IosYouTubeDownloader(private val http: HttpClient, private val allowLocalHttp: Boolean = false) : YouTubeDownloader {
    @Throws(Exception::class)
    override suspend fun download(plan: PlannedDownload, destinationPath: String,
        onProgress: suspend (YouTubeDownloadProgress) -> Unit): YouTubeDownloadResult = withContext(Dispatchers.Default) {
        YouTubeDownloadPolicy.requirePlan(plan)
        require(plan.credentialRef == null) { "The iOS adapter accepts anonymous inspected resources only." }
        require(plan.sabrCheckpoint == null) { "iOS cannot restore a checkpoint without its original private track files. Create a fresh plan." }
        YouTubeFormatCapabilities.downloadProblem(plan.primary, plan.audio, "iOS")?.let { error(it) }
        TransferPlanning.parts(plan) // Validate all framing headers/ranges before touching disk.
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
        require(destinationPath.startsWith('/') && '\u0000' !in destinationPath &&
            destinationPath.split('/').none { it == "." || it == ".." } && !destinationPath.endsWith('/')) {
            "Choose an absolute local destination file path."
        }
        val manager = NSFileManager.defaultManager
        require(!manager.fileExistsAtPath(destinationPath)) { "The destination already exists; choose another file." }
        val parent = destinationPath.substringBeforeLast('/').ifEmpty { "/" }
        check(manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)) {
            "Could not create the destination directory."
        }
        val working = parent.trimEnd('/') + "/." + destinationPath.substringAfterLast('/') + ".kmpyt-" + NSUUID().UUIDString
        check(manager.createDirectoryAtPath(working, withIntermediateDirectories = false, attributes = null, error = null)) {
            "Could not create private download storage."
        }
        val files = mutableListOf<IosMediaFile>()
        try {
            val total = TransferPlanning.totalBytes(plan)
            total?.let { requireIosStorage(working, it) }
            onProgress(YouTubeDownloadProgress(0, total))
            representations.forEachIndexed { index, representation ->
                files += IosMediaFile("$working/track-$index", representation.sizeBytes.takeIf { representation.transport == TransportKind.SABR },
                    durableWrites = representation.transport == TransportKind.SABR)
            }
            if (plan.primary.transport == TransportKind.SABR) {
                require(plan.requestHeaders.isEmpty()) { "SABR uses its source-declared anonymous client headers." }
                val transfer = YouTubeSabrTransfer(http)
                try {
                    val sizes = representations.associate { requireNotNull(it.sabr).formatId to requireNotNull(it.sizeBytes) }
                    val sinks = representations.mapIndexed { index, representation -> representation.role to files[index] }.toMap()
                    transfer.download(plan.sabrSource(), plan.sabrSelection(), requireNotNull(plan.primary.sabr).clientInfo,
                        sizes, sinks, validateResource = { validate(it, plan) },
                        onProgress = { onProgress(YouTubeDownloadProgress(it.bytesWritten, it.totalBytes)) },
                        nowEpochMs = { (NSDate().timeIntervalSince1970 * 1000).toLong() })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // The shared protocol wraps sink failures; retain actionable native disk errors without retrying them as HTTP.
                    files.firstNotNullOfOrNull { it.storageFailure }?.let { throw it }
                    throw failure
                } finally { transfer.close() }
            } else {
                TransferCoordinator().run(plan, emptyList(), isCurrent = {
                    currentCoroutineContext().ensureActive(); true
                }, transfer = { part, initial, progress ->
                    val track = files[part.key.substringBefore('-').toInt()]
                    fetch(part, initial, track, plan, progress)
                }, persist = { states, speed, size ->
                    onProgress(YouTubeDownloadProgress(checkedIosSum(states.map { it.bytes }), size, speed))
                })
            }
            currentCoroutineContext().ensureActive()
            files.forEach { it.flush(); it.close() }
            val output = "$working/output.mp4"
            val remux = plan.audio != null || plan.primary.segments.isNotEmpty()
            if (remux) {
                val bytes = checkedIosSum(files.map { file ->
                    (manager.attributesOfItemAtPath(file.path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue
                        ?: error("The downloaded media file is missing.")
                })
                requireIosStorage(working, bytes)
                IosMediaMuxer.mux(files[0].path, plan.primary.role, files.getOrNull(1)?.path, output)
            } else {
                check(manager.moveItemAtPath(files.single().path, toPath = output, error = null)) { "Could not prepare the complete media file." }
            }
            currentCoroutineContext().ensureActive()
            val written = (manager.attributesOfItemAtPath(output, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            check(written > 0) { "The downloaded media is empty." }
            require(!manager.fileExistsAtPath(destinationPath)) { "The destination was created by another writer." }
            check(manager.moveItemAtPath(output, toPath = destinationPath, error = null)) { "Could not publish the complete media file." }
            YouTubeDownloadResult(destinationPath, written,
                if (remux) if (plan.primary.role == TrackRole.AUDIO) "audio/mp4" else "video/mp4"
                else plan.primary.mimeType.substringBefore(';'))
        } finally {
            files.forEach { it.close() }
            // Exact UUID directory created above; no caller directory or existing destination is removed.
            manager.removeItemAtPath(working, error = null)
        }
    }

    private fun validate(url: String, plan: PlannedDownload) {
        UrlPolicy.validate(url, allowLocalHttp)?.let { throw IllegalArgumentException(it) }
        YouTubeDownloadPolicy.requireResource(url, plan.inspectionUrl, plan.mediaId)
    }

    private suspend fun fetch(part: TransferPart, initial: PartState, file: IosMediaFile, plan: PlannedDownload,
        progress: suspend (PartState, Long) -> Unit): PartState {
        var url = part.resource.url
        val origin = ResearchAccess.origin(url)
        val offset = file.length()
        repeat(6) { redirect ->
            currentCoroutineContext().ensureActive()
            validate(url, plan)
            var next: String? = null
            var finished: PartState? = null
            http.prepareGet(url) {
                header(HttpHeaders.AcceptEncoding, "identity")
                // Caller headers may not be forwarded to a different origin by a redirect.
                if (ResearchAccess.origin(url) == origin) plan.requestHeaders.forEach { (key, value) -> header(key, value) }
                part.resource.rangeStart?.let { start ->
                    header(HttpHeaders.Range, "bytes=$start-${start + requireNotNull(part.resource.rangeLength) - 1}")
                }
            }.execute { response ->
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5) { "Too many media redirects." }
                    val location = response.headers[HttpHeaders.Location] ?: error("The media redirect has no destination.")
                    next = NSURL(string = location, relativeToURL = NSURL(string = url)).absoluteURL?.absoluteString
                        ?: error("The media redirect has an invalid destination.")
                    return@execute
                }
                val expectedStatus = if (part.resource.rangeStart != null) 206 else 200
                check(response.status.value == expectedStatus) {
                    if (response.status.value in setOf(401, 403, 410)) "The media link is no longer available. Inspect the video again and retry."
                    else "The media server returned HTTP ${response.status.value}."
                }
                check(response.headers[HttpHeaders.ContentEncoding].let { it == null || it.equals("identity", true) }) {
                    "Compressed media responses cannot be saved as the inspected byte stream."
                }
                val type = (response.headers[HttpHeaders.ContentType] ?: part.mimeType).substringBefore(';').trim().lowercase()
                check(!type.contains("mpegurl") && !type.contains("xml") &&
                    (type.startsWith("video/") || type.startsWith("audio/") ||
                        type in setOf("application/mp4", "application/ogg", "application/octet-stream"))) {
                    "The media server returned a document or unsupported content type instead of media."
                }
                val length = response.headers[HttpHeaders.ContentLength]?.let { value ->
                    value.toLongOrNull()?.takeIf { it >= 0 } ?: error("The media server returned an invalid content length.")
                }
                val rangeLength = part.resource.rangeLength
                if (rangeLength != null) {
                    val actual = DownloadProtocol.parseContentRange(response.headers[HttpHeaders.ContentRange])
                        ?: error("The media server returned an invalid byte range.")
                    check(actual.first == part.resource.rangeStart &&
                        actual.last == requireNotNull(part.resource.rangeStart) + rangeLength - 1 &&
                        (length == null || length == rangeLength)) { "The media server returned a different byte range." }
                }
                val total = rangeLength ?: length ?: initial.total
                total?.let { requireIosStorage(file.path.substringBeforeLast('/'), it) }
                var bytes = 0L
                var last = NSDate().timeIntervalSince1970
                var lastBytes = 0L
                var state = PartState(part.key, total = total, etag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified])
                progress(state, 0)
                val buffer = ByteArray(64 * 1024)
                val channel = response.bodyAsChannel()
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        require(bytes <= Long.MAX_VALUE - count && offset <= Long.MAX_VALUE - bytes - count)
                        check(total == null || bytes + count <= total) { "The source sent more media than its reported length." }
                        file.write(offset + bytes, buffer, count)
                        bytes += count
                        val now = NSDate().timeIntervalSince1970
                        if (lastBytes == 0L || now - last >= 0.4) {
                            val speed = if (now > last) ((bytes - lastBytes) / (now - last)).toLong().coerceAtLeast(0) else 0
                            state = state.copy(bytes = bytes)
                            progress(state, speed)
                            last = now; lastBytes = bytes
                        }
                    }
                } finally { channel.cancel() }
                check(bytes > 0 && (total == null || bytes == total)) { "The media response ended before the complete track was received." }
                file.flush()
                finished = state.copy(bytes = bytes, total = bytes, complete = true)
            }
            finished?.let { return it }
            url = requireNotNull(next)
        }
        error("Too many media redirects.")
    }
}
