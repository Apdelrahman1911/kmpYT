@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import platform.posix.*
import kotlin.test.*

class IosResumeLockTest {
    @Test fun reportedDependentUncertaintyRetainsTheRealLockUntilItsOwnerExplicitlyReleasesIt() = iosTest {
        for (suppressed in listOf(false, true)) inIosTestDirectory { directory ->
            val path = "$directory/lock"
            val owner = TestDescriptor.open(path, create = true)
            try {
                assertEquals(0, flock(owner.value, LOCK_EX or LOCK_NB))
                val contender = TestDescriptor.open(path)
                try {
                    val uncertainty = IosMediaStorageException("authored unconfirmed release", releaseUncertain = true)
                    val earlier: Throwable = if (suppressed) IllegalStateException("authored transfer failure").also {
                        it.addSuppressed(uncertainty)
                    } else uncertainty
                    // Exercise the real close/failed-open disposition, not a claimed POSIX close fault.
                    val result = iosReleaseCheckpointLock(owner.value, held = true, earlierFailure = earlier)
                    owner.recordDisposition(result)
                    assertSame(earlier, result.failure)
                    assertFalse(result.descriptorReleased)
                    assertTrue(result.retained)
                    assertContended(contender.value)
                    // This test owns the known fd and injected report; no uncertain media fd exists.
                    // Production never performs this recovery: its retained lock lasts until exit.
                    owner.close()
                    assertEquals(0, flock(contender.value, LOCK_EX or LOCK_NB))
                } finally { contender.close() }
            } finally { owner.close() }
        }
    }

    @Test fun anOrdinaryFailureStillReleasesTheHeldLockAndPreservesTheOriginalError() = iosTest {
        inIosTestDirectory { directory ->
            val path = "$directory/lock"
            val owner = TestDescriptor.open(path, create = true)
            try {
                assertEquals(0, flock(owner.value, LOCK_EX or LOCK_NB))
                val contender = TestDescriptor.open(path)
                try {
                    val earlier = IllegalStateException("authored non-ownership failure")
                    val result = iosReleaseCheckpointLock(owner.value, held = true, earlierFailure = earlier)
                    owner.recordDisposition(result)
                    assertSame(earlier, result.failure)
                    assertTrue(result.descriptorReleased)
                    assertFalse(result.retained)
                    assertEquals(0, flock(contender.value, LOCK_EX or LOCK_NB))
                } finally { contender.close() }
            } finally { owner.close() }
        }
    }

    @Test fun closingAFailedAcquisitionDoesNotReleaseTheOtherOpenDescriptionsLock() = iosTest {
        inIosTestDirectory { directory ->
            val path = "$directory/lock"
            val owner = TestDescriptor.open(path, create = true)
            try {
                assertEquals(0, flock(owner.value, LOCK_EX or LOCK_NB))
                val rejected = TestDescriptor.open(path)
                try {
                    assertContended(rejected.value)
                    val earlier = IllegalStateException("authored acquisition refusal")
                    val result = iosReleaseCheckpointLock(rejected.value, held = false, earlierFailure = earlier)
                    rejected.recordDisposition(result)
                    assertSame(earlier, result.failure)
                    assertTrue(result.descriptorReleased)
                    assertFalse(result.retained)
                    val contender = TestDescriptor.open(path)
                    try { assertContended(contender.value) } finally { contender.close() }
                } finally { rejected.close() }
            } finally { owner.close() }
        }
    }

    private fun assertContended(descriptor: Int) {
        val result = flock(descriptor, LOCK_EX or LOCK_NB)
        val error = errno
        assertEquals(-1, result)
        assertTrue(error == EWOULDBLOCK || error == EAGAIN)
    }

    private class TestDescriptor(val value: Int) {
        private var owned = true

        fun recordDisposition(result: IosCheckpointLockRelease) {
            // Never retry a descriptor after production attempted close, even if it reported failure.
            owned = result.retained
        }

        fun close() {
            if (!owned) return
            owned = false
            assertEquals(0, platform.posix.close(value))
        }

        companion object {
            fun open(path: String, create: Boolean = false): TestDescriptor {
                val descriptor = platform.posix.open(path,
                    O_RDWR or O_NOFOLLOW or O_NONBLOCK or (if (create) O_CREAT or O_EXCL else 0), 384)
                check(descriptor >= 0) { "Could not create the authored lock fixture." }
                return TestDescriptor(descriptor)
            }
        }
    }
}
