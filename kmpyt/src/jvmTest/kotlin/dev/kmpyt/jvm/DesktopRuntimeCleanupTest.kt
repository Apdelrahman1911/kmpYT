package dev.kmpyt.jvm

import dev.kmpyt.extraction.YouTubeJavaScriptFunction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopRuntimeCleanupTest {
    @Test fun stalledHelperClosesItsOwnedChildProcessAndPrivateProfileWithinABound() {
        val receipt = Files.createTempFile("kmpyt-owned-process-test-", ".json")
        val helper = """
            const fs = require('node:fs');
            const cp = require('node:child_process');
            const lines = require('node:readline').createInterface({input:process.stdin});
            process.on('SIGTERM', () => {});
            lines.on('line', line => {
              const command = JSON.parse(line);
              if (command.action === 'prepare') {
                const child = cp.spawn(process.execPath, ['-e',
                    "process.on('SIGTERM',()=>{});setInterval(()=>{},1000)"], {windowsHide:true,stdio:'ignore'});
                fs.writeFileSync(${JsonPrimitive(receipt.toString())}, JSON.stringify({parent:process.pid, child:child.pid, profile:command.profile}));
                console.log(JSON.stringify({ok:true,capabilities:{signature:true,throttling:true}}));
              }
              // Deliberately never answer call/close: cleanup must not depend on helper cooperation.
            });
            setInterval(()=>{},1000);
        """.trimIndent()
        val runtime = DesktopYouTubeJavaScriptRuntime(helper, callTimeoutSeconds = 1)
        var parent: Long? = null
        var child: Long? = null
        var profile: Path? = null
        try {
            val session = runtime.prepare("owned test fixture")
            val process = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
            parent = process.getValue("parent").jsonPrimitive.long
            child = process.getValue("child").jsonPrimitive.long
            profile = Path.of(process.getValue("profile").jsonPrimitive.content)
            assertTrue(Files.isDirectory(profile))
            assertTrue(ProcessHandle.of(child).map { it.isAlive }.orElse(false))
            val started = System.nanoTime()
            assertFailsWith<IllegalStateException> { session.call(YouTubeJavaScriptFunction.SIGNATURE, "owned") }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 12_000, "Timeout cleanup must remain bounded")
            assertFalse(ProcessHandle.of(parent).map { it.isAlive }.orElse(false), "Owned Node process leaked")
            assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false), "Owned child process leaked")
            assertFalse(Files.exists(profile), "Private runtime profile leaked")
            assertEquals(0, runtime.trackedSessionCount)
            runtime.close()
            assertFailsWith<IllegalStateException> { runtime.prepare("closed") }
        } finally {
            runtime.close()
            // Failure cleanup is scoped to the PIDs and exact temporary paths authored by this test.
            listOfNotNull(child, parent).forEach { pid -> ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() } }
            profile?.takeIf(Files::exists)?.let { directory ->
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
            Files.deleteIfExists(receipt)
        }
    }
}
