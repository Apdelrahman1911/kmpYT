package dev.kmpyt.extraction

/**
 * Optional private, application-owned storage for exact response-declared player programs.
 *
 * A player program URL is versioned, so its text is reusable across process restarts until
 * a watch response declares a different URL. Only program text is stored: never watch
 * responses, player responses, media URLs, cookies or transform results. Implementations
 * must keep the data private to the application and fail closed (return null) for any
 * missing, unreadable or corrupt entry. Every stored program is still compiled and verified
 * exactly like a freshly downloaded one before use.
 */
interface YouTubePlayerProgramStore {
    /** The exact program previously written for [url], or null. */
    fun read(url: String): String?

    /** Persists the exact successful program for [url]. Failures are non-fatal to callers. */
    fun write(url: String, program: String)

    /** The URL of the most recently written program, used for offline prewarming. */
    fun latest(): String?
}
