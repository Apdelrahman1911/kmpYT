package dev.kmpyt.jvm

import dev.kmpyt.extraction.YouTubeJavaScriptFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/** Run the packaged Node helper; authored process/CDP failures must expose only stage codes. */
class DesktopRuntimeDiagnosticsTest {
    private fun helper(prefix: String): String = prefix + "\n" +
        requireNotNull(javaClass.getResourceAsStream("/youtube/chromium-runtime.cjs"))
            .bufferedReader().use { it.readText() }

    private fun scaledSlowBrowser(endpointAfterMs: Int?, stderrMessage: String? = null): String = helper("""
        const TestEmitter = require('node:events').EventEmitter;
        const TestPipe = require('node:stream').PassThrough;
        // Scale authored/helper timers equally: 20s startup stays later than 15s and earlier than 30s.
        const actualTimer = globalThis.setTimeout;
        globalThis.setTimeout = (callback, ms, ...args) => actualTimer(callback, Math.max(1, Math.ceil(ms / 100)), ...args);
        require('node:child_process').spawn = () => {
          const child = new TestEmitter();
          child.stderr = new TestPipe(); child.exitCode = null; child.signalCode = null;
          child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
          setImmediate(() => child.emit('spawn'));
          ${if (stderrMessage == null) "" else "setImmediate(() => child.stderr.emit('data', Buffer.from(${JsonPrimitive(stderrMessage)})));"}
          ${if (endpointAfterMs == null) "" else """
          setTimeout(() => child.stderr.emit('data', Buffer.from(
            'DevTools listening on ws://127.0.0.1:32123/devtools/browser/00000000-0000-4000-8000-000000000001\n')), $endpointAfterMs);
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
            assertEquals(listOf("OFFLINE_STARTUP_SPAWN_OBSERVED", "OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED",
                "OFFLINE_STARTUP_STDERR_NONE", "OFFLINE_STARTUP_PORT_MISSING"), observations(error))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000,
                "Scaled startup timeout and cleanup must remain bounded")
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun browserStartupFailureReportsAStaticCodeWithoutChildErrorText() {
        val source = helper("""
            const TestEmitter = require('node:events').EventEmitter;
            const TestPipe = require('node:stream').PassThrough;
            require('node:child_process').spawn = () => {
              const child = new TestEmitter();
              child.stderr = new TestPipe(); child.exitCode = null; child.signalCode = null;
              child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
              setImmediate(() => child.emit('error', new Error('authored-secret https://private.invalid/source')));
              return child;
            };
        """.trimIndent())
        DesktopYouTubeJavaScriptRuntime(source, 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_BROWSER_SPAWN_FAILED", error.code)
            assertEquals(listOf("OFFLINE_STARTUP_SPAWN_UNOBSERVED", "OFFLINE_STARTUP_CHILD_UNCONFIRMED",
                "OFFLINE_STARTUP_STDERR_NONE", "OFFLINE_STARTUP_PORT_MISSING"), observations(error))
            assertPrivateDetailsAbsent(error)
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun cdpFailureNamesTheAuthoredStageWithoutProtocolPayload() {
        val source = helper("""
            const TestEmitter = require('node:events').EventEmitter;
            const TestPipe = require('node:stream').PassThrough;
            require('node:child_process').spawn = () => {
              const child = new TestEmitter();
              child.stderr = new TestPipe(); child.exitCode = null; child.signalCode = null;
              child.kill = () => { child.exitCode = 1; child.emit('exit', 1); };
              setImmediate(() => child.emit('spawn'));
              setImmediate(() => child.stderr.emit('data', Buffer.from(
                'DevTools listening on ws://127.0.0.1:32123/devtools/browser/00000000-0000-4000-8000-000000000002\n')));
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
            assertTrue(error.suppressed.isEmpty(), "Startup receipts apply only to discovery failures")
            assertPrivateDetailsAbsent(error)
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    private val pendingObservations = listOf("OFFLINE_STARTUP_SPAWN_UNOBSERVED", "OFFLINE_STARTUP_CHILD_UNCONFIRMED",
        "OFFLINE_STARTUP_STDERR_NONE", "OFFLINE_STARTUP_PORT_MISSING")

    private val stderrMessageCodes = listOf("OFFLINE_STARTUP_STDERR_DEVTOOLS_BIND_MESSAGE", "OFFLINE_STARTUP_STDERR_PORT_WRITE_MESSAGE",
        "OFFLINE_STARTUP_STDERR_PROFILE_LOCK_MESSAGE", "OFFLINE_STARTUP_STDERR_PROCESS_SINGLETON_MESSAGE",
        "OFFLINE_STARTUP_STDERR_DEVTOOLS_POLICY_MESSAGE", "OFFLINE_STARTUP_STDERR_DEVTOOLS_DATA_DIR_MESSAGE")

    private fun replyOnlyHelper(code: String, startup: JsonElement): String {
        val reply = buildJsonObject {
            put("ok", false); put("error", code); put("startup", startup)
            put("ignored", "authored-secret https://private.invalid/source?signature=fixture /private/fixture")
        }
        return """
            require('node:readline').createInterface({input:process.stdin}).on('line', line => {
              const command = JSON.parse(line);
              process.stdout.write(JSON.stringify(command.action === 'close' ? {ok:true} : $reply) + '\n');
            });
        """.trimIndent()
    }

    private fun observationArray(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun observations(error: YouTubeJavaScriptFailure): List<String> {
        val receipt = error.suppressed.single()
        assertNull(receipt.cause)
        assertTrue(receipt.stackTrace.isEmpty(), "A symbolic receipt must not retain a helper or JVM stack")
        assertTrue(receipt.suppressed.isEmpty())
        val prefix = "KMPYT_BROWSER_STARTUP_V1[${error.code};"
        val message = receipt.message.orEmpty()
        assertTrue(message.startsWith(prefix) && message.endsWith("]"))
        return message.removePrefix(prefix).removeSuffix("]").split(',')
    }

    private fun assertPrivateDetailsAbsent(error: Throwable) {
        val sentinels = listOf("authored-secret", "private.invalid", "signature=fixture", "/private/fixture", "OFFLINE_STARTUP_PRIVATE_VALUE")
        val visited = mutableSetOf<Throwable>()
        fun inspect(value: Throwable) {
            if (!visited.add(value)) return
            for (sentinel in sentinels) {
                assertFalse(value.message.orEmpty().contains(sentinel))
                assertFalse(value.stackTraceToString().contains(sentinel))
            }
            value.cause?.let(::inspect)
            value.suppressed.forEach(::inspect)
        }
        inspect(error)
    }

    @Test fun completeStaticStartupReceiptPreservesPrimaryFailureAndHasNoCauseOrStack() {
        val expected = listOf("OFFLINE_STARTUP_SPAWN_OBSERVED", "OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED",
            "OFFLINE_STARTUP_STDERR_INVALID_ANNOUNCEMENT", "OFFLINE_STARTUP_PORT_MISSING",
            "OFFLINE_STARTUP_PORT_ACCESS_DENIED", "OFFLINE_STARTUP_PORT_BUSY", "OFFLINE_STARTUP_PORT_INCOMPLETE_OR_UNRECOGNIZED")
        DesktopYouTubeJavaScriptRuntime(replyOnlyHelper("OFFLINE_BROWSER_STARTUP_TIMEOUT", observationArray(expected)), 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
            assertEquals(expected, observations(error))
            assertPrivateDetailsAbsent(error)
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun classifiedStderrReceiptsPreserveThePrimaryFailureAndOmitPrivateDetails() {
        for (code in stderrMessageCodes) {
            val expected = listOf(pendingObservations[0], pendingObservations[1], code, pendingObservations[3])
            DesktopYouTubeJavaScriptRuntime(replyOnlyHelper("OFFLINE_BROWSER_STARTUP_TIMEOUT", observationArray(expected)), 1).use { runtime ->
                val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
                assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
                assertEquals(expected, observations(error))
                assertPrivateDetailsAbsent(error)
                assertEquals(0, runtime.trackedSessionCount)
            }
        }
    }

    @Test fun packagedHelperClassifiesPortWriteMessageWithoutItsPrivateSuffix() {
        val message = "[authored-secret ERROR] Error writing DevTools active port to file /private/fixture https://private.invalid/?signature=fixture\n"
        DesktopYouTubeJavaScriptRuntime(scaledSlowBrowser(null, message), 1).use { runtime ->
            val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
            assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
            assertEquals(listOf("OFFLINE_STARTUP_SPAWN_OBSERVED", "OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED",
                "OFFLINE_STARTUP_STDERR_PORT_WRITE_MESSAGE", "OFFLINE_STARTUP_PORT_MISSING"), observations(error))
            assertPrivateDetailsAbsent(error)
            assertEquals(0, runtime.trackedSessionCount)
        }
    }

    @Test fun packagedHelperClassifiesChrome156RestrictionMessagesWithoutClaimingAnOsCause() {
        for ((message, token) in listOf(
            "DevTools remote debugging is disallowed by the system admin." to "OFFLINE_STARTUP_STDERR_DEVTOOLS_POLICY_MESSAGE",
            "DevTools remote debugging requires a non-default data directory. Specify this using --user-data-dir." to
                "OFFLINE_STARTUP_STDERR_DEVTOOLS_DATA_DIR_MESSAGE"
        )) {
            val text = "[authored-secret] $message /private/fixture https://private.invalid/?signature=fixture\n"
            DesktopYouTubeJavaScriptRuntime(scaledSlowBrowser(null, text), 1).use { runtime ->
                val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
                assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
                assertEquals(listOf("OFFLINE_STARTUP_SPAWN_OBSERVED", "OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED",
                    token, "OFFLINE_STARTUP_PORT_MISSING"), observations(error))
                assertPrivateDetailsAbsent(error)
                assertEquals(0, runtime.trackedSessionCount)
            }
        }
    }

    @Test fun malformedUnknownDuplicateOversizedAndContradictoryReceiptsAreDroppedWhole() {
        val malformed = stderrMessageCodes.flatMap { code -> listOf(
            observationArray(pendingObservations + code),
            observationArray(pendingObservations.take(2) + code.dropLast(1) + pendingObservations.last()),
            observationArray(pendingObservations.take(2) + (code + "_PRIVATE") + pendingObservations.last())
        ) } + listOf(
            JsonNull, JsonPrimitive("authored-secret"), buildJsonObject { put("private", "authored-secret") },
            JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive(true)) + pendingObservations.drop(1).map(::JsonPrimitive)),
            observationArray(pendingObservations + "OFFLINE_STARTUP_PRIVATE_VALUE"),
            observationArray(pendingObservations + "authored-secret https://private.invalid/source"),
            observationArray(pendingObservations + pendingObservations.last()),
            observationArray(List(9) { "OFFLINE_STARTUP_PORT_MISSING" }),
            observationArray(pendingObservations + "OFFLINE_STARTUP_SPAWN_OBSERVED"),
            observationArray(pendingObservations + "OFFLINE_STARTUP_CHILD_EXIT_OBSERVED"),
            observationArray(pendingObservations + "OFFLINE_STARTUP_STDERR_SOME"),
            observationArray(pendingObservations + "OFFLINE_STARTUP_PORT_UNCHECKED"),
            observationArray(pendingObservations.map { if (it == "OFFLINE_STARTUP_CHILD_UNCONFIRMED") "OFFLINE_STARTUP_CHILD_NO_EXIT_OBSERVED" else it }),
            observationArray(pendingObservations.map { if (it == "OFFLINE_STARTUP_SPAWN_UNOBSERVED") "OFFLINE_STARTUP_SPAWN_OBSERVED" else it }),
            observationArray(pendingObservations.map { if (it == "OFFLINE_STARTUP_STDERR_NONE") "OFFLINE_STARTUP_STDERR_VALID_ANNOUNCEMENT" else it }),
            observationArray(pendingObservations.map { if (it == "OFFLINE_STARTUP_PORT_MISSING") "OFFLINE_STARTUP_PORT_VALID" else it }),
            observationArray(pendingObservations + "OFFLINE_STARTUP_PORT_FILE_REJECTED")
        )
        for (startup in malformed) {
            DesktopYouTubeJavaScriptRuntime(replyOnlyHelper("OFFLINE_BROWSER_STARTUP_TIMEOUT", startup), 1).use { runtime ->
                val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
                assertEquals("OFFLINE_BROWSER_STARTUP_TIMEOUT", error.code)
                assertTrue(error.suppressed.isEmpty(), "Reject the whole receipt without altering the primary failure")
                assertPrivateDetailsAbsent(error)
                assertEquals(0, runtime.trackedSessionCount)
            }
        }
    }

    @Test fun startupReceiptsRequireMatchingDiscoveryFailureStage() {
        for (code in listOf("OFFLINE_CDP_CONNECTION_FAILED", "OFFLINE_INPUT_INVALID", "OFFLINE_RUNTIME_FAILURE",
            "OFFLINE_BROWSER_EXITED_BEFORE_ENDPOINT", "OFFLINE_BROWSER_ENDPOINT_FILE_INVALID")) {
            DesktopYouTubeJavaScriptRuntime(replyOnlyHelper(code, observationArray(pendingObservations)), 1).use { runtime ->
                val error = assertFailsWith<YouTubeJavaScriptFailure> { runtime.prepare("owned fixture") }
                assertEquals(code, error.code)
                assertTrue(error.suppressed.isEmpty())
                assertPrivateDetailsAbsent(error)
                assertEquals(0, runtime.trackedSessionCount)
            }
        }
    }
}
