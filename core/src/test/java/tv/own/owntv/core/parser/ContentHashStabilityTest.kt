package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.network.HttpClient

/**
 * AC10: a switch never changes the URLs sync stores, so content hashes stay put and the guide's
 * identity (the exact string [tv.own.owntv.core.epg.EpgSourceStore.ensure] keys on, which
 * [tv.own.owntv.core.repository.EpgRepository.guideUrl] returns for an Xtream source) stays put.
 * A recording or a download compares the played URL through [HostPool.canonical]. Sync tries at
 * most [HostSelector.MAX_API_HOSTS] servers inside [HostSelector.API_BUDGET_MS].
 */
class ContentHashStabilityTest {

    private val primary = "http://a.example.top:8080"
    private val backup = "http://b.example.top:8080"
    private val s = SourceEntity(
        id = 9, name = "X", type = SourceType.XTREAM, url = primary, username = "u", password = "p",
        backupUrls = HostPool.encode(listOf(backup)),
    )
    private val selector = HostSelector { _, _ -> }
    private val client = XtreamClient(HttpClient(OkHttpClient()), selector)

    @Test
    fun `a switch leaves stored urls, content hashes and the guide identity unchanged`() = runBlocking {
        val live = client.liveUrl(s, "11")
        val movie = client.movieUrl(s, "22", "mp4")
        val episode = client.seriesEpisodeUrl(s, "33", "mkv")
        val guide = client.xmltvUrl(s)
        val channel = ChannelEntity(sourceId = s.id, name = "News", streamUrl = live, remoteId = "11")
        val film = MovieEntity(sourceId = s.id, name = "Film", streamUrl = movie, remoteId = "22")
        val channelHash = channel.computeContentHash()
        val movieHash = film.computeContentHash()

        selector.confirm(s, backup)

        assertEquals(live, client.liveUrl(s, "11"))
        assertEquals(movie, client.movieUrl(s, "22", "mp4"))
        assertEquals(episode, client.seriesEpisodeUrl(s, "33", "mkv"))
        assertEquals(guide, client.xmltvUrl(s))
        assertEquals(channelHash, channel.copy(streamUrl = client.liveUrl(s, "11")).computeContentHash())
        assertEquals(movieHash, film.copy(streamUrl = client.movieUrl(s, "22", "mp4")).computeContentHash())

        val played = client.requestUrl(s, live)
        assertNotEquals(live, played)
        assertEquals(live, HostPool.canonical(played, s))
        assertEquals(movie, HostPool.canonical(client.requestUrl(s, movie), s))
        assertEquals(episode, HostPool.canonical(client.requestUrl(s, episode), s))
        assertEquals(guide, HostPool.canonical(client.requestUrl(s, guide), s))
    }

    @Test
    fun `sync tries at most three hosts inside sixty seconds`() {
        assertEquals(3, HostSelector.MAX_API_HOSTS)
        assertEquals(60_000L, HostSelector.API_BUDGET_MS)
        val backups = (1..10).map { "http://h$it.example.top" }
        val big = s.copy(backupUrls = HostPool.encode(backups))
        val tried = selector.apiHosts(big)
        assertTrue(tried.size <= HostSelector.MAX_API_HOSTS)
        assertEquals(HostSelector.MAX_API_HOSTS, tried.size)
    }
}
