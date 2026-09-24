package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared player-control order.
 *
 * The point of every test here is the same: the definition has to be *total* and *unambiguous*, or
 * the two apps go on disagreeing while looking like they agree.
 */
class PlayerControlsTest {

    @Test
    fun `the order is total and has no duplicates`() {
        val all = PlayerControl.entries
        assertEquals(all.size, all.distinct().size)
        assertEquals(all.size, all.map { it.name }.distinct().size)
    }

    @Test
    fun `every control has a label`() {
        // A control with no words is one a screen reader cannot announce and a tooltip cannot show.
        PlayerControl.entries.forEach { control ->
            assertTrue("${control.name} has no label", control.labelRes != 0)
        }
    }

    @Test
    fun `every control appears on at least one app`() {
        PlayerControl.entries.forEach { control ->
            assertTrue("${control.name} appears nowhere", control.onTv || control.onMobile)
        }
    }

    @Test
    fun `each app's order is a subsequence of the canonical one`() {
        // This is what makes the two bars read alike: neither app may reorder, only omit.
        val canonical = PlayerControl.entries
        listOf(true, false).forEach { tv ->
            val ordered = PlayerControl.orderFor(tv)
            assertEquals(
                ordered,
                canonical.filter { it in ordered },
            )
        }
    }

    @Test
    fun `the two clusters partition each app's bar, in order`() {
        listOf(true, false).forEach { tv ->
            val media = PlayerControl.clusterFor(tv, ControlCluster.MEDIA)
            val tools = PlayerControl.clusterFor(tv, ControlCluster.TOOLS)
            assertEquals(PlayerControl.orderFor(tv), media + tools)
            assertTrue(media.none { it in tools })
        }
    }

    @Test
    fun `the platform-only controls are the ones H0 measured, and no others`() {
        // Named explicitly so that adding a per-platform control is a deliberate act with a test to
        // change, rather than something that quietly creeps back in.
        assertEquals(
            listOf(PlayerControl.BRIGHTNESS, PlayerControl.CHANNEL_LIST),
            PlayerControl.entries.filter { it.host == ControlHost.MOBILE_ONLY },
        )
        // Nothing is television-only any more: H3 found that the phone has a Go Live pill too, and
        // it was the last entry claiming to be one-sided.
        assertEquals(emptyList<PlayerControl>(), PlayerControl.entries.filter { it.host == ControlHost.TV_ONLY })
    }

    @Test
    fun `speed is on both apps now`() {
        // It was television-only for no reason anybody could name, which is exactly the kind of
        // difference this definition exists to remove.
        assertTrue(PlayerControl.SPEED.onTv)
        assertTrue(PlayerControl.SPEED.onMobile)
    }

    @Test
    fun `brightness never reaches a television`() {
        // A television's brightness is the television's; nothing the app does to its own window
        // would change it.
        assertFalse(PlayerControl.BRIGHTNESS.onTv)
    }

    @Test
    fun `the controls Plan D added are in the shared order, not bolted on per app`() {
        // H4's whole purpose: the alignment has to have held through the features built before it.
        assertTrue(PlayerControl.RECORD.onTv && PlayerControl.RECORD.onMobile)
        assertTrue(PlayerControl.MULTIVIEW.onTv && PlayerControl.MULTIVIEW.onMobile)
    }

    @Test
    fun `report sits last, because it is shown only while Info is open`() {
        // The H1 decision, pinned: both apps follow the television's rule. If Report ever moves out
        // of last place, the rule it depends on has probably been forgotten.
        assertEquals(PlayerControl.REPORT, PlayerControl.entries.last())
        assertEquals(PlayerControl.INFO, PlayerControl.entries[PlayerControl.entries.size - 2])
    }

    @Test
    fun `the order is the television's own, control for control`() {
        // The reference, written out. H2 renders the television's HUD from this list, so if the two
        // ever disagree something moved on a shipped screen — which is exactly what this phase is
        // meant to catch. It caught one already: RECORD and MULTIVIEW were first written into the
        // media cluster here, and the television has always had them among the tools.
        assertEquals(
            listOf(
                PlayerControl.GO_LIVE,
                PlayerControl.VOLUME,
                PlayerControl.SPEED,
                PlayerControl.SUBTITLES,
                PlayerControl.AUDIO,
                PlayerControl.FAVOURITE,
                PlayerControl.CATCH_UP,
                PlayerControl.PREVIOUS_CHANNEL,
                PlayerControl.ENGINE,
                PlayerControl.ASPECT,
                PlayerControl.MINI_PLAYER,
                PlayerControl.AUDIO_ONLY,
                PlayerControl.MULTIVIEW,
                PlayerControl.RECORD,
                PlayerControl.SLEEP_TIMER,
                PlayerControl.INFO,
                PlayerControl.REPORT,
            ),
            PlayerControl.orderFor(tv = true),
        )
    }
}
