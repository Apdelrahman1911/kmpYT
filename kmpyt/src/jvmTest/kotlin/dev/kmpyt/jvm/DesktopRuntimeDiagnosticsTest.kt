package dev.kmpyt.jvm

import dev.kmpyt.extraction.YouTubeJavaScriptFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Run the packaged Node helper; authored process/CDP failures must expose only stage codes. */
class DesktopRuntimeDiagnosticsTest {
    private fun helper(prefix: String): String = prefix + "\n" +
        requireNotNull(javaClass.getResourceAsStream("/youtube/chromium-runtime.cjs"))
            .bufferedReader().use { it.readText() }

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
