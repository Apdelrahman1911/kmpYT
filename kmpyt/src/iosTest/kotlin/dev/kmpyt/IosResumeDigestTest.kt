@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import kotlinx.coroutines.*
import kotlin.test.*

class IosResumeDigestTest {
    @Test fun coreCryptoKnownVectorsAndSnapshotsDoNotConsumeTheRunningContext() {
        val hash = IosResumeSha256()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash.snapshot())
        hash.update("a".encodeToByteArray())
        val first = hash.snapshot()
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", first)
        assertEquals(first, hash.snapshot())
        hash.update("bc-unused".encodeToByteArray(), 2)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.snapshot())
        hash.update(byteArrayOf(), 0)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.snapshot())
    }

    @Test fun nativeFilePrefixHashStreamsMultipleChunksAndHonorsCancellation() = iosTest {
        inIosTestDirectory { directory ->
            val file = IosMediaFile("$directory/authored.bin", 1_000_000)
            try {
                val chunk = ByteArray(10_000) { 'a'.code.toByte() }
                repeat(100) { file.write(it * 10_000L, chunk) }
                file.flush()
                val digest = iosPrefixHasher(file, 1_000_000)
                assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", digest.snapshot())
                coroutineScope {
                    launch {
                        currentCoroutineContext().cancel()
                        assertFailsWith<CancellationException> { iosPrefixHasher(file, 1_000_000) }
                    }.join()
                }
                assertEquals(1_000_000L, file.length())
                assertEquals(digest.snapshot(), iosPrefixHasher(file, 1_000_000).snapshot())
            } finally { file.close() }
        }
    }

    @Test fun independentCleanupAttemptsEveryActionAndPreservesOriginalAndSuppressedFailures() = iosTest {
        val attempted = mutableListOf<Int>()
        val original = IllegalStateException("authored transfer failure")
        val firstClose = IosMediaStorageException("authored uncertain close", releaseUncertain = true)
        val secondClose = IosMediaStorageException("authored second close")
        iosCleanup(original, { attempted += 1; throw firstClose }, { attempted += 2; throw secondClose }, { attempted += 3 })
        assertEquals(listOf(1, 2, 3), attempted)
        assertEquals(listOf(firstClose, secondClose), original.suppressedExceptions)
        assertTrue(iosReleaseUncertain(original))
        assertFalse(iosReleaseUncertain(secondClose))
        attempted.clear()
        val cancellation = CancellationException("authored cancellation")
        iosCleanupAsync(cancellation, { attempted += 1; throw firstClose }, { attempted += 2; throw secondClose }, { attempted += 3 })
        assertEquals(listOf(1, 2, 3), attempted)
        assertEquals(listOf(firstClose, secondClose), cancellation.suppressedExceptions)
        val initial = assertFailsWith<IosMediaStorageException> {
            iosCleanup(null, { throw firstClose }, { throw secondClose })
        }
        assertSame(firstClose, initial)
        assertEquals(listOf(secondClose), initial.suppressedExceptions)
    }
}
