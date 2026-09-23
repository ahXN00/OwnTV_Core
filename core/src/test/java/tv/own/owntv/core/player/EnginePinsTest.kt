package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One pin lookup for both apps, and a restore that never leaves a key in both lists. */
class EnginePinsTest {

    @Test
    fun `stable key wins, in either direction`() {
        assertEquals(ForceMpvStore.PinLookup(onMpv = true, legacy = false), ForceMpvStore.pinOf("1:LIVE:a", "http://x", setOf("1:LIVE:a"), emptySet()))
        assertEquals(ForceMpvStore.PinLookup(onMpv = false, legacy = false), ForceMpvStore.pinOf("1:LIVE:a", "http://x", emptySet(), setOf("1:LIVE:a")))
    }

    @Test
    fun `a legacy URL pin is found and flagged for migration`() {
        assertEquals(ForceMpvStore.PinLookup(onMpv = false, legacy = true), ForceMpvStore.pinOf("1:LIVE:a", "http://x", emptySet(), setOf("http://x")))
        assertEquals(ForceMpvStore.PinLookup(onMpv = true, legacy = true), ForceMpvStore.pinOf(null, "http://x", setOf("http://x"), emptySet()))
    }

    @Test
    fun `an unpinned channel follows the setting`() {
        assertNull(ForceMpvStore.pinOf("1:LIVE:a", "http://x", setOf("2:LIVE:b"), setOf("3:LIVE:c")))
    }

    @Test
    fun `an incoming pin wins and leaves the opposite list`() {
        val (mpv, exo) = ForceMpvStore.mergePins(setOf("a"), setOf("b"), incomingMpv = listOf("b"), incomingExo = listOf("a"))
        assertEquals(setOf("b"), mpv)
        assertEquals(setOf("a"), exo)
        assertTrue((mpv intersect exo).isEmpty())
    }

    @Test
    fun `a key in both incoming lists is dropped and the device keeps its own`() {
        val (mpv, exo) = ForceMpvStore.mergePins(setOf("k"), emptySet(), incomingMpv = listOf("k", " n "), incomingExo = listOf("k", ""))
        assertEquals(setOf("k", "n"), mpv)
        assertEquals(emptySet<String>(), exo)
    }
}
