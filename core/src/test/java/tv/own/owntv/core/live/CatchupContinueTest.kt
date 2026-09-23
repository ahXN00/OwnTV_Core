package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three outcomes of a finished catch-up programme. Pinned because two of them look alike from the
 * guide's point of view and only one of them plays: a programme that has finished airing has a
 * complete archive, while the one currently on the air does not, and asking the provider for it would
 * end again within seconds and drop the viewer straight back here.
 */
class CatchupContinueTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `a programme that has finished airing is played from the archive`() {
        val start = now - 2 * hour
        val stop = now - hour
        assertEquals(
            CatchupContinue.Next.Programme(start, stop),
            CatchupContinue.decide(start, stop, now),
        )
    }

    @Test
    fun `catching up with the programme currently on the air hands over to live`() {
        assertEquals(
            CatchupContinue.Next.Live,
            CatchupContinue.decide(now - 10 * 60_000, now + 50 * 60_000, now),
        )
    }

    /** The instant it ends is the instant its archive is complete — the boundary belongs to the archive. */
    @Test
    fun `a programme ending exactly now is played rather than handed to live`() {
        assertEquals(
            CatchupContinue.Next.Programme(now - hour, now),
            CatchupContinue.decide(now - hour, now, now),
        )
    }

    @Test
    fun `no guide beyond this point stops`() {
        assertEquals(CatchupContinue.Next.Stop, CatchupContinue.decide(null, null, now))
    }

    /** A guide entry that has not started yet is not "the next programme" — there is nothing to play. */
    @Test
    fun `an entry that has not started yet stops`() {
        assertEquals(
            CatchupContinue.Next.Stop,
            CatchupContinue.decide(now + hour, now + 2 * hour, now),
        )
    }
}
