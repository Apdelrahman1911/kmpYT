package dev.kmpyt.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kmpyt.extraction.YouTubeJavaScriptCapabilities
import dev.kmpyt.extraction.YouTubeJavaScriptFunction
import dev.kmpyt.extraction.YouTubeJavaScriptRuntime
import dev.kmpyt.extraction.YouTubeJavaScriptSession
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Executes the complete, source-declared player program in a real ephemeral DOM.
 * Shared Kotlin discovers/exports functions; this adapter supplies only the native JS VM.
 * The authored blank document uses a reserved application origin, not a platform/account
 * origin. Its real cookie getter remains available; every resource request is denied.
 */
class AndroidYouTubeJavaScriptRuntime(
    context: Context,
    private val initializationTimeoutMs: Long = 20_000,
    private val evaluationTimeoutMs: Long = 10_000,
) : YouTubeJavaScriptRuntime {
    private val context = context.applicationContext

    init {
        require(initializationTimeoutMs in 1..20_000 && evaluationTimeoutMs in 1..10_000) {
            "The offline JavaScript runtime requires bounded timeouts"
        }
    }

    override fun prepare(program: String): YouTubeJavaScriptSession {
        requireWorkerThread()
        require(program.isNotBlank() && program.length <= MAX_PROGRAM_CHARS) {
            "Player program is empty or exceeds the offline runtime limit"
        }
        val session = Session(context, evaluationTimeoutMs)
        try {
            session.initialize(program, initializationTimeoutMs)
            return session
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    private class Session(private val context: Context, private val timeoutMs: Long) : YouTubeJavaScriptSession {
        private val handler = Handler(Looper.getMainLooper())
        private val browser = AtomicReference<WebView?>()
        private val closed = AtomicBoolean(false)
        private val evaluationLock = ReentrantLock()
        private lateinit var available: YouTubeJavaScriptCapabilities
        override val capabilities: YouTubeJavaScriptCapabilities get() = available

        @SuppressLint("SetJavaScriptEnabled")
        fun initialize(program: String, timeout: Long) {
            val ready = CountDownLatch(1)
            val response = AtomicReference<String?>()
            val failure = AtomicReference<String?>()
            val initialized = AtomicBoolean(false)
            val authoredDocumentServed = AtomicBoolean(false)
            handler.post {
                if (closed.get()) { ready.countDown(); return@post }
                try {
                    val view = WebView(context)
                    browser.set(view)
                    view.settings.apply {
                        javaScriptEnabled = true
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        domStorageEnabled = false
                        databaseEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    // Per-view policy only: never read, clear, or replace the user's global cookie jar.
                    // The reserved application origin has no platform cookies; transport is blocked.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                    view.setDownloadListener { _, _, _, _, _ -> /* No network/download bridge. */ }
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
                        override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.cancel(); return true
                        }
                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.cancel(); return true
                        }
                        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                            result?.cancel(); return true
                        }
                        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.cancel(); return true
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                            // A real native main-document navigation establishes the reserved
                            // HTTPS origin. Its authored body comes from memory, never the network.
                            if (request?.isForMainFrame == true && request.method == "GET" &&
                                request.url.toString() == OFFLINE_DOCUMENT_URL &&
                                authoredDocumentServed.compareAndSet(false, true)) return authoredDocument()
                            return deniedResource()
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse = deniedResource()

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != OFFLINE_DOCUMENT_URL) view?.stopLoading()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != OFFLINE_DOCUMENT_URL || closed.get() || !initialized.compareAndSet(false, true)) return
                            // No player/DOM replacements: execute the exact supplied program, then
                            // read only the shared compiler's explicit, typed export contract.
                            val script = """
                                globalThis.__kmpyt_runtime_failed = false;
                                try {
                                $program
                                } catch (_) { globalThis.__kmpyt_runtime_failed = true; }
                                (function() {
                                    try {
                                        if (globalThis.__kmpyt_runtime_failed) return JSON.stringify({ok:false,code:'PROGRAM_FAILED'});
                                        const c = globalThis.__kmpyt_capabilities;
                                        if (!c || typeof c.signature !== 'boolean' || typeof c.throttling !== 'boolean' ||
                                            typeof globalThis.__kmpyt_transform !== 'function')
                                            return JSON.stringify({ok:false,code:'EXPORT_UNAVAILABLE'});
                                        return JSON.stringify({ok:true,signature:c.signature,throttling:c.throttling});
                                    } catch (_) { return JSON.stringify({ok:false,code:'PROGRAM_FAILED'}); }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(script) { value -> response.set(value); ready.countDown() }
                        }
                    }
                    // shouldInterceptRequest fulfills ONLY this first native main-document
                    // GET from memory; all subresources and later requests are denied.
                    view.loadUrl(OFFLINE_DOCUMENT_URL)
                } catch (_: Exception) { failure.set("WEBVIEW_INITIALIZATION_FAILED"); ready.countDown() }
            }
            await(ready, timeout, "WEBVIEW_INITIALIZATION_TIMEOUT")
            check(!closed.get()) { "OFFLINE_JAVASCRIPT_SESSION_CLOSED" }
            failure.get()?.let { throw IllegalStateException(it) }
            val envelope = parseEnvelope(response.get())
            requireOk(envelope)
            available = YouTubeJavaScriptCapabilities(
                envelope["signature"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalStateException("INVALID_CAPABILITIES"),
                envelope["throttling"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalStateException("INVALID_CAPABILITIES"),
            )
        }

        override fun call(function: YouTubeJavaScriptFunction, input: String): String {
            requireWorkerThread()
            require(input.isNotEmpty() && input.length <= MAX_INPUT_CHARS) { "Invalid player-function input size" }
            return evaluationLock.withLock {
                check(!closed.get()) { "OFFLINE_JAVASCRIPT_SESSION_CLOSED" }
                val supported = when (function) {
                    YouTubeJavaScriptFunction.SIGNATURE -> capabilities.signature
                    YouTubeJavaScriptFunction.THROTTLING -> capabilities.throttling
                }
                check(supported) { "PLAYER_FUNCTION_UNAVAILABLE" }
                val kind = when (function) {
                    YouTubeJavaScriptFunction.SIGNATURE -> "signature"
                    YouTubeJavaScriptFunction.THROTTLING -> "throttling"
                }
                val inputLiteral = JsonPrimitive(input).toString()
                val script = """
                    (function() {
                        try {
                            const value = globalThis.__kmpyt_transform('$kind', $inputLiteral);
                            return JSON.stringify(typeof value === 'string' ? {ok:true,value:value} : {ok:false,code:'NON_STRING_RESULT'});
                        } catch (_) { return JSON.stringify({ok:false,code:'PLAYER_FUNCTION_FAILED'}); }
                    })();
                """.trimIndent()
                val finished = CountDownLatch(1)
                val result = AtomicReference<String?>()
                handler.post {
                    val view = browser.get()
                    if (closed.get() || view == null) { finished.countDown(); return@post }
                    try { view.evaluateJavascript(script) { value -> result.set(value); finished.countDown() } }
                    catch (_: Exception) { finished.countDown() }
                }
                try { await(finished, timeoutMs, "PLAYER_FUNCTION_TIMEOUT") }
                catch (error: Exception) { close(); throw error }
                check(!closed.get()) { "OFFLINE_JAVASCRIPT_SESSION_CLOSED" }
                val envelope = parseEnvelope(result.get())
                requireOk(envelope)
                val value = envelope["value"]?.jsonPrimitive?.takeIf { it.isString }?.content
                    ?: throw IllegalStateException("NON_STRING_RESULT")
                check(value.isNotEmpty() && value.length <= MAX_OUTPUT_CHARS && value != input &&
                    !value.startsWith("enhanced_except_") && !value.startsWith("enhanced_error_") &&
                    !value.startsWith("exception_") && !value.startsWith("error_")) { "PLAYER_FUNCTION_INVALID_RESULT" }
                value
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val destroy = Runnable {
                browser.getAndSet(null)?.apply { stopLoading(); webViewClient = WebViewClient(); webChromeClient = null; destroy() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) destroy.run() else handler.post(destroy)
        }

        private fun await(latch: CountDownLatch, timeout: Long, code: String) {
            try { if (!latch.await(timeout, TimeUnit.MILLISECONDS)) throw IllegalStateException(code) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IllegalStateException("PLAYER_FUNCTION_INTERRUPTED") }
        }

        private fun parseEnvelope(raw: String?): JsonObject {
            // WebView serializes the JS string result once; unwrap that layer, then the
            // typed envelope. Neither source nor raw exception/string data enters diagnostics.
            if (raw == null || raw.length > MAX_OUTPUT_CHARS * 8) throw IllegalStateException("INVALID_RUNTIME_RESPONSE")
            return try {
                val outer = Json.parseToJsonElement(raw).jsonPrimitive
                check(outer.isString)
                Json.parseToJsonElement(outer.content).jsonObject
            } catch (_: Exception) { throw IllegalStateException("INVALID_RUNTIME_RESPONSE") }
        }

        private fun requireOk(envelope: JsonObject) {
            if ((envelope["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                val code = (envelope["code"] as? JsonPrimitive)?.contentOrNull
                throw IllegalStateException(code?.takeIf { it in setOf("PROGRAM_FAILED", "EXPORT_UNAVAILABLE", "NON_STRING_RESULT", "PLAYER_FUNCTION_FAILED") }
                    ?: "INVALID_RUNTIME_RESPONSE")
            }
        }

        private fun deniedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Offline runtime", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private fun authoredDocument() = WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; connect-src 'none'; img-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'",
        ), ByteArrayInputStream("<!doctype html><html><head><meta charset='UTF-8'></head><body></body></html>".encodeToByteArray()))
    }

    companion object {
        private const val OFFLINE_DOCUMENT_URL = "https://kmpyt.invalid/player-offline"
        private const val MAX_PROGRAM_CHARS = 5_000_000
        private const val MAX_INPUT_CHARS = 32_768
        private const val MAX_OUTPUT_CHARS = 131_072
        private fun requireWorkerThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Offline player JavaScript must be called from a worker thread" }
        }
    }
}
