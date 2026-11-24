package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.skip.SkipKind
import tv.own.owntv.core.skip.SkipMode
import tv.own.owntv.core.skip.SkipSegment

class SkipPlannerTest {

    private val duration = 3_000_000L
    private val credits = SkipSegment(SkipKind.CREDITS, 2_900_000, duration)
    private val intro = SkipSegment(SkipKind.INTRO, 436_000, 536_000)

    private val nextOnAutoplay = SkipPlanner.Situation(nextFollows = true, autoPlayNext = true, stopsAtItemEnd = false)
    private val nextAutoplayOff = SkipPlanner.Situation(nextFollows = true, autoPlayNext = false, stopsAtItemEnd = false)
    private val lastOrFilm = SkipPlanner.Situation(nextFollows = false, autoPlayNext = true, stopsAtItemEnd = false)
    private val sleepTimerEnd = SkipPlanner.Situation(nextFollows = true, autoPlayNext = true, stopsAtItemEnd = true)

    private val todays = UpNext(
        cardAtMs = duration - OwnTVPlayer.END_TOLERANCE_MS - SkipPlanner.CARD_LEAD_MS,
        advanceAtMs = duration - OwnTVPlayer.END_TOLERANCE_MS,
        notice = false,
        coreAdvances = false,
    )

    // --- The end-credits table, row by row ---

    @Test
    fun `next episode with autoplay - button mode shows the card at the credits with a 10 s countdown`() {
        assertEquals(
            UpNext(2_900_000, 2_910_000, notice = false, coreAdvances = true),
            SkipPlanner.upNext(credits, duration, SkipMode.BUTTON, nextOnAutoplay),
        )
        assertNull(SkipPlanner.action(credits, isEndCredits = true, SkipMode.BUTTON, nextOnAutoplay))
    }

    @Test
    fun `next episode with autoplay - automatic mode moves on after a 3 s notice`() {
        assertEquals(
            UpNext(2_900_000, 2_903_000, notice = true, coreAdvances = true),
            SkipPlanner.upNext(credits, duration, SkipMode.AUTO, nextOnAutoplay),
        )
        assertNull(SkipPlanner.action(credits, isEndCredits = true, SkipMode.AUTO, nextOnAutoplay))
    }

    @Test
    fun `autoplay off - both modes give a Next episode button and nothing moves by itself`() {
        for (mode in listOf(SkipMode.BUTTON, SkipMode.AUTO)) {
            assertEquals(SkipAction.NEXT_EPISODE, SkipPlanner.action(credits, true, mode, nextAutoplayOff))
            assertEquals(todays, SkipPlanner.upNext(credits, duration, mode, nextAutoplayOff))
        }
    }

    @Test
    fun `a film, a last episode or the sleep timer's end - Skip credits, never automatic`() {
        for (s in listOf(lastOrFilm, sleepTimerEnd)) {
            for (mode in listOf(SkipMode.BUTTON, SkipMode.AUTO)) {
                assertEquals(SkipAction.SKIP_CREDITS, SkipPlanner.action(credits, true, mode, s))
                assertFalse(SkipPlanner.upNext(credits, duration, mode, s).coreAdvances)
            }
        }
    }

    @Test
    fun `credits off or unknown - today's card and advance`() {
        assertEquals(todays, SkipPlanner.upNext(credits, duration, SkipMode.OFF, nextOnAutoplay))
        assertNull(SkipPlanner.action(credits, true, SkipMode.OFF, nextOnAutoplay))
        assertEquals(todays, SkipPlanner.upNext(null, duration, SkipMode.BUTTON, nextOnAutoplay))
    }

    // --- Other parts ---

    @Test
    fun `middle credits and every other part skip past themselves`() {
        val middle = SkipSegment(SkipKind.CREDITS, 2_000_000, 2_100_000)
        assertEquals(SkipAction.SKIP_CREDITS, SkipPlanner.action(middle, false, SkipMode.BUTTON, nextOnAutoplay))
        assertEquals(SkipAction.SKIP_INTRO, SkipPlanner.action(intro, false, SkipMode.AUTO, nextOnAutoplay))
        assertNull(SkipPlanner.action(intro, false, SkipMode.OFF, nextOnAutoplay))
    }

    @Test
    fun `an automatic skip fires only when the playhead plays into the part`() {
        // Played into it from just before.
        assertTrue(SkipPlanner.playedInto(previousMs = 435_500, positionMs = 436_200, segment = intro))
        // Opened at (or just before) the part: an intro from 0:00.
        assertTrue(SkipPlanner.playedInto(previousMs = null, positionMs = 0, segment = SkipSegment(SkipKind.INTRO, 0, 60_000)))
        // A seek or a resume that lands inside it.
        assertFalse(SkipPlanner.playedInto(previousMs = 100_000, positionMs = 450_000, segment = intro))
        assertFalse(SkipPlanner.playedInto(previousMs = null, positionMs = 450_000, segment = intro))
        // Seeking back into it from after it.
        assertFalse(SkipPlanner.playedInto(previousMs = 900_000, positionMs = 440_000, segment = intro))
    }

    @Test
    fun `the next episode starts only when the playhead plays past the moment`() {
        assertTrue(SkipPlanner.playedPast(previousMs = 2_909_500, positionMs = 2_910_200, atMs = 2_910_000))
        // A seek that lands past it: the viewer chose to watch the credits.
        assertFalse(SkipPlanner.playedPast(previousMs = 2_000_000, positionMs = 2_950_000, atMs = 2_910_000))
        assertFalse(SkipPlanner.playedPast(previousMs = null, positionMs = 2_950_000, atMs = 2_910_000))
        // Already past it.
        assertFalse(SkipPlanner.playedPast(previousMs = 2_920_000, positionMs = 2_921_000, atMs = 2_910_000))
    }

    @Test
    fun `leaving during the end credits saves the item as finished`() {
        assertEquals(duration, SkipPlanner.savedPosition(2_950_000, duration, credits))
        assertEquals(1_000_000, SkipPlanner.savedPosition(1_000_000, duration, credits))
        assertEquals(2_950_000, SkipPlanner.savedPosition(2_950_000, duration, null))
    }
}
