package dev.kmpyt.internal

import io.ktor.http.Url

/**
 * Googlevideo can pace an open-ended/full GET at the media's consumption rate.
 * Request the exact inspected remaining extent, without changing its signed URL.
 * A missing/complete length keeps the existing unbounded/416 validation path.
 */
internal fun boundedProgressiveRangeEnd(url: String, offset: Long, expectedBytes: Long?): Long? {
    if (offset < 0 || expectedBytes == null || expectedBytes <= offset) return null
    val host = runCatching { Url(url).host.lowercase() }.getOrNull() ?: return null
    if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return null
    return expectedBytes - 1
}
