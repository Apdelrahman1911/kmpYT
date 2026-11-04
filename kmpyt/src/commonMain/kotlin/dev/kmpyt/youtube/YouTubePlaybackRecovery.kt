package dev.kmpyt.youtube

/** Builds only the canonical public watch page, without copying signed media URLs or credentials. */
fun youTubePlaybackRecoveryUrl(videoId: String, positionMs: Long): String? {
    if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return null
    val seconds = positionMs.coerceAtLeast(0L) / 1_000L
    return "https://www.youtube.com/watch?v=$videoId&t=${seconds}s"
}
