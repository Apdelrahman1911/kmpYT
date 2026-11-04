package dev.kmpyt.android

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Real HTTP, files and MediaExtractor/MediaMuxer; generated CC0 assets require no device encoder. */
@RunWith(AndroidJUnit4::class)
class AndroidYouTubeDownloaderTest {
    @Test fun nativeDownloadAndMuxKeepEveryEncodedVideoAndAudioSample(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val video = context.assets.open("research/video.mp4").use { it.readBytes() }
        val audio = context.assets.open("research/audio.m4a").use { it.readBytes() }
        val directory = File(context.cacheDir, "kmpyt-native-${UUID.randomUUID()}").apply { mkdirs() }
        val sourceVideo = File(directory, "video.mp4").apply { writeBytes(video) }
        val sourceAudio = File(directory, "audio.m4a").apply { writeBytes(audio) }
        val output = File(directory, "saved.mp4")
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(video)))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(audio)))
        server.start()
        val http = HttpClient(Android)
        try {
            val primary = Representation("video", server.url("/video").toString(), TrackRole.VIDEO,
                mimeType = "video/mp4", codecs = "avc1.4d401e", sizeBytes = video.size.toLong())
            val sound = Representation("audio", server.url("/audio").toString(), TrackRole.AUDIO,
                mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = audio.size.toLong())
            val plan = PlannedDownload(primary, sound, inspectionUrl = server.url("/owned").toString(), mediaId = "owned")
            val progress = mutableListOf<YouTubeDownloadProgress>()
            val result = AndroidYouTubeDownloader(http, true).download(plan, output.path) { progress += it }
            assertEquals("video/mp4", result.mimeType)
            assertEquals(output.length(), result.bytesWritten)
            assertEquals((video.size + audio.size).toLong(), progress.first().totalBytes)
            assertEquals((video.size + audio.size).toLong(), progress.last().bytesDownloaded)
            assertEquals(samples(sourceVideo, "video/"), samples(output, "video/"))
            assertEquals(samples(sourceAudio, "audio/"), samples(output, "audio/"))
            assertEquals(2, server.requestCount)
            assertFalse(File(directory, ".saved.mp4.kmpyt").exists())
        } finally { http.close(); server.shutdown(); directory.deleteRecursively() }
    }

    private fun samples(file: File, family: String): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(family) }
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val result = mutableListOf<String>()
            while (true) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                assertTrue(count > 0)
                val bytes = ByteArray(count); buffer.position(0); buffer.get(bytes)
                result += MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                extractor.advance()
            }
            assertTrue(result.size > 20)
            return result
        } finally { extractor.release() }
    }
}
