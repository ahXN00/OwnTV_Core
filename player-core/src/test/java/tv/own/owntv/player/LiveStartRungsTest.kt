package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.player.PlaybackDefaults
import tv.own.owntv.player.LiveStartRungs.Rung

class LiveStartRungsTest {

    private val none = LiveStartRungs.Input(
        containerUnsupported = false, formatFailure = false, httpStatus = null, decoderFailure = false, decoderExhausted = false,
        identityRefusal = false, activeIsHls = false, activeIsDash = false, responseWasHls = false,
        responseWasDash = false, hlsFromHostLesson = false, redirectedHlsRetryDone = false,
        redirectedDashRetryDone = false, sessionLimitSeen = false, sessionLimitRetryDone = false,
        fallbackUserAgentAvailable = true, decoderRebuildsDone = 0, altFormatRetryDone = false,
        directSourceUseful = false,
    )

    @Test
    fun `a ts endpoint that answered HLS reopens as HLS, once`() {
        val i = none.copy(responseWasHls = true, containerUnsupported = true, formatFailure = true)
        assertEquals(Rung.REDIRECT_HLS, LiveStartRungs.before(i))
        assertNull(LiveStartRungs.before(i.copy(redirectedHlsRetryDone = true)))
        assertNull(LiveStartRungs.before(i.copy(activeIsHls = true)))
    }

    @Test
    fun `a wrong HLS lesson, DASH and the session wait`() {
        assertEquals(Rung.DROP_HOST_HLS_LESSON, LiveStartRungs.before(none.copy(hlsFromHostLesson = true, formatFailure = true)))
        // An offline channel 404s on its manifest whatever the panel serves: not proof the lesson is wrong.
        assertNull(LiveStartRungs.before(none.copy(hlsFromHostLesson = true, formatFailure = true, httpStatus = 404)))
        // Any status, and any failure that is not about the format, leaves the lesson for the ladder.
        for (status in listOf(410, 500)) {
            assertNull(LiveStartRungs.before(none.copy(hlsFromHostLesson = true, formatFailure = true, httpStatus = status)))
        }
        assertNull(LiveStartRungs.before(none.copy(hlsFromHostLesson = true)))
        // A lesson-free load never drops it, whatever failed.
        assertNull(LiveStartRungs.before(none.copy(formatFailure = true)))
        assertEquals(Rung.REDIRECT_DASH, LiveStartRungs.before(none.copy(responseWasDash = true, containerUnsupported = true)))
        assertNull(LiveStartRungs.before(none.copy(responseWasDash = true, containerUnsupported = true, activeIsDash = true)))
        assertEquals(Rung.SESSION_WAIT, LiveStartRungs.before(none.copy(sessionLimitSeen = true)))
        assertNull(LiveStartRungs.before(none.copy(sessionLimitSeen = true, sessionLimitRetryDone = true)))
    }

    @Test
    fun `after the back-off, in order`() {
        assertEquals(Rung.FALLBACK_USER_AGENT, LiveStartRungs.after(none.copy(identityRefusal = true, decoderFailure = true)))
        assertEquals(Rung.DECODER_EXHAUSTED, LiveStartRungs.after(none.copy(decoderExhausted = true, decoderFailure = true)))
        assertEquals(Rung.REBUILD_DECODER, LiveStartRungs.after(none.copy(decoderFailure = true, formatFailure = true)))
        assertEquals(Rung.ALT_FORMAT, LiveStartRungs.after(none.copy(decoderFailure = true, decoderRebuildsDone = 3, formatFailure = true)))
        assertEquals(Rung.DIRECT_SOURCE, LiveStartRungs.after(none.copy(formatFailure = true, altFormatRetryDone = true, directSourceUseful = true)))
        assertEquals(Rung.FAIL, LiveStartRungs.after(none))
    }

    @Test
    fun `a decoder that dies before the first frame is rebuilt three times, waiting longer each time`() {
        val dead = none.copy(decoderFailure = true)
        assertEquals(listOf(500L, 1_500L, 3_000L), (0..2).map { LiveStartRungs.decoderRebuildDelayMs(it) })
        for (done in 0..2) assertEquals(Rung.REBUILD_DECODER, LiveStartRungs.after(dead.copy(decoderRebuildsDone = done)))
        assertNull(LiveStartRungs.decoderRebuildDelayMs(3))
        assertEquals(Rung.FAIL, LiveStartRungs.after(dead.copy(decoderRebuildsDone = 3)))
    }

    @Test
    fun `the rebuilds leave most of the default give-up budget for the other engine`() {
        // An attempt fails about two seconds in (codec start plus the first sample), so the three waits
        // and four attempts must stay inside half of the budget.
        val waits = (0..2).sumOf { LiveStartRungs.decoderRebuildDelayMs(it)!! }
        assertTrue(waits + 4 * 2_000L <= PlaybackDefaults.LIVE_TUNE_BUDGET_SECS * 1_000L / 2)
    }

    @Test
    fun `an open stalled with the video not ready is a decoder failure only after one`() {
        assertTrue(LiveStartRungs.stalledOpenIsDecoder(decoderFailedThisLoad = true, videoReady = false))
        assertFalse(LiveStartRungs.stalledOpenIsDecoder(decoderFailedThisLoad = false, videoReady = false))
        assertFalse(LiveStartRungs.stalledOpenIsDecoder(decoderFailedThisLoad = true, videoReady = true))
        assertFalse(LiveStartRungs.stalledOpenIsDecoder(decoderFailedThisLoad = true, videoReady = null))
    }

    @Test
    fun `no user-agent retry once it is spent or the source sets its own`() {
        assertEquals(Rung.FAIL, LiveStartRungs.after(none.copy(identityRefusal = true, fallbackUserAgentAvailable = false)))
    }
}
