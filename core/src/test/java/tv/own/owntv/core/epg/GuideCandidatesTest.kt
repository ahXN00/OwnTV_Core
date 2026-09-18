package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgChannelName

/**
 * The candidate merge, which is what the "Match EPG" picker and auto-match both see.
 *
 * Every case here is a real report or a real near-miss: a feed with no `<channel>` entries, a feed
 * that names channels it has no schedule for, and two feeds naming the same channel differently.
 */
class GuideCandidatesTest {

    @Test
    fun `a programme-only feed still produces candidates`() {
        // The defect: a feed carrying <programme> without <channel> was invisible to the picker,
        // while the guide happily drew its rows.
        val merged = GuideCandidates.merge(
            named = emptyList(),
            withProgrammes = listOf("bbc1", "itv1"),
        )

        assertEquals(listOf("bbc1", "itv1"), merged.map { it.epgChannelId })
        assertTrue(merged.all { it.hasProgrammes })
        assertNull(merged.first().displayName)
    }

    @Test
    fun `a named channel with no programmes is offered but flagged`() {
        // Not filtered out: a feed may name a channel today and schedule it tomorrow. But Phase 4
        // must be able to tell the difference, which is what hasProgrammes is for.
        val merged = GuideCandidates.merge(
            named = listOf(named("sky1", "Sky One")),
            withProgrammes = emptyList(),
        )

        assertEquals(1, merged.size)
        assertEquals("Sky One", merged.single().displayName)
        assertFalse(merged.single().hasProgrammes)
    }

    @Test
    fun `the longest name wins when two feeds name one channel`() {
        // GROUP BY returned an arbitrary feed's name, so the picker's label changed depending on
        // which feed was synced last. Order of the input must not matter.
        val forwards = GuideCandidates.merge(
            named = listOf(named("bbc1", "BBC1"), named("bbc1", "BBC One HD")),
            withProgrammes = listOf("bbc1"),
        )
        val backwards = GuideCandidates.merge(
            named = listOf(named("bbc1", "BBC One HD"), named("bbc1", "BBC1")),
            withProgrammes = listOf("bbc1"),
        )

        assertEquals("BBC One HD", forwards.single().displayName)
        assertEquals("BBC One HD", backwards.single().displayName)
    }

    @Test
    fun `a blank name never wins over a real one`() {
        val merged = GuideCandidates.merge(
            named = listOf(named("bbc1", "   "), named("bbc1", "BBC1"), named("bbc1", null)),
            withProgrammes = emptyList(),
        )

        assertEquals("BBC1", merged.single().displayName)
    }

    @Test
    fun `the two tables are combined, not concatenated`() {
        // One id in both tables is one candidate, not two — the picker used to be able to list the
        // same guide channel twice once the source filter came off.
        val merged = GuideCandidates.merge(
            named = listOf(named("bbc1", "BBC One"), named("sky1", "Sky One")),
            withProgrammes = listOf("bbc1", "itv1"),
        )

        assertEquals(listOf("bbc1", "sky1", "itv1"), merged.map { it.epgChannelId })
        assertEquals(
            mapOf("bbc1" to true, "sky1" to false, "itv1" to true),
            merged.associate { it.epgChannelId to it.hasProgrammes },
        )
        assertNull(merged.first { it.epgChannelId == "itv1" }.displayName)
    }

    @Test
    fun `no feeds means no candidates`() {
        assertEquals(emptyList<GuideCandidate>(), GuideCandidates.merge(emptyList(), emptyList()))
    }

    /** A row as written before v41: no stored normalized forms, so the reader computes them. */
    private fun named(epgChannelId: String, displayName: String?) =
        EpgChannelName(epgChannelId, displayName, normName = null, normId = null)

    @Test
    fun `stored normalized forms are used when present`() {
        // Written by a v41 sync: the reader must take these rather than recompute them.
        val merged = GuideCandidates.merge(
            named = listOf(
                EpgChannelName("bbc1", "BBC One", normName = "bbc 1", normId = "bbc1"),
            ),
            withProgrammes = listOf("bbc1"),
        )

        assertEquals("bbc 1", merged.single().normName)
        assertEquals("bbc1", merged.single().normId)
    }

    @Test
    fun `a row written before the columns existed is normalized on the fly`() {
        // The whole reason both columns are nullable: an un-backfilled row must still be searchable,
        // not silently unmatchable.
        val merged = GuideCandidates.merge(
            named = listOf(named("bbc.one.uk", "BBC One")),
            withProgrammes = emptyList(),
        )

        assertEquals(EpgMatcher.normalizeForEpg("BBC One"), merged.single().normName)
        assertEquals(EpgMatcher.normalizeForEpg("bbc.one.uk"), merged.single().normId)
    }

    @Test
    fun `an id known only to the programmes table still gets a normalized id`() {
        val merged = GuideCandidates.merge(named = emptyList(), withProgrammes = listOf("itv.one.uk"))

        assertEquals("", merged.single().normName)
        assertEquals(EpgMatcher.normalizeForEpg("itv.one.uk"), merged.single().normId)
    }
}
