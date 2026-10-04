package tv.own.owntv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.player.LiveLadder.Rung

/**
 * The cross-engine fallback ladder — the sequence a live channel walks when the engine it started on
 * cannot play it. Both playback audits independently named this the single highest-value test in the
 * app: nothing else decides so much with so little visible feedback, and its failure mode (a channel
 * bouncing between engines, or giving up before the combination that works) is invisible in a log.
 *
 * The properties pinned here are the ones the ladder exists to guarantee:
 *  - each rung is spent at most once per tune, so the ladder always terminates;
 *  - the order follows where the tune started, so a pinned engine gets both its formats first;
 *  - HLS rungs are dropped when there is no `.m3u8`/`.ts` distinction to try, or when *that* engine
 *    has already learned this channel's `.m3u8` does not work for it;
 *  - a late failure from a superseded tune cannot advance the ladder of the current one.
 *
 * [LiveStreamQuirks] is process-global session state with no reset, so every test uses its own host.
 */
class LiveLadderTest {

    private fun ladderFor(
        url: String,
        startsOnMpv: Boolean = false,
        hasHls: Boolean = true,
        preference: EnginePreference = EnginePreference.firstOn(startsOnMpv),
    ): LiveLadder = LiveLadder().also { runBlocking { it.arm(url, preference) { hasHls } } }

    @Test
    fun `a tune that started on ExoPlayer tries both ExoPlayer formats before mpv is considered`() {
        val ladder = ladderFor("http://a.test/live/1.ts")
        assertEquals(
            listOf(Rung.EXO_HLS, Rung.EXO_TS, Rung.MPV_HLS, Rung.MPV_TS),
            ladder.plan,
        )
    }

    @Test
    fun `a tune that started on mpv tries both mpv formats before ExoPlayer is considered`() {
        // A channel pinned to mpv, or a panel already caught refusing ExoPlayer's segment URLs, must not
        // be handed back to the engine it was routed away from until mpv has genuinely run out.
        val ladder = ladderFor("http://b.test/live/1.ts", startsOnMpv = true)
        assertEquals(
            listOf(Rung.MPV_HLS, Rung.MPV_TS, Rung.EXO_HLS, Rung.EXO_TS),
            ladder.plan,
        )
    }

    @Test
    fun `an only-ExoPlayer preference keeps both ExoPlayer formats and drops the handover`() {
        // The point of the mode: a user who has established that mpv never works on their box and panel
        // stops paying for its turn — several seconds of black on every unplayable channel — but does
        // NOT lose the `.m3u8` → `.ts` step, which is what rescues most channels in practice.
        val ladder = ladderFor("http://u.test/live/1.ts", preference = EnginePreference.EXO_ONLY)
        assertEquals(listOf(Rung.EXO_HLS, Rung.EXO_TS), ladder.plan)
        assertEquals(Rung.EXO_TS, ladder.advance())
        assertNull(ladder.advance()) // never mpv, however it fails
    }

    @Test
    fun `an only-mpv preference is the same promise mirrored`() {
        val ladder = ladderFor("http://v.test/live/1.ts", preference = EnginePreference.MPV_ONLY)
        assertEquals(listOf(Rung.MPV_HLS, Rung.MPV_TS), ladder.plan)
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertNull(ladder.advance())
    }

    @Test
    fun `an only mode with no HLS alternative is a single rung, and it still terminates`() {
        // Both filters compose: one engine, one format. Nothing left to try is a legitimate outcome —
        // the caller leaves the failure on screen rather than bouncing.
        val ladder = ladderFor("http://w.test/live/1.m3u8", hasHls = false, preference = EnginePreference.EXO_ONLY)
        assertEquals(listOf(Rung.EXO_TS), ladder.plan)
        assertNull(ladder.advance())
    }

    @Test
    fun `a refusal in an only mode cannot escape to the other engine either`() {
        // [advance] prefers the OTHER engine when a panel refused the request, because a handover releases
        // the session a one-session panel was blocking. In an only mode that rung does not exist, so the
        // fallback-of-the-fallback applies: the same engine's other format, then the end.
        val url = "http://x.test/live/1.ts"
        val ladder = ladderFor(url, preference = EnginePreference.MPV_ONLY)
        assertEquals(Rung.MPV_TS, ladder.advance(failureWasAboutFormat = false))
        assertNull(ladder.advance(failureWasAboutFormat = false))
    }

    @Test
    fun `the preference decides both where a tune starts and whether it may hand over`() {
        assertFalse(EnginePreference.EXO_FIRST.startsOnMpv)
        assertTrue(EnginePreference.MPV_FIRST.startsOnMpv)
        assertFalse(EnginePreference.EXO_ONLY.startsOnMpv)
        assertTrue(EnginePreference.MPV_ONLY.startsOnMpv)

        assertTrue(EnginePreference.EXO_FIRST.allowsHandover)
        assertTrue(EnginePreference.MPV_FIRST.allowsHandover)
        assertFalse(EnginePreference.EXO_ONLY.allowsHandover)
        assertFalse(EnginePreference.MPV_ONLY.allowsHandover)

        assertEquals(EnginePreference.MPV_FIRST, EnginePreference.firstOn(onMpv = true))
        assertEquals(EnginePreference.EXO_FIRST, EnginePreference.firstOn(onMpv = false))
        assertEquals(EnginePreference.MPV_ONLY, EnginePreference.onlyOn(onMpv = true))
        assertEquals(EnginePreference.EXO_ONLY, EnginePreference.onlyOn(onMpv = false))
    }

    @Test
    fun `the rung already on screen is spent, so the first advance is a real change`() {
        val ladder = ladderFor("http://c.test/live/1.ts")
        assertTrue(ladder.isSpent(Rung.EXO_HLS))
        assertFalse(ladder.isSpent(Rung.EXO_TS))
        assertEquals(Rung.EXO_TS, ladder.advance())
    }

    @Test
    fun `every rung is spent at most once and the ladder then ends`() {
        // The whole safety property: an ExoPlayer failure handing over to mpv and an mpv failure handing
        // back could otherwise bounce a channel between engines forever.
        val ladder = ladderFor("http://d.test/live/1.ts")
        assertEquals(Rung.EXO_TS, ladder.advance())
        assertEquals(Rung.MPV_HLS, ladder.advance())
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertNull(ladder.advance())
        assertNull(ladder.advance()) // exhausted stays exhausted
    }

    @Test
    fun `the same walk from mpv ends just as finitely`() {
        val ladder = ladderFor("http://e.test/live/1.ts", startsOnMpv = true)
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertEquals(Rung.EXO_HLS, ladder.advance())
        assertEquals(Rung.EXO_TS, ladder.advance())
        assertNull(ladder.advance())
    }

    @Test
    fun `with no HLS alternative the ladder is the plain engine pair it always was`() {
        // "Prefer HLS" off, a Stalker portal cmd, or a URL already `.m3u8`: there is no second format to
        // try, and a rung the tune code would silently turn into the `.ts` one anyway would cost a
        // duplicate attempt before the real next rung.
        val exoFirst = ladderFor("http://f.test/live/1.ts", hasHls = false)
        assertEquals(listOf(Rung.EXO_TS, Rung.MPV_TS), exoFirst.plan)
        assertEquals(Rung.MPV_TS, exoFirst.advance())
        assertNull(exoFirst.advance())

        val mpvFirst = ladderFor("http://g.test/live/1.ts", startsOnMpv = true, hasHls = false)
        assertEquals(listOf(Rung.MPV_TS, Rung.EXO_TS), mpvFirst.plan)
    }

    @Test
    fun `an engine's own HLS lesson drops only that engine's HLS rung`() {
        // Traced on a 4K channel whose `.m3u8` ExoPlayer's audio renderer can never start, while mpv
        // plays that very same manifest. A shared verdict would have skipped the one rung that worked.
        val url = "http://h.test/live/1.ts"
        LiveStreamQuirks.rememberNoHlsVariant(url) // ExoPlayer's verdict only
        assertEquals(
            listOf(Rung.EXO_TS, Rung.MPV_HLS, Rung.MPV_TS),
            ladderFor(url).plan,
        )

        val mpvUrl = "http://i.test/live/1.ts"
        LiveStreamQuirks.rememberNoHlsVariantMpv(mpvUrl)
        assertEquals(
            listOf(Rung.EXO_HLS, Rung.EXO_TS, Rung.MPV_TS),
            ladderFor(mpvUrl).plan,
        )
    }

    @Test
    fun `dropping to an engine's TS rung records that engine's format lesson`() {
        // So the NEXT tune of this channel skips the dead rung instead of paying for it again.
        val url = "http://j.test/live/1.ts"
        val ladder = ladderFor(url)
        assertFalse(LiveStreamQuirks.lacksHlsVariant(url))
        assertEquals(Rung.EXO_TS, ladder.advance())
        assertTrue(LiveStreamQuirks.lacksHlsVariant(url))
        assertFalse(LiveStreamQuirks.lacksHlsVariantMpv(url)) // mpv has not had its turn yet

        assertEquals(Rung.MPV_HLS, ladder.advance())
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertTrue(LiveStreamQuirks.lacksHlsVariantMpv(url))
    }

    @Test
    fun `no lesson is recorded when there was no HLS rung to blame`() {
        // Nothing was swapped, so the `.ts` rung proves nothing about a `.m3u8` that was never tried —
        // recording one here would poison the next tune of a channel whose HLS is fine.
        val url = "http://k.test/live/1.ts"
        val ladder = ladderFor(url, hasHls = false)
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertFalse(LiveStreamQuirks.lacksHlsVariant(url))
        assertFalse(LiveStreamQuirks.lacksHlsVariantMpv(url))
    }

    @Test
    fun `a refused request teaches the ladder nothing about format`() {
        // The regression this exists for: a one-session panel (HTTP 458) refused ten channels opened by
        // scrolling the list. Three of them stepped onto their `.ts` rung during the lockout, each wrote
        // "this channel has no HLS", and the app stopped asking for HLS at all. A refusal is not evidence
        // about a stream's format — a different file extension cannot answer an account-level refusal.
        val url = "http://o.test/live/1.ts"
        val ladder = ladderFor(url)
        ladder.advance(failureWasAboutFormat = false)
        ladder.advance(failureWasAboutFormat = false)
        ladder.advance(failureWasAboutFormat = false)
        assertFalse(LiveStreamQuirks.lacksHlsVariant(url))
        assertFalse(LiveStreamQuirks.lacksHlsVariantMpv(url))
        // The next tune therefore still gets both HLS rungs — nothing was written off.
        runBlocking { ladder.arm(url, EnginePreference.EXO_FIRST) { true } }
        assertEquals(listOf(Rung.EXO_HLS, Rung.EXO_TS, Rung.MPV_HLS, Rung.MPV_TS), ladder.plan)
    }

    @Test
    fun `a refusal changes engine instead of extension, because the handoff is what frees the session`() {
        // Owner-observed on a one-session panel: the same engine's other extension is a guaranteed-dead
        // attempt against an account-level refusal, but handing over to the other engine takes a few
        // seconds AND releases the session the previous engine was holding — which is often exactly what
        // the channel needed to open. So the ladder skips the format rung and keeps the engine change.
        val ladder = ladderFor("http://v.test/live/1.ts")
        assertEquals(Rung.MPV_HLS, ladder.advance(failureWasAboutFormat = false))

        val fromMpv = ladderFor("http://w.test/live/1.ts", startsOnMpv = true)
        assertEquals(Rung.EXO_HLS, fromMpv.advance(failureWasAboutFormat = false))
    }

    @Test
    fun `a skipped format rung is not spent, so a later format failure can still use it`() {
        // Skipping is "not worth trying against a refusal", not "proven dead" — the rung stays available.
        val ladder = ladderFor("http://x.test/live/1.ts")
        assertEquals(Rung.MPV_HLS, ladder.advance(failureWasAboutFormat = false))
        assertFalse(ladder.isSpent(Rung.EXO_TS))
        assertEquals(Rung.EXO_TS, ladder.advance(failureWasAboutFormat = true))
    }

    @Test
    fun `when only same-engine rungs are left, a refusal still takes one rather than giving up`() {
        val ladder = ladderFor("http://t.test/live/1.ts", hasHls = false) // plan: EXO_TS, MPV_TS
        assertEquals(Rung.MPV_TS, ladder.advance(failureWasAboutFormat = false))
        // Nothing on the other engine remains; the ladder is simply exhausted, not stuck.
        assertNull(ladder.advance(failureWasAboutFormat = false))
    }

    @Test
    fun `a format failure still teaches, so the guard has not disabled the learning`() {
        val url = "http://p.test/live/1.ts"
        val ladder = ladderFor(url)
        assertEquals(Rung.EXO_TS, ladder.advance(failureWasAboutFormat = true))
        assertTrue(LiveStreamQuirks.lacksHlsVariant(url))
    }

    @Test
    fun `a newer tune owns the ladder, so a late failure from the old one cannot advance it`() {
        val first = "http://l.test/live/1.ts"
        val second = "http://l.test/live/2.ts"
        val ladder = LiveLadder()
        runBlocking { ladder.arm(first, EnginePreference.EXO_FIRST) { true } }
        assertTrue(ladder.owns(first))

        runBlocking { ladder.arm(second, EnginePreference.EXO_FIRST) { true } }
        assertFalse(ladder.owns(first))
        assertTrue(ladder.owns(second))
    }

    @Test
    fun `re-arming forgets the rungs already climbed — a new tune is a new chance`() {
        // Including for a channel that ended the last tune on its final rung: the provider may simply
        // have been having a bad minute. No HLS alternative here, so nothing is learned along the way
        // and the second tune is identical to the first.
        val url = "http://m.test/live/1.ts"
        val ladder = ladderFor(url, hasHls = false)
        assertEquals(Rung.MPV_TS, ladder.advance())
        assertNull(ladder.advance())

        runBlocking { ladder.arm(url, EnginePreference.EXO_FIRST) { false } }
        assertFalse(ladder.isSpent(Rung.MPV_TS))
        assertEquals(Rung.MPV_TS, ladder.advance())
    }

    @Test
    fun `what the ladder learned about a format outlives the tune, unlike the spent rungs`() {
        // The two lifetimes are different on purpose: a fresh tune is a fresh chance at each *rung*, but
        // an `.m3u8` both engines already proved unplayable this session is not worth paying for again.
        val url = "http://n.test/live/1.ts"
        val ladder = ladderFor(url)
        // Climb to the top. An empty block rather than a bare `Unit` body: the compiler reads that
        // one as an expression whose value is discarded and says so on every build.
        while (ladder.advance() != null) { /* spend every rung */ }

        runBlocking { ladder.arm(url, EnginePreference.EXO_FIRST) { true } }
        assertEquals(listOf(Rung.EXO_TS, Rung.MPV_TS), ladder.plan)
        assertFalse(ladder.isSpent(Rung.MPV_TS))
        assertEquals(Rung.MPV_TS, ladder.advance())
    }

    // ---- the whole-tune budget -------------------------------------------------------------------

    private fun budgetedLadder(url: String, budgetMs: Long = 30_000L, armedAtMs: Long = 1_000L) =
        LiveLadder().also { runBlocking { it.arm(url, EnginePreference.EXO_FIRST, budgetMs, armedAtMs) { true } } }

    @Test
    fun `a tune that has spent its budget climbs no further, however many rungs are left`() {
        // The audit item: four rungs at 20-30s each is ~95s of black screen on a removed channel, because
        // every rung had a timeout and nothing bounded their sum.
        val ladder = budgetedLadder("http://q.test/live/1.ts")
        assertEquals(Rung.EXO_TS, ladder.advance(nowMs = 20_000L)) // inside the budget
        assertTrue(ladder.expired(31_000L))
        assertNull(ladder.advance(nowMs = 31_000L))
        // Two rungs were still unspent — the budget, not exhaustion, is what stopped it.
        assertFalse(ladder.isSpent(Rung.MPV_HLS))
        assertFalse(ladder.isSpent(Rung.MPV_TS))
    }

    @Test
    fun `a restarted deadline lets a spent tune climb to its next rung`() {
        // A channel that opened and later proved unplayable on its engine is not "out of time".
        val ladder = budgetedLadder("http://restart.test/live/1.ts")
        assertEquals(Rung.EXO_TS, ladder.advance(nowMs = 20_000L))
        assertNull(ladder.advance(nowMs = 300_000L))
        ladder.restartDeadline(nowMs = 300_000L, budgetMs = 30_000L)
        assertEquals(Rung.MPV_HLS, ladder.advance(nowMs = 300_000L))
        assertTrue(ladder.expired(331_000L))
    }

    @Test
    fun `a tune inside its budget is completely unaffected`() {
        val ladder = budgetedLadder("http://r.test/live/1.ts")
        assertFalse(ladder.expired(1_000L))
        assertEquals(Rung.EXO_TS, ladder.advance(nowMs = 5_000L))
        assertEquals(Rung.MPV_HLS, ladder.advance(nowMs = 10_000L))
        assertEquals(Rung.MPV_TS, ladder.advance(nowMs = 20_000L))
        assertNull(ladder.advance(nowMs = 25_000L)) // exhausted on its own terms, not expired
        assertFalse(ladder.expired(25_000L))
    }

    @Test
    fun `the budget is bounded but the ladder still terminates without one`() {
        // NO_BUDGET is the old behaviour verbatim, and it is what every other test in this file uses:
        // the finiteness property has to come from the rungs themselves, never from the clock.
        val ladder = LiveLadder().also {
            runBlocking { it.arm("http://s.test/live/1.ts", EnginePreference.EXO_FIRST, LiveLadder.NO_BUDGET, 0L) { true } }
        }
        assertFalse(ladder.expired(Long.MAX_VALUE))
        repeat(3) { assertTrue(ladder.advance(nowMs = Long.MAX_VALUE) != null) }
        assertNull(ladder.advance(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `a wait the app asked for is given back, so a provider back-off cannot expire the tune`() {
        // HTTP 429 + Retry-After is the panel naming the second the channel frees up, and the engine is
        // counting it down behind the spinner. Charging that to the budget would abandon a good channel.
        val ladder = budgetedLadder("http://y.test/live/1.ts")
        ladder.postponeDeadline(60_000L)
        assertFalse(ladder.expired(31_000L))
        assertEquals(Rung.EXO_TS, ladder.advance(nowMs = 31_000L))
        assertTrue(ladder.expired(91_001L)) // still bounded, just later
    }

    @Test
    fun `re-arming starts a fresh budget — a new tune is a new chance at the clock too`() {
        val url = "http://z.test/live/1.ts"
        val ladder = budgetedLadder(url)
        assertTrue(ladder.expired(40_000L))
        runBlocking { ladder.arm(url, EnginePreference.EXO_FIRST, 30_000L, 40_000L) { true } }
        assertFalse(ladder.expired(40_000L))
        assertEquals(Rung.EXO_TS, ladder.advance(nowMs = 50_000L))
    }

    @Test
    fun `every rung carries an engine and format label for the playback log`() {
        val ladder = LiveLadder()
        assertEquals("ExoPlayer + HLS", ladder.label(Rung.EXO_HLS))
        assertEquals("ExoPlayer + TS", ladder.label(Rung.EXO_TS))
        assertEquals("mpv + HLS", ladder.label(Rung.MPV_HLS))
        assertEquals("mpv + TS", ladder.label(Rung.MPV_TS))
    }

    @Test
    fun `the rung table itself is the four engine-format combinations`() {
        assertEquals(4, Rung.entries.size)
        assertTrue(Rung.EXO_HLS.isHls && !Rung.EXO_HLS.onMpv)
        assertTrue(!Rung.EXO_TS.isHls && !Rung.EXO_TS.onMpv)
        assertTrue(Rung.MPV_HLS.isHls && Rung.MPV_HLS.onMpv)
        assertTrue(!Rung.MPV_TS.isHls && Rung.MPV_TS.onMpv)
    }
}
