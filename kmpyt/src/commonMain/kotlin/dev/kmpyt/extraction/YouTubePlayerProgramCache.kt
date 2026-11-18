package dev.kmpyt.extraction

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reuses a bounded number of versioned player programs across fresh inspections.
 * A lease covers extraction, so eviction or memory-pressure cleanup cannot close a
 * native VM while its transforms are in use. Watch responses and media URLs are never cached.
 */
class YouTubePlayerProgramCache(
    transport: ResearchTransport,
    compiler: YouTubePlayerProgramCompiler = BoundedYouTubePlayerProgramCompiler(),
    cacheEntries: Int = 1,
    store: YouTubePlayerProgramStore? = null,
) {
    private val lease = Mutex()
    private val programs = YouTubePlayerProgramLoader(transport, compiler, cacheEntries, store)

    suspend fun <T> use(block: suspend (YouTubePlayerProgramLoader) -> T): T =
        lease.withLock { block(programs) }

    /** Offline: prepares the last stored program so the next matching inspection skips that work. */
    suspend fun prewarm(): Boolean = lease.withLock { programs.prewarm() }

    /** Also waits for a current inspection before releasing native runtime resources. */
    suspend fun clear() = lease.withLock { programs.clear() }
}
