package dev.kmpyt

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileYouTubePlayerProgramStoreTest {
    private val first = "https://www.youtube.com/s/player/0123abcd/player_ias.vflset/en_US/base.js"
    private val second = first.replace("0123abcd", "4567ef01")
    private val third = first.replace("0123abcd", "89abcdef")

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("kmpyt-program-store-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun roundTripsExactProgramsAcrossInstancesAndTracksTheLatest() = withDirectory { directory ->
        val program = "var _yt_player={};/* ٣ العربية 🎵 */"
        FileYouTubePlayerProgramStore(directory).write(first, program)
        val reopened = FileYouTubePlayerProgramStore(directory)
        assertEquals(program, reopened.read(first))
        assertEquals(first, reopened.latest())
        assertNull(reopened.read(second))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun corruptedOrMismatchedEntriesAreRejectedAndRemoved() = withDirectory { directory ->
        val store = FileYouTubePlayerProgramStore(directory)
        store.write(first, "original program")
        val entry = directory.listFiles().orEmpty().single { it.name.endsWith(".program") }
        entry.writeText(entry.readText().replace("original", "modified"))
        assertNull(store.read(first))
        assertFalse(entry.exists())
        // The latest pointer does not name a missing entry.
        assertNull(store.latest())

        store.write(first, "original program")
        val moved = directory.listFiles().orEmpty().single { it.name.endsWith(".program") }
        // An entry that declares another URL is never served for this one.
        moved.writeText(moved.readText().replace(first, second))
        assertNull(store.read(first))
    }

    @Test fun keepsOnlyTheBoundedNumberOfMostRecentPrograms() = withDirectory { directory ->
        val store = FileYouTubePlayerProgramStore(directory, maxEntries = 2)
        store.write(first, "one")
        File(directory.listFiles().orEmpty().single { it.name.endsWith(".program") }.path)
            .setLastModified(System.currentTimeMillis() - 60_000)
        store.write(second, "two")
        directory.listFiles().orEmpty().filter { it.name.endsWith(".program") }.forEach {
            if (it.readText().contains(second)) it.setLastModified(System.currentTimeMillis() - 30_000)
        }
        store.write(third, "three")
        assertEquals(2, directory.listFiles().orEmpty().count { it.name.endsWith(".program") })
        assertNull(store.read(first))
        assertEquals("two", store.read(second))
        assertEquals("three", store.read(third))
        assertEquals(third, store.latest())
    }

    @Test fun rejectsInvalidUrlsAndSizes() = withDirectory { directory ->
        val store = FileYouTubePlayerProgramStore(directory)
        assertFailsWith<IllegalArgumentException> { store.write("https://www.youtube.com/a\nb", "x") }
        assertFailsWith<IllegalArgumentException> { store.write(first, "") }
        assertFailsWith<IllegalArgumentException> { FileYouTubePlayerProgramStore(directory, maxEntries = 0) }
    }
}
