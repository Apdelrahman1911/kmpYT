package dev.kmpyt.extraction

import kotlin.test.*

class YouTubeTimedTextTest {
    @Test fun parsesOriginalTimedtextUrlTranscriptWithFractionalSecondsAndXmlText() {
        val document = YouTubeCaptions.parse("""<?xml version="1.0" encoding="utf-8"?><transcript><text start="1.250" dur="2.125">Hello &amp; مرحبا &#x1F642;</text><text start="4" dur="1">next</text></transcript>""")
        assertEquals(YouTubeCaptionFormat.TIMEDTEXT, document.format)
        assertEquals(YouTubeCaptionCue(1250, 3375, "Hello & مرحبا 🙂"), document.cues.first())
        assertEquals(YouTubeCaptionCue(4000, 5000, "next"), document.cues.last())
    }

    @Test fun parsesSrv3WordOffsetsAndWindowAppendMetadata() {
        val document = YouTubeCaptions.parse("""<timedtext format="3"><head><wp id="0" ap="6"/></head><body><w t="0" id="1"/><p t="1000" d="2000" w="1" a="1"><s t="0">Hello</s><s t="500"> world</s></p></body></timedtext>""")
        val cue = document.cues.single()
        assertEquals(1000L, cue.startMs); assertEquals(3000L, cue.endMs)
        assertEquals("Hello world", cue.text); assertEquals(1, cue.windowId); assertEquals(true, cue.append)
        assertEquals(listOf(YouTubeCaptionSegment("Hello", 0), YouTubeCaptionSegment(" world", 500)), cue.segments)
    }

    @Test fun rejectsExternalEntitiesInvalidIntervalsAndAmbiguousNestedText() {
        val invalid = listOf(
            """<!DOCTYPE transcript [<!ENTITY secret SYSTEM "file:///private">]><transcript><text start="0" dur="1">&secret;</text></transcript>""",
            """<transcript><text start="-1" dur="2">bad</text></transcript>""",
            """<transcript><text start="0" dur="0">bad</text></transcript>""",
            """<transcript><text start="0" dur="1"><nested>bad</nested></text></transcript>""",
            """<timedtext format="3"><body><p t="0" d="1000"><s t="1001">bad</s></p></body></timedtext>""",
            """<timedtext format="3"><body><p t="0" d="1000">mixed<s>bad order</s></p></body></timedtext>""")
        invalid.forEach { body -> assertFailsWith<YouTubeCaptionException> { YouTubeCaptions.parse(body) } }
    }
}
