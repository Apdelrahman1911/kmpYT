package dev.kmpyt.extraction

import kotlin.test.*

class DashParserTest {
    private val url = "https://fixtures.example/media/manifest.mpd"
    private fun parse(content: String, attributes: String = "type=\"static\" mediaPresentationDuration=\"PT6S\"") =
        DashParser.parse(url, "<MPD $attributes><Period>$content</Period></MPD>")

    @Test fun inheritedTemplateExpandsNumberPaddingAndInitialization() {
        val result = parse("""
            <BaseURL>../cdn/</BaseURL>
            <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401e">
              <SegmentTemplate timescale="10" duration="20" startNumber="7" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="v-${'$'}Number%05d${'$'}.m4s"/>
              <Representation id="v360" bandwidth="800000" width="640" height="360"/>
            </AdaptationSet>
        """.trimIndent())
        val representation = result.representations.single()
        assertEquals(TrackRole.VIDEO, representation.role)
        assertEquals("https://fixtures.example/cdn/init-v360.mp4", representation.initialization!!.url)
        assertEquals(listOf("v-00007.m4s", "v-00008.m4s", "v-00009.m4s"), representation.segments.map { it.url.substringAfterLast('/') })
        assertEquals(listOf(2000L, 2000L, 2000L), representation.segments.map { it.durationMs })
    }

    @Test fun timelineTimeAndNegativeRepeatAreBoundedByNextEntry() {
        val result = parse("""
          <AdaptationSet mimeType="audio/mp4"><Representation id="a">
            <SegmentTemplate timescale="1" initialization="init.mp4" media="s-${'$'}Time${'$'}.m4s">
              <SegmentTimeline><S t="0" d="2" r="-1"/><S t="4" d="2"/></SegmentTimeline>
            </SegmentTemplate>
          </Representation></AdaptationSet>
        """.trimIndent())
        assertEquals(listOf("s-0.m4s", "s-2.m4s", "s-4.m4s"), result.representations.single().segments.map { it.url.substringAfterLast('/') })
    }

    @Test fun segmentListAndXmlEscapesPreserveExactInclusiveRanges() {
        val result = parse("""
          <AdaptationSet mimeType="audio/mp4"><Representation id="a"><BaseURL>audio.mp4?x=1&amp;y=2</BaseURL>
            <SegmentList timescale="10" duration="20"><Initialization range="0-99"/>
              <SegmentURL mediaRange="100-249"/><SegmentURL mediaRange="250-399"/>
            </SegmentList>
          </Representation></AdaptationSet>
        """.trimIndent())
        val track = result.representations.single()
        assertEquals(100L, track.initialization!!.rangeLength)
        assertEquals("https://fixtures.example/media/audio.mp4?x=1&y=2", track.initialization!!.url)
        assertEquals(listOf(100L, 250L), track.segments.map { it.rangeStart })
        assertEquals(listOf(150L, 150L), track.segments.map { it.rangeLength })
    }

    @Test fun progressiveBaseUrlDoesNotInventSegments() {
        val result = parse("<AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\"><BaseURL>movie.mp4</BaseURL><SegmentBase indexRange=\"0-30\"/></Representation></AdaptationSet>")
        assertEquals("https://fixtures.example/media/movie.mp4", result.representations.single().url)
        assertTrue(result.representations.single().segments.isEmpty())
    }

    @Test fun protectedDynamicAndExternalEntityManifestsAreRejected() {
        val protected = parse("<AdaptationSet mimeType=\"video/mp4\"><ContentProtection/><Representation id=\"v\"><BaseURL>v.mp4</BaseURL></Representation></AdaptationSet>")
        assertEquals("encrypted", protected.diagnostics.single().code)
        assertEquals("live", parse("", "type=\"dynamic\"").diagnostics.single().code)
        assertTrue(DashParser.parse(url, "<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///secret'>]><MPD/>").representations.isEmpty())
    }

    @Test fun unboundedRepeatAndHugeExpansionFailSafely() {
        val template = "<AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\"><SegmentTemplate initialization=\"i.mp4\" media=\"s-${'$'}Number${'$'}\"><SegmentTimeline><S d=\"1\" r=\"-1\"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet>"
        assertTrue(parse(template, "type=\"static\"").representations.isEmpty())
        assertTrue(parse(template.replace("r=\"-1\"", "r=\"999999999\"")).representations.isEmpty())
    }

    @Test fun invalidXmlMismatchedTimelineAndUnknownTemplatesDoNotEscape() {
        assertTrue(DashParser.parse(url, "<MPD><Period></MPD>").representations.isEmpty())
        val unknown = "<AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\"><SegmentTemplate duration=\"2\" initialization=\"i.mp4\" media=\"${'$'}Unknown${'$'}\"/></Representation></AdaptationSet>"
        assertTrue(parse(unknown).representations.isEmpty())
    }
}
