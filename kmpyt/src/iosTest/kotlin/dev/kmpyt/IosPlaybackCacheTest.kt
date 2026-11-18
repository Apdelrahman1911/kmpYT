@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import kotlin.test.*
import platform.Foundation.NSFileManager

class IosPlaybackCacheTest {
    @Test fun ephemeralPlaybackReadsVerifiedWritesWithoutDurabilityBarriers() = iosTest {
        inIosTestDirectory { directory ->
            var synchronizations = 0
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val path = "$directory/playback.media"
            val cache = IosPlaybackCache(path, bytes.size.toLong()) { synchronizations++; 0 }
            try {
                cache.write(0, bytes.copyOfRange(0, 2048))
                cache.write(2048, bytes.copyOfRange(2048, bytes.size))
                assertContentEquals(bytes, cache.read(0, bytes.size))
                assertEquals(0, synchronizations)
                assertFailsWith<IllegalArgumentException> { cache.write(bytes.size.toLong(), byteArrayOf(1)) }
                assertEquals(bytes.size.toLong(), cache.size())
            } finally { cache.close() }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
        }
    }

    @Test fun retainedDownloadWritesStillRequireSuccessfulSynchronization() = iosTest {
        inIosTestDirectory { directory ->
            var synchronizations = 0
            val file = IosMediaFile("$directory/download.media", 4, durableWrites = true,
                synchronize = { synchronizations++; 0 })
            try {
                file.write(0, byteArrayOf(1, 2))
                file.write(2, byteArrayOf(3, 4))
                assertEquals(2, synchronizations)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), file.read(0, 4))
            } finally { file.close() }
        }
    }

    @Test fun retainedDownloadSynchronizationFailureStillFailsTheWrite() = iosTest {
        inIosTestDirectory { directory ->
            val file = IosMediaFile("$directory/download.media", 4, durableWrites = true, synchronize = { -1 })
            try {
                assertFailsWith<IosMediaStorageException> { file.write(0, byteArrayOf(1, 2, 3, 4)) }
                assertNotNull(file.storageFailure)
            } finally { file.close() }
        }
    }
}
