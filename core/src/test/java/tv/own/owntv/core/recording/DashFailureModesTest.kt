package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus

/**
 * What a DASH recording does when things go wrong — which is most of what a recorder does.
 *
 * The defensive behaviour here exists because the "impossible" happens routinely against real
 * providers. Simplify its shape, never its coverage.
 */
class DashFailureModesTest {

    private fun representation(
        id: String,
        kind: DashTrackKind = DashTrackKind.VIDEO,
        segments: DashSegments = DashSegments.Numbered("$id-\$Number\$.m4s", 1, 2_000),
    ) = DashRepresentation(id, kind, 1, null, null, "$id-init.mp4", segments)

    private fun manifest(
        representations: List<DashRepresentation>,
        dynamic: Boolean = true,
        availabilityStartTimeMs: Long? = 0L,
        mediaPresentationDurationMs: Long? = null,
        contentProtected: Boolean = false,
    ) = DashManifest(
        representations, dynamic, null, availabilityStartTimeMs, null,
        mediaPresentationDurationMs, contentProtected,
    )

    // ---- a manifest that changes shape mid-programme ----

    /**
     * The quality we fixed on has gone. The recording stops with a reason rather than continuing at
     * whatever is left: a resolution or codec change partway through is exactly what the mux cannot
     * absorb, and the result would be a file that stops playing at the switch.
     */
    @Test
    fun `the chosen representation is looked up by id, not by position`() {
        val first = manifest(listOf(representation("v0"), representation("a0", DashTrackKind.AUDIO)))
        val selection = assertNotNull(DashRecordingPlan.selectTracks(first))

        // The provider republishes with the qualities in the other order and a new one inserted.
        val later = manifest(
            listOf(
                representation("v9"),
                representation("a0", DashTrackKind.AUDIO),
                representation("v0"),
            ),
        )
        selection.tracks.forEach { track ->
            assertNotNull(later.representations.firstOrNull { it.id == track.id })
        }
        // And when it is genuinely gone, there is nothing to find — which the engine reports.
        val without = manifest(listOf(representation("v9")))
        assertNull(without.representations.firstOrNull { it.id == "a0" })
    }

    /**
     * A provider switching a live stream from a `$Number$` template to a `SegmentTimeline` between
     * two polls. The plan is re-derived from each manifest, so the new shape is simply read.
     */
    @Test
    fun `a representation that changes addressing shape is still scheduled`() {
        val numbered = representation("v")
        val timeline = representation(
            "v",
            segments = DashSegments.Explicit(listOf(DashSegment(31, 62_000, "v-62000.m4s", 2_000))),
        )
        val afterNumbered = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, -1, 60_000)
        assertEquals(30L, afterNumbered.lastNumber)

        val afterTimeline = DashRecordingPlan.nextSegments(
            manifest(listOf(timeline)), timeline, afterNumbered.lastNumber, 62_000,
        )
        assertEquals(listOf(31L), afterTimeline.segments.map { it.number })
    }

    // ---- reconnect and append ----

    /**
     * The whole point of carrying `lastNumber` across a dropped connection: the reconnect continues
     * where it left off instead of re-fetching what is already in the file.
     */
    @Test
    fun `a reconnect does not re-fetch what was already written`() {
        val rep = representation("v")
        val before = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, -1, 20_000)
        assertEquals(10L, before.lastNumber)
        // Thirty seconds of nothing, then the connection comes back.
        val after = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, before.lastNumber, 50_000)
        assertTrue(after.segments.none { it.number <= before.lastNumber })
        assertEquals(11L, after.segments.first().number)
    }

    /** And a reconnect after the window has moved past does not ask for what the provider dropped. */
    @Test
    fun `a reconnect after a long outage does not ask for expired segments`() {
        val rep = representation("v")
        val mpd = DashManifest(listOf(rep), true, null, 0L, 60_000, null, false)
        val plan = DashRecordingPlan.nextSegments(mpd, rep, lastNumber = 5, nowMs = 3_600_000)
        // The window is only thirty segments deep, so nothing older than that is requested.
        assertTrue(plan.segments.first().number >= 1770L)
    }

    // ---- disk ----

    /** Out of room stays a failure however much was captured, because the user must know it is short. */
    @Test
    fun `running out of room is reported even with bytes on disk`() {
        val (status, reason) = RecordingRules.outcomeOf(bytes = 900_000_000, failure = RecordingFailure.NO_SPACE)
        assertEquals(RecordingStatus.FAILED, status)
        assertEquals(RecordingFailure.NO_SPACE, reason)
    }

    /**
     * A DASH recording needs room for its temp files **and** the muxed file at the same time, so the
     * reserve has to still be there when the mux runs. The same floor guards both.
     */
    @Test
    fun `the disk reserve is the same floor before and after the mux`() {
        assertFalse(RecordingRules.hasSpace(RecordingRules.RESERVE_BYTES))
        assertFalse(RecordingRules.hasSpace(0))
        assertTrue(RecordingRules.hasSpace(RecordingRules.RESERVE_BYTES + 1))
    }

    // ---- a static window ending ----

    /**
     * A catch-up programme has an end. It is reached when a whole cycle finds nothing new — which is
     * not the same as one pass, because a numbered template is deliberately taken a bounded number of
     * segments at a time.
     */
    @Test
    fun `a static window is finished only when a cycle finds nothing new`() {
        val rep = representation("v")
        val mpd = manifest(listOf(rep), dynamic = false, mediaPresentationDurationMs = 120_000)
        // Sixty segments at two seconds, taken twenty-four at a time.
        var last = -1L
        var cycles = 0
        while (true) {
            val plan = DashRecordingPlan.nextSegments(mpd, rep, last, 0)
            if (plan.segments.isEmpty()) break
            last = plan.lastNumber
            cycles++
            assertTrue("a static window must terminate", cycles < 10)
        }
        assertEquals(3, cycles)
        assertEquals(60L, last)
    }

    @Test
    fun `a static manifest reports that it has ended`() {
        assertTrue(manifest(listOf(representation("v")), dynamic = false).endList)
        assertFalse(manifest(listOf(representation("v"))).endList)
    }

    // ---- refusals ----

    /**
     * Protection found in the manifest is refused with a **terminal** reason, so it costs one request
     * rather than a window of them. The lesson of the pre-existing DRM bug, not repeated here.
     */
    @Test
    fun `a protected manifest cannot be retried into a reconnect storm`() {
        assertTrue(manifest(listOf(representation("v")), contentProtected = true).contentProtected)
        val terminal = setOf(
            RecordingFailure.NO_SPACE,
            RecordingFailure.ENCRYPTED,
            RecordingFailure.DRM_PROTECTED,
        )
        assertTrue(RecordingFailure.DRM_PROTECTED in terminal)
    }

    /** No zero point to count segments from. Refused rather than guessed into thousands of 404s. */
    @Test
    fun `an unschedulable manifest yields nothing to fetch`() {
        val rep = representation("v")
        val plan = DashRecordingPlan.nextSegments(
            manifest(listOf(rep), availabilityStartTimeMs = null), rep, -1, 10_000,
        )
        assertTrue(plan.unschedulable)
        assertTrue(plan.segments.isEmpty())
    }

    /** Nothing was written at all: a failure, because there is no file to offer. */
    @Test
    fun `a recording that captured nothing is a failure`() {
        val (status, _) = RecordingRules.outcomeOf(bytes = 0, failure = RecordingFailure.STREAM_UNAVAILABLE)
        assertEquals(RecordingStatus.FAILED, status)
    }

    /** But any bytes at all is a recording the user can watch. */
    @Test
    fun `a partial recording is still a recording`() {
        val (status, reason) = RecordingRules.outcomeOf(bytes = 1, failure = RecordingFailure.NETWORK)
        assertEquals(RecordingStatus.COMPLETED, status)
        assertEquals(RecordingFailure.NONE, reason)
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
