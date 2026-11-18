package dev.kmpyt.internal

import kotlin.test.*

/** Authored ISO-BMFF boxes only. */
class FragmentedMp4IndexTest {
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { acc, part -> acc + part }
        return u32(8L + content.size) + type.encodeToByteArray() + content
    }
    private fun sidx(timescale: Long, firstOffset: Long, refs: List<Pair<Long, Long>>, nested: Boolean = false): ByteArray =
        box("sidx", u32(0), u32(1), u32(timescale), u32(0), u32(firstOffset), byteArrayOf(0, 0, 0, refs.size.toByte()),
            *refs.map { (size, duration) -> u32(size or if (nested) 0x8000_0000L else 0) + u32(duration) + u32(0x9000_0000L) }.toTypedArray())

    private val ftyp = box("ftyp", "dash".encodeToByteArray(), u32(0))
    private val moov = box("moov", box("mvhd", ByteArray(12)), box("mvex", box("trex", ByteArray(24))))
    private val sizes = listOf(1000L, 1200L, 800L)

    private fun file(moovBox: ByteArray = moov, nested: Boolean = false, extra: Long = 0): Pair<ByteArray, Long> {
        val head = ftyp + moovBox + sidx(44_100, 0, sizes.map { it to 441_000L }, nested)
        return head to head.size + sizes.sum() + extra
    }

    @Test fun indexesEveryFragmentFromTheObjectsOwnSidx() {
        val (head, total) = file()
        val index = assertNotNull(FragmentedMp4Index.parse(head, total))
        assertEquals((ftyp.size + moov.size).toLong(), index.initializationBytes)
        assertEquals(listOf(head.size.toLong(), head.size + 1000L, head.size + 2200L), index.segments.map { it.offset })
        assertEquals(sizes, index.segments.map { it.length })
        assertTrue(index.segments.all { it.durationSeconds == 10.0 })
    }

    @Test fun playlistPointsEveryByteRangeAtTheSameObject() {
        val (head, total) = file()
        val url = "http://127.0.0.1:9/cap/audio"
        val playlist = FragmentedMp4Index.parse(head, total)!!.hlsPlaylist(url)
        val lines = playlist.lines()
        assertEquals("#EXTM3U", lines.first())
        assertTrue("#EXT-X-TARGETDURATION:10" in lines)
        assertTrue("#EXT-X-MAP:URI=\"$url\",BYTERANGE=\"${ftyp.size + moov.size}@0\"" in lines)
        assertEquals(listOf("#EXT-X-BYTERANGE:1000@${head.size}", "#EXT-X-BYTERANGE:1200@${head.size + 1000}",
            "#EXT-X-BYTERANGE:800@${head.size + 2200}"), lines.filter { it.startsWith("#EXT-X-BYTERANGE") })
        assertEquals(3, lines.count { it == "#EXTINF:10.000000," })
        assertEquals(3, lines.count { it == url })
        assertEquals("#EXT-X-ENDLIST", lines.last { it.isNotEmpty() })
    }

    @Test fun anythingButAnExactFlatFragmentedLayoutKeepsThePlainObject() {
        assertNull(FragmentedMp4Index.parse(file(moovBox = box("moov", box("mvhd", ByteArray(12)))).let { it.first }, file().second),
            "no mvex: not fragmented")
        file(nested = true).let { (head, total) -> assertNull(FragmentedMp4Index.parse(head, total), "nested sidx") }
        file(extra = 5).let { (head, total) -> assertNull(FragmentedMp4Index.parse(head, total), "fragments must tile the object") }
        file().let { (head, total) -> assertNull(FragmentedMp4Index.parse(head.copyOf(head.size - 4), total), "truncated sidx") }
        assertNull(FragmentedMp4Index.parse(ftyp + box("mdat", ByteArray(16)), ftyp.size + 24L), "progressive mp4")
        assertNull(FragmentedMp4Index.parse(ByteArray(64) { 0x7f }, 64), "not mp4")
        assertNull(FragmentedMp4Index.parse(ByteArray(3), 3))
    }
}
