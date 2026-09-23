package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The same two properties [TuneStateTest] pins for the live engine, for the main player's two reset
 * groups — and here the *split* matters as much as the reset: [LoadState] is cleared on every load,
 * [ItemState] only on a genuinely new item, because a retry of the same film that forgot what it had
 * already tried would retry forever.
 */
class PlayerResetStateTest {

    @Test
    fun `a used load state does not compare equal to a fresh one`() {
        val used = LoadState().apply {
            fileLoaded = true
            brokenPtsHits = 4
            lastMpvError = "http: HTTP error 400"
            decodeGuardTripped = true
        }
        assertNotEquals(LoadState(), used)
        assertEquals("resetting is a whole new instance", LoadState(), LoadState())
    }

    @Test
    fun `a used item state does not compare equal to a fresh one`() {
        val used = ItemState().apply {
            autoRetries = 2
            triedAltFormat = true
            altFormatBaseUrl = "http://example.invalid/stream.ts"
        }
        assertNotEquals(ItemState(), used)
        assertEquals(ItemState(), ItemState())
    }

    @Test
    fun `the values a new load carries over are the only ones not cleared`() {
        // loadUrl hands these to the constructor rather than clearing them, because they come from the
        // call's own arguments. Everything else must read as its default on a fresh instance.
        val fresh = LoadState(
            loadStartTime = 1_000L,
            pendingStartPaused = true,
            expectingPlayback = true,
            pendingSeekMs = 42L,
        )
        assertEquals(1_000L, fresh.loadStartTime)
        assertEquals(42L, fresh.pendingSeekMs)
        assertEquals("nothing else is carried", null, fresh.lastMpvError)
        assertEquals("nothing else is carried", false, fresh.fileLoaded)
    }

    @Test
    fun `every field of both states takes part in the reset comparison`() {
        // A property declared in the class body instead of the primary constructor is excluded from a
        // data class's equals, so it would be reset in practice yet invisible to the tests above.
        listOf(LoadState::class.java, ItemState::class.java).forEach { cls ->
            val fields = cls.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
            val components = cls.declaredMethods.count { it.name.startsWith("component") }
            assertEquals(
                "${cls.simpleName} has ${fields.size} fields but only $components in equals() — a field " +
                    "declared outside the primary constructor is not compared when checking a reset",
                fields.size,
                components,
            )
        }
    }
}
