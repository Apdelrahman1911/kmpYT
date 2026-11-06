@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.cinterop.convert
import platform.Foundation.NSFileManager
import platform.posix.*
import kotlin.test.*

class IosResumeStorageSafetyTest {
    @Test fun ownerLockAndMediaSymlinksAndFifosAreRejectedBeforeAnyWritableOpenOrHttpRequest() = iosTest {
        for (entry in listOf("owner", "lock", "part-0-0.mp4")) for (fifo in listOf(false, true)) {
            inIosTestDirectory { directory ->
                val destination = "$directory/output.mp4"
                val initial = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan()))
                val working = initial.directory
                initial.close()
                val marker = IosMediaFile("$directory/caller-owned")
                try { marker.write(0, byteArrayOf(7, 19, 23)); marker.flush() } finally { marker.close() }
                val path = "$working/$entry"
                if (NSFileManager.defaultManager.fileExistsAtPath(path)) assertEquals(0, unlink(path))
                if (fifo) assertEquals(0, mkfifo(path, 384.convert()))
                else assertEquals(0, symlink("$directory/caller-owned", path))
                val before = iosTestChildren(working)
                var requests = 0
                val http = HttpClient(MockEngine { requests++; error("Unsafe storage must fail before network execution.") })
                try {
                    assertFailsWith<IllegalStateException> { IosYouTubeDownloader(http).download(plan(), destination) {} }
                    assertEquals(0, requests)
                    assertEquals(before, iosTestChildren(working))
                    val retained = IosMediaFile("$directory/caller-owned", existing = true)
                    try { assertContentEquals(byteArrayOf(7, 19, 23), retained.read(0, 3)); assertEquals(3L, retained.length()) }
                    finally { retained.close() }
                    assertEquals(0, unlink(path))
                    // Restore an owner marker if it was the hostile entry, without adopting any other files.
                    if (entry == "owner") {
                        val owner = IosMediaFile(path)
                        try { owner.write(0, "kmpyt-ios-resume-v1".encodeToByteArray()); owner.flush() } finally { owner.close() }
                    }
                    val retry = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan()))
                    retry.close()
                } finally { http.close() }
            }
        }
    }

    @Test fun nativeMediaFileCannotOpenASymlinkOrFifoAsExistingWritableStorage() = iosTest {
        inIosTestDirectory { directory ->
            val marker = IosMediaFile("$directory/caller-owned")
            try { marker.write(0, byteArrayOf(5)); marker.flush() } finally { marker.close() }
            assertEquals(0, symlink("$directory/caller-owned", "$directory/linked"))
            assertEquals(0, mkfifo("$directory/fifo", 384.convert()))
            assertFailsWith<IosMediaStorageException> { IosMediaFile("$directory/linked", existing = true) }
            assertFailsWith<IosMediaStorageException> { IosMediaFile("$directory/fifo", existing = true) }
        }
    }

    @Test fun successfulPublicationAndInvalidCheckpointResetKeepTheExactReusableLockInode() = iosTest {
        inIosTestDirectory { directory ->
            val destination = "$directory/output.mp4"
            val initial = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan()))
            val working = initial.directory
            val inode = iosTestInode("$working/lock")
            initial.close()
            assertIosResumeResidue(directory)
            val malformed = IosMediaFile("$working/checkpoint.json")
            try { malformed.write(0, "{invalid".encodeToByteArray()); malformed.flush() } finally { malformed.close() }
            val reset = IosDownloadResumeStore.open(destination, iosSelectionDigest(plan()))
            try {
                reset.load(plan(), TransferPlanning.parts(plan()))
                assertEquals(inode, iosTestInode("$working/lock"))
                assertTrue(reset.parts.isEmpty())
            } finally { reset.close() }
            val payload = IosAuthoredMedia.video
            val http = HttpClient(MockEngine {
                assertEquals(inode, iosTestInode("$working/lock"))
                respond(payload, headers = headersOf(HttpHeaders.ContentType to listOf("video/mp4"),
                    HttpHeaders.ContentLength to listOf(payload.size.toString())))
            })
            try { IosYouTubeDownloader(http).download(plan(), destination) {} }
            finally { http.close() }
            assertEquals(inode, iosTestInode("$working/lock"))
            assertIosResumeResidue(directory, listOf("output.mp4"))
        }
    }

    private fun plan() = PlannedDownload(Representation("136", "https://fixture.invalid/video", TrackRole.VIDEO,
        mimeType = "video/mp4", codecs = "avc1.42c00a", sizeBytes = IosAuthoredMedia.video.size.toLong()),
        inspectionUrl = "https://fixture.invalid/watch", mediaId = "AUTHORED001")
}
