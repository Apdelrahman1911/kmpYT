package dev.kmpyt.extraction

/** The only native responsibility is executing a bounded, isolated JS program. */
interface YouTubeJavaScriptRuntime {
    @Throws(Exception::class)
    fun prepare(program: String): YouTubeJavaScriptSession
}

/** Only bounded symbolic codes cross the native boundary, never JavaScript payloads or stacks. */
class YouTubeJavaScriptFailure(val code: String) : IllegalStateException("Offline player JavaScript failed ($code).") {
    init { require(code.length in 1..128 && Regex("[A-Za-z0-9_$:-]+").matches(code)) }
}

enum class YouTubeJavaScriptFunction { SIGNATURE, THROTTLING }
data class YouTubeJavaScriptCapabilities(val signature: Boolean, val throttling: Boolean)

interface YouTubeJavaScriptSession {
    val capabilities: YouTubeJavaScriptCapabilities
    @Throws(Exception::class)
    fun call(function: YouTubeJavaScriptFunction, input: String): String
    @Throws(Exception::class)
    fun close()
}
