package dev.kmpyt.internal

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real API-21+ lstat checks when instrumented; compilation alone is not device evidence. */
@RunWith(AndroidJUnit4::class)
class PrivateCheckpointPathsTest {
    @Test fun symlinksDanglingLinksAndFifosAreNeverPrivateRegularFiles() = withDirectory { directory ->
        val marker = File(directory, "caller-owned").apply { writeText("authored retained marker") }
        for (kind in listOf("file-link", "dangling", "fifo")) {
            val entry = File(directory, kind)
            if (kind == "fifo") Os.mkfifo(entry.path, 384)
            else Os.symlink(if (kind == "dangling") File(directory, "missing").path else marker.path, entry.path)
            try {
                expectRejected { requirePrivateCheckpointPath(entry, allowMissing = true) }
                assertEquals("authored retained marker", marker.readText())
            } finally { Os.remove(entry.path) }
        }
        requirePrivateCheckpointPath(marker)
    }

    @Test fun missingLeafStillChecksEveryExistingPrivateAncestor() = withDirectory { directory ->
        val outside = File(directory, "caller-owned").apply { check(mkdir()) }
        val link = File(directory, "linked-parent")
        Os.symlink(outside.path, link.path)
        try {
            expectRejected { requirePrivateCheckpointPath(File(link, "missing/leaf"), allowMissing = true) }
            assertFalse(File(outside, "missing").exists())
        } finally { Os.remove(link.path) }
        val absent = File(directory, "ordinary-new/leaf")
        requirePrivateCheckpointPath(absent, allowMissing = true)
        expectRejected { requirePrivateCheckpointPath(absent) }
        assertFalse(absent.exists())
    }

    @Test fun treeCleanupRejectsLinksBeforeDeletingAnythingAndKeepsTheOriginalLock(): Unit = runBlocking {
        withDirectory { directory ->
            val tree = File(directory, "checkpoint").apply { check(mkdir()) }
            val lock = File(tree, "lock").apply { writeText("authored lock") }
            val original = Os.lstat(lock.path)
            val part = File(tree, "0-0").apply { writeText("authored partial") }
            val outside = File(directory, "caller-owned").apply { check(mkdir()) }
            val marker = File(outside, "keep").apply { writeText("authored retained marker") }
            val link = File(tree, "processing-stale")
            Os.symlink(outside.path, link.path)
            try {
                expectRejected { deleteCheckpointTree(tree, keepLock = true) }
                assertEquals("authored partial", part.readText())
                assertEquals("authored retained marker", marker.readText())
            } finally { Os.remove(link.path) }
            deleteCheckpointTree(tree, keepLock = true)
            assertEquals(listOf("lock"), tree.list()!!.sorted())
            val retained = Os.lstat(lock.path)
            assertEquals(original.st_dev, retained.st_dev)
            assertEquals(original.st_ino, retained.st_ino)
        }
    }

    private inline fun withDirectory(block: (File) -> Unit) {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val directory = File(parent, "kmpyt-private-paths-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }

    private inline fun expectRejected(block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        fail("Unsafe or unverifiable private storage was accepted.")
    }
}
