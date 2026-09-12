package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.live.OpenStreams
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.StreamRefusal
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.RecordingFailure
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.model.SourceType

/**
 * The decisions a recording makes, tested without a network, a disk or a clock.
 *
 * The four things Phase A3 has to get right — the space floor, the stop-and-keep rule, the
 * concurrency/reserve rule and clash → MISSED — are all here.
 */
class RecordingRulesTest {

    // --- The 500 MB floor (D2 / D8) ---

    @Test
    fun `there is room while free space is above the reserve`() {
        assertTrue(RecordingRules.hasSpace(RecordingRules.RESERVE_BYTES + 1))
        assertTrue(RecordingRules.hasSpace(20L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the reserve itself is not room`() {
        assertFalse(RecordingRules.hasSpace(RecordingRules.RESERVE_BYTES))
        assertFalse(RecordingRules.hasSpace(0))
        // A volume that reports nonsense is treated as full rather than written to.
        assertFalse(RecordingRules.hasSpace(-1))
    }

    @Test
    fun `the reserve is 500 MB, not zero`() {
        assertEquals(500L * 1024 * 1024, RecordingRules.RESERVE_BYTES)
    }

    // --- The stop condition: the only thing that ends a recording ---

    @Test
    fun `a recording runs until its window closes, and not a moment past it`() {
        val stop = 1_757_700_000_000L
        assertFalse(RecordingRules.shouldStop(stop - 1, stop))
        assertTrue(RecordingRules.shouldStop(stop, stop))
        assertTrue(RecordingRules.shouldStop(stop + 1, stop))
    }

    @Test
    fun `a recording well past its stop time is overrunning and must be cut off`() {
        val stop = 1_757_700_000_000L
        // Between the two: the pump is expected to notice on its own between reads.
        assertFalse(RecordingRules.isOverrunning(stop, stop))
        assertFalse(RecordingRules.isOverrunning(stop + 5_000, stop))
        // Past the grace: the socket has gone quiet without closing, so nothing will notice.
        assertTrue(RecordingRules.isOverrunning(stop + 60_000, stop))
    }

    @Test
    fun `the overrun backstop never fires before the stop condition does`() {
        val stop = 1_757_700_000_000L
        listOf(-1_000L, 0L, 1L, 14_999L, 15_000L, 100_000L).forEach { offset ->
            val now = stop + offset
            if (RecordingRules.isOverrunning(now, stop)) {
                assertTrue("overrun implies stop at offset $offset", RecordingRules.shouldStop(now, stop))
            }
        }
    }

    // --- How a recording is closed out ---

    @Test
    fun `any bytes at all is a completed recording`() {
        // A ts file is playable to whatever point it reached, so a programme cut short is still a
        // recording the user can watch — marking it failed would hide a file that is on disk.
        assertEquals(
            RecordingStatus.COMPLETED to RecordingFailure.NONE,
            RecordingRules.outcomeOf(bytes = 1, failure = RecordingFailure.NONE),
        )
        assertEquals(
            RecordingStatus.COMPLETED to RecordingFailure.NONE,
            RecordingRules.outcomeOf(bytes = 900_000_000, failure = RecordingFailure.NETWORK),
        )
    }

    @Test
    fun `running out of room stays a failure however much was captured`() {
        // The user has to be told why it is short, and "completed" would not tell them.
        assertEquals(
            RecordingStatus.FAILED to RecordingFailure.NO_SPACE,
            RecordingRules.outcomeOf(bytes = 4_000_000_000L, failure = RecordingFailure.NO_SPACE),
        )
    }

    @Test
    fun `nothing written is a failure, and it keeps the reason`() {
        assertEquals(
            RecordingStatus.FAILED to RecordingFailure.NO_CONNECTION,
            RecordingRules.outcomeOf(bytes = 0, failure = RecordingFailure.NO_CONNECTION),
        )
        // Nothing written and nothing said: the channel never played.
        assertEquals(
            RecordingStatus.FAILED to RecordingFailure.STREAM_UNAVAILABLE,
            RecordingRules.outcomeOf(bytes = 0, failure = RecordingFailure.NONE),
        )
    }

    // --- Clash and refusal → MISSED (D10) ---

    @Test
    fun `a refusal becomes a reason the user can read`() {
        assertEquals(RecordingFailure.NO_CONNECTION, RecordingRules.missedBecause(StreamRefusal.SINGLE_CONNECTION))
        assertEquals(RecordingFailure.CLASH, RecordingRules.missedBecause(StreamRefusal.ALL_IN_USE))
        assertEquals(RecordingFailure.NO_CONNECTION, RecordingRules.missedBecause(StreamRefusal.RESERVED_FOR_WATCHING))
    }

    // --- The concurrency and reserve rule, as the engine asks it (D10 / D11) ---

    private fun source(maxConnections: Int) = SourceEntity(
        id = 1,
        name = "Playlist",
        type = SourceType.XTREAM,
        url = "http://example.invalid",
        maxConnections = maxConnections,
    )

    private fun grant(max: Int, watching: Int, recording: Int, reserve: Boolean) = connectionBudget(
        source = source(max),
        open = OpenStreams(watching = watching, recording = recording),
        purpose = StreamPurpose.RECORDING,
        reserveOneForWatching = reserve,
    )

    @Test
    fun `a one-connection account records, because a live programme is gone forever`() {
        // D9: the recording wins the only stream there is. The picture can come back; the programme
        // cannot. The reserve is meaningless here and must not block it.
        assertEquals(StreamGrant.Allowed, grant(max = 1, watching = 0, recording = 0, reserve = true))
    }

    @Test
    fun `a one-connection account records only one thing at a time`() {
        val refused = grant(max = 1, watching = 0, recording = 1, reserve = true) as StreamGrant.Refused
        assertEquals(StreamRefusal.SINGLE_CONNECTION, refused.reason)
        assertEquals(RecordingFailure.NO_CONNECTION, RecordingRules.missedBecause(refused.reason))
    }

    @Test
    fun `with the reserve on, recordings leave one connection for watching`() {
        // Three connections: two may record, the third is kept back.
        assertEquals(StreamGrant.Allowed, grant(max = 3, watching = 0, recording = 1, reserve = true))
        val refused = grant(max = 3, watching = 0, recording = 2, reserve = true) as StreamGrant.Refused
        assertEquals(StreamRefusal.RESERVED_FOR_WATCHING, refused.reason)
    }

    @Test
    fun `giving up the reserve lets recordings use every connection`() {
        assertEquals(StreamGrant.Allowed, grant(max = 3, watching = 0, recording = 2, reserve = false))
        val refused = grant(max = 3, watching = 0, recording = 3, reserve = false) as StreamGrant.Refused
        // Every connection is recording — that is the clash the Recordings screen names.
        assertEquals(StreamRefusal.ALL_IN_USE, refused.reason)
        assertEquals(RecordingFailure.CLASH, RecordingRules.missedBecause(refused.reason))
    }

    @Test
    fun `a playlist that never said how many connections it allows is never refused`() {
        // 0 means unknown. Let the provider answer with its own 458 rather than inventing a limit.
        assertEquals(StreamGrant.Allowed, grant(max = 0, watching = 4, recording = 4, reserve = true))
    }

    @Test
    fun `watching does not consume the recording budget, only other recordings do`() {
        // Two connections, one already watching: a recording may still take the other, because the
        // reserve is about leaving *a* stream free, and the user already has theirs.
        assertEquals(StreamGrant.Allowed, grant(max = 2, watching = 1, recording = 0, reserve = true))
    }

    // --- Reconnect backoff ---

    @Test
    fun `reconnect backoff grows and then stops growing`() {
        assertTrue(RecordingRules.retryDelayMs(1) < RecordingRules.retryDelayMs(3))
        // A channel down for an hour is still retried on a sane interval, not once more at bedtime.
        assertEquals(RecordingRules.retryDelayMs(1_000), RecordingRules.retryDelayMs(10_000))
        assertTrue(RecordingRules.retryDelayMs(10_000) <= 30_000L)
    }

    // --- File naming ---

    @Test
    fun `a recording is named so two showings cannot collide`() {
        val first = RecordingRules.fileName("BBC One", "The News", 1_757_700_000_000L)
        val second = RecordingRules.fileName("BBC One", "The News", 1_757_786_400_000L)
        assertTrue(first.startsWith("BBC One - The News - "))
        assertTrue(first.endsWith(".ts"))
        assertTrue("two showings must not share a file name", first != second)
    }

    @Test
    fun `a title that cannot be a file name is sanitised, not rejected`() {
        val name = RecordingRules.fileName("Sky/Sports", "Marvel's What If…?", 1_757_700_000_000L)
        assertTrue(name.endsWith(".ts"))
        assertFalse("a path separator would silently write into another folder", name.contains('/'))
        assertFalse(name.contains('\\'))
    }

    @Test
    fun `a recording with no title still gets a file name`() {
        assertTrue(RecordingRules.fileName("", "", 1_757_700_000_000L).endsWith(".ts"))
    }
}
