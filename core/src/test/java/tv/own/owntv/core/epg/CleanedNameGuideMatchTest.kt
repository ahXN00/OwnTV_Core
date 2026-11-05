package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.own.owntv.core.customize.ChannelNameCleanup
import tv.own.owntv.core.customize.EpgMatchResolver
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchTier

/**
 * "Clean channel names" must never make the guide worse: everything that matches a channel to a
 * guide (Auto-match, a stored match found by name, the sports "Watch live" filters) reads the
 * provider's own name, not the cleaned one. Names are neutral made-up examples.
 */
class CleanedNameGuideMatchTest {

    private fun synced(raw: String, strip: String? = null, remoteId: String? = "key-1") =
        ChannelNameCleanup.of(strip).applyTo(
            ChannelEntity(sourceId = 42, name = raw, streamUrl = "https://example.com/1.ts", remoteId = remoteId),
        )

    @Test
    fun `auto-match scores the provider's name, so a stripped word cannot pick another guide`() {
        val candidates = listOf(
            EpgMatcher.Candidate("starsportsnews.uk", "Star Sports News"),
            EpgMatcher.Candidate("sportsnews.example", "Sports News"),
        )
        // The user strips "Star" from their names; the guide still calls the channel by it.
        val channel = synced("UK: Star Sports News HD", strip = "Star")
        assertEquals("Sports News", channel.name)

        val viaProviderName = EpgMatcher.bestEpgMatch(channel.matchName, candidates)
        val uncleaned = EpgMatcher.bestEpgMatch("UK: Star Sports News HD", candidates)
        assertEquals("starsportsnews.uk", viaProviderName?.epgChannelId)
        assertEquals(uncleaned, viaProviderName)
        // The cleaned name alone would have chosen the wrong guide channel.
        assertNotEquals("starsportsnews.uk", EpgMatcher.bestEpgMatch(channel.name, candidates)?.epgChannelId)
    }

    @Test
    fun `a stored match found by name still resolves after cleaning`() {
        // A channel with no provider id is matched by name; the match was saved before cleaning.
        val resolver = EpgMatchResolver(mapOf("7:Film Club Backup" to "filmclub.example"))
        val channel = synced("Film Club Backup", strip = "Backup", remoteId = null)
        assertEquals("Film Club", channel.name)
        assertEquals("filmclub.example", resolver.epgIdFor(channel))
    }

    @Test
    fun `sports filters still see the country and quality the cleanup took off`() {
        val channel = synced("UK| Arena Sports 1 FHD")
        assertEquals("Arena Sports 1", channel.name)
        val facets = WatchOption(channel, WatchTier.LIKELY).facets
        assertEquals("UK", facets.country)
        val raw = ChannelEntity(sourceId = 42, name = "UK| Arena Sports 1 FHD", streamUrl = "https://example.com/1.ts")
        assertEquals(WatchOption(raw, WatchTier.LIKELY).facets, facets)
    }
}
