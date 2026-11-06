@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpyt

import kotlinx.cinterop.*
import platform.Foundation.NSFileManager
import platform.posix.*
import kotlin.test.*

internal fun iosTestChildren(directory: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null).orEmpty().map { it as String }.sorted()

/** Successful/discarded transfers keep only the stable owner/lock, never leftover private media. */
internal suspend fun assertIosResumeResidue(directory: String, publicFiles: List<String> = emptyList(), stores: Int = 1) {
    val children = iosTestChildren(directory)
    val privateDirectories = children.filter { it.startsWith(".kmpyt-") }
    assertEquals(stores, privateDirectories.size)
    assertEquals(publicFiles.sorted(), children - privateDirectories.toSet())
    for (name in privateDirectories) {
        val path = "$directory/$name"
        assertEquals(listOf("lock", "owner"), iosTestChildren(path), "Only reusable lock ownership may remain.")
        for (entry in listOf("owner", "lock")) memScoped {
            val info = alloc<stat>()
            assertEquals(0, lstat("$path/$entry", info.ptr))
            assertEquals(S_IFREG, info.st_mode.toInt() and S_IFMT)
        }
        val owner = IosMediaFile("$path/owner", existing = true)
        try { assertEquals("kmpyt-ios-resume-v1", owner.read(0, owner.length().toInt()).decodeToString()) }
        finally { owner.close() }
    }
}

internal fun iosTestInode(path: String): ULong = memScoped {
    val info = alloc<stat>()
    assertEquals(0, lstat(path, info.ptr))
    info.st_ino.toULong()
}
