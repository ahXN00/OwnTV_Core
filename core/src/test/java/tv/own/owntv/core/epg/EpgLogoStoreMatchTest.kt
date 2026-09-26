package tv.own.owntv.core.epg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.EpgMatchResolver
import tv.own.owntv.core.database.entity.ChannelEntity

/** A channel matched in Match EPG shows the logo of the guide channel it is matched to. */
class EpgLogoStoreMatchTest {

    private val channel = ChannelEntity(
        sourceId = 1, name = "BBC One HD", logoUrl = "http://provider/bbc.png",
        streamUrl = "http://x/1", epgChannelId = "bbc1.wrong", remoteId = "101",
    )

    @After
    fun reset() {
        EpgLogoStore.icons = emptyMap()
        EpgLogoStore.matches = EpgMatchResolver(emptyMap())
    }

    @Test
    fun `a matched channel takes the matched guide channel's logo`() {
        EpgLogoStore.icons = mapOf("bbc1.uk" to "http://guide/bbc1.png", "bbc1.wrong" to "http://guide/wrong.png")
        EpgLogoStore.matches = EpgMatchResolver(mapOf(CustomizeKeys.channel(channel) to "bbc1.uk"))
        assertEquals("http://guide/bbc1.png", channel.displayLogoUrl)
    }

    @Test
    fun `an unmatched channel keeps using its own guide id`() {
        EpgLogoStore.icons = mapOf("bbc1.wrong" to "http://guide/wrong.png")
        assertEquals("http://guide/wrong.png", channel.displayLogoUrl)
    }

    @Test
    fun `with no guide logos on, the playlist logo stays`() {
        EpgLogoStore.matches = EpgMatchResolver(mapOf(CustomizeKeys.channel(channel) to "bbc1.uk"))
        assertEquals("http://provider/bbc.png", channel.displayLogoUrl)
    }
}
