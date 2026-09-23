package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDecodeStoreTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun `a lesson younger than fourteen days is kept`() {
        val stored = setOf("panel.example:8080|${now - 13 * day}")
        assertEquals(mapOf("panel.example:8080" to now - 13 * day), ArchiveDecodeStore.active(stored, now))
    }

    @Test
    fun `a lesson fourteen days old has expired`() {
        assertTrue(ArchiveDecodeStore.active(setOf("panel.example:8080|${now - 14 * day}"), now).isEmpty())
    }

    @Test
    fun `an entry from before expiry existed counts as learned now`() {
        // Written by an older build as a bare host: forgetting it at upgrade would cost a failed open.
        assertEquals(mapOf("old.panel:80" to now), ArchiveDecodeStore.active(setOf("old.panel:80"), now))
    }

    @Test
    fun `a malformed entry is dropped`() {
        assertTrue(ArchiveDecodeStore.active(setOf("panel:80|notanumber", "|123"), now).isEmpty())
    }

    @Test
    fun `encode and active round-trip`() {
        val entries = mapOf("a:1" to now - day, "b:2" to now)
        assertEquals(entries, ArchiveDecodeStore.active(ArchiveDecodeStore.encode(entries), now))
    }
}
