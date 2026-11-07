package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.PlaylistAutoRefresh

/** Which saved sources the one-time re-sort syncs. See `M3uVodResort`. */
class M3uVodResortTest {

    private val films = listOf("http://example.test/live/1.ts", "http://example.test/movie/101.mp4")
    private val channels = listOf("http://example.test/live/1.ts", "http://example.test/live/2.m3u8")

    private fun needs(type: SourceType, refresh: PlaylistAutoRefresh, urls: List<String>) =
        runBlocking { M3uVodResort.needsResort(type, refresh) { urls } }

    @Test
    fun m3uWithAFilmInLiveTvIsResorted() {
        assertTrue(needs(SourceType.M3U, PlaylistAutoRefresh.STARTUP, films))
    }

    @Test
    fun m3uWithOnlyChannelsIsLeftAlone() {
        assertFalse(needs(SourceType.M3U, PlaylistAutoRefresh.STARTUP, channels))
    }

    /** Auto refresh Off: the user decides when the playlist downloads. */
    @Test
    fun autoRefreshOffIsLeftAlone() {
        assertFalse(needs(SourceType.M3U, PlaylistAutoRefresh.OFF, films))
    }

    @Test
    fun otherSourceTypesAreLeftAloneWithoutReadingUrls() {
        val read = runBlocking { M3uVodResort.needsResort(SourceType.XTREAM, PlaylistAutoRefresh.STARTUP) { error("read") } }
        assertFalse(read)
    }
}
