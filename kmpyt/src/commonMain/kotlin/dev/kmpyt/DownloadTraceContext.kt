package dev.kmpyt

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional numeric diagnostic correlation supplied by the caller. Use an opaque value with no media or user data. */
class DownloadTraceContext(val correlationKey: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DownloadTraceContext>
}

/** Links source requests to an operation chosen by an application using the client. */
class ResearchTraceContext(val correlationId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResearchTraceContext>
}
