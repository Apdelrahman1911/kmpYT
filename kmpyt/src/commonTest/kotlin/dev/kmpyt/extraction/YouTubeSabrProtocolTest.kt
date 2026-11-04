package dev.kmpyt.extraction

import kotlin.test.*

/** Golden bytes encode actual annotated field tags, not a successful production session. */
class YouTubeSabrProtocolTest {
    private fun hex(value: String) = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
    private fun fields(bytes: ByteArray) = YouTubeProtobufWire.decode(bytes)
    private fun message(field: SabrWireField) = fields((field.value as SabrWireValue.Bytes).value)

    @Test fun initialRequestMatchesAnnotatedGoldenTagsWithoutInventedStateOrTokens() {
        val request = SabrInitialRequest(byteArrayOf(1, 2), selectedAudioFormatIds = listOf(SabrFormatId(140)),
            selectedVideoFormatIds = listOf(SabrFormatId(137, 300uL, "x")))
        val actual = YouTubeSabrProtocol.encodeInitialRequest(request)
        // request config=5; audio=16 (FormatId itag=1); video=17 (itag=1,lmt=2,xtags=3)
        assertContentEquals(hex("2a 02 01 02 82 01 03 08 8c 01 8a 01 09 08 89 01 10 ac 02 1a 01 78"), actual)
        assertEquals(listOf(5, 16, 17), fields(actual).map { it.number })
        assertFalse(fields(actual).any { it.number == 19 || it.number == 1 })
        assertFalse(request.toString().contains("[1, 2]"))
    }

    @Test fun explicitClientStateUsesCorrectIntBoolAndFloatWireTypes() {
        val request = SabrInitialRequest(byteArrayOf(3), selectedFormatIds = listOf(SabrFormatId(18)),
            clientAbrState = SabrClientAbrState(720, false, 1_500, 1, 1.5f, SabrEnabledTrackTypes.VIDEO_AND_AUDIO.value))
        val state = message(fields(YouTubeSabrProtocol.encodeInitialRequest(request)).first { it.number == 1 })
        assertEquals(listOf(21, 22, 28, 34, 35, 40), state.map { it.number })
        assertEquals(720uL, (state[0].value as SabrWireValue.Varint).value)
        assertEquals(0uL, (state[1].value as SabrWireValue.Varint).value)
        assertEquals(1500uL, (state[2].value as SabrWireValue.Varint).value)
        assertEquals(1.5f.toBits().toUInt(), (state[4].value as SabrWireValue.Fixed32).bits)
        assertContentEquals(hex("a8 01 d0 05 b0 01 00 e0 01 dc 0b 90 02 01 9d 02 00 00 c0 3f c0 02 00"),
            (fields(YouTubeSabrProtocol.encodeInitialRequest(request)).first().value as SabrWireValue.Bytes).value)
    }

    @Test fun suppliedClientContextCookiesAndTokensRemainActualOpaqueBytesAndAreNotLogged() {
        val context = SabrStreamerContext(SabrClientInfo(1, "owned-1"), "secret-token".encodeToByteArray(),
            "secret-cookie".encodeToByteArray(), listOf(hex("08 01")), unsentSabrContexts = listOf(4))
        val request = SabrInitialRequest(byteArrayOf(3), selectedFormatIds = listOf(SabrFormatId(18)), streamerContext = context)
        val contextFields = message(fields(YouTubeSabrProtocol.encodeInitialRequest(request)).first { it.number == 19 })
        assertEquals(listOf(1, 2, 3, 5, 6), contextFields.map { it.number })
        val info = message(contextFields.first())
        assertEquals(listOf(16, 17), info.map { it.number })
        assertContentEquals("secret-token".encodeToByteArray(), (contextFields[1].value as SabrWireValue.Bytes).value)
        assertFalse(context.toString().contains("secret"))
        assertFalse(contextFields.toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { SabrStreamerContext(additionalFields = listOf(SabrWireField(2, SabrWireValue.Bytes(byteArrayOf(1))))) }
    }

    @Test fun wireRoundTripPreservesUint64ExtremesFixedValuesUnknownFieldsAndDuplicates() {
        val original = listOf(SabrWireField(1, SabrWireValue.Varint(ULong.MAX_VALUE)),
            SabrWireField(2, SabrWireValue.Varint(Long.MAX_VALUE.toULong())),
            SabrWireField(99, SabrWireValue.Fixed64(0x0123456789abcdefuL)),
            SabrWireField(100, SabrWireValue.Fixed32(0xfedcba98u)),
            SabrWireField(101, SabrWireValue.Bytes(hex("00 ff 10 80"))),
            SabrWireField(101, SabrWireValue.Bytes(byteArrayOf())))
        val encoded = YouTubeProtobufWire.encode(original)
        val decoded = fields(encoded)
        assertEquals(original.map { it.number }, decoded.map { it.number })
        assertEquals(ULong.MAX_VALUE, (decoded[0].value as SabrWireValue.Varint).value)
        assertEquals(0x0123456789abcdefuL, (decoded[2].value as SabrWireValue.Fixed64).bits)
        assertContentEquals(encoded, YouTubeProtobufWire.encode(decoded))
        val format = fields(YouTubeSabrProtocol.encodeFormatId(SabrFormatId(140, ULong.MAX_VALUE)))
        assertEquals(ULong.MAX_VALUE, (format[1].value as SabrWireValue.Varint).value)
    }

    @Test fun malformedTruncatedOverlongAndUnsupportedWireDataFailsPrecisely() {
        listOf("00", "08 80", "08 80 00", "08 ff ff ff ff ff ff ff ff ff 02", "0b", "0f", "0a 03 01 02", "0d 01 02", "09 01 02 03")
            .forEach { bytes -> assertFailsWith<IllegalArgumentException>(bytes) { fields(hex(bytes)) } }
    }

    @Test fun allocationFieldCountAndNestedInspectionBoundsAreEnforced() {
        val limits = SabrWireLimits(maxMessageBytes = 32, maxFieldBytes = 16, maxFields = 2, maxDepth = 2)
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Bytes(ByteArray(17)))), limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.encode(List(3) { SabrWireField(1, SabrWireValue.Varint(1uL)) }, limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.decode(ByteArray(33), limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.decode(hex("08 01 08 01 08 01"), limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.decode(hex("0a 11"), limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.decode(byteArrayOf(), limits, depth = 2) }
        assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.encode(List(2) { SabrWireField(1, SabrWireValue.Bytes(ByteArray(16))) }, limits) }
    }

    @Test fun noSourceConfigurationNoSelectedFormatsAndInvalidStateAreRejected() {
        assertFailsWith<IllegalArgumentException> { SabrInitialRequest(byteArrayOf(), selectedFormatIds = listOf(SabrFormatId(18))) }
        assertFailsWith<IllegalArgumentException> { SabrInitialRequest(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { SabrFormatId(0) }
        assertFailsWith<IllegalArgumentException> { SabrClientAbrState(playbackRate = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SabrClientAbrState(playerTimeMs = -1) }
        assertFailsWith<IllegalArgumentException> { SabrWireField(0, SabrWireValue.Varint(0uL)) }
    }

    @Test fun umpIntegerWidthsMatchRecoveredPrefixEncodingRatherThanProtobufVarints() {
        val cases = listOf(127 to "7f", 128 to "80 02", 16_383 to "bf ff", 16_384 to "c0 00 02",
            2_097_151 to "df ff ff", 2_097_152 to "e0 00 00 02", 268_435_455 to "ef ff ff ff",
            268_435_456 to "f0 10 00 00 00", Int.MAX_VALUE to "f0 7f ff ff ff")
        cases.forEach { (value, bytes) ->
            val encoded = YouTubeUmpFraming.encodeInteger(value)
            assertContentEquals(hex(bytes), encoded)
            assertEquals(value, YouTubeUmpFraming.integer(encoded, encoded.size))
            val decoder = YouTubeUmpDecoder()
            val part = decoder.feed(encoded + byteArrayOf(0)).single()
            assertEquals(value, part.type)
            assertTrue(part.payload.isEmpty())
            decoder.finish()
        }
        assertEquals(20, YouTubeUmpFraming.integer(hex("ff 00 00 00 14"), 5))
        assertEquals(0, YouTubeUmpFraming.integer(hex("80 00"), 2))
        assertFailsWith<IllegalArgumentException> { YouTubeUmpFraming.encodeInteger(-1) }
    }

    @Test fun fragmentedRealTaggedHeaderDataAndEndFramesBindBytesToActualHeaderId() {
        val header = hex("08 0c 12 0b 41 42 43 44 45 46 47 48 49 4a 4b 18 89 01 20 ac 02 38 02 40 01 48 05 58 00 60 d0 0f 6a 09 08 89 01 10 ac 02 1a 01 78 70 04")
        val wire = byteArrayOf(20, header.size.toByte()) + header + hex("15 05 0c 01 02 03 04 16 01 0c")
        for (size in 1..wire.size) {
            val decoder = YouTubeUmpDecoder()
            val parts = mutableListOf<SabrUmpPart>()
            var offset = 0
            while (offset < wire.size) {
                val end = minOf(offset + size, wire.size)
                parts += decoder.feed(wire.copyOfRange(offset, end))
                offset = end
            }
            decoder.finish()
            assertEquals(listOf(20, 21, 22), parts.map { it.type })
            val metadata = assertNotNull(parts[0].mediaHeader())
            assertEquals(12u, metadata.headerId)
            assertEquals("ABCDEFGHIJK", metadata.videoId)
            assertEquals(137, metadata.itag)
            assertEquals(300uL, metadata.lastModified)
            assertEquals(2, metadata.compression) // Source enum GZIP; no decompression/parity claim.
            assertEquals(5L, metadata.sequenceNumber)
            assertEquals(2000, metadata.durationMs)
            assertEquals(SabrFormatId(137, 300uL, "x"), metadata.formatId)
            assertEquals(12, parts[1].mediaHeaderId())
            assertContentEquals(byteArrayOf(1, 2, 3, 4), parts[1].mediaBytes())
            assertEquals(12, parts[2].mediaHeaderId())
            assertNull(parts[2].mediaBytes())
        }
    }

    @Test fun unknownAndPolicyFramesPreserveOpaquePayloadsWithoutPretendingToBeMedia() {
        val decoder = YouTubeUmpDecoder()
        val secret = "token=secret".encodeToByteArray()
        val input = YouTubeUmpFraming.encodePart(999, secret) + hex("23 00 2b 03 0a 01 78 3a 00")
        val parts = decoder.feed(input)
        decoder.finish()
        assertEquals(listOf(SabrUmpPartKind.UNKNOWN, SabrUmpPartKind.NEXT_REQUEST_POLICY,
            SabrUmpPartKind.REDIRECT, SabrUmpPartKind.STREAM_PROTECTION_STATUS), parts.map { it.kind })
        assertContentEquals(secret, parts[0].payload)
        assertFalse(parts.toString().contains("secret"))
        assertTrue(parts.all { it.mediaBytes() == null && it.mediaHeaderId() == null })
    }

    @Test fun umpTruncatedIntegersAndBodiesFailWithoutInventedCompletion() {
        listOf("80", "14", "14 80", "14 04 01 02", "f0 00 00", "14 f0 00 00")
            .forEach { bytes ->
                val decoder = YouTubeUmpDecoder()
                decoder.feed(hex(bytes))
                assertFailsWith<IllegalArgumentException>(bytes) { decoder.finish() }
                assertFailsWith<IllegalStateException> { decoder.feed(byteArrayOf(0)) }
            }
        val overflowing = YouTubeUmpDecoder()
        assertFailsWith<IllegalArgumentException> { overflowing.feed(hex("f0 ff ff ff ff 00")) }
        assertFailsWith<IllegalStateException> { overflowing.feed(byteArrayOf(0)) }
    }

    @Test fun umpPartCountAllocationAndTotalByteBudgetsAreIndependent() {
        val limits = SabrUmpLimits(maxPartBytes = 4, maxTotalBytes = 10, maxParts = 2)
        assertFailsWith<IllegalArgumentException> { YouTubeUmpDecoder(limits).feed(hex("14 05")) }
        assertFailsWith<IllegalArgumentException> { YouTubeUmpFraming.encodePart(20, ByteArray(5), limits) }
        assertFailsWith<IllegalArgumentException> { YouTubeUmpDecoder(limits).feed(ByteArray(11)) }
        assertFailsWith<IllegalArgumentException> { YouTubeUmpDecoder(limits).feed(hex("14 00 14 00 14 00")) }
        val decoder = YouTubeUmpDecoder(limits)
        assertEquals(1, decoder.feed(hex("15 03 0c 01 02")).size)
        assertEquals(1, decoder.feed(hex("16 01 0c")).size)
        decoder.finish()
        assertFailsWith<IllegalStateException> { decoder.feed(byteArrayOf()) }
    }

    @Test fun typedHeaderRejectsAmbiguousIdentityAndMalformedWireTypes() {
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(hex("08 01 08 02")) }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(hex("0a 01 01")) }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(hex("40 02")) }
        assertFailsWith<IllegalArgumentException> { SabrUmpPart(21, byteArrayOf()).mediaHeaderId() }
        assertNull(YouTubeSabrProtocol.decodeFormatId(hex("10 01")))
        val header = YouTubeSabrProtocol.decodeMediaHeader(hex("08 01 c8 01 07"))
        assertEquals(listOf(1, 25), header.fields.map { it.number })
        assertNull(header.itag)
        assertNull(header.compression)
    }

    @Test fun typedSignedIntegersAcceptOnlyCorrectInt32SignExtensionAndExactInt64Patterns() {
        listOf(0, 1, Int.MAX_VALUE, -1, -2, Int.MIN_VALUE).forEach { expected ->
            val bits = expected.toLong().toULong()
            val wire = YouTubeProtobufWire.encode(listOf(SabrWireField(1, SabrWireValue.Varint(bits))))
            val parsed = (fields(wire).single().value as SabrWireValue.Varint).value
            assertEquals(expected, YouTubeProtobufWire.decodeInt32(parsed))
        }
        listOf(2_147_483_648uL, 4_294_967_295uL, 4_294_967_298uL,
            4_294_967_433uL, 0xffff_ffff_7fff_ffffuL).forEach { oversized ->
            assertFailsWith<IllegalArgumentException> { YouTubeProtobufWire.decodeInt32(oversized) }
        }
        listOf(0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L).forEach { expected ->
            assertEquals(expected, YouTubeProtobufWire.decodeInt64(expected.toULong()))
        }
    }

    @Test fun typedMediaHeaderRejectsTruncatedIntegersAndNegativeIdentityOrLengths() {
        fun header(number: Int, bits: ULong) = YouTubeProtobufWire.encode(listOf(SabrWireField(number, SabrWireValue.Varint(bits))))
        listOf(3, 7, 11, 12).forEach { number ->
            assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(header(number, 4_294_967_433uL)) }
        }
        listOf(0uL, (-1L).toULong()).forEach { invalidItag ->
            assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(header(3, invalidItag)) }
            assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeFormatId(header(1, invalidItag)) }
        }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeFormatId(header(1, 4_294_967_433uL)) }
        val nested = YouTubeProtobufWire.encode(listOf(SabrWireField(13, SabrWireValue.Bytes(header(1, 4_294_967_433uL)))))
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(nested) }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(header(12, (-1L).toULong())) }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(header(14, Long.MAX_VALUE.toULong() + 1uL)) }
        assertFailsWith<IllegalArgumentException> { YouTubeSabrProtocol.decodeMediaHeader(header(14, ULong.MAX_VALUE)) }
    }

    @Test fun typedMediaHeaderPreservesUint64IdentityOffsetsAndValidSignedFields() {
        val format = SabrFormatId(137, ULong.MAX_VALUE, "actual")
        val wire = YouTubeProtobufWire.encode(listOf(
            SabrWireField(3, SabrWireValue.Varint(137uL)),
            SabrWireField(4, SabrWireValue.Varint(ULong.MAX_VALUE)),
            SabrWireField(6, SabrWireValue.Varint(ULong.MAX_VALUE)),
            SabrWireField(9, SabrWireValue.Varint(Long.MAX_VALUE.toULong())),
            SabrWireField(11, SabrWireValue.Varint((-3L).toULong())),
            SabrWireField(12, SabrWireValue.Varint(Int.MAX_VALUE.toULong())),
            SabrWireField(13, SabrWireValue.Bytes(YouTubeSabrProtocol.encodeFormatId(format))),
            SabrWireField(14, SabrWireValue.Varint(Long.MAX_VALUE.toULong()))))
        val parsed = YouTubeSabrProtocol.decodeMediaHeader(wire)
        assertEquals(137, parsed.itag)
        assertEquals(ULong.MAX_VALUE, parsed.lastModified)
        assertEquals(ULong.MAX_VALUE, parsed.startDataRange)
        assertEquals(format, parsed.formatId)
        assertEquals(-3, parsed.startMs)
        assertEquals(Int.MAX_VALUE, parsed.durationMs)
        assertEquals(Long.MAX_VALUE, parsed.sequenceNumber)
        assertEquals(Long.MAX_VALUE, parsed.contentLength)
    }
}
