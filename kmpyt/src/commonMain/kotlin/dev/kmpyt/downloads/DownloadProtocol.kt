package dev.kmpyt.downloads

/** HTTP representation checks shared by download transports on future platforms. */
object DownloadProtocol {
    data class ContentRange(val first: Long, val last: Long, val total: Long?)

    sealed interface ResponsePlan {
        data class Transfer(val offset: Long, val total: Long?, val append: Boolean) : ResponsePlan
        data object AlreadyComplete : ResponsePlan
        data class Restart(val reason: String) : ResponsePlan
        data class Reject(val reason: String) : ResponsePlan
    }

    fun strongValidator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
            ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }

    fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim() ?: return null) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        if (last < first || last == Long.MAX_VALUE || (total != null && total <= last)) return null
        return ContentRange(first, last, total)
    }

    private fun validatorsAgree(
        oldEtag: String?, oldModified: String?, newEtag: String?, newModified: String?,
        requireResponseValidator: Boolean = false,
    ): Boolean {
        val oldStrongEtag = oldEtag?.takeUnless { it.startsWith("W/", ignoreCase = true) }
        if (!oldStrongEtag.isNullOrBlank()) {
            return if (newEtag == null) !requireResponseValidator else oldStrongEtag == newEtag
        }
        if (!oldModified.isNullOrBlank()) {
            return if (newModified == null) !requireResponseValidator else oldModified == newModified
        }
        return false
    }

    fun plan(
        offset: Long,
        status: Int,
        contentRange: String?,
        contentLength: Long?,
        oldEtag: String?,
        oldLastModified: String?,
        newEtag: String?,
        newLastModified: String?,
    ): ResponsePlan {
        if (offset < 0 || (contentLength != null && contentLength < 0)) {
            return ResponsePlan.Reject("Invalid download size.")
        }
        return when (status) {
            200 -> if (contentRange != null) ResponsePlan.Reject("A full media response must not contain a partial Content-Range.")
                else ResponsePlan.Transfer(0, contentLength, append = false)
            206 -> {
                val range = parseContentRange(contentRange)
                    ?: return ResponsePlan.Reject("Server returned an invalid Content-Range.")
                if (range.first != offset) {
                    return ResponsePlan.Reject("Server returned bytes from an unexpected offset.")
                }
                if (contentLength != null && contentLength != range.last - range.first + 1) {
                    return ResponsePlan.Reject("Server returned inconsistent range length.")
                }
                if (offset > 0 && !validatorsAgree(oldEtag, oldLastModified, newEtag, newLastModified)) {
                    return ResponsePlan.Restart("The remote file changed; restarting safely.")
                }
                ResponsePlan.Transfer(offset, range.total, append = offset > 0)
            }
            416 -> {
                val total = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)
                    .matchEntire(contentRange?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (offset > 0 && total == offset && validatorsAgree(
                        oldEtag, oldLastModified, newEtag, newLastModified, requireResponseValidator = true,
                    )
                ) ResponsePlan.AlreadyComplete
                else ResponsePlan.Restart("The stored range is no longer available; restarting.")
            }
            else -> ResponsePlan.Reject("Server returned HTTP $status.")
        }
    }

    fun isRetryableStatus(status: Int): Boolean = status == 408 || status == 429 || status in 500..599

    fun retryDelaySeconds(attempt: Int): Long = (10L shl attempt.coerceIn(0, 5)).coerceAtMost(300)

    fun retryAfterSeconds(value: String?): Long? = value?.trim()?.toLongOrNull()?.coerceIn(1, 300)

    const val MAX_AUTOMATIC_RETRIES = 4
}
