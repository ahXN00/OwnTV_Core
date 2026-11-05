package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.epgKeyOf
import tv.own.owntv.core.parser.XtEpgEntry

class ArchiveGuidePlanTest {

    private val now = 1_800_000_000_000L
    private val h = 60 * 60 * 1000L
    private val day = 24 * h
    private val week = 7 * day

    /** A programme from [startH] hours ago to [stopH] hours ago (negative: in the future). */
    private fun p(startH: Long, stopH: Long, title: String = "News") =
        XtEpgEntry(title, null, now - startH * h, now - stopH * h)

    @Test fun `asked at once the first time, then again after six hours`() {
        assertTrue(ArchiveGuidePlan.due(null, now))
        assertFalse(ArchiveGuidePlan.due(now - 5 * h, now))
        assertTrue(ArchiveGuidePlan.due(now - 6 * h, now))
    }

    @Test fun `a failed attempt is retried after half an hour`() {
        val recorded = ArchiveGuidePlan.failedAt(now)
        assertFalse(ArchiveGuidePlan.due(recorded, now + 29 * 60_000))
        assertTrue(ArchiveGuidePlan.due(recorded, now + 30 * 60_000))
    }

    @Test fun `the window is the channel's archive, within the guide's retention`() {
        assertEquals(now - 4 * day, ArchiveGuidePlan.windowStart(now, 4, week))
        assertEquals(now - week, ArchiveGuidePlan.windowStart(now, 30, week))
        // A playlist that does not say how deep its archive goes gets the shared default.
        assertEquals(now - week, ArchiveGuidePlan.windowStart(now, 0, week))
    }

    @Test fun `only finished programmes before the feed's oldest one, inside the window`() {
        val listings = listOf(
            p(5 * 24, 5 * 24 - 1),       // older than the archive
            p(4 * 24 - 2, 4 * 24 - 3),   // the fourth day back
            p(30, 29),                    // before the feed
            p(25, 24),                    // ends exactly where the feed starts
            p(23, 22),                    // the feed already covers it
            p(1, -1),                     // still on
            p(10, 11),                    // stop before start
        )
        val keep = ArchiveGuidePlan.toStore(listings, now - 24 * h, now - 4 * day, now)
        assertEquals(listOf(now - (4 * 24 - 2) * h, now - 30 * h, now - 25 * h), keep.map { it.startMs })
    }

    @Test fun `an earlier fill's rows are kept while the schedule says the same`() {
        val keep = ArchiveGuidePlan.toStore(listOf(p(30, 29), p(29, 28)), now - 24 * h, now - day * 2, now)
        assertFalse(ArchiveGuidePlan.changed(keep, listOf(now - 29 * h, now - 30 * h)))
    }

    @Test fun `an earlier fill's rows are replaced once the feed's own past moved`() {
        val listings = listOf(p(30, 29), p(29, 28), p(28, 27))
        val before = ArchiveGuidePlan.toStore(listings, now - 27 * h, now - day * 2, now)
        // The feed now starts an hour earlier: the last stored programme would overlap it.
        val after = ArchiveGuidePlan.toStore(listings, now - 28 * h, now - day * 2, now)
        assertEquals(listOf(now - 30 * h, now - 29 * h), after.map { it.startMs })
        assertTrue(ArchiveGuidePlan.changed(after, before.map { it.startMs }))
        // Nothing left to store: the stored rows go.
        assertTrue(ArchiveGuidePlan.changed(emptyList(), before.map { it.startMs }))
        assertFalse(ArchiveGuidePlan.changed(emptyList(), emptyList()))
    }

    @Test fun `the rows have a store of their own, clear of playlists and EPG sources`() {
        val store = ArchiveGuidePlan.storeId(3)
        assertTrue(store < -1_000_000)
        assertTrue(store != ArchiveGuidePlan.storeId(4))
        assertTrue(ArchiveGuidePlan.storeId(1) < 0)
    }

    @Test fun `the rows are keyed the way the guide reads them`() {
        // Providers send mixed-case ids; the guide stores and reads them trimmed and lower-cased.
        val channel = ChannelEntity(sourceId = 3, name = "One", streamUrl = "http://x/1", epgChannelId = " BBC.One.UK ")
        assertEquals("bbc.one.uk", epgKeyOf(channel, SectionCustomizations()))
    }

    @Test fun `untitled and duplicate entries are dropped, the rest sorted`() {
        val listings = listOf(p(28, 27), p(30, 29, " "), p(28, 27, "Repeat"), p(29, 28))
        val keep = ArchiveGuidePlan.toStore(listings, now - 24 * h, now - day * 2, now)
        assertEquals(listOf(now - 29 * h, now - 28 * h), keep.map { it.startMs })
        assertEquals("News", keep.last().title)
    }

    @Test fun `nothing is added to a channel the feeds have no guide for`() {
        assertTrue(ArchiveGuidePlan.toStore(listOf(p(3, 2)), null, now - day, now).isEmpty())
    }
}
