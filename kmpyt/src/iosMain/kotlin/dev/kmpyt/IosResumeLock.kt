@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import platform.posix.LOCK_UN
import platform.posix.flock

internal data class IosCheckpointLockRelease(
    val failure: Throwable?,
    val descriptorReleased: Boolean,
    val retained: Boolean,
)

/**
 * A terminal disposition of an owned descriptor; callers must not invoke this twice for one handle.
 * If a dependent media/checkpoint/owner close was uncertain, retain the held flock and descriptor
 * until process exit. Unlocking first would let another process mutate still-owned private files.
 * This cannot reconstruct a lock after an attempted unlock/close itself returns an error.
 */
internal fun iosReleaseCheckpointLock(descriptor: Int, held: Boolean,
    earlierFailure: Throwable?): IosCheckpointLockRelease {
    check(descriptor >= 0 || !held)
    if (held && iosReleaseUncertain(earlierFailure)) {
        return IosCheckpointLockRelease(earlierFailure, descriptorReleased = false, retained = true)
    }
    var released = descriptor < 0
    val failure = iosCleanupFailure(earlierFailure,
        {
            if (held && flock(descriptor, LOCK_UN) != 0)
                throw IosMediaStorageException("Could not release the checkpoint lock.")
        },
        {
            if (descriptor >= 0) iosCloseCheckpointDescriptor(descriptor)
            released = true
        },
    )
    return IosCheckpointLockRelease(failure, descriptorReleased = released, retained = false)
}

internal fun iosCloseCheckpointDescriptor(descriptor: Int) {
    if (platform.posix.close(descriptor) != 0)
        throw IosMediaStorageException("Could not confirm release of checkpoint storage.", releaseUncertain = true)
}
