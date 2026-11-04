package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.player.FrozenPictureLadder.Rung

class FrozenPictureLadderTest {

    @Test
    fun `a frozen picture escalates reconnect, fresh decoder and surface, other engine`() {
        assertEquals(Rung.RECONNECT, FrozenPictureLadder.rungFor(1))
        assertEquals(Rung.FRESH_DECODER, FrozenPictureLadder.rungFor(2))
        assertEquals(Rung.OTHER_ENGINE, FrozenPictureLadder.rungFor(3))
    }

    @Test
    fun `further freezes stay on the other engine`() {
        assertEquals(Rung.OTHER_ENGINE, FrozenPictureLadder.rungFor(7))
    }
}
