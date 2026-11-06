package dev.kmpyt.internal

import dev.kmpyt.extraction.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.test.*

class SabrResumeReceiptsTest {
    private val data = ByteArray(300_000) { (it * 31).toByte() }
    private val id = "AUTHORED001"
    private val endpoint = "https://rr1.googlevideo.com/sabr?sig=authored-sensitive-endpoint"
    private val client = SabrClientInfo(1, "authored-client")
    private val primary = Representation("140", endpoint, TrackRole.AUDIO, TransportKind.SABR, "audio/mp4",
        codecs = "mp4a.40.2", sizeBytes = data.size.toLong(),
        sabr = YouTubeSabrResource(id, endpoint, "AQID", SabrFormatId(140, 1uL, "authored"), client))
    private val plan = PlannedDownload(primary, inspectionUrl = "https://www.youtube.com/watch?v=$id", mediaId = id)
    private fun receipts(value: PlannedDownload = plan) = SabrResumeReceipts(value, ::resumeDigest, ::JvmResumeSha256)
    private fun state(coverage: List<SabrTransferByteRange>, segments: List<SabrTransferSegment> = emptyList(),
        selected: PlannedDownload = plan) = SabrTransferCheckpoint(id, selected.sabrSelection().audio,
        selected.sabrSelection().video, client, listOfNotNull(selected.primary, selected.audio).map {
            SabrTransferTrackCheckpoint(it.role, it.sabr!!.formatId, it.sizeBytes!!, coverage, segments)
        }, resourceGeneration = YouTubeIntegrityBinding.forSabr(selected.sabrSource(), client).resourceGeneration)
    private suspend fun read(index: Int, offset: Long, count: Int): ByteArray {
        assertTrue(index in 0..1); assertTrue(count in 1..65_536)
        return data.copyOfRange(offset.toInt(), offset.toInt() + count)
    }

    @Test fun snapshotsUseRealSha256AndExcludeEveryOpaqueFieldWhileRestoreIsAnonymous() = runTest {
        val ranges = listOf(SabrTransferByteRange(0, 100_000))
        val segments = listOf(SabrTransferSegment(0, 100_000, 0, 0, 1000, false))
        val active = state(ranges, segments).copy(playbackCookie = "private-cookie".encodeToByteArray(),
            contexts = listOf(SabrTransferContextCheckpoint(7, "private-context".encodeToByteArray())),
            nextRequestNumber = 17, completedRounds = 16, activeEndpoint = "https://other.example/private-redirect",
            protocolRedirects = listOf("https://other.example/private-redirect"), activeContextTypes = listOf(7))
        val saved = receipts().snapshot(active, ::read)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(data.copyOfRange(0, 100_000)).resumeHex(),
            saved.tracks.single().rangeDigests.single())
    }

    @Test fun encodedReceiptHasNoSessionDataAndRetainsOnlyVerifiedSegmentAcknowledgements() = runTest {
        val ranges = listOf(SabrTransferByteRange(0, 100_000))
        val segments = listOf(SabrTransferSegment(0, 100_000, 0, 0, 1000, false))
        val active = state(ranges, segments).copy(playbackCookie = "private-cookie".encodeToByteArray(),
            contexts = listOf(SabrTransferContextCheckpoint(7, "private-context".encodeToByteArray())),
            nextRequestNumber = 17, completedRounds = 16, activeEndpoint = "https://other.example/private-redirect",
            protocolRedirects = listOf("https://other.example/private-redirect"), activeContextTypes = listOf(7))
        val saved = receipts().snapshot(active, ::read)
        val encoded = Json.encodeToString(SabrSavedReceipt.serializer(), saved)
        for (secret in listOf("http", "sabr?", "private", "AQID", "authored-client", "resourceGeneration", "playbackCookie", "contexts", "activeEndpoint", "protocolRedirects"))
            assertFalse(secret in encoded)
        val restored = assertNotNull(receipts().restore(saved, { data.size.toLong() }, ::read))
        assertEquals(active.tracks, restored.tracks)
        assertEquals(active.resourceGeneration, restored.resourceGeneration)
        assertEquals(1, restored.nextRequestNumber); assertEquals(0, restored.completedRounds)
        assertNull(restored.playbackCookie); assertNull(restored.activeEndpoint)
        assertTrue(restored.contexts.isEmpty()); assertTrue(restored.protocolRedirects.isEmpty())
        assertTrue(restored.receivedFormatIds.isEmpty()); assertTrue(restored.activeContextTypes.isEmpty())
    }

    @Test fun sameLengthCorruptionMissingHashTruncationAndForgedCoverageCannotResume() = runTest {
        val saved = receipts().snapshot(state(listOf(SabrTransferByteRange(0, 100_000))), ::read)
        assertNull(receipts().restore(saved, { data.size.toLong() }) { index, offset, count ->
            read(index, offset, count).also { if (offset == 0L) it[0] = (it[0].toInt() xor 1).toByte() }
        })
        val track = saved.tracks.single()
        for (changed in listOf(track.copy(rangeDigests = emptyList()), track.copy(rangeDigests = listOf("0".repeat(64))),
            track.copy(coverage = listOf(SabrTransferByteRange(0, 100_001))),
            track.copy(completedSegments = listOf(SabrTransferSegment(100_000, 1, 0, 0, 1, false)))))
            assertNull(receipts().restore(saved.copy(tracks = listOf(changed)), { data.size.toLong() }, ::read))
        assertNull(receipts().restore(saved, { 99_999 }, ::read))
        assertNull(receipts().restore(saved, { null }, ::read))
    }

    @Test fun exactGenerationClientRoleLengthAndMediaMetadataChangesInvalidateReceipts() = runTest {
        val saved = receipts().snapshot(state(listOf(SabrTransferByteRange(0, 10))), ::read)
        val resource = assertNotNull(primary.sabr)
        val nextEndpoint = "$endpoint&next=1"
        for (changed in listOf(
            primary.copy(url = nextEndpoint, sabr = resource.copy(endpoint = nextEndpoint)),
            primary.copy(sabr = resource.copy(configuration = "BAUG")),
            primary.copy(sabr = resource.copy(clientInfo = client.copy(clientVersion = "other"))),
            primary.copy(role = TrackRole.VIDEO), primary.copy(sizeBytes = primary.sizeBytes!! + 1),
            primary.copy(codecs = "mp4a.40.5"), primary.copy(audioLanguage = "ar"),
        )) assertNull(receipts(plan.copy(primary = changed)).restore(saved, { data.size.toLong() }, ::read))
    }

    @Test fun sparseRangesAndReorderedTwoTrackReceiptsRestoreByExactSelectedIndex() = runTest {
        val video = primary.copy(id = "136", role = TrackRole.VIDEO,
            sabr = primary.sabr!!.copy(formatId = SabrFormatId(136, 1uL, "authored")))
        val selected = plan.copy(primary = video, audio = primary)
        val ranges = listOf(SabrTransferByteRange(13, 30), SabrTransferByteRange(100, 120))
        val segments = listOf(SabrTransferSegment(13, 17, 0, 0, 10, false))
        val original = state(ranges, segments, selected)
        val saved = receipts(selected).snapshot(original, ::read)
        val restored = assertNotNull(receipts(selected).restore(saved.copy(tracks = saved.tracks.reversed()), { 120 }, ::read))
        assertEquals(original.tracks, restored.tracks)
        assertEquals(37L, restored.tracks.first().coverage.sumOf { it.length })
        val duplicate = saved.copy(tracks = listOf(saved.tracks[0], saved.tracks[0]))
        assertNull(receipts(selected).restore(duplicate, { error("Invalid indices must fail before file I/O") }, ::read))
    }

    @Test fun streamingSnapshotsHashOnlyNewSuffixesAndMergeWithoutReadingUncoveredHoles() = runTest {
        val manager = receipts()
        val reads = mutableListOf<Pair<Long, Int>>()
        suspend fun tracked(index: Int, offset: Long, count: Int) = read(index, offset, count).also { reads += offset to count }
        val initial = state(listOf(SabrTransferByteRange(0, 100_000), SabrTransferByteRange(200_000, 250_000)))
        manager.snapshot(initial, ::tracked)
        assertEquals(150_000L, reads.sumOf { it.second.toLong() })
        assertTrue(reads.all { (offset, count) -> offset + count <= 100_000 || offset >= 200_000 })
        reads.clear(); manager.snapshot(initial, ::tracked); assertTrue(reads.isEmpty())
        val extended = state(listOf(SabrTransferByteRange(0, 110_000), SabrTransferByteRange(200_000, 250_000)))
        manager.snapshot(extended, ::tracked); assertEquals(listOf(100_000L to 10_000), reads)
        reads.clear()
        val merged = state(listOf(SabrTransferByteRange(0, 250_000)))
        val saved = manager.snapshot(merged, ::tracked)
        assertEquals(140_000L, reads.sumOf { it.second.toLong() }); assertEquals(110_000L, reads.first().first)
        assertNotNull(receipts().restore(saved, { 250_000 }, ::read))
    }

    @Test fun interruptedValidationDoesNotMutateThePreviouslyDurableReceipt() = runTest {
        val saved = receipts().snapshot(state(listOf(SabrTransferByteRange(0, 100_000))), ::read)
        val encoded = Json.encodeToString(SabrSavedReceipt.serializer(), saved)
        assertFailsWith<CancellationException> {
            receipts().restore(saved, { data.size.toLong() }) { _, _, _ -> throw CancellationException("authored interruption") }
        }
        assertEquals(encoded, Json.encodeToString(SabrSavedReceipt.serializer(), saved))
        assertNotNull(receipts().restore(saved, { data.size.toLong() }, ::read))
    }
}
