package dev.kmpyt.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadProtocolTest {
    private fun plan(
        offset: Long = 100, status: Int = 206, range: String? = "bytes 100-199/200",
        length: Long? = 100, oldEtag: String? = "\"file-a\"", newEtag: String? = "\"file-a\"",
    ) = DownloadProtocol.plan(offset, status, range, length, oldEtag, null, newEtag, null)

    @Test fun resumeRequiresMatchingOffset() {
        assertIs<DownloadProtocol.ResponsePlan.Reject>(plan(range = "bytes 0-99/200"))
        assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan())
    }

    @Test fun ignoredRangeStartsFromZeroInsteadOfAppending() {
        val transfer = assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan(status = 200, range = null, length = 200))
        assertEquals(0L, transfer.offset)
        assertEquals(false, transfer.append)
    }

    @Test fun fullResponseCannotCertifyAnUnexpectedPartialContentRange() {
        for (range in listOf("bytes 0-99/1000", "bytes 0-99/*", "")) {
            assertIs<DownloadProtocol.ResponsePlan.Reject>(plan(offset = 0, status = 200, range = range, length = 100))
        }
        assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan(offset = 0, status = 200, range = null, length = null))
    }

    @Test fun changedRepresentationRestarts() {
        assertIs<DownloadProtocol.ResponsePlan.Restart>(plan(newEtag = "\"file-b\""))
    }

    @Test fun absentStoredValidatorNeverAppends() {
        assertIs<DownloadProtocol.ResponsePlan.Restart>(plan(oldEtag = null))
    }

    @Test fun completePartialRequiresConfirmedSizeAndValidator() {
        assertIs<DownloadProtocol.ResponsePlan.AlreadyComplete>(plan(offset = 200, status = 416, range = "bytes */200", length = null))
        assertIs<DownloadProtocol.ResponsePlan.Restart>(plan(offset = 200, status = 416, range = "bytes */200", length = null, newEtag = null))
        assertIs<DownloadProtocol.ResponsePlan.Restart>(plan(offset = 100, status = 416, range = "bytes */200", length = null))
    }

    @Test fun rejectsMalformedAndContradictoryRanges() {
        for (range in listOf("bytes 200-100/300", "bytes 100-200/200", "bytes a-b/c", "items 100-199/200")) {
            assertNull(DownloadProtocol.parseContentRange(range))
        }
        assertIs<DownloadProtocol.ResponsePlan.Reject>(plan(length = 99))
    }

    @Test fun unknownTotalIsAllowedWithoutFabricatedProgress() {
        val result = assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan(range = "bytes 100-199/*"))
        assertNull(result.total)
    }

    @Test fun multiGiBResumeAndLargestRepresentableContentRangeRemainExact() {
        val offset = 5L * 1024 * 1024 * 1024
        val large = assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan(offset = offset,
            range = "bytes $offset-${offset + 1023}/${offset + 1024}", length = 1024))
        assertEquals(offset, large.offset)
        assertEquals(offset + 1024, large.total)
        assertTrue(large.append)
        val maximum = assertIs<DownloadProtocol.ResponsePlan.Transfer>(plan(offset = 0,
            range = "bytes 0-${Long.MAX_VALUE - 1}/${Long.MAX_VALUE}", length = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, maximum.total)
        assertIs<DownloadProtocol.ResponsePlan.AlreadyComplete>(plan(offset = Long.MAX_VALUE, status = 416,
            range = "bytes */${Long.MAX_VALUE}", length = null))
    }

    @Test fun anOverflowingNumericTotalCannotMasqueradeAsUnknownLength() {
        assertNull(DownloadProtocol.parseContentRange("bytes 0-99/9223372036854775808"))
        assertNull(DownloadProtocol.parseContentRange("bytes 0-${Long.MAX_VALUE}/*"))
        assertIs<DownloadProtocol.ResponsePlan.Reject>(plan(offset = 0,
            range = "bytes 0-99/9223372036854775808", length = 100))
    }

    @Test fun weakEtagFallsBackToLastModified() {
        assertEquals("yesterday", DownloadProtocol.strongValidator("W/\"weak\"", "yesterday"))
        assertNull(DownloadProtocol.strongValidator("W/\"weak\"", null))
    }

    @Test fun retryPolicySeparatesTransientAndPermanentErrors() {
        assertTrue(DownloadProtocol.isRetryableStatus(429))
        assertTrue(DownloadProtocol.isRetryableStatus(503))
        assertEquals(false, DownloadProtocol.isRetryableStatus(404))
        assertEquals(300L, DownloadProtocol.retryDelaySeconds(100))
        assertEquals(300L, DownloadProtocol.retryAfterSeconds("99999"))
    }
}
