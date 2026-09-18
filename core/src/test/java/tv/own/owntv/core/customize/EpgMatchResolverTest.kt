package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * A manual EPG match must survive its playlist being deleted and added back.
 *
 * Every channel returns under a new source id after a re-import, so the stored key
 * `"<sourceId>:<remoteId>"` stops resolving and every match goes quiet at once. These cases pin the
 * three tiers, and — just as importantly — the cases where the resolver must decline to guess.
 */
class EpgMatchResolverTest {

    @Test
    fun `the exact key always wins`() {
        val resolver = EpgMatchResolver(
            mapOf("7:bbc1" to "bbc.one.uk", "9:bbc1" to "bbc.one.hd"),
        )

        // Both tiers could answer, and they disagree — but tier 1 is never in doubt.
        assertEquals("bbc.one.uk", resolver.epgIdFor(channel(sourceId = 7, remoteId = "bbc1")))
        assertEquals("bbc.one.hd", resolver.epgIdFor(channel(sourceId = 9, remoteId = "bbc1")))
    }

    @Test
    fun `a match survives the playlist being deleted and re-added`() {
        // The match was stored under source 7; the re-imported playlist is source 42.
        val resolver = EpgMatchResolver(mapOf("7:bbc1" to "bbc.one.uk"))

        assertEquals("bbc.one.uk", resolver.epgIdFor(channel(sourceId = 42, remoteId = "bbc1")))
    }

    @Test
    fun `a channel with no provider id is found by its name`() {
        // An M3U channel with no id is keyed by its name, so that is the only thing left to match on.
        val resolver = EpgMatchResolver(mapOf("7:Sky Sports F1" to "sky.f1.uk"))

        assertEquals(
            "sky.f1.uk",
            resolver.epgIdFor(channel(sourceId = 42, remoteId = null, name = "SKY SPORTS F1 HD")),
        )
    }

    @Test
    fun `an ambiguous provider id is never guessed`() {
        // Two old playlists matched the same provider id to different guide channels. Guessing would
        // put a wrong guide on a channel, which is worse than showing none.
        val resolver = EpgMatchResolver(
            mapOf("7:bbc1" to "bbc.one.uk", "9:bbc1" to "bbc.one.hd"),
        )

        assertNull(resolver.epgIdFor(channel(sourceId = 42, remoteId = "bbc1")))
    }

    @Test
    fun `duplicate entries that agree are not ambiguous`() {
        // The same channel matched under two playlists, to the same guide channel. Nothing to resolve.
        val resolver = EpgMatchResolver(
            mapOf("7:bbc1" to "bbc.one.uk", "9:bbc1" to "bbc.one.uk"),
        )

        assertEquals("bbc.one.uk", resolver.epgIdFor(channel(sourceId = 42, remoteId = "bbc1")))
    }

    @Test
    fun `an unmatched channel stays unmatched`() {
        val resolver = EpgMatchResolver(mapOf("7:bbc1" to "bbc.one.uk"))

        assertNull(resolver.epgIdFor(channel(sourceId = 42, remoteId = "itv1", name = "ITV1")))
    }

    @Test
    fun `no stored matches resolves nothing`() {
        assertNull(EpgMatchResolver(emptyMap()).epgIdFor(channel(sourceId = 1, remoteId = "bbc1")))
    }

    @Test
    fun `the resolver on a snapshot is the same instance every time`() {
        // The guide asks this once per channel per load. Rebuilding the maps each time would make a
        // five-thousand-channel lineup rebuild them five thousand times.
        val cust = SectionCustomizations(epgMatches = mapOf("7:bbc1" to "bbc.one.uk"))

        assertEquals(cust.epgMatchResolver, cust.epgMatchResolver)
        assert(cust.epgMatchResolver === cust.epgMatchResolver)
    }

    private fun channel(
        sourceId: Long,
        remoteId: String?,
        name: String = "Channel",
    ) = ChannelEntity(
        sourceId = sourceId,
        remoteId = remoteId,
        name = name,
        streamUrl = "http://example.invalid/stream",
    )
}
