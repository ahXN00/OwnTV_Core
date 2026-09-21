package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Track selection and segment scheduling — every decision a DASH recording makes before it touches
 * the network.
 */
class DashRecordingPlanTest {

    private fun representation(
        id: String,
        kind: DashTrackKind,
        bandwidth: Int,
        segments: DashSegments = DashSegments.Numbered("$id-\$Number\$.m4s", startNumber = 1, durationMs = 2_000),
    ) = DashRepresentation(
        id = id,
        kind = kind,
        bandwidthBps = bandwidth,
        mimeType = null,
        codecs = null,
        initializationUrl = "$id-init.mp4",
        segments = segments,
    )

    private fun manifest(
        representations: List<DashRepresentation>,
        dynamic: Boolean = true,
        minimumUpdatePeriodMs: Long? = null,
        availabilityStartTimeMs: Long? = 0L,
        timeShiftBufferDepthMs: Long? = null,
        mediaPresentationDurationMs: Long? = null,
    ) = DashManifest(
        representations = representations,
        dynamic = dynamic,
        minimumUpdatePeriodMs = minimumUpdatePeriodMs,
        availabilityStartTimeMs = availabilityStartTimeMs,
        timeShiftBufferDepthMs = timeShiftBufferDepthMs,
        mediaPresentationDurationMs = mediaPresentationDurationMs,
        contentProtected = false,
    )

    // ---- selection ----

    @Test
    fun `the best video and the best audio are chosen`() {
        val selection = assertNotNull(
            DashRecordingPlan.selectTracks(
                manifest(
                    listOf(
                        representation("v0", DashTrackKind.VIDEO, 800_000),
                        representation("v1", DashTrackKind.VIDEO, 2_400_000),
                        representation("a0", DashTrackKind.AUDIO, 64_000),
                        representation("a1", DashTrackKind.AUDIO, 128_000),
                    ),
                ),
            ),
        )
        assertEquals("v1", selection.video?.id)
        assertEquals("a1", selection.audio?.id)
        assertTrue(selection.needsMux)
        assertEquals(listOf("v1", "a1"), selection.tracks.map { it.id })
    }

    /** One Representation carrying both is the fast path: concatenation, exactly as HLS is recorded. */
    @Test
    fun `a muxed representation needs no mux`() {
        val selection = assertNotNull(
            DashRecordingPlan.selectTracks(
                manifest(
                    listOf(
                        representation("m0", DashTrackKind.MUXED, 1_000_000),
                        representation("m1", DashTrackKind.MUXED, 3_000_000),
                        representation("v", DashTrackKind.VIDEO, 5_000_000),
                    ),
                ),
            ),
        )
        assertEquals("m1", selection.combined?.id)
        assertFalse(selection.needsMux)
        assertEquals(listOf("m1"), selection.tracks.map { it.id })
    }

    /** A video-only stream is a recording, not a failure — there is simply nothing to mux it with. */
    @Test
    fun `video with no audio is recorded alone`() {
        val selection = assertNotNull(
            DashRecordingPlan.selectTracks(manifest(listOf(representation("v", DashTrackKind.VIDEO, 1)))),
        )
        assertEquals("v", selection.video?.id)
        assertNull(selection.audio)
        assertFalse(selection.needsMux)
    }

    @Test
    fun `a lone undeclared representation is taken as the whole stream`() {
        val selection = assertNotNull(
            DashRecordingPlan.selectTracks(manifest(listOf(representation("r", DashTrackKind.UNKNOWN, 1)))),
        )
        assertEquals("r", selection.combined?.id)
        assertFalse(selection.needsMux)
    }

    @Test
    fun `a manifest with nothing in it selects nothing`() {
        assertNull(DashRecordingPlan.selectTracks(manifest(emptyList())))
    }

    // ---- scheduling: $Number$ templates ----

    private val numbered = representation("v", DashTrackKind.VIDEO, 1)

    /** Segment n is fetchable one duration after it begins, so at t=10s the newest complete one is 5. */
    @Test
    fun `the first cycle starts at the live edge`() {
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, lastNumber = -1, nowMs = 10_000)
        assertEquals(listOf(5L), plan.segments.map { it.number })
        assertEquals("v-5.m4s", plan.segments.single().url)
        assertEquals(5L, plan.lastNumber)
    }

    /** The live edge does not backfill the window, however deep the provider's window is. */
    @Test
    fun `the first cycle does not backfill an hour of window`() {
        val mpd = manifest(listOf(numbered), timeShiftBufferDepthMs = 3_600_000)
        val plan = DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = -1, nowMs = 3_600_000)
        assertEquals(1, plan.segments.size)
        assertEquals(1800L, plan.segments.single().number)
    }

    @Test
    fun `a later cycle takes only what is new`() {
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, lastNumber = 5, nowMs = 16_000)
        assertEquals(listOf(6L, 7L, 8L), plan.segments.map { it.number })
        assertEquals(8L, plan.lastNumber)
    }

    /** Nothing new yet — the poll landed inside a segment. An empty cycle, not an error. */
    @Test
    fun `a cycle with nothing new fetches nothing and keeps its place`() {
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, lastNumber = 8, nowMs = 16_000)
        assertTrue(plan.segments.isEmpty())
        assertEquals(8L, plan.lastNumber)
        assertFalse(plan.unschedulable)
    }

    /** Before the first segment has finished publishing there is nothing to ask for. */
    @Test
    fun `nothing is fetched before the first segment completes`() {
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, lastNumber = -1, nowMs = 500)
        assertTrue(plan.segments.isEmpty())
    }

    /**
     * A long stall. The gap is caught up, but not all in one burst at a provider that has just been
     * unreachable — the next cycle takes the rest.
     */
    @Test
    fun `a long gap is caught up over several cycles rather than in one burst`() {
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(numbered)), numbered, lastNumber = 5, nowMs = 600_000)
        assertEquals(DashRecordingPlan.MAX_SEGMENTS_PER_CYCLE.toInt(), plan.segments.size)
        // Newest-biased: it keeps up with the live edge rather than falling further behind.
        assertEquals(300L, plan.segments.last().number)
    }

    /** Older than the provider still serves: asking would be a 404 per segment. */
    @Test
    fun `segments older than the window are not requested`() {
        val mpd = manifest(listOf(numbered), timeShiftBufferDepthMs = 20_000)
        val plan = DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = 2, nowMs = 600_000)
        // The window holds 10 segments behind the live edge at 300.
        assertEquals(290L, plan.segments.first().number)
        assertEquals(300L, plan.segments.last().number)
    }

    /** No zero point to count from. Said plainly, rather than guessed at. */
    @Test
    fun `a live number template with no availability start is unschedulable`() {
        val mpd = manifest(listOf(numbered), availabilityStartTimeMs = null)
        val plan = DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = -1, nowMs = 10_000)
        assertTrue(plan.unschedulable)
        assertTrue(plan.segments.isEmpty())
    }

    /** A static numbered template is a catch-up programme: counted off its own duration, from the top. */
    @Test
    fun `a static number template is counted from the start`() {
        val mpd = manifest(listOf(numbered), dynamic = false, mediaPresentationDurationMs = 20_000)
        val plan = DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = -1, nowMs = 0)
        assertEquals(10, plan.segments.size)
        assertEquals(1L, plan.segments.first().number)
        assertEquals(10L, plan.segments.last().number)
    }

    /**
     * **The cap must take from the opposite end for a static window.** A live stream keeps the newest
     * segments so it rejoins the edge after a stall; catch-up must keep the *oldest*, because those
     * are the opening minutes of the programme. Biasing both the same way silently recorded a 2-minute
     * catch-up window starting 72 seconds in, with no error anywhere.
     */
    @Test
    fun `a static window keeps the start of the programme, not the end`() {
        val mpd = manifest(listOf(numbered), dynamic = false, mediaPresentationDurationMs = 120_000)
        val plan = DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = -1, nowMs = 0)
        assertEquals(DashRecordingPlan.MAX_SEGMENTS_PER_CYCLE.toInt(), plan.segments.size)
        assertEquals(1L, plan.segments.first().number)
        assertEquals(24L, plan.segments.last().number)
    }

    /** And the cycle after it continues from there, contiguously, until the window is exhausted. */
    @Test
    fun `a static window is collected contiguously over cycles`() {
        val mpd = manifest(listOf(numbered), dynamic = false, mediaPresentationDurationMs = 120_000)
        val collected = mutableListOf<Long>()
        var last = -1L
        while (true) {
            val plan = DashRecordingPlan.nextSegments(mpd, numbered, last, 0)
            if (plan.segments.isEmpty()) break
            collected += plan.segments.map { it.number }
            last = plan.lastNumber
        }
        assertEquals((1L..60L).toList(), collected)
    }

    @Test
    fun `a static number template with no duration is unschedulable`() {
        val mpd = manifest(listOf(numbered), dynamic = false, mediaPresentationDurationMs = null)
        assertTrue(DashRecordingPlan.nextSegments(mpd, numbered, lastNumber = -1, nowMs = 0).unschedulable)
    }

    // ---- scheduling: enumerated segments ----

    private fun explicit(vararg numbers: Long) = representation(
        "e",
        DashTrackKind.VIDEO,
        1,
        DashSegments.Explicit(numbers.map { DashSegment(it, it * 1000, "e-$it.m4s", 2_000) }),
    )

    /** Live: the timeline may enumerate a deep window, so only the newest is taken to begin with. */
    @Test
    fun `a live timeline starts at its newest segment`() {
        val rep = explicit(10, 11, 12)
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, lastNumber = -1, nowMs = 0)
        assertEquals(listOf(12L), plan.segments.map { it.number })
    }

    /** Catch-up: the whole list *is* the recording. */
    @Test
    fun `a static timeline is taken in full`() {
        val rep = explicit(1, 2, 3)
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep), dynamic = false), rep, lastNumber = -1, nowMs = 0)
        assertEquals(listOf(1L, 2L, 3L), plan.segments.map { it.number })
        assertEquals(3L, plan.lastNumber)
    }

    @Test
    fun `a later timeline cycle takes only what is new`() {
        val rep = explicit(10, 11, 12, 13)
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, lastNumber = 11, nowMs = 0)
        assertEquals(listOf(12L, 13L), plan.segments.map { it.number })
    }

    /**
     * The window scrolled past while we were away. Take it from where it is now, the same rule
     * `recordHls` applies when the media sequence has jumped — asking for 12..40 would be 29 × 404.
     */
    @Test
    fun `a timeline window that has scrolled past is rejoined at its start`() {
        val rep = explicit(41, 42, 43)
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, lastNumber = 11, nowMs = 0)
        assertEquals(listOf(41L, 42L, 43L), plan.segments.map { it.number })
    }

    @Test
    fun `an empty timeline fetches nothing and keeps its place`() {
        val rep = explicit()
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep)), rep, lastNumber = 4, nowMs = 0)
        assertTrue(plan.segments.isEmpty())
        assertEquals(4L, plan.lastNumber)
    }

    /** Segments keep the URL the manifest gave them — the timeline already substituted `$Time$`. */
    @Test
    fun `timeline segments keep their own urls`() {
        val rep = explicit(7)
        val plan = DashRecordingPlan.nextSegments(manifest(listOf(rep), dynamic = false), rep, lastNumber = -1, nowMs = 0)
        assertEquals("e-7.m4s", plan.segments.single().url)
    }

    // ---- a single self-contained file ----

    @Test
    fun `a single file is fetched once and never again`() {
        val rep = representation("s", DashTrackKind.MUXED, 1, DashSegments.Single("https://cdn.example/film.mp4"))
        val mpd = manifest(listOf(rep), dynamic = false)
        val first = DashRecordingPlan.nextSegments(mpd, rep, lastNumber = -1, nowMs = 0)
        assertEquals(listOf("https://cdn.example/film.mp4"), first.segments.map { it.url })
        assertEquals(0L, first.lastNumber)

        val second = DashRecordingPlan.nextSegments(mpd, rep, lastNumber = first.lastNumber, nowMs = 1_000)
        assertTrue(second.segments.isEmpty())
    }

    // ---- polling ----

    @Test
    fun `the provider's update period is preferred`() {
        assertEquals(
            2_000L,
            DashRecordingPlan.pollIntervalMs(manifest(listOf(numbered), minimumUpdatePeriodMs = 2_000), numbered),
        )
    }

    /** `PT0S` is common and means "as often as you like", not "spin". Half a segment instead. */
    @Test
    fun `a zero update period falls back to half a segment`() {
        assertEquals(
            1_000L,
            DashRecordingPlan.pollIntervalMs(manifest(listOf(numbered), minimumUpdatePeriodMs = 0), numbered),
        )
    }

    @Test
    fun `the poll interval is clamped at both ends`() {
        assertEquals(
            1_000L,
            DashRecordingPlan.pollIntervalMs(manifest(listOf(numbered), minimumUpdatePeriodMs = 10), numbered),
        )
        assertEquals(
            10_000L,
            DashRecordingPlan.pollIntervalMs(manifest(listOf(numbered), minimumUpdatePeriodMs = 600_000), numbered),
        )
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
