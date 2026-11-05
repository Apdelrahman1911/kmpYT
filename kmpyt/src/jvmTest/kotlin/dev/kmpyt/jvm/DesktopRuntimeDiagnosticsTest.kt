package dev.kmpyt.jvm

import dev.kmpyt.extraction.YouTubeJavaScriptFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Run the packaged Node helper; authored process/CDP failures must expose only stage codes. */
class DesktopRuntimeDiagnosticsTest {
    private fun helper(prefix: String): String = prefix + "\n" +
        requireNotNull(javaClass.getResourceAsStream("/youtube/chromium-runtime.cjs"))
            .bufferedReader().use { it.readText() }

    private fun scaledSlowBrowser(endpointAfterMs: Int?): String = helper("""
        const TestEmitter = require('node:events').EventEmitter;
        // Scale authored/helper timers equally: 20s startup stays later than 15s and earlier than 30s.
        const actualTimer = globalThis.setTimeout;
        globalThis.setTimeout = (callback, ms, ...args) => actualTimer(callback, Math.max(1, Math.ceil(ms / 100)), ...args);
        require('node:child_process').spawn = () => {
          const child = new TestEmitter();
          child.stderr = new TestEmitter(); child.exitCode = null;
          child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
          ${if (endpointAfterMs == null) "" else """
          setTimeout(() => child.stderr.emit('data', Buffer.from(
            'DevTools listening on ws://127.0.0.1:32123/devtools/browser/authored-slow-fixture\n')), $endpointAfterMs);
          """.trimIndent()}
          return child;
        };
        globalThis.WebSocket = class {
          constructor() { this.readyState = 1; setImmediate(() => this.onopen()); }
          send(text) {
            const command = JSON.parse(text);
            setImmediate(() => this.onmessage({data:JSON.stringify({id:command.id,error:{message:'authored CDP boundary'}})}));
          }
          close() { this.readyState = 3; if (this.onclose) this.onclose(); }
        };
    """.trimIndent())

    @Test fun coldBrowserCanReachCdpAfterThePreviousStartupDeadline() {
        DesktopYouTubeJavaScriptRuntime(scaledSlowBrowser(20_000), 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_CDP_REJECTED_TARGET_CREATE", error.code,
                "A 20s cold start must reach CDP instead of expiring at the former 15s startup deadline")
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun browserThatNeverStartsStillTimesOutAndReleasesItsSession() {
        DesktopYouTubeJavaScriptRuntime(scaledSlowBrowser(null), 1).use { runtime ->
            val started = System.nanoTime()
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000,
                "Scaled startup timeout and cleanup must remain bounded")
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun browserStartupFailureReportsAStaticCodeWithoutChildErrorText() {
        val source = helper("""
            const TestEmitter = require('node:events').EventEmitter;
            require('node:child_process').spawn = () => {
              const child = new TestEmitter();
              child.stderr = new TestEmitter(); child.exitCode = null;
              child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
              setImmediate(() => child.emit('error', new Error('authored-secret https://private.invalid/source')));
              return child;
            };
        """.trimIndent())
        DesktopYouTubeJavaScriptRuntime(source, 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_BROWSER_SPAWN_FAILED", error.code)
            assertFalse(error.message.orEmpty().contains("authored-secret"))
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun cdpFailureNamesTheAuthoredStageWithoutProtocolPayload() {
        val source = helper("""
            const TestEmitter = require('node:events').EventEmitter;
            require('node:child_process').spawn = () => {
              const child = new TestEmitter();
              child.stderr = new TestEmitter(); child.exitCode = null;
              child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
              setImmediate(() => child.stderr.emit('data', Buffer.from(
                'DevTools listening on ws://127.0.0.1:32123/devtools/browser/authored-fixture\n')));
              return child;
            };
            globalThis.WebSocket = class {
              constructor() { this.readyState = 1; setImmediate(() => this.onopen()); }
              send(text) {
                const command = JSON.parse(text);
                setImmediate(() => this.onmessage({data:JSON.stringify({id:command.id,
                  error:{message:'authored-secret https://private.invalid/source'}})}));
              }
              close() { this.readyState = 3; if (this.onclose) this.onclose(); }
            };
        """.trimIndent())
        DesktopYouTubeJavaScriptRuntime(source, 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_CDP_REJECTED_TARGET_CREATE", error.code)
            assertFalse(error.message.orEmpty().contains("authored-secret"))
            assertEquals(0, runtime.trackedSessionCount)
        }
    }
}
