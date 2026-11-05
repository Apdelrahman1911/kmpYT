package dev.kmpyt

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dev.kmpyt.extraction.ResearchAccess

class UrlPolicyTest {
    @Test fun cleartextLoopbackRequiresExplicitOptInIncludingIpv6() {
        for (url in listOf("http://localhost:4321/media", "http://127.0.0.1:4321/media",
            "http://[::1]:4321/media", "http://[0:0:0:0:0:0:0:1]:4321/media")) {
            assertNotNull(UrlPolicy.validate(url), url)
            assertNull(UrlPolicy.validate(url, allowLocalHttp = true), url)
        }
    }

    @Test fun localOptInDoesNotPermitOtherAddressesOrDeceptiveHosts() {
        for (url in listOf("http://[::]/media", "http://[::2]/media", "http://[fe80::1]/media",
            "http://[::ffff:127.0.0.1]/media", "http://[::1%25lo0]/media", "http://127.0.0.2/media",
            "http://192.168.1.1/media", "http://localhost.example/media", "http://example.com/media",
            "http://localhost@example.com/media", "http://account@[::1]/media")) {
            assertNotNull(UrlPolicy.validate(url, allowLocalHttp = true), url)
        }
        assertNull(UrlPolicy.validate("https://example.com/media"))
    }

    @Test fun ipv6OriginRetainsBracketsAndPortIsolation() {
        assertEquals("http://[::1]:4321", ResearchAccess.origin("http://[::1]:4321/media"))
        ResearchAccess.requireSessionOrigin("http://[::1]:4321/media", "http://[::1]:4321/")
        assertFailsWith<IllegalArgumentException> {
            ResearchAccess.requireSessionOrigin("http://[::1]:4322/media", "http://[::1]:4321/")
        }
        assertFailsWith<IllegalArgumentException> { ResearchAccess.origin("http://[::2]:4321/media") }
    }
}
