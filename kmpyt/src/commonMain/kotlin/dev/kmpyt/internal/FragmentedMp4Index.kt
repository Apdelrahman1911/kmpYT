package dev.kmpyt.internal

/**
 * The segment layout a fragmented MP4 declares about itself: the initialization bytes
 * (`ftyp` + `moov` with `mvex`) and the media fragments its `sidx` box lists, each with its exact
 * byte range and duration. Parsed only from the object's own leading bytes; never guessed.
 */
internal data class FragmentedMp4Index(val initializationBytes: Long, val segments: List<Segment>) {
    data class Segment(val offset: Long, val length: Long, val durationSeconds: Double)

    /**
     * A VOD HLS media playlist whose byte-range segments all point at [mediaUrl], the same exact
     * object. AVPlayer plays a progressive fragmented MP4 only after reading every fragment; as
     * HLS it fetches the initialization bytes and then just the fragments it plays or seeks to.
     */
    fun hlsPlaylist(mediaUrl: String): String = buildString {
        require(segments.isNotEmpty() && '"' !in mediaUrl && '\n' !in mediaUrl && '\r' !in mediaUrl)
        val target = segments.maxOf { kotlin.math.ceil(it.durationSeconds).toLong() }.coerceAtLeast(1)
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-MAP:URI=\"$mediaUrl\",BYTERANGE=\"$initializationBytes@0\"\n")
        for (segment in segments) {
            append("#EXTINF:${seconds(segment.durationSeconds)},\n")
            append("#EXT-X-BYTERANGE:${segment.length}@${segment.offset}\n")
            append(mediaUrl).append('\n')
        }
        append("#EXT-X-ENDLIST\n")
    }

    companion object {
        /**
         * Returns the index when [prefix] (the object's first bytes) holds `ftyp`, a fragmented
         * `moov` and a complete `sidx` whose media references exactly tile the rest of an object
         * of [totalBytes]; otherwise null, and the caller keeps serving the plain object.
         */
        fun parse(prefix: ByteArray, totalBytes: Long): FragmentedMp4Index? = try {
            parseOrThrow(prefix, totalBytes)
        } catch (_: IndexOutOfBoundsException) { null } catch (_: IllegalArgumentException) { null }

        private fun parseOrThrow(prefix: ByteArray, totalBytes: Long): FragmentedMp4Index? {
            var offset = 0L
            var sawFtyp = false
            var moovEnd: Long? = null
            while (offset + 8 <= prefix.size) {
                val (type, header, size) = box(prefix, offset, totalBytes)
                val end = offset + size
                when (type) {
                    "ftyp" -> { if (offset != 0L) return null; sawFtyp = true }
                    "moov" -> {
                        if (!sawFtyp || moovEnd != null || end > prefix.size) return null
                        if (!hasChild(prefix, offset + header, end, "mvex", totalBytes)) return null
                        moovEnd = end
                    }
                    "sidx" -> {
                        val initialization = moovEnd ?: return null
                        if (end > prefix.size) return null
                        return sidx(prefix, offset + header, end, initialization, totalBytes)
                    }
                    "moof", "mdat" -> return null // Fragments before any index: not a sidx layout.
                }
                offset = end
            }
            return null
        }

        private fun sidx(bytes: ByteArray, start: Long, end: Long, initialization: Long, total: Long): FragmentedMp4Index? {
            var at = start
            val version = u8(bytes, at); at += 4 // version + flags
            at += 4 // reference_ID
            val timescale = u32(bytes, at); at += 4
            if (timescale <= 0) return null
            val firstOffset: Long
            if (version == 0) { at += 4; firstOffset = u32(bytes, at); at += 4 }
            else if (version == 1) { at += 8; firstOffset = u64(bytes, at); at += 8 }
            else return null
            at += 2 // reserved
            val count = u16(bytes, at); at += 2
            if (count == 0 || at + count * 12L != end) return null
            var position = end + firstOffset
            val segments = ArrayList<Segment>(count)
            repeat(count) {
                val reference = u32(bytes, at)
                if (reference and 0x8000_0000L != 0L) return null // Nested sidx: not a flat media list.
                val length = reference and 0x7fff_ffffL
                val duration = u32(bytes, at + 4)
                at += 12
                if (length <= 0 || duration <= 0) return null
                segments += Segment(position, length, duration.toDouble() / timescale)
                position += length
            }
            if (position != total || segments.first().offset < initialization) return null
            return FragmentedMp4Index(initialization, segments)
        }

        private fun hasChild(bytes: ByteArray, start: Long, end: Long, wanted: String, total: Long): Boolean {
            var offset = start
            while (offset + 8 <= end) {
                val (type, _, size) = box(bytes, offset, total)
                if (type == wanted) return true
                offset += size
            }
            return false
        }

        /** (type, header length, total size); a size beyond the object is malformed. */
        private fun box(bytes: ByteArray, offset: Long, total: Long): Triple<String, Int, Long> {
            val small = u32(bytes, offset)
            val type = (4..7).map { bytes[(offset + it).toInt()].toInt().toChar() }.joinToString("")
            require(type.all { it in ' '..'~' })
            val (header, size) = when (small) {
                1L -> 16 to u64(bytes, offset + 8)
                0L -> 8 to total - offset
                else -> 8 to small
            }
            require(size >= header && offset + size <= total)
            return Triple(type, header, size)
        }

        private fun u8(b: ByteArray, at: Long) = b[at.toInt()].toInt() and 0xff
        private fun u16(b: ByteArray, at: Long) = (u8(b, at) shl 8) or u8(b, at + 1)
        private fun u32(b: ByteArray, at: Long): Long = (0..3).fold(0L) { acc, i -> (acc shl 8) or u8(b, at + i).toLong() }
        private fun u64(b: ByteArray, at: Long): Long = (u32(b, at) shl 32) or u32(b, at + 4)

        private fun seconds(value: Double): String {
            val micros = kotlin.math.round(value * 1_000_000).toLong()
            return "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"
        }
    }
}
