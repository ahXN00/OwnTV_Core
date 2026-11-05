package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.backup.sourceMatchKey
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.stalker.StalkerClient

/**
 * A portal with no series section lists its shows in the film catalogue, flagged `is_series`. The
 * film walk keeps the films and sets the shows aside; a shows-only walk keeps the shows.
 */
class StalkerVodSplitTest {

    private fun item(id: String, series: Boolean) = StalkerClient.VodItem(
        id = id, name = id, cmd = null, poster = null, year = null, plot = null, rating = null,
        categoryId = null, addedAt = null, isSeries = series,
    )

    @Test
    fun theFilmWalkKeepsFilmsAndSetsShowsAside() {
        val films = StalkerSyncer.VodSplit(keepSeries = false)
        assertTrue(films.keeps(item("film", series = false)))
        assertFalse(films.keeps(item("show", series = true)))
    }

    @Test
    fun aShowsOnlyWalkKeepsShows() {
        val shows = StalkerSyncer.VodSplit(keepSeries = true)
        assertTrue(shows.keeps(item("show", series = true)))
        assertFalse(shows.keeps(item("film", series = false)))
    }

    @Test
    fun theFlagIsReadInEveryShapePortalsSendIt() {
        assertTrue(StalkerClient.isFlagSet("1"))
        assertTrue(StalkerClient.isFlagSet(" true "))
        assertFalse(StalkerClient.isFlagSet("0"))
        assertFalse(StalkerClient.isFlagSet(""))
        assertFalse(StalkerClient.isFlagSet(null))
    }

    @Test
    fun aStalkerLoginIsNotPartOfItsRestoreIdentity() {
        val stalker = SourceType.STALKER.name
        assertEquals(
            "adding a login must not turn the playlist into another one on restore",
            sourceMatchKey(stalker, "http://portal/c/", null, "00:1A:79:AA:BB:CC"),
            sourceMatchKey(stalker, "http://portal/c/", "anna", "001a79aabbcc"),
        )
        assertTrue(
            "Xtream still tells accounts apart by username",
            sourceMatchKey(SourceType.XTREAM.name, "http://p", "a") != sourceMatchKey(SourceType.XTREAM.name, "http://p", "b"),
        )
    }
}
