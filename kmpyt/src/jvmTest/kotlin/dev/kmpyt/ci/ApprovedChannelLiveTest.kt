package dev.kmpyt.ci

import dev.kmpyt.extraction.*
import dev.kmpyt.jvm.createJvmYouTubeClient
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.kmpyt.youtube.YouTubeFormatSelection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Never enabled by ordinary CI; media transfer requires a verified member of the approved channel. */
class ApprovedChannelLiveTest {
    @Test fun approvedChannelVideoSupportsMetadataCaptionsAndCompleteDownload(): Unit = runBlocking {
        assumeTrue("Live YouTube runs require the explicit approved-channel workflow.", System.getenv("KMPYT_LIVE_TESTS") == "true")
        val configFile = listOf(File("ci/approved-channel.json"), File("../ci/approved-channel.json")).first { it.isFile }
        val config = Json.parseToJsonElement(configFile.readText()).jsonObject
        val channelUrl = config.getValue("channelUrl").jsonPrimitive.content
        val byteBudget = config.getValue("maximumDownloadBytesPerVideo").jsonPrimitive.long
        require(channelUrl == "https://www.youtube.com/@oPiiLz" && byteBudget in 1..67_108_864)
        val ffmpeg = System.getenv("KMPYT_FFMPEG") ?: "/usr/bin/ffmpeg"
        val client = createJvmYouTubeClient(ffmpeg)
        val directory = Files.createTempDirectory("kmpyt-approved-live-").toFile()
        val reportDirectory = File("build/reports/live").apply { mkdirs() }
        val events = mutableListOf<JsonObject>()
        var passed = false
        var failureType: String? = null
        var verifiedChannelId: String? = null
        var stage = "channel"
        try {
            withTimeout(12 * 60_000L) {
                val channel = client.channel("$channelUrl/videos")
                verifiedChannelId = requireNotNull(channel.channelId) { "The approved channel did not provide a verifiable ID." }
                require(Regex("UC[A-Za-z0-9_-]{22}").matches(verifiedChannelId!!))
                val explicit = System.getenv("KMPYT_LIVE_VIDEO_URL")?.trim()?.takeIf { it.isNotEmpty() }
                val candidates = if (explicit != null) listOf(requireNotNull(YouTubeProvider().normalize(explicit))) else {
                    val uploads = channel.items.filter { it.kind == YouTubeBrowseKind.VIDEO }.map { it.url }
                    // Prefer short uploads when available to keep complete-download CI economical.
                    val shorts = try { client.channel("$channelUrl/shorts") }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        ?.takeIf { it.channelId == verifiedChannelId }?.items.orEmpty()
                        .filter { it.kind == YouTubeBrowseKind.VIDEO }.map { it.url }
                    (shorts + uploads).distinct().take(8)
                }
                assertTrue("The approved channel supplied no video candidates", candidates.isNotEmpty())
                var completed = 0
                for (url in candidates) {
                    stage = "inspect:${YouTubeDownloadPolicy.videoId(url)}"
                    val report = client.inspect(url)
                    val media = report.media.singleOrNull { it.id == YouTubeDownloadPolicy.videoId(url) }
                    events += buildJsonObject {
                        put("videoId", YouTubeDownloadPolicy.videoId(url)); put("result", "inspection")
                        put("mediaCount", report.media.size); put("uploaderChannelId", media?.uploaderChannelId)
                        put("formatCount", media?.representations?.size ?: 0)
                        put("diagnostics", JsonArray(report.diagnostics.map { it.code }
                            .filter { it.length <= 100 && Regex("[A-Z0-9_]+").matches(it) }
                            .distinct().map(::JsonPrimitive)))
                    }
                    if (media == null || media.uploaderChannelId != verifiedChannelId) {
                        events += buildJsonObject { put("videoId", YouTubeDownloadPolicy.videoId(url)); put("result", "unverified-owner-no-media-request") }
                        if (explicit != null) fail("The supplied video is not a verified member of the approved channel.")
                        continue
                    }
                    val direct = media.copy(representations = media.representations.filter { it.transport == TransportKind.PROGRESSIVE })
                    val plan = listOf(360, 240, 144).firstNotNullOfOrNull { height ->
                        val selection = YouTubeFormatSelection.recommendedDownload(direct, "Android", maxHeight = height)
                            ?: return@firstNotNullOfOrNull null
                        val primary = direct.representations.single { it.id == selection.primaryId }
                        val audio = selection.audioId?.let { id -> direct.representations.single { it.id == id } }
                        if (primary.role != TrackRole.MUXED && audio == null) return@firstNotNullOfOrNull null
                        PlannedDownload(primary, audio, inspectionUrl = report.input, mediaId = media.id)
                            .takeIf { TransferPlanning.totalBytes(it)?.let { bytes -> bytes in 1..byteBudget } == true }
                    }
                    if (plan == null) {
                        events += buildJsonObject { put("videoId", media.id); put("result", "no-complete-compatible-resource-within-ci-budget") }
                        continue
                    }
                    val selectedPlan = client.plan(report, YouTubeFormatSelection(plan.primary.id, plan.audio?.id))
                    var captionStatus = "not-provided"
                    media.captions?.tracks?.firstOrNull()?.let { track ->
                        stage = "captions:${media.id}"
                        val document = client.captions(report, track.sourceIndex)
                        captionStatus = "loaded-${document.cues.size}-cues"
                    }
                    val destination = File(directory, "${media.id}.mp4")
                    val started = System.nanoTime()
                    var initialTotal: Long? = null
                    var progressSeen = false
                    stage = "download:${media.id}"
                    val result = client.download(selectedPlan, destination.absolutePath) { progress ->
                        if (!progressSeen) { initialTotal = progress.totalBytes; progressSeen = true }
                        check(progress.bytesDownloaded <= byteBudget) { "The CI media-byte budget was exceeded." }
                    }
                    assertTrue(destination.isFile && result.bytesWritten == destination.length())
                    assertEquals(TransferPlanning.totalBytes(selectedPlan), initialTotal)
                    stage = "verify-saved-media:${media.id}"
                    val packetReport = inspectSavedFile(destination)
                    val streams = packetReport.getValue("streams").jsonArray.map { it.jsonObject }
                    assertTrue(streams.any { it["codec_type"]?.jsonPrimitive?.content == "video" })
                    assertTrue(streams.any { it["codec_type"]?.jsonPrimitive?.content == "audio" })
                    assertTrue(streams.all { (it["nb_read_packets"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) > 0 })
                    val actualDuration = packetReport["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull()
                    media.durationMs?.let { expected ->
                        assertNotNull("The saved file must declare its duration", actualDuration)
                        assertTrue("The saved file must cover the source duration", kotlin.math.abs(actualDuration!! * 1000 - expected) <= 4000)
                    }
                    events += buildJsonObject {
                        put("videoId", media.id); put("uploaderChannelId", media.uploaderChannelId)
                        put("result", "complete-download-verified"); put("declaredTransferBytes", initialTotal)
                        put("savedBytes", result.bytesWritten); put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                        put("captionStatus", captionStatus); put("formats", JsonArray(listOfNotNull(plan.primary, plan.audio).map { JsonPrimitive(it.id) }))
                    }
                    completed++
                    // One complete sampled video exercises the live pipeline; the channel approval covers all candidates.
                    break
                }
                assertTrue("No approved video completed a verified download; inspect the redacted live report.", completed > 0)
                passed = true
            }
        } catch (failure: Throwable) {
            failureType = failure.javaClass.simpleName
            // Do not expose source response bodies, signed URLs or subprocess output in public test logs.
            fail("Approved-channel live test failed at $stage ($failureType). See the redacted result artifact.")
        } finally {
            client.close()
            File(reportDirectory, "approved-channel.json").writeText(buildJsonObject {
                put("approvedChannel", channelUrl); put("resolvedChannelId", verifiedChannelId)
                put("passed", passed); put("failureType", failureType); put("signedUrlsRecorded", false)
                put("stage", stage)
                put("events", JsonArray(events))
            }.toString())
            directory.deleteRecursively()
        }
    }

    private fun inspectSavedFile(file: File): JsonObject {
        val process = ProcessBuilder("ffprobe", "-v", "error", "-count_packets", "-show_streams", "-show_format",
            "-of", "json", file.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return try {
            val output = process.inputStream.readNBytes(256 * 1024)
            check(output.size < 256 * 1024 && process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0)
            Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
