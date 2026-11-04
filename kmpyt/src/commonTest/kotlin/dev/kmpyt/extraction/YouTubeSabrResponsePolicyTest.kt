package dev.kmpyt.extraction

import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeSabrResponsePolicyTest {
    @Test fun nonemptyProtectionTwoAndThreeHaveOptionalRefresh() {
        for (status in listOf(2, 3)) assertEquals(SabrResponseProtectionAction.OPTIONAL_REFRESH_WITH_MEDIA,
            YouTubeSabrResponsePolicy.classify(status, mediaPresent = true, protocolCanAdvance = false))
    }
    @Test fun emptyProtectionThreeRequiresActualRefresh() {
        assertEquals(SabrResponseProtectionAction.REQUIRED_REFRESH_EMPTY,
            YouTubeSabrResponsePolicy.classify(3, mediaPresent = false, protocolCanAdvance = true))
    }
    @Test fun emptyProtectionTwoAllowsRetainedContextAndBackoff() {
        assertEquals(SabrResponseProtectionAction.OPTIONAL_REFRESH_EMPTY_BACKOFF,
            YouTubeSabrResponsePolicy.classify(2, mediaPresent = false, protocolCanAdvance = false))
    }
    @Test fun emptyNormalResponseAdvancesOnlyWithObservedProtocolState() {
        assertEquals(SabrResponseProtectionAction.REJECT_EMPTY, YouTubeSabrResponsePolicy.classify(null, false, false))
        assertEquals(SabrResponseProtectionAction.ADVANCE_EMPTY_PROTOCOL, YouTubeSabrResponsePolicy.classify(null, false, true))
    }
    @Test fun numericUnknownStatusIsPreservedAsUnsupported() {
        assertEquals(SabrResponseProtectionAction.REJECT_UNKNOWN_STATUS, YouTubeSabrResponsePolicy.classify(42, true, true))
    }
}
