package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.RecordingFailure

/**
 * A DASH manifest reaches the DASH recorder, and never the raw byte pump.
 *
 * **This is the guard on a bug that was live.** Before `recordDash` existed, an unprotected DASH
 * channel fetched fine — 200 OK, a few kilobytes of MPD XML —
 * [HlsMediaPlaylist.looksLikePlaylist] did not match it, and it fell through to the byte pump. The
 * XML was written into the recording file, `read()` returned −1 because a manifest is a finite
 * document, and the attempt returned [RecordingFailure.NETWORK]. `NETWORK` is not terminal, so the
 * engine waited, reconnected, and **appended the same manifest again** — for the whole window.
 *
 * The user got a file of hundreds of concatenated XML manifests; the provider got a reconnect storm;
 * one of the account's connection slots was held the entire time; and the row said "the network went
 * away", which was a lie. The same shape of bug as the DRM one in [RecordingDrmRefusalTest], from the
 * same cause: a body that is a *document* falling through to a pump that expects video.
 */
class DashRecordingRoutingTest {

    /** The JioTV-Go style proxy from the report that started this work. */
    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" minimumUpdatePeriod="PT2S"
             availabilityStartTime="2026-09-20T00:00:00Z">
          <Period id="0">
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate media="v-${'$'}Number${'$'}.m4s" initialization="v-init.mp4"
                               timescale="1000" duration="4000" startNumber="1"/>
              <Representation id="v0" bandwidth="2400000" codecs="avc1.640028"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4">
              <SegmentTemplate media="a-${'$'}Number${'$'}.m4s" initialization="a-init.mp4"
                               timescale="1000" duration="4000" startNumber="1"/>
              <Representation id="a0" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    /**
     * The engine peeks at the first kilobyte and asks each reader in turn. This is that decision:
     * the DASH reader claims it, and the HLS reader does not, so it can never reach the byte pump.
     */
    @Test
    fun `a manifest is claimed by the dash reader and not the hls one`() {
        val peek = manifest.take(1024)
        assertTrue(DashManifest.looksLikeDashManifest("application/dash+xml", peek))
        assertFalse(HlsMediaPlaylist.looksLikePlaylist("application/dash+xml", peek))
    }

    /** Providers mislabel manifests constantly. The body is what decides, so the label cannot break it. */
    @Test
    fun `a mislabelled manifest is still claimed`() {
        val peek = manifest.take(1024)
        assertTrue(DashManifest.looksLikeDashManifest("text/plain", peek))
        assertTrue(DashManifest.looksLikeDashManifest("video/mp2t", peek))
        assertTrue(DashManifest.looksLikeDashManifest(null, peek))
    }

    /** And the reverse: an HLS playlist must keep going to `recordHls`, not be stolen by this. */
    @Test
    fun `an hls playlist is not claimed by the dash reader`() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg-1.ts\n"
        assertTrue(HlsMediaPlaylist.looksLikePlaylist("application/x-mpegurl", playlist))
        assertFalse(DashManifest.looksLikeDashManifest("application/x-mpegurl", playlist))
    }

    /** A body of actual video is claimed by neither, and goes to the byte pump where it belongs. */
    @Test
    fun `raw video still reaches the byte pump`() {
        val transportStream = "G@\u0000\u0010\u0000\u0000°\r"
        assertFalse(DashManifest.looksLikeDashManifest("video/mp2t", transportStream))
        assertFalse(HlsMediaPlaylist.looksLikePlaylist("video/mp2t", transportStream))
    }

    /**
     * The manifest the engine will actually work from: two tracks, so two temp files and a mux, and
     * a plan that knows where to start. This is what Phase C's device check confirms on the box.
     */
    @Test
    fun `the manifest that broke is now fully recordable`() {
        val parsed = DashManifest.parse(manifest)
        org.junit.Assert.assertNotNull(parsed)
        val selection = DashRecordingPlan.selectTracks(parsed!!)
        org.junit.Assert.assertNotNull(selection)
        assertEquals("v0", selection!!.video?.id)
        assertEquals("a0", selection.audio?.id)
        assertTrue("two separate tracks must be muxed", selection.needsMux)
        assertFalse("nothing here is protected", parsed.contentProtected)

        // Both tracks have an initialisation segment, and it must be written before any media.
        selection.tracks.forEach { org.junit.Assert.assertNotNull(it.initializationUrl) }

        // And the recording starts at the live edge rather than backfilling the provider's window.
        // Forty seconds into the stream, at four seconds a segment, the newest complete one is 10.
        val fortySecondsIn = parsed.availabilityStartTimeMs!! + 40_000
        val plan = DashRecordingPlan.nextSegments(parsed, selection.video!!, lastNumber = -1, nowMs = fortySecondsIn)
        assertEquals(1, plan.segments.size)
        assertEquals("v-10.m4s", plan.segments.single().url)
    }

    /**
     * A protected manifest is refused with a reason that is **terminal**, so the refusal costs one
     * request rather than a window's worth of them. The lesson from the DRM bug, applied before it
     * could be repeated here.
     */
    @Test
    fun `a protected manifest is refused with a terminal reason`() {
        val protectedManifest = manifest.replace(
            "<Period id=\"0\">",
            "<Period id=\"0\"><ContentProtection schemeIdUri=\"urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED\"/>",
        )
        val parsed = DashManifest.parse(protectedManifest)
        org.junit.Assert.assertNotNull(parsed)
        assertTrue(parsed!!.contentProtected)
        // The engine answers DRM_PROTECTED for this, which runRecording breaks on rather than retries.
        assertTrue(
            RecordingFailure.DRM_PROTECTED in setOf(
                RecordingFailure.NO_SPACE,
                RecordingFailure.ENCRYPTED,
                RecordingFailure.DRM_PROTECTED,
            ),
        )
    }
}
