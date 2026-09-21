package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a muxed DASH recording is called, and which recordings need muxing at all.
 *
 * The mux itself is `MediaMuxer` and `MediaExtractor`, which only exist on a device — so what is
 * pinned here is the reasoning around it, which is where the silent mistakes live.
 */
class DashMuxOutcomeTest {

    /**
     * `.ts` is chosen for a live recording because a transport stream plays while it is still being
     * written and survives being cut off. A muxed file is an MP4 and is neither, so keeping the name
     * would tell a file manager, a media scanner and every external player something untrue.
     */
    @Test
    fun `a muxed recording is named as the mp4 it is`() {
        assertEquals(
            "BBC One - The Nine O'Clock News - 2026-09-12 21-00.mp4",
            RecordingRules.muxedNameOf("BBC One - The Nine O'Clock News - 2026-09-12 21-00.ts"),
        )
    }

    /** A name with dots of its own must lose only the extension. */
    @Test
    fun `only the final extension is replaced`() {
        assertEquals("Episode 1.2 - 2026-01-01 20-00.mp4", RecordingRules.muxedNameOf("Episode 1.2 - 2026-01-01 20-00.ts"))
    }

    @Test
    fun `a name with no extension simply gains one`() {
        assertEquals("recording.mp4", RecordingRules.muxedNameOf("recording"))
    }

    /** It is the name the engine really builds against, so the two rules must agree. */
    @Test
    fun `the muxed name follows the name the scheduler chose`() {
        val chosen = RecordingRules.fileName("BBC One", "The News", 0L)
        assertTrue(chosen.endsWith(".ts"))
        assertTrue(RecordingRules.muxedNameOf(chosen).endsWith(".mp4"))
        assertEquals(chosen.dropLast(3), RecordingRules.muxedNameOf(chosen).dropLast(4))
    }

    // ---- which recordings need it ----

    private fun representation(id: String, kind: DashTrackKind, bandwidth: Int = 1) = DashRepresentation(
        id = id,
        kind = kind,
        bandwidthBps = bandwidth,
        mimeType = null,
        codecs = null,
        initializationUrl = "$id-init.mp4",
        segments = DashSegments.Numbered("$id-\$Number\$.m4s", 1, 2_000),
    )

    private fun manifestOf(vararg representations: DashRepresentation) = DashManifest(
        representations = representations.toList(),
        dynamic = true,
        minimumUpdatePeriodMs = null,
        availabilityStartTimeMs = 0L,
        timeShiftBufferDepthMs = null,
        mediaPresentationDurationMs = null,
        contentProtected = false,
    )

    /**
     * The whole reason the mux exists. Two Representations are two half-files: concatenating them the
     * way HLS is concatenated produces video with no sound and sound with no video, not a recording.
     */
    @Test
    fun `separate video and audio need muxing`() {
        val selection = DashRecordingPlan.selectTracks(
            manifestOf(representation("v", DashTrackKind.VIDEO), representation("a", DashTrackKind.AUDIO)),
        )
        assertTrue(selection!!.needsMux)
        assertEquals(2, selection.tracks.size)
    }

    /**
     * And the fast path, which is the one that costs nothing: a Representation already carrying both
     * is written straight into the recording, with no temp file, no mux and no MP4 rename.
     */
    @Test
    fun `a single muxed representation is written straight through`() {
        val selection = DashRecordingPlan.selectTracks(manifestOf(representation("m", DashTrackKind.MUXED)))
        assertFalse(selection!!.needsMux)
        assertEquals(1, selection.tracks.size)
    }

    /** Video with no audio at all: one file, nothing to interleave it with, so no mux either. */
    @Test
    fun `a video-only recording needs no mux`() {
        val selection = DashRecordingPlan.selectTracks(manifestOf(representation("v", DashTrackKind.VIDEO)))
        assertFalse(selection!!.needsMux)
    }
}
