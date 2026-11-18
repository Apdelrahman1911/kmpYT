package dev.kmpyt.jvm

import dev.kmpyt.extraction.YouTubeJavaScriptFunction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Native Node helper-orchestration fixtures, not successful Chromium/player execution claims. */
class DesktopYouTubeRuntimeOptionsTest {
    private val node = (listOfNotNull(System.getenv("KMPYT_NODE")) + listOf(
        "C:/Program Files/nodejs/node.exe", "/usr/bin/node", "/usr/local/bin/node", "/opt/homebrew/bin/node",
    )).map(Path::of).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toRealPath()

    private fun removeOwned(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    @Test fun explicitLocationsScopeBothJavaFilesAndChildTempsWithoutMutatingTheParent() {
        assumeTrue("Node is required for the real child-process orchestration fixture", node != null)
        val actualNode = requireNotNull(node)
        val root = Files.createTempDirectory("kmpyt-options-fixture-").toRealPath()
        val temporary = Files.createDirectory(root.resolve("owned-temporary"))
        val snapshot = listOf("TMPDIR", "TMP", "TEMP").associateWith(System::getenv)
        val javaTemporary = System.getProperty("java.io.tmpdir")
        val observation = temporary.resolve("observation.json")
        val helper = """
            const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
            const expected = ${JsonPrimitive(temporary.toString())};
            const readline = require('node:readline').createInterface({input:process.stdin});
            const output = value => process.stdout.write(JSON.stringify(value)+'\n');
            readline.on('line', line => {
                const command = JSON.parse(line);
                if (command.action === 'prepare') {
                    const fields = {temporary:os.tmpdir(), TMPDIR:process.env.TMPDIR, TMP:process.env.TMP,
                        TEMP:process.env.TEMP, profile:command.profile, helper:process.argv[1], executable:command.executable};
                    fs.writeFileSync(path.join(expected,'observation.json'), JSON.stringify(fields));
                    if (fields.temporary!==expected || fields.TMPDIR!==expected || fields.TMP!==expected || fields.TEMP!==expected ||
                        path.dirname(fields.profile)!==expected || path.dirname(fields.helper)!==expected)
                        return output({ok:false,error:'AUTHORED_TEMP_MISMATCH'});
                    output({ok:true,capabilities:{signature:true,throttling:true}});
                } else if (command.action === 'call') output({ok:true,result:command.input+':scoped'});
                else if (command.action === 'close') {output({ok:true});readline.close();}
            });
        """.trimIndent()
        val runtime = DesktopYouTubeJavaScriptRuntime(helper, 1, DesktopYouTubeRuntimeOptions(
            actualNode.toString(), actualNode.toString(), temporary.toString(),
        ))
        try {
            val session = runtime.prepare("// Authored helper protocol fixture, no player program executed.")
            assertEquals("input:scoped", session.call(YouTubeJavaScriptFunction.SIGNATURE, "input"))
            val values = Json.parseToJsonElement(Files.readString(observation)).jsonObject
            fun field(name: String) = values.getValue(name).jsonPrimitive.content
            assertEquals(actualNode.toString(), field("executable"))
            assertEquals(temporary, Path.of(field("profile")).parent)
            assertEquals(temporary, Path.of(field("helper")).parent)
            assertTrue(Files.isDirectory(Path.of(field("profile"))))
            session.close()
            assertFalse(Files.exists(Path.of(field("profile"))))
            assertEquals(0, runtime.trackedSessionCount)
            runtime.close()
            assertFalse(Files.exists(Path.of(field("helper"))))
            assertEquals(snapshot, listOf("TMPDIR", "TMP", "TEMP").associateWith(System::getenv))
            assertEquals(javaTemporary, System.getProperty("java.io.tmpdir"))
        } finally { runtime.close(); removeOwned(root) }
    }

    @Test fun invalidExplicitRuntimeLocationsFailInsteadOfFallingBackOrCreatingFiles() {
        val root = Files.createTempDirectory("kmpyt-invalid-options-").toRealPath()
        val nonExecutable = Files.createFile(root.resolve("not-an-executable"))
        try {
            val options = listOf(
                DesktopYouTubeRuntimeOptions(nodeExecutable = "relative-node"),
                DesktopYouTubeRuntimeOptions(chromiumExecutable = "relative-browser"),
                DesktopYouTubeRuntimeOptions(temporaryDirectory = "relative-temp"),
                DesktopYouTubeRuntimeOptions(nodeExecutable = root.resolve("missing-node").toString()),
                DesktopYouTubeRuntimeOptions(chromiumExecutable = root.toString()),
                DesktopYouTubeRuntimeOptions(temporaryDirectory = nonExecutable.toString()),
                DesktopYouTubeRuntimeOptions(temporaryDirectory = root.resolve("missing-temporary").toString()),
            )
            options.forEach { invalid -> assertFailsWith<IllegalArgumentException> { DesktopYouTubeJavaScriptRuntime(invalid) } }
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                assertFalse(Files.isExecutable(nonExecutable))
                assertFailsWith<IllegalArgumentException> {
                    DesktopYouTubeJavaScriptRuntime(DesktopYouTubeRuntimeOptions(nodeExecutable = nonExecutable.toString()))
                }
            }
            Files.list(root).use { assertEquals(listOf(nonExecutable), it.toList()) }
        } finally { removeOwned(root) }
    }

    @Test fun legacyAndExplicitFactoriesRemainUsableWithoutGlobalConfiguration() = runBlocking {
        DesktopYouTubeJavaScriptRuntime().close()
        DesktopYouTubeJavaScriptRuntime(null, 1).close()
        val root = Files.createTempDirectory("kmpyt-factory-options-").toRealPath()
        try {
            createJvmYouTubeClient(ffmpegPath = null).close()
            createJvmYouTubeClient(ffmpegPath = null, runtimeOptions = DesktopYouTubeRuntimeOptions(temporaryDirectory = root.toString())).close()
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { removeOwned(root) }
    }
}
