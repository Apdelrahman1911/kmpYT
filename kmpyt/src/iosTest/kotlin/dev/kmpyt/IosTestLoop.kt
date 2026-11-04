@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import kotlinx.coroutines.*
import platform.Foundation.*

/** Native tests often start on main. Keep WebKit's main queue alive while its synchronous caller runs on Default. */
internal fun iosTest(seconds: Double = 60.0, block: suspend () -> Unit) {
    val lock = NSLock()
    var result: Result<Unit>? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val job = scope.launch {
        val answer = runCatching { block() }
        lock.lock()
        try { result = answer } finally { lock.unlock() }
    }
    val deadline = NSDate().timeIntervalSince1970 + seconds
    try {
        while (true) {
            lock.lock()
            val answer = try { result } finally { lock.unlock() }
            if (answer != null) { answer.getOrThrow(); return }
            check(NSDate().timeIntervalSince1970 < deadline) { "iOS native integration test timed out." }
            if (NSThread.isMainThread) NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            else NSThread.sleepForTimeInterval(0.01)
        }
    } finally { job.cancel(); scope.cancel() }
}

internal suspend fun inIosTestDirectory(block: suspend (String) -> Unit) {
    val root = NSTemporaryDirectory().trimEnd('/') + "/kmpyt-test-" + NSUUID().UUIDString
    check(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
    try { block(root) }
    finally {
        check(root.startsWith(NSTemporaryDirectory().trimEnd('/') + "/kmpyt-test-"))
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }
}
