package dev.kmpyt.jvm

import dev.kmpyt.extraction.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Native DOM/V8 execution in a fresh offline Chromium profile. No user browser profile is read. */
class DesktopYouTubeJavaScriptRuntime : YouTubeJavaScriptRuntime, AutoCloseable {
    private val sessions = mutableListOf<Session>()
    private val helperDelegate = lazy {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/youtube/chromium-runtime.cjs"))
            .use { it.readBytes() }
        Files.createTempFile("kmpyt-player-runtime-", ".cjs").also { Files.write(it, bytes) }
    }
    private val helper by helperDelegate

    @Synchronized override fun prepare(program: String): YouTubeJavaScriptSession {
        require(program.length <= 5_200_000) { "Player program exceeds the execution bound." }
        val node = executable("KMPYT_NODE", listOf("C:/Program Files/nodejs/node.exe", "/usr/bin/node", "/usr/local/bin/node", "/opt/homebrew/bin/node"))
        val browser = executable("KMPYT_CHROMIUM", listOf(
            "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
        val session = Session(ProcessBuilder(node.toString(), helper.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start())
        try {
            val reply = session.exchange(buildJsonObject { put("action", "prepare"); put("program", program); put("executable", browser.toString()) }, 40)
            val capabilities = requireNotNull(reply["capabilities"] as? JsonObject)
            session.actualCapabilities = YouTubeJavaScriptCapabilities(
                capabilities["signature"]?.jsonPrimitive?.booleanOrNull == true,
                capabilities["throttling"]?.jsonPrimitive?.booleanOrNull == true)
            sessions += session
            return session
        } catch (failure: YouTubeJavaScriptFailure) {
            session.close()
            throw failure
        } catch (_: Exception) {
            session.close()
            throw IllegalStateException("The isolated desktop player JavaScript runtime could not initialize.")
        }
    }

    @Synchronized override fun close() { sessions.forEach { it.close() }; sessions.clear(); if (helperDelegate.isInitialized()) Files.deleteIfExists(helper) }
    private fun executable(environment: String, candidates: List<String>): Path =
        (listOfNotNull(System.getenv(environment)) + candidates).map(Path::of).firstOrNull(Files::isRegularFile)
            ?: throw IllegalStateException("Offline JavaScript execution requires a local Node.js and Chromium installation.")

    private class Session(private val process: Process) : YouTubeJavaScriptSession {
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "kmpyt-player-js").apply { isDaemon = true } }
        private var closed = false
        var actualCapabilities = YouTubeJavaScriptCapabilities(false, false)
        override val capabilities get() = actualCapabilities

        @Synchronized fun exchange(command: JsonObject, timeout: Long): JsonObject {
            check(!closed) { "The player JavaScript session has closed." }
            try {
                writer.write(command.toString()); writer.newLine(); writer.flush()
                val line = reads.submit<String> { reader.readLine() }.get(timeout, TimeUnit.SECONDS)
                require(line != null && line.length <= 100_000)
                val result = Json.parseToJsonElement(line).jsonObject
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    val code = result["error"]?.jsonPrimitive?.content?.takeIf { it.length <= 128 && Regex("[A-Za-z0-9_$:-]+").matches(it) }
                    throw YouTubeJavaScriptFailure(code ?: "OFFLINE_RUNTIME_FAILURE")
                }
                return result
            } catch (failure: YouTubeJavaScriptFailure) { throw failure }
            catch (_: Exception) { throw IllegalStateException("Offline player JavaScript execution failed.") }
        }

        override fun call(function: YouTubeJavaScriptFunction, input: String): String {
            require(input.length in 1..16384)
            val response = exchange(buildJsonObject { put("action", "call"); put("kind", if (function == YouTubeJavaScriptFunction.SIGNATURE) "signature" else "throttling"); put("input", input) }, 15)
            return response["result"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() && it.length <= 65536 && it != input }
                ?: throw IllegalStateException("The player did not supply a usable JavaScript transformation.")
        }

        @Synchronized override fun close() {
            if (closed) return
            runCatching { exchange(buildJsonObject { put("action", "close") }, 3) }
            closed = true
            runCatching { writer.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
            reads.shutdownNow()
        }
    }
}
