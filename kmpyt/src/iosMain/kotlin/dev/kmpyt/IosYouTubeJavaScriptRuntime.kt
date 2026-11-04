@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import dev.kmpyt.extraction.*
import kotlinx.serialization.json.*
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** A real DOM/URL runtime. Public YouTubeClient operations move off main before using this synchronous adapter. */
class IosYouTubeJavaScriptRuntime @Throws(Exception::class) constructor(private val timeoutSeconds: Double = 20.0) : YouTubeJavaScriptRuntime {
    init { require(timeoutSeconds > 0 && timeoutSeconds <= 120) }

    @Throws(Exception::class)
    override fun prepare(program: String): YouTubeJavaScriptSession {
        requireWorker()
        if (program.length !in 1..5_000_000) throw YouTubeJavaScriptFailure("PROGRAM_SIZE_INVALID")
        val ready = IosRuntimeResult<IosJavaScriptSession>()
        val session = IosJavaScriptSession(timeoutSeconds)
        onMain {
            val ruleStore = WKContentRuleListStore.defaultStore()
            if (ruleStore == null) { ready.fail("NETWORK_BLOCKER_UNAVAILABLE"); return@onMain }
            ruleStore.compileContentRuleListForIdentifier(
                "dev.kmpyt.offline.v1", OFFLINE_RULES,
            ) { rules, error ->
                if (error != null || rules == null) ready.fail("NETWORK_BLOCKER_UNAVAILABLE")
                else session.start(program, rules, ready)
            }
        }
        return try { ready.await(timeoutSeconds) }
        catch (failure: Throwable) { session.close(); throw failure }
    }

    private companion object {
        // CSP independently denies subresources. This rule also blocks dynamically-created HTTP(S) requests.
        const val OFFLINE_RULES = """[{"trigger":{"url-filter":"^https?://","resource-type":["image","style-sheet","script","font","raw","svg-document","media","popup"]},"action":{"type":"block"}}]"""
    }
}

private class IosJavaScriptSession(private val timeoutSeconds: Double) : YouTubeJavaScriptSession {
    private val stateLock = NSLock()
    private val callLock = NSLock()
    private var closed = false
    private var view: WKWebView? = null // Created, evaluated and disposed only on the main queue.
    private var navigation: OfflineNavigation? = null // WKWebView's delegate reference is weak.
    private var actualCapabilities = YouTubeJavaScriptCapabilities(false, false)
    override val capabilities: YouTubeJavaScriptCapabilities get() = actualCapabilities

    fun start(program: String, rules: WKContentRuleList, ready: IosRuntimeResult<IosJavaScriptSession>) {
        if (isClosed()) { ready.fail("SESSION_CLOSED"); return }
        try {
            val configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
            configuration.userContentController.addContentRuleList(rules)
            val web = WKWebView(CGRectMake(0.0, 0.0, 1.0, 1.0), configuration)
            view = web
            val delegate = OfflineNavigation(onLoaded = {
                if (isClosed()) ready.fail("SESSION_CLOSED")
                else web.evaluateJavaScript(program + "\n;JSON.stringify(globalThis.__kmpyt_capabilities || null)") { result, error ->
                    if (isClosed()) ready.fail("SESSION_CLOSED")
                    else if (error != null || result !is String) ready.fail("PROGRAM_INITIALIZATION_FAILED")
                    else try {
                        val value = Json.parseToJsonElement(result).jsonObject
                        actualCapabilities = YouTubeJavaScriptCapabilities(
                            value["signature"]?.jsonPrimitive?.booleanOrNull == true,
                            value["throttling"]?.jsonPrimitive?.booleanOrNull == true,
                        )
                        if (!actualCapabilities.signature && !actualCapabilities.throttling) ready.fail("PROGRAM_EXPORT_MISSING")
                        else ready.succeed(this)
                    } catch (_: Exception) { ready.fail("PROGRAM_EXPORT_INVALID") }
                }
            }, onFailed = { ready.fail("OFFLINE_DOCUMENT_FAILED") })
            navigation = delegate
            web.navigationDelegate = delegate
            web.loadHTMLString(OFFLINE_DOCUMENT, NSURL(string = "https://kmpyt.invalid/"))
        } catch (_: Exception) { ready.fail("RUNTIME_INITIALIZATION_FAILED") }
    }

    override fun call(function: YouTubeJavaScriptFunction, input: String): String {
        requireWorker()
        if (input.length !in 1..8192) throw YouTubeJavaScriptFailure("INPUT_SIZE_INVALID")
        callLock.lock()
        try {
            if (isClosed()) throw YouTubeJavaScriptFailure("SESSION_CLOSED")
            val pending = IosRuntimeResult<String>()
            val kind = if (function == YouTubeJavaScriptFunction.SIGNATURE) "signature" else "throttling"
            // JsonPrimitive escapes quotes/newlines without interpolating caller input into JavaScript syntax.
            val script = "globalThis.__kmpyt_transform(${JsonPrimitive(kind)},${JsonPrimitive(input)})"
            onMain {
                val web = view
                if (isClosed() || web == null) pending.fail("SESSION_CLOSED")
                else web.evaluateJavaScript(script) { result, error ->
                    when {
                        isClosed() -> pending.fail("SESSION_CLOSED")
                        error != null -> pending.fail("PLAYER_FUNCTION_FAILED")
                        result !is String -> pending.fail("NON_STRING_RESULT")
                        result.length !in 1..8192 || result == input || result.startsWith("enhanced_except_") ||
                            result.startsWith("undefined") || result.startsWith("null") -> pending.fail("PLAYER_FUNCTION_INVALID_RESULT")
                        else -> pending.succeed(result)
                    }
                }
            }
            return pending.await(timeoutSeconds)
        } finally { callLock.unlock() }
    }

    override fun close() {
        stateLock.lock()
        val first = try { if (closed) false else { closed = true; true } } finally { stateLock.unlock() }
        if (!first) return
        onMain {
            view?.stopLoading()
            view?.navigationDelegate = null
            view?.configuration?.userContentController?.removeAllUserScripts()
            view?.removeFromSuperview()
            view = null
            navigation = null
        }
    }

    private fun isClosed(): Boolean {
        stateLock.lock()
        return try { closed } finally { stateLock.unlock() }
    }

    private companion object {
        const val OFFLINE_DOCUMENT = """<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; connect-src 'none'; media-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"></head><body></body></html>"""
    }
}

private class OfflineNavigation(private val onLoaded: () -> Unit, private val onFailed: () -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    private var initial = true
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        initial = false
        onLoaded()
    }
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) = onFailed()
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val allowed = initial && (url == "about:blank" || url == "https://kmpyt.invalid/")
        decisionHandler(if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }
}

private class IosRuntimeResult<T> {
    private val condition = NSCondition()
    private var result: Result<T>? = null
    fun succeed(value: T) = finish(Result.success(value))
    fun fail(code: String) = finish(Result.failure(YouTubeJavaScriptFailure(code)))
    private fun finish(value: Result<T>) {
        condition.lock()
        try { if (result == null) { result = value; condition.broadcast() } } finally { condition.unlock() }
    }
    fun await(seconds: Double): T {
        requireWorker()
        condition.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(seconds)
            while (result == null) {
                if (!condition.waitUntilDate(deadline) && result == null) {
                    result = Result.failure(YouTubeJavaScriptFailure("RUNTIME_TIMEOUT"))
                }
            }
            return requireNotNull(result).getOrThrow()
        } finally { condition.unlock() }
    }
}

private fun requireWorker() {
    check(!NSThread.isMainThread) { "Offline JavaScript must run off the main thread. Use the suspend YouTubeClient API." }
}
private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
