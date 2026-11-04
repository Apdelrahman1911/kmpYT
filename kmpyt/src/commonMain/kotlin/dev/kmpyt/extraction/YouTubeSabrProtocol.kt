package dev.kmpyt.extraction

import kotlinx.serialization.Serializable

/** Bounds apply before allocating payloads; protobuf groups are deliberately unsupported. */
data class SabrWireLimits(
    val maxMessageBytes: Int = 1_048_576,
    val maxFieldBytes: Int = 524_288,
    val maxFields: Int = 4_096,
    val maxDepth: Int = 16,
) {
    init {
        require(maxMessageBytes in 1..16_777_216 && maxFieldBytes in 1..maxMessageBytes)
        require(maxFields in 1..65_536 && maxDepth in 1..64)
    }
}

sealed interface SabrWireValue {
    data class Varint(val value: ULong) : SabrWireValue
    data class Fixed64(val bits: ULong) : SabrWireValue
    data class Fixed32(val bits: UInt) : SabrWireValue
    /** Opaque bytes are accessible for inspection, but never interpolated into diagnostics. */
    class Bytes(val value: ByteArray) : SabrWireValue {
        override fun toString() = "Bytes(length=${value.size}, payload=redacted)"
    }
}

data class SabrWireField(val number: Int, val value: SabrWireValue) {
    init { require(number in 1..536_870_911) { "Invalid protobuf field number" } }
}

/**
 * Independent standard protobuf wire codec (types 0, 1, 2 and 5). Unknown fields
 * retain their number, type and bytes rather than being mistaken for media data.
 * The SABR tags below are verified from retained generated WireField annotations,
 * not proof that the current server accepts any particular request/session.
 */
object YouTubeProtobufWire {
    /** INT32 negatives use a sign-extended ten-byte varint, not arbitrary low-32-bit truncation. */
    fun decodeInt32(value: ULong): Int {
        require(value <= Int.MAX_VALUE.toULong() || value >= Int.MIN_VALUE.toLong().toULong()) {
            "Protobuf INT32 value is outside its positive or sign-extended negative range"
        }
        return value.toInt()
    }

    /** Every validated uint64 wire pattern has an exact two's-complement INT64 interpretation. */
    fun decodeInt64(value: ULong): Long = value.toLong()

    fun encode(fields: List<SabrWireField>, limits: SabrWireLimits = SabrWireLimits()): ByteArray {
        require(fields.size <= limits.maxFields) { "Protobuf field-count limit exceeded" }
        val output = SabrByteWriter(limits.maxMessageBytes)
        fields.forEach { field ->
            val type = when (field.value) {
                is SabrWireValue.Varint -> 0
                is SabrWireValue.Fixed64 -> 1
                is SabrWireValue.Bytes -> 2
                is SabrWireValue.Fixed32 -> 5
            }
            output.varint((field.number.toULong() shl 3) or type.toULong())
            when (val value = field.value) {
                is SabrWireValue.Varint -> output.varint(value.value)
                is SabrWireValue.Fixed64 -> repeat(8) { output.byte((value.bits shr (it * 8)).toByte()) }
                is SabrWireValue.Fixed32 -> repeat(4) { output.byte((value.bits shr (it * 8)).toByte()) }
                is SabrWireValue.Bytes -> {
                    require(value.value.size <= limits.maxFieldBytes) { "Protobuf byte-field limit exceeded" }
                    output.varint(value.value.size.toULong())
                    output.bytes(value.value)
                }
            }
        }
        return output.result()
    }

    fun decode(input: ByteArray, limits: SabrWireLimits = SabrWireLimits(), depth: Int = 0): List<SabrWireField> {
        require(input.size <= limits.maxMessageBytes && depth in 0 until limits.maxDepth) { "Protobuf message/depth limit exceeded" }
        var offset = 0
        fun next(): Int {
            require(offset < input.size) { "Truncated protobuf field" }
            return input[offset++].toInt() and 255
        }
        fun varint(): ULong {
            var value = 0uL
            repeat(10) { index ->
                val byte = next()
                require(index != 9 || byte <= 1) { "Protobuf varint overflows uint64" }
                value = value or ((byte and 127).toULong() shl (index * 7))
                if (byte and 128 == 0) {
                    require(index == 0 || byte != 0) { "Overlong protobuf varint" }
                    return value
                }
            }
            throw IllegalArgumentException("Overlong protobuf varint")
        }
        val result = mutableListOf<SabrWireField>()
        while (offset < input.size) {
            require(result.size < limits.maxFields) { "Protobuf field-count limit exceeded" }
            val tag = varint()
            val number = tag shr 3
            require(number in 1uL..536_870_911uL) { "Invalid protobuf field number" }
            val value = when ((tag and 7uL).toInt()) {
                0 -> SabrWireValue.Varint(varint())
                1 -> {
                    var bits = 0uL
                    repeat(8) { bits = bits or (next().toULong() shl (it * 8)) }
                    SabrWireValue.Fixed64(bits)
                }
                2 -> {
                    val size = varint()
                    require(size <= limits.maxFieldBytes.toULong() && size <= (input.size - offset).toULong()) { "Truncated/oversized protobuf byte field" }
                    SabrWireValue.Bytes(input.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() })
                }
                5 -> {
                    var bits = 0u
                    repeat(4) { bits = bits or (next().toUInt() shl (it * 8)) }
                    SabrWireValue.Fixed32(bits)
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
            result += SabrWireField(number.toInt(), value)
        }
        return result
    }
}

/** Source FormatId fields: itag=1 INT32, last_modified=2 UINT64, xtags=3 STRING. */
@Serializable
data class SabrFormatId(val itag: Int, val lastModified: ULong? = null, val xtags: String? = null) {
    init { require(itag > 0 && (xtags == null || xtags.length <= 4_096)) }
}

/** Only explicit, known state is encoded. Absent fields are not invented defaults. */
data class SabrClientAbrState(
    val stickyResolution: Int? = null,
    val viewportFlexible: Boolean? = null,
    val playerTimeMs: Long? = null,
    val visibility: Int? = null,
    val playbackRate: Float? = null,
    val enabledTrackTypesBitfield: Int? = null,
) {
    init {
        require(stickyResolution == null || stickyResolution >= 0)
        require(playerTimeMs == null || playerTimeMs >= 0)
        require(visibility == null || visibility >= 0)
        require(playbackRate == null || playbackRate.isFinite() && playbackRate > 0)
        require(enabledTrackTypesBitfield == null || enabledTrackTypesBitfield >= 0)
    }
}

@Serializable
data class SabrClientInfo(val clientName: Int, val clientVersion: String) {
    init { require(clientName >= 0 && clientVersion.isNotBlank() && clientVersion.length <= 256 && clientVersion.none { it.code < 32 }) }
}

/** Exact retained enum values; the source's 'bitfield' name does not imply bitmask 3. */
enum class SabrEnabledTrackTypes(val value: Int) { VIDEO_AND_AUDIO(0), AUDIO_ONLY(1), VIDEO_ONLY(2) }

class SabrContextValue(val type: Int, val value: ByteArray) {
    override fun toString() = "SabrContextValue(type=$type, bytes=${value.size}, payload=redacted)"
}

class SabrStreamerContext(
    val clientInfo: SabrClientInfo? = null,
    val poToken: ByteArray? = null,
    val playbackCookie: ByteArray? = null,
    /** Retained context messages must already be encoded by an authorized source. */
    val sabrContexts: List<ByteArray> = emptyList(),
    /** Wire fields supplied by an observed contract, pending typed context semantics. */
    val additionalFields: List<SabrWireField> = emptyList(),
    val unsentSabrContexts: List<Int> = emptyList(),
) {
    init { require(additionalFields.all { it.number !in setOf(1, 2, 3, 5, 6) }) }
    override fun toString() = "SabrStreamerContext(clientInfo=$clientInfo, poToken=${poToken?.size}, playbackCookie=${playbackCookie?.size}, contexts=${sabrContexts.size}, payloads=redacted)"
}

class SabrInitialRequest(
    val ustreamerConfig: ByteArray,
    val selectedFormatIds: List<SabrFormatId> = emptyList(),
    val selectedAudioFormatIds: List<SabrFormatId> = emptyList(),
    val selectedVideoFormatIds: List<SabrFormatId> = emptyList(),
    val clientAbrState: SabrClientAbrState? = null,
    val streamerContext: SabrStreamerContext? = null,
    val bufferedRanges: List<ByteArray> = emptyList(),
) {
    init {
        require(ustreamerConfig.isNotEmpty()) { "Actual Ustreamer configuration is required" }
        require(selectedFormatIds.isNotEmpty() || selectedAudioFormatIds.isNotEmpty() || selectedVideoFormatIds.isNotEmpty()) { "At least one actual source format identifier is required" }
    }
    override fun toString() = "SabrInitialRequest(configBytes=${ustreamerConfig.size}, selected=${selectedFormatIds.size}, audio=${selectedAudioFormatIds.size}, video=${selectedVideoFormatIds.size}, payloads=redacted)"
}

object YouTubeSabrProtocol {
    /** No endpoint, token, client identity or unknown playback state is generated here. */
    fun encodeInitialRequest(request: SabrInitialRequest, limits: SabrWireLimits = SabrWireLimits()): ByteArray {
        require(request.selectedFormatIds.size.toLong() + request.selectedAudioFormatIds.size + request.selectedVideoFormatIds.size + request.bufferedRanges.size <= limits.maxFields) { "SABR selected-format/range limit exceeded" }
        val fields = buildList {
            request.clientAbrState?.let { add(bytes(1, encodeState(it, limits))) }
            request.selectedFormatIds.forEach { add(bytes(2, encodeFormatId(it, limits))) }
            request.bufferedRanges.forEach { add(bytes(3, it)) }
            add(bytes(5, request.ustreamerConfig))
            request.selectedAudioFormatIds.forEach { add(bytes(16, encodeFormatId(it, limits))) }
            request.selectedVideoFormatIds.forEach { add(bytes(17, encodeFormatId(it, limits))) }
            request.streamerContext?.let { add(bytes(19, encodeContext(it, limits))) }
        }
        return YouTubeProtobufWire.encode(fields, limits)
    }

    fun encodeFormatId(format: SabrFormatId, limits: SabrWireLimits = SabrWireLimits()): ByteArray = YouTubeProtobufWire.encode(buildList {
        add(number(1, format.itag.toULong()))
        format.lastModified?.let { add(number(2, it)) }
        format.xtags?.let { add(bytes(3, it.encodeToByteArray())) }
    }, limits)

    fun encodeSabrContext(context: SabrContextValue, limits: SabrWireLimits = SabrWireLimits()): ByteArray =
        YouTubeProtobufWire.encode(listOf(number(1, context.type.toLong().toULong()), bytes(2, context.value)), limits)

    fun decodeFormatId(input: ByteArray, limits: SabrWireLimits = SabrWireLimits(), depth: Int = 0): SabrFormatId? {
        val fields = YouTubeProtobufWire.decode(input, limits, depth)
        val itag = fields.int32(1)?.also { require(it > 0) { "SABR format itag must be positive" } } ?: return null
        return SabrFormatId(itag, fields.integer(2), fields.string(3))
    }

    fun decodeMediaHeader(input: ByteArray, limits: SabrWireLimits = SabrWireLimits()): SabrUmpMediaHeader {
        val fields = YouTubeProtobufWire.decode(input, limits)
        val format = fields.binary(13)?.let { decodeFormatId(it, limits, depth = 1) }
        val headerId = fields.integer(1)?.also { require(it <= UInt.MAX_VALUE.toULong()) }?.toUInt()
        val itag = fields.int32(3)?.also { require(it > 0) { "SABR media itag must be positive" } }
        val duration = fields.int32(12)?.also { require(it >= 0) { "SABR media duration must be nonnegative" } }
        val contentLength = fields.int64(14)?.also { require(it >= 0) { "SABR media content length must be nonnegative" } }
        // last_modified and start_data_range are UINT64: retain all their bits.
        return SabrUmpMediaHeader(headerId, fields.string(2), itag, fields.integer(4),
            fields.string(5), fields.integer(6), fields.int32(7), fields.integer(8)?.let { require(it <= 1uL); it == 1uL }, fields.int64(9),
            fields.int32(11), duration, format, contentLength, fields)
    }

    private fun encodeState(state: SabrClientAbrState, limits: SabrWireLimits): ByteArray = YouTubeProtobufWire.encode(buildList {
        state.stickyResolution?.let { add(number(21, it.toULong())) }
        state.viewportFlexible?.let { add(number(22, if (it) 1uL else 0uL)) }
        state.playerTimeMs?.let { add(number(28, it.toULong())) }
        state.visibility?.let { add(number(34, it.toULong())) }
        state.playbackRate?.let { add(SabrWireField(35, SabrWireValue.Fixed32(it.toBits().toUInt()))) }
        state.enabledTrackTypesBitfield?.let { add(number(40, it.toULong())) }
    }, limits)

    private fun encodeContext(context: SabrStreamerContext, limits: SabrWireLimits): ByteArray {
        require(context.sabrContexts.size.toLong() + context.unsentSabrContexts.size + context.additionalFields.size <= limits.maxFields) { "SABR context-count limit exceeded" }
        return YouTubeProtobufWire.encode(buildList {
        context.clientInfo?.let { info -> add(bytes(1, YouTubeProtobufWire.encode(listOf(
            number(16, info.clientName.toULong()), bytes(17, info.clientVersion.encodeToByteArray())), limits))) }
        context.poToken?.let { add(bytes(2, it)) }
        context.playbackCookie?.let { add(bytes(3, it)) }
        context.sabrContexts.forEach { add(bytes(5, it)) }
        context.unsentSabrContexts.forEach { add(number(6, it.toLong().toULong())) }
        addAll(context.additionalFields)
        }, limits)
    }

    private fun List<SabrWireField>.singleton(number: Int): SabrWireValue? {
        val matches = filter { it.number == number }
        require(matches.size <= 1) { "Repeated singleton SABR protobuf field" }
        return matches.firstOrNull()?.value
    }
    private fun List<SabrWireField>.integer(number: Int): ULong? = singleton(number)?.let {
        require(it is SabrWireValue.Varint) { "SABR protobuf field has wrong wire type" }; it.value
    }
    private fun List<SabrWireField>.int32(number: Int): Int? = integer(number)?.let(YouTubeProtobufWire::decodeInt32)
    private fun List<SabrWireField>.int64(number: Int): Long? = integer(number)?.let(YouTubeProtobufWire::decodeInt64)
    private fun List<SabrWireField>.binary(number: Int): ByteArray? = singleton(number)?.let {
        require(it is SabrWireValue.Bytes) { "SABR protobuf field has wrong wire type" }; it.value
    }
    private fun List<SabrWireField>.string(number: Int) = binary(number)?.decodeToString(throwOnInvalidSequence = true)

    private fun bytes(number: Int, value: ByteArray) = SabrWireField(number, SabrWireValue.Bytes(value))
    private fun number(number: Int, value: ULong) = SabrWireField(number, SabrWireValue.Varint(value))
}

private class SabrByteWriter(private val limit: Int) {
    private var buffer = ByteArray(minOf(128, limit))
    private var size = 0
    private fun reserve(count: Int) {
        require(count >= 0 && count <= limit - size) { "Protobuf message-size limit exceeded" }
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(size + count, minOf(limit, buffer.size * 2)))
    }
    fun byte(value: Byte) { reserve(1); buffer[size++] = value }
    fun bytes(value: ByteArray) { reserve(value.size); value.copyInto(buffer, size); size += value.size }
    fun varint(input: ULong) {
        var value = input
        while (value >= 128uL) { byte(((value and 127uL) or 128uL).toByte()); value = value shr 7 }
        byte(value.toByte())
    }
    fun result() = buffer.copyOf(size)
}

class SabrUmpMediaHeader(
    val headerId: UInt?, val videoId: String?, val itag: Int?, val lastModified: ULong?, val xtags: String?,
    val startDataRange: ULong?, val compression: Int?,
    val isInitialization: Boolean?, val sequenceNumber: Long?, val startMs: Int?, val durationMs: Int?,
    val formatId: SabrFormatId?, val contentLength: Long?, val fields: List<SabrWireField>,
) {
    override fun toString() = "SabrUmpMediaHeader(headerId=$headerId, itag=$itag, compression=$compression, sequence=$sequenceNumber, fields=${fields.size}, payloads=redacted)"
}

/** IDs verified from retained o.rjb.y dispatch; unfamiliar IDs remain opaque. */
enum class SabrUmpPartKind(val type: Int) {
    MEDIA_HEADER(20), MEDIA_DATA(21), MEDIA_END(22), NEXT_REQUEST_POLICY(35),
    FORMAT_INITIALIZATION_METADATA(42), REDIRECT(43), ERROR(44), SABR_CONTEXT_UPDATE(57),
    STREAM_PROTECTION_STATUS(58), SABR_CONTEXT_SENDING_POLICY(59), SNACKBAR_MESSAGE(67), UNKNOWN(-1);
    companion object { fun fromType(type: Int) = entries.firstOrNull { it.type == type } ?: UNKNOWN }
}

class SabrUmpPart(val type: Int, val payload: ByteArray) {
    val kind: SabrUmpPartKind get() = SabrUmpPartKind.fromType(type)
    fun mediaHeader(limits: SabrWireLimits = SabrWireLimits()): SabrUmpMediaHeader? =
        if (kind == SabrUmpPartKind.MEDIA_HEADER) YouTubeSabrProtocol.decodeMediaHeader(payload, limits) else null
    /** Data/end bodies prefix their bytes with an unsigned one-byte header identifier. */
    fun mediaHeaderId(): Int? {
        if (kind !in setOf(SabrUmpPartKind.MEDIA_DATA, SabrUmpPartKind.MEDIA_END)) return null
        require(payload.isNotEmpty()) { "UMP media data/end part has no header identifier" }
        return payload[0].toInt() and 255
    }
    fun mediaBytes(): ByteArray? = if (kind == SabrUmpPartKind.MEDIA_DATA) {
        mediaHeaderId(); payload.copyOfRange(1, payload.size)
    } else null
    override fun toString() = "SabrUmpPart(type=$type, kind=$kind, bytes=${payload.size}, payload=redacted)"
}

data class SabrUmpLimits(val maxPartBytes: Int = 8_388_608, val maxTotalBytes: Long = 67_108_864, val maxParts: Int = 4_096) {
    init { require(maxPartBytes in 1..67_108_864 && maxTotalBytes >= maxPartBytes && maxTotalBytes <= 1_073_741_824 && maxParts in 1..65_536) }
}

/**
 * UMP uses a prefix-width integer, NOT protobuf's LEB128 varint. Retained o.aib.b
 * uses widths 1/2/3/4 with 7/14/21/28 payload bits; width 5 retains its final four
 * bytes in big-endian order. A record is TYPE, LENGTH, LENGTH bytes. This codec
 * implements framing only; policy/context/protection responses do not imply media.
 */
object YouTubeUmpFraming {
    fun encodeInteger(value: Int): ByteArray {
        require(value >= 0) { "Negative UMP integer" }
        return when {
            value < 128 -> byteArrayOf(value.toByte())
            value < 16_384 -> byteArrayOf((128 or (value and 63)).toByte(), (value shr 6).toByte())
            value < 2_097_152 -> byteArrayOf((192 or (value and 31)).toByte(), (value shr 5).toByte(), (value shr 13).toByte())
            value < 268_435_456 -> byteArrayOf((224 or (value and 15)).toByte(), (value shr 4).toByte(), (value shr 12).toByte(), (value shr 20).toByte())
            else -> byteArrayOf(240.toByte(), (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        }
    }
    fun encodePart(type: Int, payload: ByteArray, limits: SabrUmpLimits = SabrUmpLimits()): ByteArray {
        require(payload.size <= limits.maxPartBytes) { "UMP part-size limit exceeded" }
        return encodeInteger(type) + encodeInteger(payload.size) + payload
    }
    internal fun width(first: Int) = when { first < 128 -> 1; first < 192 -> 2; first < 224 -> 3; first < 240 -> 4; else -> 5 }
    internal fun integer(bytes: ByteArray, width: Int): Int {
        fun at(index: Int) = bytes[index].toUInt() and 255u
        val value = when (width) {
            1 -> at(0)
            2 -> (at(0) and 63u) or (at(1) shl 6)
            3 -> (at(0) and 31u) or (at(1) shl 5) or (at(2) shl 13)
            4 -> (at(0) and 15u) or (at(1) shl 4) or (at(2) shl 12) or (at(3) shl 20)
            else -> (at(1) shl 24) or (at(2) shl 16) or (at(3) shl 8) or at(4)
        }
        require(value <= Int.MAX_VALUE.toUInt()) { "UMP integer exceeds supported nonnegative range" }
        // Original accepts non-minimal widths. Preserve that behavior within bounds.
        return value.toInt()
    }
}

/** Incremental framing survives arbitrary network chunk boundaries without fake media. */
class YouTubeUmpDecoder(private val limits: SabrUmpLimits = SabrUmpLimits()) {
    private enum class Stage { TYPE, LENGTH, PAYLOAD }
    private var stage = Stage.TYPE
    private val integerBytes = ByteArray(5)
    private var integerCount = 0
    private var integerWidth = 0
    private var type = 0
    private var payload = byteArrayOf()
    private var payloadOffset = 0
    private var bytesSeen = 0L
    private var partCount = 0
    private var failed = false
    private var finished = false

    fun feed(chunk: ByteArray): List<SabrUmpPart> {
        check(!failed && !finished) { "UMP decoder is closed after completion/failure" }
        try {
            require(chunk.size.toLong() <= limits.maxTotalBytes - bytesSeen) { "UMP total-size limit exceeded" }
            bytesSeen += chunk.size
            val output = mutableListOf<SabrUmpPart>()
            var offset = 0
            fun emit() {
                require(partCount < limits.maxParts) { "UMP record-count limit exceeded" }
                partCount++
                output += SabrUmpPart(type, payload)
                payload = byteArrayOf(); payloadOffset = 0; stage = Stage.TYPE
            }
            while (offset < chunk.size) {
                if (stage == Stage.PAYLOAD) {
                    val count = minOf(payload.size - payloadOffset, chunk.size - offset)
                    chunk.copyInto(payload, payloadOffset, offset, offset + count)
                    payloadOffset += count; offset += count
                    if (payloadOffset == payload.size) emit()
                } else {
                    integerBytes[integerCount++] = chunk[offset++]
                    if (integerCount == 1) integerWidth = YouTubeUmpFraming.width(integerBytes[0].toInt() and 255)
                    if (integerCount == integerWidth) {
                        val value = YouTubeUmpFraming.integer(integerBytes, integerWidth)
                        integerCount = 0
                        if (stage == Stage.TYPE) { type = value; stage = Stage.LENGTH }
                        else {
                            require(value <= limits.maxPartBytes) { "UMP part-size limit exceeded" }
                            require(partCount < limits.maxParts) { "UMP record-count limit exceeded" }
                            payload = ByteArray(value); payloadOffset = 0; stage = Stage.PAYLOAD
                            if (value == 0) emit()
                        }
                    }
                }
            }
            return output
        } catch (error: Exception) { failed = true; throw error }
    }

    fun finish() {
        check(!failed && !finished) { "UMP decoder is closed after completion/failure" }
        if (stage != Stage.TYPE || integerCount != 0) { failed = true; throw IllegalArgumentException("Truncated UMP integer/record payload") }
        finished = true
    }
}
