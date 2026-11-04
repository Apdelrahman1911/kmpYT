package dev.kmpyt.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kmpyt.extraction.YouTubeJavaScriptFunction
import dev.kmpyt.extraction.YouTubeJavaScriptSession
import dev.kmpyt.extraction.YouTubeJavaScriptRuntime
import dev.kmpyt.extraction.ContextYouTubePlayerProgramCompiler
import dev.kmpyt.extraction.YouTubeProgramBinding
import dev.kmpyt.extraction.YouTubeDownloadPolicy
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Uses Android's actual JS VM/DOM and real HTTP server, not a fake evaluator. */
@RunWith(AndroidJUnit4::class)
class AndroidYouTubeJavaScriptRuntimeTest {
    private fun runtime() = AndroidYouTubeJavaScriptRuntime(ApplicationProvider.getApplicationContext<Context>())
    private fun exported(body: String) = """
        globalThis.__kmpyt_capabilities = {signature:true,throttling:true};
        globalThis.__kmpyt_transform = function(kind,input) { $body };
    """.trimIndent()

    @Test fun executesRealDomAndUrlFunctionsAndPreservesQuotedUnicodeInput() = runBlocking {
        withContext(Dispatchers.IO) {
            val session = runtime().prepare(exported("""
                const node = document.createElement('span');
                node.textContent = input;
                document.body.appendChild(node);
                const url = new URL('https://media.invalid/videoplayback');
                url.searchParams.set('owned', node.textContent);
                return kind + ':' + node.tagName + ':' + url.searchParams.get('owned');
            """))
            try {
                assertTrue(session.capabilities.signature)
                assertTrue(session.capabilities.throttling)
                val input = "owned ' \\" + "\n" + "مرحبا 😀"
                assertEquals("signature:SPAN:$input", session.call(YouTubeJavaScriptFunction.SIGNATURE, input))
                assertEquals("throttling:SPAN:$input", session.call(YouTubeJavaScriptFunction.THROTTLING, input))
            } finally { session.close() }
        }
    }

    @Test fun authoredBlankDocumentHasNativeCookieGetterAndReservedNonopaqueOrigin() = runBlocking {
        withContext(Dispatchers.IO) {
            val session = runtime().prepare(exported("""
                try { return location.origin + ':' + document.cookie + ':' + input; }
                catch (error) { return 'native-cookie-failure:' + error.name + ':' + location.origin; }
            """))
            try {
                val observed = session.call(YouTubeJavaScriptFunction.SIGNATURE, "owned")
                println("KMPYT_ANDROID_OFFLINE_ORIGIN observed=$observed")
                assertEquals("https://kmpyt.invalid::owned", observed)
            } finally { session.close() }
        }
    }

    @Test fun externalFileAndContentFetchesFailWithoutAnyActualHttpRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("This server must never be reached"))
        try {
            server.start()
            withContext(Dispatchers.IO) {
                val url = server.url("/offline-probe").toString()
                val session = runtime().prepare("""
                    globalThis.__offline_states = ['waiting','waiting','waiting','waiting'];
                    ['$url', 'file:///etc/hosts', 'content://dev.kmpyt.not-a-provider/private',
                     'https://kmpyt.invalid/player-offline'].forEach(function(url,index) {
                        try {
                            fetch(url).then(function(response) { globalThis.__offline_states[index] = response.ok ? 'allowed' : 'blocked'; })
                                      .catch(function() { globalThis.__offline_states[index] = 'blocked'; });
                        } catch (_) { globalThis.__offline_states[index] = 'blocked'; }
                    });
                    ${exported("return globalThis.__offline_states.join(',') + ':' + input;")}
                """.trimIndent())
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var state = ""
                    while (System.nanoTime() < deadline) {
                        state = session.call(YouTubeJavaScriptFunction.SIGNATURE, "probe")
                        if (!state.contains("waiting")) break
                        Thread.sleep(25)
                    }
                    assertEquals("blocked,blocked,blocked,blocked:probe", state)
                    assertEquals(0, server.requestCount)
                } finally { session.close() }
            }
        } finally { server.shutdown() }
    }

    @Test fun invalidResultsAndJsExceptionsExposeOnlyTypedFailureCodes() = runBlocking {
        withContext(Dispatchers.IO) {
            val bodies = listOf(
                "return input;" to "PLAYER_FUNCTION_INVALID_RESULT",
                "return '';" to "PLAYER_FUNCTION_INVALID_RESULT",
                "return 123;" to "NON_STRING_RESULT",
                "throw new Error('private_token_value');" to "PLAYER_FUNCTION_FAILED",
                "return 'enhanced_except_secret';" to "PLAYER_FUNCTION_INVALID_RESULT",
            )
            for ((body, expected) in bodies) {
                val session = runtime().prepare(exported(body))
                try {
                    try { session.call(YouTubeJavaScriptFunction.SIGNATURE, "private_input_value"); fail("Invalid JS result accepted") }
                    catch (error: IllegalStateException) {
                        assertEquals(expected, error.message)
                        assertFalse(error.toString().contains("private_"))
                    }
                } finally { session.close() }
            }
        }
    }

    @Test fun malformedProgramAndMissingExportsAreRejected() = runBlocking {
        withContext(Dispatchers.IO) {
            for (program in listOf("throw new Error('private_program_value');", "var actualNativeValue = 7;", "function (")) {
                try { runtime().prepare(program); fail("Invalid runtime program accepted") }
                catch (error: IllegalStateException) { assertFalse(error.toString().contains("private_program_value")) }
            }
        }
    }

    @Test fun mainThreadCallsAreRejectedAndClosedSessionCannotEvaluate() = runBlocking {
        withContext(Dispatchers.IO) {
            val session: YouTubeJavaScriptSession = runtime().prepare(exported("return input + ':owned';"))
            try {
                val prepareFailure = AtomicReference<Exception?>()
                val callFailure = AtomicReference<Exception?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    try { runtime().prepare(exported("return input + ':owned';")) }
                    catch (error: Exception) { prepareFailure.set(error) }
                    try { session.call(YouTubeJavaScriptFunction.SIGNATURE, "owned") }
                    catch (error: Exception) { callFailure.set(error) }
                }
                assertTrue(prepareFailure.get() is IllegalStateException)
                assertTrue(callFailure.get() is IllegalStateException)
                session.close()
                try { session.call(YouTubeJavaScriptFunction.SIGNATURE, "owned"); fail("Closed runtime evaluated JS") }
                catch (error: IllegalStateException) { assertEquals("OFFLINE_JAVASCRIPT_SESSION_CLOSED", error.message) }
            } finally { session.close() }
        }
    }

}
