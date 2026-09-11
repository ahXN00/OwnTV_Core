package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

/**
 * Phase 4 / S8 — the lazy episode cache used to be write-once (`episodeCount > 0` meant "never ask
 * again"), and since the syncers deliberately never fetch episodes either, a show could never gain
 * the episodes a provider added after the user first opened it. Only deleting and re-adding the
 * source worked, by cascade-dropping the rows.
 *
 * Two halves are tested here, both pure: when a refresh happens, and what a refresh does to the
 * rows already stored. The second matters as much as the first — episode row ids carry watch
 * history, resume positions and next-episode autoplay, so a refresh that rebuilt them would trade
 * one bug for a worse one.
 */
class EpisodeCacheTest {

    private val now = 1_700_000_000_000L
    private val ttl = EPISODE_CACHE_TTL_MS

    private fun ep(
        season: Int, number: Int, name: String = "E$number", remoteId: String? = "r$season-$number",
        id: Long = 0, airDateMs: Long? = null,
    ) =
        EpisodeEntity(
            id = id, seriesId = 1, seasonNumber = season, episodeNumber = number,
            name = name, streamUrl = "http://x/$season/$number", remoteId = remoteId,
            airDateMs = airDateMs,
        )

    // --- freshness ---

    @Test
    fun `empty cache always fetches`() {
        assertTrue(shouldRefreshEpisodes(cachedCount = 0, episodesSyncedAt = now, now = now))
    }

    @Test
    fun `fresh cache does not fetch`() {
        assertFalse(shouldRefreshEpisodes(cachedCount = 12, episodesSyncedAt = now - 1000, now = now))
    }

    @Test
    fun `stale cache fetches`() {
        assertTrue(shouldRefreshEpisodes(cachedCount = 12, episodesSyncedAt = now - ttl - 1, now = now))
    }

    /** What a source sync does: zero the stamp so the next open of the show refreshes it. */
    @Test
    fun `invalidated cache fetches even when populated`() {
        assertTrue(shouldRefreshEpisodes(cachedCount = 12, episodesSyncedAt = 0L, now = now))
    }

    /** A stamp in the future (clock moved backwards) must go stale, not stay fresh forever. */
    @Test
    fun `future stamp is treated as stale`() {
        assertTrue(shouldRefreshEpisodes(cachedCount = 12, episodesSyncedAt = now + 60_000, now = now))
    }

    // --- merge ---

    /** The bug itself: a show that gained an episode picks it up, and keeps every existing row id. */
    @Test
    fun `a new episode is inserted and existing rows keep their ids`() {
        val existing = listOf(ep(1, 1, id = 10), ep(1, 2, id = 11))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1), ep(1, 2), ep(1, 3)))

        assertEquals(1, plan.inserts.size)
        assertEquals(3, plan.inserts.single().episodeNumber)
        assertEquals(0L, plan.inserts.single().id) // a fresh row, autoGenerate assigns the id
        assertTrue("nothing else changed, so nothing else is rewritten", plan.updates.isEmpty())
        assertTrue("no existing episode may be deleted", plan.deleteIds.isEmpty())
    }

    @Test
    fun `an unchanged list is a complete no-op`() {
        val existing = listOf(ep(1, 1, id = 10), ep(1, 2, id = 11))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1), ep(1, 2)))

        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deleteIds.isEmpty())
    }

    /** A renamed or re-pointed episode is updated in place — same row id, so progress survives. */
    @Test
    fun `a changed episode is updated on its existing id`() {
        val existing = listOf(ep(1, 1, name = "Pilot", id = 10))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1, name = "Pilot (Remastered)")))

        assertTrue(plan.inserts.isEmpty())
        assertEquals(1, plan.updates.size)
        assertEquals(10L, plan.updates.single().id)
        assertEquals("Pilot (Remastered)", plan.updates.single().name)
        assertTrue(plan.deleteIds.isEmpty())
    }

    /**
     * The air date is the one field a stored row may know better than the provider: it can have been
     * filled in from TMDB for a panel that dates nothing. A refresh must not blank it back out.
     */
    @Test
    fun `a refresh without dates keeps the one already stored`() {
        val existing = listOf(ep(1, 1, id = 10, airDateMs = 1_555_200_000_000L))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1)))

        assertTrue("nothing actually changed, so nothing is rewritten", plan.updates.isEmpty())
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.deleteIds.isEmpty())
    }

    /** A provider that starts sending dates does update the stored row. */
    @Test
    fun `a date the provider newly supplies is written`() {
        val existing = listOf(ep(1, 1, id = 10))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1, airDateMs = 1_555_200_000_000L)))

        assertEquals(1, plan.updates.size)
        assertEquals(10L, plan.updates.single().id)
        assertEquals(1_555_200_000_000L, plan.updates.single().airDateMs)
    }

    @Test
    fun `an episode the provider dropped is deleted`() {
        val existing = listOf(ep(1, 1, id = 10), ep(1, 2, id = 11))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1)))

        assertEquals(listOf(11L), plan.deleteIds)
        assertTrue(plan.inserts.isEmpty())
    }

    /** M3U-style rows carry no remoteId, so season/episode number is the identity. */
    @Test
    fun `episodes without a remote id match on season and episode number`() {
        val existing = listOf(ep(2, 5, remoteId = null, id = 42))
        val plan = planEpisodeMerge(existing, listOf(ep(2, 5, remoteId = null), ep(2, 6, remoteId = null)))

        assertTrue("the existing row must be matched, not re-inserted", plan.updates.isEmpty())
        assertEquals(1, plan.inserts.size)
        assertEquals(6, plan.inserts.single().episodeNumber)
        assertTrue(plan.deleteIds.isEmpty())
    }

    /** Duplicates left by an older build are collapsed onto one row rather than preserved. */
    @Test
    fun `duplicate stored rows are cleaned up`() {
        val existing = listOf(ep(1, 1, id = 10), ep(1, 1, id = 99))
        val plan = planEpisodeMerge(existing, listOf(ep(1, 1)))

        assertEquals(listOf(99L), plan.deleteIds)
        assertTrue(plan.inserts.isEmpty())
    }

    /**
     * The empty-fetch case is handled by the caller (`loadEpisodes` returns early and never reaches
     * the merge), because a provider hiccup must not wipe a season the user is midway through. This
     * pins the merge's own behaviour so that guarantee can't be quietly moved here and lost.
     */
    @Test
    fun `an empty incoming list would delete everything - which is why the caller never passes one`() {
        val existing = listOf(ep(1, 1, id = 10), ep(1, 2, id = 11))
        val plan = planEpisodeMerge(existing, emptyList())

        assertEquals(listOf(10L, 11L), plan.deleteIds)
    }
}
