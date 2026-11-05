package tv.own.owntv.core.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.content.AirDate
import tv.own.owntv.core.network.HttpClient

/**
 * `get_series_info` bodies that carry an episode's air date more than once. The reader used to skip
 * the second date key unread once the first had matched, and then failed the whole show with
 * "Expected a name but was STRING". Runs on the real reader (see the test-only android.util.JsonReader).
 */
class XtreamSeriesInfoParseTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun parse(json: String) = client.readSeriesInfoEpisodes(json.byteInputStream())

    private fun day(text: String) = AirDate.parse(text)

    @Test
    fun `dispatcharr body with air_date and release_date inside info parses every episode`() {
        val body = javaClass.getResourceAsStream("/xtream/series_info_dispatcharr.json")!!
        val episodes = client.readSeriesInfoEpisodes(body)
        assertEquals(listOf("788", "789", "798", "799"), episodes.map { it.id })
        assertEquals(listOf(1, 1, 2, 2), episodes.map { it.seasonNumber })
        assertEquals(listOf(1, 2, 1, 2), episodes.map { it.episodeNumber })
        assertEquals(
            listOf(day("2018-08-17"), day("2018-08-17"), day("2021-01-15"), day("2021-01-15")),
            episodes.map { it.airDateMs },
        )
    }

    @Test
    fun `direct date then info keeps the direct date and reads past info`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":1,"release_date":"2020-01-01","info":{"air_date":"2021-02-02"},"title":"A"},
                {"id":"2","episode_num":2,"title":"B"}
            ]}}""",
        )
        assertEquals(listOf("1", "2"), episodes.map { it.id })
        assertEquals(day("2020-01-01"), episodes[0].airDateMs)
        assertEquals("A", episodes[0].title)
    }

    @Test
    fun `info then direct date keeps the info date`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","info":{"release_date":"2021-02-02"},"air_date":"2020-01-01","episode_num":3}
            ]}}""",
        )
        assertEquals(1, episodes.size)
        assertEquals(day("2021-02-02"), episodes[0].airDateMs)
        assertEquals(3, episodes[0].episodeNumber)
    }

    @Test
    fun `two direct dates keep the first and read the second`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","air_date":"2020-01-01","releasedate":"2022-03-03","episode_num":4,"title":"A"}
            ]}}""",
        )
        assertEquals(1, episodes.size)
        assertEquals(day("2020-01-01"), episodes[0].airDateMs)
        assertEquals(4, episodes[0].episodeNumber)
        assertEquals("A", episodes[0].title)
    }

    @Test
    fun `a first date that does not parse lets the second one in`() {
        val episodes = parse(
            """{"episodes":{"1":[{"id":"1","air_date":"0000-00-00","release_date":"2022-03-03"}]}}""",
        )
        assertEquals(day("2022-03-03"), episodes.single().airDateMs)
    }

    @Test
    fun `a fractional or oversized episode number reads as none instead of failing the show`() {
        val episodes = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":1.5},
                {"id":"2","episode_num":99999999999},
                {"id":"3","episode_num":"7.0"},
                {"id":"4","episode_num":8.0}
            ]}}""",
        )
        assertEquals(listOf("1", "2", "3", "4"), episodes.map { it.id })
        assertEquals(listOf(0, 0, 7, 8), episodes.map { it.episodeNumber })
    }
}
