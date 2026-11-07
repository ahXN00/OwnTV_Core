package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/** A name given in Customize is the one shown; without one, the provider's. */
class DisplayNameTest {

    private val channel = ChannelEntity(sourceId = 3, name = "SKY SPORTS F1 FHD", streamUrl = "http://x/1", remoteId = "101")
    private val movie = MovieEntity(sourceId = 3, name = "Film.2024.1080p", streamUrl = "http://x/2", remoteId = "202")
    private val series = SeriesEntity(sourceId = 3, name = "show_raw", remoteId = "303")

    @Test
    fun `a renamed item shows its new name`() {
        val cust = SectionCustomizations(
            itemNames = mapOf(
                CustomizeKeys.channel(channel) to "Sky Sports F1",
                CustomizeKeys.movie(movie) to "Film",
                CustomizeKeys.series(series) to "Show",
            ),
        )
        assertEquals("Sky Sports F1", cust.displayName(channel))
        assertEquals("Film", cust.displayName(movie))
        assertEquals("Show", cust.displayName(series))
    }

    @Test
    fun `an item never renamed keeps the provider's name`() {
        val cust = SectionCustomizations()
        assertEquals("SKY SPORTS F1 FHD", cust.displayName(channel))
        assertEquals("Film.2024.1080p", cust.displayName(movie))
        assertEquals("show_raw", cust.displayName(series))
    }
}
