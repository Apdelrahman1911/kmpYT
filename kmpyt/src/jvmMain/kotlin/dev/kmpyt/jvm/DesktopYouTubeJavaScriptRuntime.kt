package dev.kmpyt.jvm

import dev.kmpyt.extraction.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.*

/** Native DOM/V8 execution in a fresh offline Chromium profile. No user browser profile is read. */
class DesktopYouTubeJavaScriptRuntime internal constructor(private val helperSource: String?,
    private val callTimeoutSeconds: Long) : YouTubeJavaScriptRuntime, AutoCloseable {
    constructor() : this(null, 15)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private var closed = false
    internal val trackedSessionCount get() = sessions.size
    private val helperDelegate = lazy {
        val bytes = helperSource?.encodeToByteArray() ?: requireNotNull(javaClass.getResourceAsStream("/youtube/chromium-runtime.cjs"))
            .use { it.readBytes() }
        Files.createTempFile("kmpyt-player-runtime-", ".cjs").also { Files.write(it, bytes) }
    }
    private val helper by helperDelegate

    @Synchronized override fun prepare(program: String): YouTubeJavaScriptSession {
        check(!closed) { "The offline JavaScript runtime has closed." }
        require(callTimeoutSeconds in 1..15)
        require(program.length <= 5_200_000) { "Player program exceeds the execution bound." }
        val node = executable("KMPYT_NODE", listOf("C:/Program Files/nodejs/node.exe", "/usr/bin/node", "/usr/local/bin/node", "/opt/homebrew/bin/node"))
        val browser = executable("KMPYT_CHROMIUM", listOf(
            "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
        val profile = Files.createTempDirectory("kmpyt-player-")
        val process = try { ProcessBuilder(node.toString(), helper.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        catch (failure: Exception) { Files.deleteIfExists(profile); throw failure }
        val session = Session(process, profile, callTimeoutSeconds) { sessions.remove(it) }
        sessions += session
        try {
            val reply = session.exchange(buildJsonObject { put("action", "prepare"); put("program", program); put("executable", browser.toString()); put("profile", profile.toString()) }, 40)
            val capabilities = requireNotNull(reply["capabilities"] as? JsonObject)
            session.actualCapabilities = YouTubeJavaScriptCapabilities(
                capabilities["signature"]?.jsonPrimitive?.booleanOrNull == true,
                capabilities["throttling"]?.jsonPrimitive?.booleanOrNull == true)
            return session
        } catch (failure: YouTubeJavaScriptFailure) {
            session.close()
            throw failure
        } catch (_: Exception) {
            session.close()
            throw YouTubeJavaScriptFailure("OFFLINE_HELPER_INITIALIZATION_FAILED")
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { sessions.toList().forEach { it.close() } }
        finally { sessions.clear(); if (helperDelegate.isInitialized()) Files.deleteIfExists(helper) }
    }
    private fun executable(environment: String, candidates: List<String>): Path =
        (listOfNotNull(System.getenv(environment)) + candidates).map(Path::of).firstOrNull(Files::isRegularFile)
            ?: throw IllegalStateException("Offline JavaScript execution requires a local Node.js and Chromium installation.")

    private class Session(private val process: Process, private val profile: Path,
        private val callTimeoutSeconds: Long, private val onClosed: (Session) -> Unit) : YouTubeJavaScriptSession {
        private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "kmpyt-player-js").apply { isDaemon = true } }
        private var closed = false
        private var broken = false
        var actualCapabilities = YouTubeJavaScriptCapabilities(false, false)
        override val capabilities get() = actualCapabilities

        @Synchronized fun exchange(command: JsonObject, timeout: Long): JsonObject {
            check(!closed && !broken) { "The player JavaScript session has closed." }
            try {
                writer.write(command.toString()); writer.newLine(); writer.flush()
                val line = reads.submit<String> { reader.readLine() }.get(timeout, TimeUnit.SECONDS)
                if (line == null) { broken = true; throw YouTubeJavaScriptFailure("OFFLINE_HELPER_EXITED") }
                if (line.length > 100_000) { broken = true; throw YouTubeJavaScriptFailure("OFFLINE_HELPER_RESPONSE_LIMIT") }
                val result = Json.parseToJsonElement(line).jsonObject
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    val code = result["error"]?.jsonPrimitive?.content?.takeIf { it.length <= 128 && Regex("[A-Za-z0-9_$:-]+").matches(it) }
                    throw YouTubeJavaScriptFailure(code ?: "OFFLINE_RUNTIME_FAILURE")
                }
                return result
            } catch (failure: YouTubeJavaScriptFailure) { throw failure }
            catch (_: TimeoutException) { broken = true; throw YouTubeJavaScriptFailure("OFFLINE_HELPER_TIMEOUT") }
            catch (_: Exception) { broken = true; throw YouTubeJavaScriptFailure("OFFLINE_HELPER_PROTOCOL_FAILED") }
        }

        override fun call(function: YouTubeJavaScriptFunction, input: String): String {
            require(input.length in 1..16384)
            try {
                val response = exchange(buildJsonObject { put("action", "call"); put("kind", if (function == YouTubeJavaScriptFunction.SIGNATURE) "signature" else "throttling"); put("input", input) }, callTimeoutSeconds)
                return response["result"]?.jsonPrimitive?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() && it.length <= 65536 && it != input }
                    ?: throw IllegalStateException("The player did not supply a usable JavaScript transformation.")
            } catch (failure: Exception) {
                if (broken) close()
                throw failure
            }
        }

        @Synchronized override fun close() {
            if (closed) return
            // Capture ownership while the parent still exists; never search for unrelated browsers.
            val descendants = ownedDescendants()
            if (!broken) runCatching { exchange(buildJsonObject { put("action", "close") }, 3) }
            closed = true
            try {
                runCatching { writer.close() }
                val owned = (descendants + ownedDescendants()).distinctBy { it.pid() }
                if (!process.waitFor(500, TimeUnit.MILLISECONDS) || owned.any { it.isAlive }) {
                    owned.asReversed().filter { it.isAlive }.forEach { runCatching { it.destroy() } }
                    if (process.isAlive) process.destroy()
                    waitFor(owned, 500)
                    owned.asReversed().filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
                    // Let a still-running parent reap its children before forcing its own exit.
                    waitFor(owned, 1000)
                    if (process.isAlive) process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                    waitFor(owned, 500)
                }
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { reader.close() }
                runCatching { process.errorStream.close() }
                reads.shutdownNow()
                // This exact directory was created by this session. walk() never follows symlinks.
                repeat(3) {
                    if (Files.exists(profile)) runCatching {
                        Files.walk(profile).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    }
                }
                onClosed(this)
            }
        }

        private fun ownedDescendants(): List<ProcessHandle> = runCatching {
            process.descendants().use { it.toList() }
        }.getOrDefault(emptyList())

        private fun waitFor(handles: List<ProcessHandle>, milliseconds: Long) {
            val deadline = System.nanoTime() + milliseconds * 1_000_000
            while (handles.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
        }
    }
}
