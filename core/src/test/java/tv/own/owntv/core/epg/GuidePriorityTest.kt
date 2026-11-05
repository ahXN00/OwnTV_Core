package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * A channel two guide feeds carry, where the feeds disagree. The EPG Sources list order decides: the
 * feed nearer the top is drawn in full, and one further down only fills the time it leaves empty.
 */
class GuidePriorityTest {

    private val h = 60 * 60_000L

    /** Two EPG feeds, ids as the store hands them out (negative, counting down). */
    private val first = -1L
    private val second = -2L

    /** A playlist's own guide: a Room source id, never on the EPG Sources list. */
    private val playlist = 7L

    private fun row(id: Long, sourceId: Long, start: Long, stop: Long, title: String) = EpgProgrammeEntity(
        id = id,
        sourceId = sourceId,
        epgChannelId = "news.example",
        startMs = start,
        stopMs = stop,
        title = title,
    )

    private fun ids(rows: List<EpgProgrammeEntity>) = rows.map { it.id }

    @Test
    fun `the feed higher in the list wins where two disagree`() {
        val a = row(1, first, 20 * h, 21 * h, "Evening News")
        val b = row(2, second, 20 * h, 21 * h, "Quiz Night")
        assertEquals(listOf(1L), ids(GuidePriority.of(listOf(first, second)).resolve(listOf(a, b))))
        assertEquals(listOf(2L), ids(GuidePriority.of(listOf(second, first)).resolve(listOf(a, b))))
    }

    @Test
    fun `a lower feed fills a gap it fits in, and is dropped where it would overlap`() {
        val rows = listOf(
            row(1, first, 18 * h, 20 * h, "Film One"),
            row(2, first, 21 * h, 22 * h, "Late Show"),
            row(3, second, 20 * h, 21 * h, "Documentary"),
            row(4, second, 19 * h, 20 * h, "Cartoons"),
            row(5, second, 22 * h, 23 * h, "Weather"),
        )
        val out = GuidePriority.of(listOf(first, second)).resolve(rows)
        assertEquals(listOf(1L, 3L, 2L, 5L), ids(out))
    }

    @Test
    fun `a lower feed is used in full for a channel the higher one does not carry`() {
        val rows = listOf(
            row(1, second, 0, h, "Morning"),
            row(2, second, h, 2 * h, "Midday"),
        )
        assertEquals(listOf(1L, 2L), ids(GuidePriority.of(listOf(first, second)).resolve(rows)))
    }

    @Test
    fun `the same programme from two feeds keeps the higher feed's copy, even when it is shorter`() {
        val a = row(1, first, 20 * h + 5 * 60_000, 21 * h, "Evening News")
        val b = row(2, second, 20 * h, 21 * h + 10 * 60_000, "Evening News")
        assertEquals(listOf(1L), ids(GuidePriority.of(listOf(first, second)).resolve(listOf(b, a))))
    }

    @Test
    fun `rows from one feed are collapsed exactly as before`() {
        val rows = listOf(
            row(1, first, 0, 2 * h, "Film"),
            row(2, first, 10 * 60_000, 2 * h, "Film"),
            row(3, first, 2 * h, 3 * h, "News"),
        )
        val priority = GuidePriority.of(listOf(first, second))
        assertEquals(ids(EpgDedupe.collapse(rows)), ids(priority.resolve(rows)))
    }

    @Test
    fun `a playlist's own guide ranks below every listed feed`() {
        val a = row(1, playlist, 20 * h, 21 * h, "Provider Listing")
        val b = row(2, second, 20 * h, 21 * h, "Feed Listing")
        assertEquals(listOf(2L), ids(GuidePriority.of(listOf(first, second)).resolve(listOf(a, b))))
    }

    @Test
    fun `sources off the list rank the same, so their overlaps stay as they were`() {
        val a = row(1, playlist, 20 * h, 21 * h, "Provider Listing")
        val b = row(2, 8L, 20 * h, 21 * h, "Other Listing")
        assertEquals(listOf(1L, 2L), ids(GuidePriority.of(listOf(first)).resolve(listOf(a, b))))
    }

    @Test
    fun `the result is in start order whatever order the rows came in`() {
        val rows = listOf(
            row(3, second, 2 * h, 3 * h, "C"),
            row(1, first, 0, h, "A"),
            row(2, second, h, 2 * h, "B"),
        )
        assertEquals(listOf(1L, 2L, 3L), ids(GuidePriority.of(listOf(first, second)).resolve(rows)))
    }

    @Test
    fun `an empty or single row list comes back unchanged`() {
        val priority = GuidePriority.of(listOf(first, second))
        assertEquals(emptyList<Long>(), ids(priority.resolve(emptyList())))
        assertEquals(listOf(1L), ids(priority.resolve(listOf(row(1, second, 0, h, "Only")))))
    }
}
