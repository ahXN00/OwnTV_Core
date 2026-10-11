package tv.own.owntv.core.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Live rewind sequencing — the half of the feature that decides *where* in the archive the user is,
 * with no player, no database and no view model, exactly as [CatchupJumps] tests the offsets it offers.
 *
 * What is pinned here is what the rewind gets wrong when it breaks in the field: a rapid burst of
 * button presses loading the archive more than once, a jump stacking on top of the previous one
 * instead of replacing it, an offset that outlives the stream it was counting against, and the
 * "behind live" figure surviving an error screen.
 *
 * Time is injected, so a "second" costs a millisecond here.
 */
class LiveTimeshiftTest {

    private val channel = ChannelEntity(
        sourceId = 1, categoryId = null, name = "Sports 1", streamUrl = "http://x/1.ts", remoteId = "1",
        catchup = true, catchupDays = 2,
    )
    private val noArchive = channel.copy(catchup = false)

    /** A stream that is playing, at a position the test can move. */
    private class FakePlayback(
        override var positionMs: Long = 0,
        override var hasError: Boolean = false,
        override var hasActiveStream: Boolean = true,
    ) : LiveTimeshift.Playback

    private class Harness(
        val playback: FakePlayback = FakePlayback(),
        val loadSucceeds: Boolean = true,
    ) {
        val scope = CoroutineScope(Dispatchers.Default)
        var now = 1_000_000_000L
        val loads = mutableListOf<Pair<Long, Int>>() // startMs to offsetSec
        var liveEdgeRequests = 0

        val timeshift = LiveTimeshift(
            scope = scope,
            playback = playback,
            loadArchive = { _, startMs, offsetSec ->
                loads += startMs to offsetSec
                loadSucceeds
            },
            onLiveEdge = { liveEdgeRequests++ },
            nowMs = { now },
            coalesceMs = 20,
            tickMs = 10,
        )

        /** Wait until [condition] holds, or fail the test. Real time, but only milliseconds of it. */
        fun await(what: String, condition: () -> Boolean) = runBlocking {
            repeat(200) {
                if (condition()) return@runBlocking
                delay(5)
            }
            throw AssertionError("timed out waiting for $what")
        }

        fun stop() = scope.cancel()
    }

    @Test
    fun `a channel without an archive cannot be rewound`() {
        val h = Harness()
        h.timeshift.beginAt(noArchive, 600)
        h.timeshift.scrub(noArchive, 30)
        assertNull(h.timeshift.offsetSec.value)
        assertFalse(h.timeshift.isRewound)
        assertEquals(emptyList<Int>(), h.timeshift.jumpOptions(noArchive))
        h.stop()
    }

    @Test
    fun `a burst of presses loads the archive once, at the final point`() {
        val h = Harness()
        repeat(5) { h.timeshift.scrub(channel, 30) }
        // The counter follows every press; the first one lands on the archive's one-minute minimum.
        assertEquals(180, h.timeshift.offsetSec.value)
        h.await("the coalesced load") { h.loads.isNotEmpty() }
        runBlocking { delay(40) }
        assertEquals(1, h.loads.size)
        assertEquals(180, h.loads.single().second)
        assertEquals(h.now - 180_000L, h.loads.single().first)
        h.stop()
    }

    @Test
    fun `a jump aims at an offset rather than stacking on the previous one`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 900)
        h.timeshift.beginAt(channel, 1_800)
        assertEquals(1_800, h.timeshift.offsetSec.value)
        h.await("the load") { h.loads.isNotEmpty() }
        assertEquals(1_800, h.loads.last().second)
        h.stop()
    }

    @Test
    fun `a jump is clamped to the depth of the archive`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 30 * 24 * 3600) // a month back, on a 2-day archive
        assertEquals(2 * 24 * 3600, h.timeshift.offsetSec.value)
        h.stop()
    }

    @Test
    fun `a short rewind never asks the archive for the live edge itself`() {
        val h = Harness()
        h.timeshift.scrub(channel, 30)
        h.await("the load") { h.loads.isNotEmpty() }
        assertEquals(CatchupJumps.MIN_OFFSET_SEC, h.loads.single().second)
        assertEquals(h.now - CatchupJumps.MIN_OFFSET_SEC * 1000L, h.loads.single().first)
        assertEquals(CatchupJumps.MIN_OFFSET_SEC, h.timeshift.offsetSec.value) // the counter says where it really is
        h.stop()
    }

    @Test
    fun `a step forward inside the last minute goes live rather than back to the minimum`() {
        val h = Harness()
        h.timeshift.beginAt(channel, CatchupJumps.MIN_OFFSET_SEC)
        h.timeshift.scrub(channel, -30)
        assertEquals(1, h.liveEdgeRequests)
        h.stop()
    }

    @Test
    fun `scrubbing forward to the live edge hands back instead of loading an archive`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 60)
        h.timeshift.scrub(channel, -60)
        assertEquals(1, h.liveEdgeRequests)
        assertEquals(60, h.timeshift.offsetSec.value) // the caller decides; nothing is cleared here
        h.stop()
    }

    @Test
    fun `the counters follow playback, and the offset shrinks as the archive plays forward`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 600)
        h.await("the archive to load") { h.loads.isNotEmpty() }
        val startMs = h.loads.single().first
        h.playback.positionMs = 300_000 // five minutes into a ten-minute rewind
        h.await("the counters") { h.timeshift.offsetSec.value == 300 }
        assertEquals(startMs + 300_000, h.timeshift.watchingWallMs.value)
        h.stop()
    }

    @Test
    fun `an archive that fails ends the rewind rather than counting against an error screen`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 600)
        h.await("the archive to load") { h.loads.isNotEmpty() }
        h.playback.hasError = true
        h.await("the rewind to end") { !h.timeshift.isRewound }
        assertNull(h.timeshift.watchingWallMs.value)
        h.stop()
    }

    @Test
    fun `a stream that goes away stops the counters but keeps the offset for a reload`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 600)
        h.await("the archive to load") { h.loads.isNotEmpty() }
        h.playback.hasActiveStream = false
        runBlocking { delay(60) }
        assertTrue(h.timeshift.isRewound)
        h.stop()
    }

    /**
     * An archive that never opened must not leave the rewind standing. The offset used to survive a
     * failed load, so the HUD reported "10 minutes behind live" over a picture that had never left the
     * live edge, [LiveTimeshift.isRewound] stayed true (hiding the live engine toggle), and entering
     * from the browse list named a channel that was never tuned. Failure now hands back to live.
     */
    @Test
    fun `a failed load ends the rewind and returns to live`() {
        val h = Harness(loadSucceeds = false)
        h.timeshift.beginAt(channel, 600)
        h.await("the attempted load") { h.loads.isNotEmpty() }
        h.playback.positionMs = 300_000
        h.await("the return to live") { h.liveEdgeRequests == 1 }
        runBlocking { delay(60) }
        assertNull(h.timeshift.watchingWallMs.value)
        assertNull(h.timeshift.offsetSec.value)
        assertFalse(h.timeshift.isRewound)
        assertEquals(1, h.liveEdgeRequests) // once — not once per tick
        h.stop()
    }

    @Test
    fun `a guide catch-up programme runs the watching clock without a rewind offset`() {
        val h = Harness()
        val airedAt = 1_700_000_000_000L
        h.timeshift.followArchiveFrom(airedAt)
        h.await("the clock") { h.timeshift.watchingWallMs.value == airedAt }
        assertNull(h.timeshift.offsetSec.value) // a fixed programme is not the live rewind
        h.playback.positionMs = 60_000
        h.await("the clock to advance") { h.timeshift.watchingWallMs.value == airedAt + 60_000 }
        assertNull(h.timeshift.offsetSec.value)
        h.stop()
    }

    @Test
    fun `clearing drops the offset, the clock and any pending load`() {
        val h = Harness()
        h.timeshift.beginAt(channel, 600)
        h.timeshift.clear()
        runBlocking { delay(60) }
        assertNull(h.timeshift.offsetSec.value)
        assertNull(h.timeshift.watchingWallMs.value)
        assertEquals(emptyList<Pair<Long, Int>>(), h.loads) // the coalesced load never fired
        h.stop()
    }
}
