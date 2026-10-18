package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.model.SourceType

/** How the cleanup lands on a synced channel row: what changes, what is kept, what re-syncs. */
class ChannelNameCleanupSyncTest {

    private fun source(clean: Boolean, strip: String? = null) = SourceEntity(
        id = 7, name = "Test", type = SourceType.M3U, url = "https://example.com/list.m3u",
        cleanChannelNames = clean, channelNameStrip = strip,
    )

    private fun channel(name: String) = ChannelEntity(
        sourceId = 7, name = name, streamUrl = "https://example.com/1.ts", remoteId = "key-1",
    )

    @Test
    fun `option off leaves the row exactly as the provider sent it`() {
        val raw = channel("UK: News One HD")
        assertNull(ChannelNameCleanup.forSource(source(clean = false)))
        assertSame(raw, ChannelNameCleanup.apply(source(clean = false), raw))
    }

    @Test
    fun `option on cleans the name and keeps the provider's beside it`() {
        val cleaned = ChannelNameCleanup.apply(source(clean = true), channel("UK: News One HD"))
        assertEquals("News One", cleaned.name)
        assertEquals("UK: News One HD", cleaned.providerName)
        assertEquals("UK: News One HD", cleaned.matchName)
        // Everything keyed on the channel stays put: favorites, history and renames use these.
        assertEquals("key-1", cleaned.remoteId)
        assertEquals(CustomizeKeys.channel(channel("UK: News One HD")), CustomizeKeys.channel(cleaned))
    }

    @Test
    fun `a name the rules do not touch stores no provider name`() {
        val raw = channel("News One")
        val synced = ChannelNameCleanup.apply(source(clean = true), raw)
        assertSame(raw, synced)
        assertNull(synced.providerName)
        assertEquals("News One", synced.matchName)
    }

    @Test
    fun `the playlist's custom words apply`() {
        val cleaned = ChannelNameCleanup.apply(source(clean = true, strip = "VIP"), channel("VIP News One"))
        assertEquals("News One", cleaned.name)
    }

    @Test
    fun `switching the option on or off changes the stored hash, so the next sync rewrites the row`() {
        val raw = channel("UK: News One HD")
        val cleaned = ChannelNameCleanup.apply(source(clean = true), raw)
        assertNotEquals(raw.computeContentHash(), cleaned.computeContentHash())
    }

    @Test
    fun `a changed provider name re-syncs even when it cleans to the same name`() {
        val fromUk = ChannelNameCleanup.apply(source(clean = true), channel("UK: News One"))
        val fromUs = ChannelNameCleanup.apply(source(clean = true), channel("US: News One"))
        assertEquals(fromUk.name, fromUs.name)
        assertNotEquals(fromUk.computeContentHash(), fromUs.computeContentHash())
    }

    @Test
    fun `an uncleaned row hashes as it did before the column existed`() {
        // providerName is folded in only when set, so upgrading does not rewrite every channel.
        val raw = channel("News One")
        assertEquals(raw.copy(providerName = null).computeContentHash(), raw.computeContentHash())
        assertEquals(java.util.Objects.hash(7L, null, "News One", null, "https://example.com/1.ts", null, null, "key-1", false, 0, null), raw.computeContentHash())
    }
}
