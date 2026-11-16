@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.kmpyt

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import kotlin.test.*

/** Actual files in a private temporary directory; no network or account access. */
class IosYouTubePlayerProgramStoreTest {
    private val first = "https://www.youtube.com/s/player/0123abcd/player_ias.vflset/en_US/base.js"
    private val second = first.replace("0123abcd", "4567ef01")
    private val third = first.replace("0123abcd", "89abcdef")
    private val manager get() = NSFileManager.defaultManager

    private fun withDirectory(block: (String) -> Unit) = iosTest { inIosTestDirectory { block(it) } }

    private fun names(directory: String) = manager.contentsOfDirectoryAtPath(directory, null).orEmpty().map { it as String }
    private fun programs(directory: String) = names(directory).filter { it.endsWith(".program") }.map { "$directory/$it" }
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun readText(path: String) = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as String
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun writeText(path: String, text: String) =
        assertTrue((text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null))
    private fun age(path: String, seconds: Double) =
        assertTrue(manager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceNow(-seconds)), path, null))

    @Test fun roundTripsExactProgramsAcrossInstancesAndTracksTheLatest() = withDirectory { directory ->
        val program = "var _yt_player={};/* ٣ العربية 🎵 */"
        IosYouTubePlayerProgramStore(directory).write(first, program)
        val reopened = IosYouTubePlayerProgramStore(directory)
        assertEquals(program, reopened.read(first))
        assertEquals(first, reopened.latest())
        assertNull(reopened.read(second))
        assertTrue(names(directory).none { it.endsWith(".tmp") })
    }

    @Test fun corruptedOrMismatchedEntriesAreRejectedAndRemoved() = withDirectory { directory ->
        val store = IosYouTubePlayerProgramStore(directory)
        store.write(first, "original program")
        val entry = programs(directory).single()
        writeText(entry, readText(entry).replace("original", "modified"))
        assertNull(store.read(first))
        assertFalse(manager.fileExistsAtPath(entry))
        // The latest pointer does not name a missing entry.
        assertNull(store.latest())

        store.write(first, "original program")
        val moved = programs(directory).single()
        // An entry that declares another URL is never served for this one.
        writeText(moved, readText(moved).replace(first, second))
        assertNull(store.read(first))

        // Invalid UTF-8 is rejected and removed, never decoded leniently.
        store.write(first, "original program")
        val broken = programs(directory).single()
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertTrue(manager.createFileAtPath(broken, bytes.toNSData(), null))
        assertNull(store.read(first))
        assertFalse(manager.fileExistsAtPath(broken))
    }

    @Test fun keepsOnlyTheBoundedNumberOfMostRecentPrograms() = withDirectory { directory ->
        val store = IosYouTubePlayerProgramStore(directory, maxEntries = 2)
        store.write(first, "one")
        age(programs(directory).single(), 60.0)
        store.write(second, "two")
        programs(directory).forEach { if (second in readText(it)) age(it, 30.0) }
        store.write(third, "three")
        assertEquals(2, programs(directory).size)
        assertNull(store.read(first))
        assertEquals("two", store.read(second))
        assertEquals("three", store.read(third))
        assertEquals(third, store.latest())
    }

    @Test fun rejectsInvalidUrlsAndSizes() = withDirectory { directory ->
        val store = IosYouTubePlayerProgramStore(directory)
        assertFailsWith<IllegalArgumentException> { store.write("https://www.youtube.com/a\nb", "x") }
        assertFailsWith<IllegalArgumentException> { store.write(first, "") }
        assertFailsWith<IllegalArgumentException> { IosYouTubePlayerProgramStore(directory, maxEntries = 0) }
        assertNull(store.latest())
    }

    private fun ByteArray.toNSData(): NSData =
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}
