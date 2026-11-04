package dev.kmpyt.extraction

import io.ktor.http.Url

/** Binds YouTube transfers to the inspected video, for any supported video URL. */
object YouTubeDownloadPolicy {
    const val DENIED_MESSAGE = "Use a supported YouTube video link and a download plan matching that video."

    fun videoId(input: String): String? = YouTubeProvider().normalize(input)?.let { Url(it).parameters["v"] }
    fun isSupportedSource(input: String): Boolean = videoId(input) != null

    private fun requireSourceIdentity(input: String, mediaId: String?): String {
        val sourceId = videoId(input)
        require(sourceId != null && sourceId == mediaId) { DENIED_MESSAGE }
        return sourceId
    }

    fun requireReport(report: ExtractionReport, mediaId: String) {
        // Local capture/test pages remain usable. Platform sources must retain the
        // actual inspected video identity and a matching parsed provider/item.
        if (isYouTubeResource(report.input)) {
            requireSourceIdentity(report.input, mediaId)
            require(report.provider == ProviderId.YOUTUBE &&
                report.media.any { it.id == mediaId && it.provider == ProviderId.YOUTUBE }) { DENIED_MESSAGE }
        }
    }

    fun requirePlan(plan: PlannedDownload) {
        val sourceIsYouTube = isYouTubeResource(plan.inspectionUrl)
        if (sourceIsYouTube) requireSourceIdentity(plan.inspectionUrl, plan.mediaId)
        listOfNotNull(plan.primary, plan.audio).forEach { representation ->
            representation.url?.let { requireResource(it, plan.inspectionUrl, plan.mediaId) }
            representation.initialization?.let { requireResource(it.url, plan.inspectionUrl, plan.mediaId) }
            representation.segments.forEach { requireResource(it.url, plan.inspectionUrl, plan.mediaId) }
        }
    }

    /** Called for every actual destination, including native HTTP redirects. */
    fun requireResource(url: String, inspectionUrl: String? = null, mediaId: String? = null) {
        if (!isYouTubeResource(url)) return
        require(inspectionUrl != null) { DENIED_MESSAGE }
        val sourceId = requireSourceIdentity(inspectionUrl, mediaId)
        videoId(url)?.let { require(it == sourceId) { DENIED_MESSAGE } }
    }

    fun isYouTubeResource(url: String): Boolean = runCatching {
        val host = Url(url).host.lowercase().trimEnd('.')
        listOf("youtube.com", "youtube-nocookie.com", "youtu.be", "googlevideo.com", "youtubei.googleapis.com")
            .any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
}
