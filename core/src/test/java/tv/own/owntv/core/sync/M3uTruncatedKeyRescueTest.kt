package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Correcting an M3U display name also changes the stable key derived from it, which would insert the
 * corrected entry as a new row and prune the truncated one — losing that title's favourites, history
 * and resume position. The rescue maps the new key onto the existing stored row so the upsert updates
 * it in place instead. See `M3uSyncer.rescueTruncatedKeys`.
 */
class M3uTruncatedKeyRescueTest {

    /** [M3uSyncer] joins name and group with this. */
    private val sep = "\u0001"

    private fun stored(vararg keys: Pair<String, Long>): Map<String, StoredRow> =
        keys.associate { (k, id) -> k to StoredRow(id = id, contentHash = 1, sortOrder = 0) }

    private fun rescue(stored: Map<String, StoredRow>, vararg keys: Pair<String, String>) =
        M3uSyncer.rescueTruncatedKeys(stored, keys.toList())

    /** The whole point: the truncated row's local id is reused, so favourites survive the rename. */
    @Test
    fun truncatedRowIsRelinkedToItsFullName() {
        val db = stored("The (1999)${sep}Movies" to 42L)
        val out = rescue(db, "Movie, The (1999)${sep}Movies" to "The (1999)${sep}Movies")
        assertEquals(42L, out["Movie, The (1999)${sep}Movies"]?.id)
    }

    /** A name without a comma parses to the same key it always did — nothing to rescue, same map back. */
    @Test
    fun untouchedNamesAreLeftAlone() {
        val db = stored("BBC One${sep}News" to 7L)
        val out = rescue(db, "BBC One${sep}News" to "BBC One${sep}News")
        assertSame(db, out)
    }

    /** After the first sync the row carries the new key, so the rescue finds nothing to do. */
    @Test
    fun alreadyMigratedRowIsNotRescuedAgain() {
        val db = stored("Movie, The (1999)${sep}Movies" to 42L)
        val out = rescue(db, "Movie, The (1999)${sep}Movies" to "The (1999)${sep}Movies")
        assertSame(db, out)
    }

    /** A real channel named "Music" alongside "Live, Love, Music": the legacy key is a genuine
     *  current name, so which row is meant is a guess and neither is touched. */
    @Test
    fun legacyKeyThatIsItselfACurrentNameIsNotStolen() {
        val db = stored("Music${sep}Pop" to 5L)
        val out = rescue(
            db,
            "Music${sep}Pop" to "Music${sep}Pop",
            "Live, Love, Music${sep}Pop" to "Music${sep}Pop",
        )
        assertNull(out["Live, Love, Music${sep}Pop"])
        assertEquals(5L, out["Music${sep}Pop"]?.id)
    }

    /** Two corrected names collapsing onto one stored truncated row — ambiguous, so neither claims it. */
    @Test
    fun twoNamesClaimingOneLegacyRowRescueNeither() {
        val db = stored("The End${sep}G" to 9L)
        val out = rescue(
            db,
            "Movie, The End${sep}G" to "The End${sep}G",
            "Show, The End${sep}G" to "The End${sep}G",
        )
        assertNull(out["Movie, The End${sep}G"])
        assertNull(out["Show, The End${sep}G"])
    }

    /** The group is part of the key: the same truncated title in another folder is a different row. */
    @Test
    fun groupMustMatchToo() {
        val db = stored("The (1999)${sep}Kids" to 3L)
        val out = rescue(db, "Movie, The (1999)${sep}Movies" to "The (1999)${sep}Movies")
        assertSame(db, out)
    }

    /** Nothing stored under the legacy key either — a genuinely new entry, inserted as normal. */
    @Test
    fun unknownEntryIsNotInvented() {
        val db = stored("Something Else${sep}G" to 1L)
        val out = rescue(db, "Brand, New${sep}G" to "New${sep}G")
        assertSame(db, out)
    }
}
