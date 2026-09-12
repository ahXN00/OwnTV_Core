package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * The rules of D5, D10 and D11 as arithmetic. Every case here is one the owner can hit with a real
 * account: a one-connection panel, a panel that never said, and a recording holding a stream while
 * tiles ask for more.
 */
class ConnectionBudgetTest {

    private fun source(max: Int) = SourceEntity(
        id = 1,
        name = "Playlist",
        type = SourceType.XTREAM,
        url = "http://example.test",
        maxConnections = max,
    )

    private fun grant(
        max: Int,
        watching: Int = 0,
        recording: Int = 0,
        purpose: StreamPurpose = StreamPurpose.WATCHING,
        reserve: Boolean = true,
    ) = connectionBudget(source(max), OpenStreams(watching, recording), purpose, reserve)

    private fun refusal(grant: StreamGrant) = (grant as StreamGrant.Refused).reason

    // --- maxConnections = 0, the playlist never said ---------------------------------------------

    @Test
    fun `an unknown limit allows everything`() {
        assertEquals(StreamGrant.Allowed, grant(0, watching = 4))
        assertEquals(StreamGrant.Allowed, grant(0, recording = 4, purpose = StreamPurpose.RECORDING))
    }

    @Test
    fun `no playlist at all allows everything`() {
        assertEquals(
            StreamGrant.Allowed,
            connectionBudget(null, OpenStreams(watching = 3), StreamPurpose.WATCHING),
        )
    }

    // --- maxConnections = 1 -----------------------------------------------------------------------

    @Test
    fun `one connection means the first tile tunes and the second explains`() {
        assertEquals(StreamGrant.Allowed, grant(1))
        assertEquals(StreamRefusal.SINGLE_CONNECTION, refusal(grant(1, watching = 1)))
    }

    @Test
    fun `one connection held by a recording names the recording`() {
        val refused = grant(1, recording = 1) as StreamGrant.Refused
        assertEquals(StreamRefusal.SINGLE_CONNECTION, refused.reason)
        assertEquals(1, refused.open.recording)
    }

    @Test
    fun `a recording may take the only connection even while something is watching`() {
        // D9: the recording wins and the picture stops, because a live programme does not come back.
        assertEquals(StreamGrant.Allowed, grant(1, watching = 1, purpose = StreamPurpose.RECORDING))
    }

    @Test
    fun `but only one recording at a time on a one-connection account`() {
        assertEquals(
            StreamRefusal.SINGLE_CONNECTION,
            refusal(grant(1, recording = 1, purpose = StreamPurpose.RECORDING)),
        )
    }

    // --- maxConnections = 2, 3, 4 -----------------------------------------------------------------

    @Test
    fun `two connections fill two tiles and refuse the third`() {
        assertEquals(StreamGrant.Allowed, grant(2, watching = 1))
        assertEquals(StreamRefusal.ALL_IN_USE, refusal(grant(2, watching = 2)))
    }

    @Test
    fun `three connections fill three tiles and refuse the fourth`() {
        assertEquals(StreamGrant.Allowed, grant(3, watching = 2))
        assertEquals(StreamRefusal.ALL_IN_USE, refusal(grant(3, watching = 3)))
    }

    @Test
    fun `four connections fill all four tiles`() {
        assertEquals(StreamGrant.Allowed, grant(4, watching = 3))
        assertEquals(StreamRefusal.ALL_IN_USE, refusal(grant(4, watching = 4)))
    }

    @Test
    fun `more connections than tiles is never a problem`() {
        assertEquals(StreamGrant.Allowed, grant(8, watching = 4))
    }

    // --- tiles and recordings share the budget (D11) ------------------------------------------------

    @Test
    fun `a recording counts against the tiles`() {
        assertEquals(StreamGrant.Allowed, grant(2, recording = 1))
        assertEquals(StreamRefusal.ALL_IN_USE, refusal(grant(2, watching = 1, recording = 1)))
    }

    @Test
    fun `the refusal carries what is recording so the sentence can say so`() {
        val refused = grant(3, watching = 1, recording = 2) as StreamGrant.Refused
        assertEquals(3, refused.maxConnections)
        assertEquals(2, refused.open.recording)
        assertEquals(3, refused.open.total)
    }

    // --- the reserve rule (D10) ---------------------------------------------------------------------

    @Test
    fun `recordings leave one connection for watching by default`() {
        // Three connections, two already recording: the third is kept back.
        assertEquals(StreamGrant.Allowed, grant(3, recording = 1, purpose = StreamPurpose.RECORDING))
        assertEquals(
            StreamRefusal.RESERVED_FOR_WATCHING,
            refusal(grant(3, recording = 2, purpose = StreamPurpose.RECORDING)),
        )
    }

    @Test
    fun `giving up the reserve lets every connection record`() {
        assertEquals(
            StreamGrant.Allowed,
            grant(3, recording = 2, purpose = StreamPurpose.RECORDING, reserve = false),
        )
        assertEquals(
            StreamRefusal.ALL_IN_USE,
            refusal(grant(3, recording = 3, purpose = StreamPurpose.RECORDING, reserve = false)),
        )
    }

    @Test
    fun `the reserve does not stop a tile from using the last connection`() {
        // The reserve exists for watching; watching is exactly what this is.
        assertEquals(StreamGrant.Allowed, grant(3, recording = 2, purpose = StreamPurpose.WATCHING))
    }

    // --- the registry both sides count against ------------------------------------------------------

    @Test
    fun `the registry counts per playlist and forgets a released claim`() {
        val registry = OpenStreamRegistry()
        val tile = registry.claim(sourceId = 1, purpose = StreamPurpose.WATCHING)
        registry.claim(sourceId = 1, purpose = StreamPurpose.RECORDING)
        registry.claim(sourceId = 2, purpose = StreamPurpose.WATCHING)

        assertEquals(OpenStreams(watching = 1, recording = 1), registry.openOn(1))
        assertEquals(OpenStreams(watching = 1, recording = 0), registry.openOn(2))
        assertEquals(OpenStreams(), registry.openOn(3))

        registry.release(tile)
        assertEquals(OpenStreams(watching = 0, recording = 1), registry.openOn(1))
        assertTrue(registry.claims.value.size == 2)
    }

    @Test
    fun `a second playlist has its own budget`() {
        val registry = OpenStreamRegistry()
        registry.claim(sourceId = 1, purpose = StreamPurpose.WATCHING)
        // One connection each, one tile each: the second playlist's tile is fine.
        assertEquals(StreamRefusal.SINGLE_CONNECTION, refusal(
            connectionBudget(source(1), registry.openOn(1), StreamPurpose.WATCHING),
        ))
        assertEquals(
            StreamGrant.Allowed,
            connectionBudget(source(1), registry.openOn(2), StreamPurpose.WATCHING),
        )
    }
}
