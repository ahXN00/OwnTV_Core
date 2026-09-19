package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 / S1 — `sortOrder` is compared alongside the content hash instead of being folded into it,
 * so a row the provider only *moved* is still rewritten, while an untouched row is still skipped.
 * Folding it into `computeContentHash()` would have changed every stored hash at once and turned the
 * next resync of a 170k-item catalog into a full rewrite.
 *
 * These exercise [SyncSupport.upsertStable] through a fake [ContentAdapter], so no DAO, Context or
 * android.util.Log is involved.
 */
class UpsertStableTest {

    private companion object {
        const val SOURCE_ID = 6L
    }

    private data class Row(
        val remoteId: String,
        val name: String,
        val sortOrder: Int,
        val id: Long = 0,
        val hash: Int = 0,
        val sourceId: Long = SOURCE_ID,
    )

    private class Recorder {
        val inserted = ArrayList<Row>()
        val updated = ArrayList<Row>()
        val moved = ArrayList<PositionMove>()
        val ranges = ArrayList<PositionRange>()
        var rangeSourceId = -1L
    }

    private fun adapterFor(rec: Recorder) = ContentAdapter<Row>(
        remoteIdOf = { it.remoteId },
        // Stands in for computeContentHash(): everything except sortOrder.
        hashOf = { it.name.hashCode() },
        sortOrderOf = { it.sortOrder },
        sourceIdOf = { it.sourceId },
        copyWith = { row, id, hash -> row.copy(id = id ?: 0, hash = hash) },
        updateAll = { rec.updated.addAll(it) },
        insertAll = { rec.inserted.addAll(it) },
        moveAll = { rec.moved.addAll(it) },
        moveRanges = { src, r -> rec.rangeSourceId = src; rec.ranges.addAll(r) },
        remoteIdsForSource = { emptyList() },
        deleteByRemoteIds = { _, _ -> },
        loadHashes = { emptyList() },
    )

    private suspend fun upsert(rows: List<Row>, stored: Map<String, StoredRow>, rec: Recorder): UpsertStats =
        SyncSupport.upsertStable(rows, stored, adapterFor(rec))

    @Test
    fun `row that only moved is repositioned, never rewritten`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 7)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.moved)
        assertEquals(0, stats.updated)
        assertEquals(0, stats.skippedUnchanged)
        assertEquals(0, stats.inserted)
        // Plan A2: a position change must not go through @Update. That writes all ~20 columns and
        // re-runs withProviderCatalogMetadata's title parse on a row whose content is byte-identical.
        assertTrue(rec.updated.isEmpty())
        // Keeps its local row id, so favorites/history/resume stay linked.
        assertEquals(PositionMove(id = 42L, sortOrder = 7), rec.moved.single())
    }

    @Test
    fun `plan A1 - ten thousand rows shifted by two cost zero content writes`() = runBlocking {
        val rec = Recorder()
        // The measured shape of issue #192: two channels removed near the top of the list shifted
        // every row below them by exactly -2. Content byte-identical throughout.
        val n = 10_000
        val rows = (0 until n).map { Row(remoteId = "r$it", name = "N$it", sortOrder = it) }
        val stored = (0 until n).associate { "r$it" to StoredRow(it + 1L, "N$it".hashCode(), it + 2) }

        val stats = upsert(rows, stored, rec)

        assertEquals(n, stats.moved)
        assertEquals(0, stats.updated)
        assertEquals(0, stats.inserted)
        assertEquals(0, stats.skippedUnchanged)
        // Not one full-row write.
        assertTrue(rec.updated.isEmpty())
        // Plan A3: one contiguous run, one delta — a single range statement, not 10,000 of them.
        assertEquals(listOf(PositionRange(fromSortOrder = 2, toSortOrder = n + 1, delta = -2)), rec.ranges)
        assertEquals(SOURCE_ID, rec.rangeSourceId)
        assertEquals(1, stats.movedByRange)
        assertTrue(rec.moved.isEmpty())
    }

    @Test
    fun `row identical in content and position is skipped entirely`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.skippedUnchanged)
        assertEquals(0, stats.updated)
        assertEquals(0, stats.moved)
        assertTrue(rec.updated.isEmpty())
    }

    @Test
    fun `changed content is updated but not counted as moved`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha renamed", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.updated)
        assertEquals(0, stats.moved)
        assertEquals(42L, rec.updated.single().id)
    }

    @Test
    fun `unknown remote id is inserted`() = runBlocking {
        val rec = Recorder()
        val stats = upsert(listOf(Row(remoteId = "new", name = "New", sortOrder = 0)), emptyMap(), rec)

        assertEquals(1, stats.inserted)
        assertEquals(0, stats.updated)
        assertEquals(0L, rec.inserted.single().id)
    }

    @Test
    fun `a provider reorder repositions only the rows that actually moved`() = runBlocking {
        val rec = Recorder()
        // Positions 0 and 1 swapped; "c" stayed put.
        val rows = listOf(
            Row(remoteId = "a", name = "A", sortOrder = 1),
            Row(remoteId = "b", name = "B", sortOrder = 0),
            Row(remoteId = "c", name = "C", sortOrder = 2),
        )
        val stored = mapOf(
            "a" to StoredRow(1L, "A".hashCode(), 0),
            "b" to StoredRow(2L, "B".hashCode(), 1),
            "c" to StoredRow(3L, "C".hashCode(), 2),
        )

        val stats = upsert(rows, stored, rec)

        assertEquals(2, stats.moved)
        assertEquals(0, stats.updated)
        assertEquals(1, stats.skippedUnchanged)
        // A swap is two runs of one row with opposite deltas, so it stays per-row.
        assertEquals(setOf(1L, 2L), rec.moved.map { it.id }.toSet())
        assertTrue(rec.updated.isEmpty())
    }
}
