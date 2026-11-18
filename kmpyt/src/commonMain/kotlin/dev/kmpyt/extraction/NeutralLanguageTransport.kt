package dev.kmpyt.extraction

/**
 * Sends `Accept-Language: *` unless the caller chose a language. Without it, Apple's URL loading
 * adds the device language, and YouTube then returns creator-supplied translated titles for that
 * language (observed 2026-11-16: an Arabic video listed as "This video is hilarious - …" for
 * `en-US`, but under its original Arabic title with no header or with `*`). A complete title
 * must stay the source's own title on every platform, so all requests share one neutral
 * preference; YouTube then chooses its interface language by region, as with no header.
 */
class NeutralLanguageTransport(private val base: ResearchTransport) : ResearchTransport {
    override suspend fun execute(request: ResearchRequest): ResearchResponse =
        if (request.headers.keys.any { it.equals("Accept-Language", ignoreCase = true) }) base.execute(request)
        else base.execute(request.copy(headers = request.headers + ("Accept-Language" to "*")))
}
