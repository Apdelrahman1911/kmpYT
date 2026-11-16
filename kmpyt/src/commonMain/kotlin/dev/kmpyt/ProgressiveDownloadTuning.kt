package dev.kmpyt

/**
 * Request shape for strictly resumable progressive downloads.
 *
 * Large objects are fetched as consecutive HTTP byte ranges of at most [sectionBytes], using up to
 * [connections] concurrent connections to the same authorized URL. Integrity is unchanged: only a
 * contiguous, synced and hashed prefix is ever checkpointed, and every section must be an exact
 * partial response of the same representation.
 */
data class ProgressiveDownloadTuning(val connections: Int = 4, val sectionBytes: Long = 8L * 1024 * 1024) {
    init {
        require(connections in 1..8) { "Download connections must be between 1 and 8." }
        require(sectionBytes >= 64L * 1024) { "Download sections must be at least 64 KiB." }
    }

    companion object {
        val Default = ProgressiveDownloadTuning()
        /** One request for the whole remaining object (the behavior before sectioning). */
        val SingleRequest = ProgressiveDownloadTuning(connections = 1, sectionBytes = Long.MAX_VALUE / 2)
    }
}
