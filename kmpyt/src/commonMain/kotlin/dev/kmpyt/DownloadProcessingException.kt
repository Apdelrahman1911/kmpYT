package dev.kmpyt

/** A local processing failure. If restartRequired is true, preserve the staging directory until process exit. */
class DownloadProcessingException(
    val reason: Reason,
    val restartRequired: Boolean = false,
) : Exception(reason.name) {
    enum class Reason { EXPORT_TIMEOUT, EXPORT_FAILED, EXPORT_CANCELLED }
}
