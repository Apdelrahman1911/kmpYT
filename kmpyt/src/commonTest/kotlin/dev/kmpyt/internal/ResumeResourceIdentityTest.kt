package dev.kmpyt.internal

import kotlin.test.*

class ResumeResourceIdentityTest {
    private fun scope(url: String, local: Boolean = false) = resumeResourceIdentity(url, "AUTHORED001", local)

    @Test fun onlyExactDocumentedSigningKeysOnApprovedResourceHostsMayChange() {
        val base = "https://rr1.googlevideo.com/videoplayback?id=media&itag=140"
        // Preserve the schema-2 JVM identity encoding when sharing it with Darwin.
        assertEquals("""{"media":"AUTHORED001","origin":"https://rr1.googlevideo.com:443","path":"/videoplayback","fragment":null,"query":["id=media","itag=140"]}""",
            scope(base))
        assertEquals(scope("$base&sig=old&expire=1&n=old"), scope("$base&sig=new&expire=2&n=new"))
        for (key in listOf("Sig", "SIGNATURE", "%73ig", "unknown", "itag", "source", "id"))
            assertNotEquals(scope("$base&$key=old"), scope("$base&$key=new"))
        for (host in listOf("media.example", "googlevideo.com.example", "rr1.googlevideo.com."))
            assertNotEquals(scope("https://$host/media?sig=old"), scope("https://$host/media?sig=new"))
    }

    @Test fun rawPathFragmentAndQueryOrderingDuplicatesAndEmptyPairsAreIdentityBearing() {
        val base = "https://rr1.googlevideo.com"
        for ((a, b) in listOf(
            "/media?a=1&b=2" to "/media?b=2&a=1", "/media?a=1&a=2" to "/media?a=2&a=1",
            "/media?a=1&&b=2" to "/media?a=1&b=2", "/media?a=1&" to "/media?a=1",
            "/media?a" to "/media?a=", "/media?q=+" to "/media?q=%20", "/media?q=%41" to "/media?q=A",
            "/%6dedia?a=1" to "/media?a=1", "/a/../media" to "/media", "" to "/",
            "/media" to "/media?", "/media" to "/media#", "/media#one" to "/media#two",
        )) assertNotEquals(scope(base + a), scope(base + b), "Raw URI components must not collapse")
        assertNotEquals(scope("$base/media?a=1"), scope("https://rr2.googlevideo.com/media?a=1"))
        assertNotEquals(scope("$base/media?a=1"), scope("$base:444/media?a=1"))
    }

    @Test fun authoredLoopbackSigningRefreshRequiresAnExplicitLocalTransportOptIn() {
        val a = "http://127.0.0.1:12345/media?itag=140&sig=old"
        val b = a.replace("old", "new")
        assertNotEquals(scope(a), scope(b))
        assertEquals(scope(a, local = true), scope(b, local = true))
        assertNotEquals(scope(a, true), scope(b.replace("12345", "12346"), true))
        assertNotEquals(scope("http://media.example/media?sig=old", true), scope("http://media.example/media?sig=new", true))
    }

    @Test fun malformedOrRelativeUrisCannotAcquireAReusableScope() {
        for (url in listOf("/media", "https://media.example/%", "https://media.example/?a=%0x", "https://media.example/a b", "https://media.example/\n"))
            assertFails { scope(url) }
    }
}
