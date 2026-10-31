package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.stalker.StalkerClient

/**
 * A portal with no `type=series` module files its shows in the VOD module, flagged `is_series` (#61):
 * the films phase and the shows phase read the same VOD pages and each keeps its own kind. The totals
 * it reports back are what the page-1 sweep plans the rest of the walk from.
 */
class StalkerVodSeriesSplitTest {

    private fun item(id: String, series: Boolean) = StalkerClient.VodItem(
        id = id, name = id, cmd = null, poster = null, year = null, plot = null, rating = null,
        categoryId = null, addedAt = null, isSeries = series,
    )

    private fun page(total: Int, vararg items: StalkerClient.VodItem) =
        StalkerClient.Page(totalItems = total, maxPageItems = 14, items = items.toList())

    @Test
    fun `a page of only the wanted kind passes through untouched`() {
        val films = page(750, item("1", false), item("2", false))
        assertSame(films, StalkerSyncer.keepingKind(films, series = false, page = 1))
    }

    @Test
    fun `each phase keeps only its own kind`() {
        val mixed = page(750, item("film", false), item("show", true))
        assertEquals(listOf("film"), StalkerSyncer.keepingKind(mixed, series = false, page = 3).items.map { it.id })
        assertEquals(listOf("show"), StalkerSyncer.keepingKind(mixed, series = true, page = 3).items.map { it.id })
    }

    @Test
    fun `a category of the other kind reports empty, so its pages are neither walked nor planned`() {
        val shows = page(750, item("a", true), item("b", true))
        val asFilms = StalkerSyncer.keepingKind(shows, series = false, page = 1)
        assertEquals(0, asFilms.totalItems)
        assertEquals(0, asFilms.items.size)
    }

    @Test
    fun `a mixed category keeps the portal's total, so the rest of it is still walked`() {
        val mixed = page(750, item("film", false), item("show", true))
        assertEquals(750, StalkerSyncer.keepingKind(mixed, series = true, page = 1).totalItems)
    }

    @Test
    fun `a later page with none of the kind keeps the total — only page 1 plans`() {
        val shows = page(750, item("a", true))
        assertEquals(750, StalkerSyncer.keepingKind(shows, series = false, page = 4).totalItems)
    }

    @Test
    fun `a single-dump page reports what it kept, so the bulk fast path still sees a whole catalogue`() {
        val dump = page(3, item("film", false), item("show", true), item("film2", false))
        val films = StalkerSyncer.keepingKind(dump, series = false, page = 1)
        assertEquals(2, films.totalItems)
        assertEquals(films.items.size, films.totalItems)
    }
}
