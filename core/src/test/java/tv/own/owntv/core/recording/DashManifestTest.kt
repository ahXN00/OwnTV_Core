package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPD reader, over the shapes a recorder actually meets.
 *
 * Every manifest below is the shape a real panel emits, trimmed to what the recorder reads. The
 * JioTV-Go style proxy that started this work is the `$Number$`-template case.
 */
class DashManifestTest {

    // ---- recognising one at all ----

    @Test
    fun `a manifest is recognised by its first tag`() {
        assertTrue(DashManifest.looksLikeDashManifest(null, "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertTrue(DashManifest.looksLikeDashManifest(null, "\n  <MPD>"))
    }

    /** The declaration comes first far more often than not, and must not hide the root tag. */
    @Test
    fun `an XML declaration does not hide the root tag`() {
        assertTrue(DashManifest.looksLikeDashManifest(null, "<?xml version=\"1.0\"?>\n<MPD type=\"dynamic\">"))
    }

    @Test
    fun `a manifest is recognised by its content type`() {
        assertTrue(DashManifest.looksLikeDashManifest("application/dash+xml", "binary"))
        assertTrue(DashManifest.looksLikeDashManifest("video/vnd.mpeg.dash.mpd", "binary"))
    }

    /** An HLS playlist and a body of video must both fall through to the paths that handle them. */
    @Test
    fun `nothing else is mistaken for a manifest`() {
        assertFalse(DashManifest.looksLikeDashManifest("application/x-mpegurl", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(DashManifest.looksLikeDashManifest("video/mp2t", "G@\u0000"))
        assertFalse(DashManifest.looksLikeDashManifest(null, ""))
    }

    @Test
    fun `text that is not XML is not parsed`() {
        assertNull(DashManifest.parse("#EXTM3U"))
        assertNull(DashManifest.parse("<html><body>404</body></html>"))
    }

    // ---- SegmentTemplate + $Number$: the live case that started this ----

    private val numberTemplate = """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD type="dynamic" minimumUpdatePeriod="PT2S" availabilityStartTime="2026-09-20T00:00:00Z"
             timeShiftBufferDepth="PT1M">
          <Period id="0">
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate media="chunk-${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s"
                               initialization="init-${'$'}RepresentationID${'$'}.mp4"
                               timescale="1000" duration="4000" startNumber="1"/>
              <Representation id="v0" bandwidth="800000" codecs="avc1.64001f"/>
              <Representation id="v1" bandwidth="2400000" codecs="avc1.640028"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4">
              <SegmentTemplate media="audio-${'$'}Number${'$'}.m4s" initialization="audio-init.mp4"
                               timescale="1000" duration="4000" startNumber="1"/>
              <Representation id="a0" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun `a number template yields one representation per quality`() {
        val mpd = assertNotNull(DashManifest.parse(numberTemplate))
        assertEquals(3, mpd.representations.size)
        assertEquals(listOf("v0", "v1", "a0"), mpd.representations.map { it.id })
        assertEquals(
            listOf(DashTrackKind.VIDEO, DashTrackKind.VIDEO, DashTrackKind.AUDIO),
            mpd.representations.map { it.kind },
        )
        assertEquals(listOf(800_000, 2_400_000, 128_000), mpd.representations.map { it.bandwidthBps })
    }

    /** The template is inherited from the AdaptationSet, and `$RepresentationID$` filled per quality. */
    @Test
    fun `the segment template is inherited and its identifiers substituted`() {
        val mpd = assertNotNull(DashManifest.parse(numberTemplate))
        val v1 = mpd.representations.first { it.id == "v1" }
        val segments = v1.segments as DashSegments.Numbered
        assertEquals("chunk-v1-\$Number%05d\$.m4s", segments.mediaTemplate)
        assertEquals(1L, segments.startNumber)
        assertEquals(4_000L, segments.durationMs)
        assertEquals("init-v1.mp4", v1.initializationUrl)
    }

    /** `$Number$` and `$Time$` survive parse untouched — they are not known until a segment is wanted. */
    @Test
    fun `the number identifier is left for the fetch loop`() {
        val mpd = assertNotNull(DashManifest.parse(numberTemplate))
        val segments = mpd.representations.first().segments as DashSegments.Numbered
        assertTrue("\$Number" in segments.mediaTemplate)
    }

    @Test
    fun `a dynamic manifest reports its update period and window`() {
        val mpd = assertNotNull(DashManifest.parse(numberTemplate))
        assertTrue(mpd.dynamic)
        assertFalse(mpd.endList)
        assertEquals(2_000L, mpd.minimumUpdatePeriodMs)
        assertEquals(60_000L, mpd.timeShiftBufferDepthMs)
        assertNotNull(mpd.availabilityStartTimeMs)
        assertFalse(mpd.contentProtected)
    }

    // ---- SegmentTemplate + SegmentTimeline ----

    private val timeline = """
        <MPD type="dynamic">
          <Period>
            <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
              <SegmentTemplate media="v/${'$'}Time${'$'}.m4s" initialization="v/init.mp4" timescale="90000">
                <SegmentTimeline>
                  <S t="900000" d="180000" r="2"/>
                  <S d="90000"/>
                </SegmentTimeline>
              </SegmentTemplate>
              <Representation id="v" bandwidth="1500000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    /** `r="2"` means three segments, and the fourth `<S>` starts where the third ended. */
    @Test
    fun `a timeline expands its repeats and carries each start time`() {
        val mpd = assertNotNull(DashManifest.parse(timeline))
        val segments = (mpd.representations.single().segments as DashSegments.Explicit).segments
        assertEquals(4, segments.size)
        assertEquals(listOf(900_000L, 1_080_000L, 1_260_000L, 1_440_000L), segments.map { it.time })
        assertEquals(listOf(1L, 2L, 3L, 4L), segments.map { it.number })
        assertEquals("v/1080000.m4s", segments[1].url)
    }

    /** 180000 at a 90000 timescale is two seconds — durations are timescale units, not milliseconds. */
    @Test
    fun `timeline durations are scaled by the timescale`() {
        val mpd = assertNotNull(DashManifest.parse(timeline))
        val segments = (mpd.representations.single().segments as DashSegments.Explicit).segments
        assertEquals(2_000L, segments[0].durationMs)
        assertEquals(1_000L, segments[3].durationMs)
        assertEquals(2_000L, mpd.representations.single().segmentDurationMs)
    }

    // ---- SegmentList ----

    @Test
    fun `an explicit segment list is read in order`() {
        val mpd = assertNotNull(
            DashManifest.parse(
                """
                <MPD type="static" mediaPresentationDuration="PT30M">
                  <Period>
                    <AdaptationSet contentType="video">
                      <Representation id="v" bandwidth="1000000" mimeType="video/mp4">
                        <SegmentList duration="2" timescale="1">
                          <Initialization sourceURL="init.mp4"/>
                          <SegmentURL media="seg-1.m4s"/>
                          <SegmentURL media="seg-2.m4s"/>
                        </SegmentList>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """.trimIndent(),
            ),
        )
        val rep = mpd.representations.single()
        val segments = (rep.segments as DashSegments.Explicit).segments
        assertEquals(listOf("seg-1.m4s", "seg-2.m4s"), segments.map { it.url })
        assertEquals(listOf(0L, 1L), segments.map { it.number })
        assertEquals(2_000L, segments[0].durationMs)
        assertEquals("init.mp4", rep.initializationUrl)
    }

    /** A static manifest has an end: the recorder stops at the last segment, whatever the clock says. */
    @Test
    fun `a static manifest reports an end`() {
        val mpd = assertNotNull(DashManifest.parse("<MPD type=\"static\" mediaPresentationDuration=\"PT30M\"/>"))
        assertFalse(mpd.dynamic)
        assertTrue(mpd.endList)
        assertEquals(1_800_000L, mpd.mediaPresentationDurationMs)
    }

    /** No `type` at all means static — that is what the spec says, and a catch-up window is the case. */
    @Test
    fun `a manifest with no type is static`() {
        assertFalse(assertNotNull(DashManifest.parse("<MPD/>")).dynamic)
    }

    // ---- SegmentBase and bare BaseURL: the fast path ----

    @Test
    fun `a self-contained representation is a single file`() {
        val mpd = assertNotNull(
            DashManifest.parse(
                """
                <MPD type="static">
                  <BaseURL>https://cdn.example.com/vod/</BaseURL>
                  <Period>
                    <AdaptationSet mimeType="video/mp4">
                      <Representation id="r" bandwidth="3000000" codecs="avc1.640028,mp4a.40.2">
                        <BaseURL>film.mp4</BaseURL>
                        <SegmentBase indexRange="0-900">
                          <Initialization range="0-800"/>
                        </SegmentBase>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """.trimIndent(),
            ),
        )
        val rep = mpd.representations.single()
        assertEquals(DashSegments.Single("https://cdn.example.com/vod/film.mp4"), rep.segments)
        // Video and audio codecs in one Representation: no mux step needed at all.
        assertEquals(DashTrackKind.MUXED, rep.kind)
    }

    // ---- track kinds ----

    @Test
    fun `a representation listing both codecs is muxed`() {
        assertEquals(DashTrackKind.MUXED, kindOfFor(mimeType = "video/mp4", codecs = "avc1.640028,mp4a.40.2"))
    }

    @Test
    fun `codecs identify a track when nothing is declared`() {
        assertEquals(DashTrackKind.VIDEO, kindOfFor(mimeType = null, codecs = "hvc1.2.4.L120.90"))
        assertEquals(DashTrackKind.AUDIO, kindOfFor(mimeType = null, codecs = "ec-3"))
    }

    /** Neither declared nor identifiable — not refused, because a lone one is very likely the media. */
    @Test
    fun `an undeclared representation is unknown rather than refused`() {
        assertEquals(DashTrackKind.UNKNOWN, kindOfFor(mimeType = null, codecs = null))
    }

    private fun kindOfFor(mimeType: String?, codecs: String?): DashTrackKind {
        val mime = mimeType?.let { " mimeType=\"$it\"" }.orEmpty()
        val codec = codecs?.let { " codecs=\"$it\"" }.orEmpty()
        return assertNotNull(
            DashManifest.parse(
                "<MPD><Period><AdaptationSet><Representation id=\"r\"$mime$codec/></AdaptationSet></Period></MPD>",
            ),
        ).representations.single().kind
    }

    // ---- protection ----

    /** The DASH `#EXT-X-KEY`: found by asking, and the reason the recorder stops before writing. */
    @Test
    fun `content protection is reported wherever it is declared`() {
        assertTrue(
            assertNotNull(
                DashManifest.parse(
                    """
                    <MPD type="dynamic">
                      <Period><AdaptationSet mimeType="video/mp4">
                        <ContentProtection schemeIdUri="urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"/>
                        <Representation id="v" bandwidth="1"/>
                      </AdaptationSet></Period>
                    </MPD>
                    """.trimIndent(),
                ),
            ).contentProtected,
        )
    }

    // ---- tolerance ----

    /** DASH gains elements all the time; one we have never seen must not cost the recording. */
    @Test
    fun `unknown elements and attributes are ignored`() {
        val mpd = assertNotNull(
            DashManifest.parse(
                """
                <MPD type="dynamic" publishTime="2026-09-20T12:00:00Z" profiles="urn:mpeg:dash:profile:isoff-live:2011">
                  <ProgramInformation><Title>Aaj Tak</Title></ProgramInformation>
                  <UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-iso:2014" value="https://time.example"/>
                  <Period>
                    <AdaptationSet mimeType="video/mp4" someFutureAttribute="7">
                      <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                      <SegmentTemplate media="s-${'$'}Number${'$'}.m4s" duration="2" startNumber="9"/>
                      <Representation id="v" bandwidth="1" codecs="avc1.4d401f"/>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """.trimIndent(),
            ),
        )
        val segments = mpd.representations.single().segments as DashSegments.Numbered
        assertEquals(9L, segments.startNumber)
        // timescale absent -> 1, so duration="2" is two seconds.
        assertEquals(2_000L, segments.durationMs)
    }

    /** A Representation overriding what its AdaptationSet said. */
    @Test
    fun `a representation overrides the inherited template`() {
        val mpd = assertNotNull(
            DashManifest.parse(
                """
                <MPD type="dynamic">
                  <Period>
                    <AdaptationSet mimeType="video/mp4">
                      <SegmentTemplate media="set-${'$'}Number${'$'}.m4s" duration="4" startNumber="1"/>
                      <Representation id="v" bandwidth="1" codecs="avc1.4d401f">
                        <SegmentTemplate media="rep-${'$'}Number${'$'}.m4s" duration="2" startNumber="50"/>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """.trimIndent(),
            ),
        )
        val segments = mpd.representations.single().segments as DashSegments.Numbered
        assertEquals("rep-\$Number\$.m4s", segments.mediaTemplate)
        assertEquals(50L, segments.startNumber)
    }

    // ---- BaseURL joining ----

    @Test
    fun `base urls stack from the manifest down to the representation`() {
        val mpd = assertNotNull(
            DashManifest.parse(
                """
                <MPD type="dynamic">
                  <BaseURL>https://cdn.example.com/live/</BaseURL>
                  <Period>
                    <BaseURL>ch173/</BaseURL>
                    <AdaptationSet mimeType="video/mp4">
                      <SegmentTemplate media="${'$'}Number${'$'}.m4s" initialization="init.mp4" duration="2"/>
                      <Representation id="v" bandwidth="1" codecs="avc1.4d401f"/>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """.trimIndent(),
            ),
        )
        val rep = mpd.representations.single()
        assertEquals("https://cdn.example.com/live/ch173/init.mp4", rep.initializationUrl)
        assertEquals(
            "https://cdn.example.com/live/ch173/\$Number\$.m4s",
            (rep.segments as DashSegments.Numbered).mediaTemplate,
        )
    }

    /** An absolute segment URL ignores every base above it. */
    @Test
    fun `an absolute reference wins over the base url`() {
        assertEquals("https://other.example/s.m4s", DashManifest.joinUrl("https://cdn.example/a/", "https://other.example/s.m4s"))
        assertEquals("/root/s.m4s", DashManifest.joinUrl("https://cdn.example/a/", "/root/s.m4s"))
    }

    // ---- the template expander ----

    @Test
    fun `the template expander fills every identifier`() {
        assertEquals(
            "v1/800000/chunk-00042-9000.m4s",
            DashManifest.expandTemplate(
                "\$RepresentationID\$/\$Bandwidth\$/chunk-\$Number%05d\$-\$Time\$.m4s",
                representationId = "v1",
                bandwidthBps = 800_000,
                number = 42,
                time = 9_000,
            ),
        )
    }

    /** The two-pass use: an identifier we were not given must survive for the second pass. */
    @Test
    fun `an identifier that was not supplied is left in place`() {
        assertEquals(
            "v1-\$Number%05d\$.m4s",
            DashManifest.expandTemplate("\$RepresentationID\$-\$Number%05d\$.m4s", representationId = "v1"),
        )
    }

    @Test
    fun `a doubled dollar is an escape`() {
        assertEquals("a\$b.m4s", DashManifest.expandTemplate("a\$\$b.m4s"))
    }

    /** A stray dollar in a signed URL must not eat the rest of it. */
    @Test
    fun `an unpaired dollar is left alone`() {
        assertEquals("seg?sig=a\$b", DashManifest.expandTemplate("seg?sig=a\$b"))
    }

    // ---- durations ----

    @Test
    fun `iso durations are read in every form a panel emits`() {
        assertEquals(6_000L, DashManifest.parseDurationMs("PT6S"))
        assertEquals(90_500L, DashManifest.parseDurationMs("PT1M30.5S"))
        assertEquals(93_600_000L, DashManifest.parseDurationMs("P1DT2H"))
        assertEquals(6_000L, DashManifest.parseDurationMs("P0Y0M0DT0H0M6.0S"))
        assertEquals(0L, DashManifest.parseDurationMs("PT0S"))
        assertNull(DashManifest.parseDurationMs("6"))
        assertNull(DashManifest.parseDurationMs(null))
    }

    @Test
    fun `availability start time is read with or without an explicit offset`() {
        assertEquals(0L, DashManifest.parseInstantMs("1970-01-01T00:00:00Z"))
        assertEquals(0L, DashManifest.parseInstantMs("1970-01-01T01:00:00+01:00"))
        assertNull(DashManifest.parseInstantMs("not a time"))
        assertNull(DashManifest.parseInstantMs(null))
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
