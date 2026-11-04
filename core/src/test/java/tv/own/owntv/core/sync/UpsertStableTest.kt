package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import tv.own.owntv.core.database.dao.PARKED_SORT_ORDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sortOrder` is compared alongside the content hash instead of being folded into it,
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
        SyncSupport.upsertStable(rows, StoredRows(stored), adapterFor(rec))

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
        // A position change must not go through @Update. That writes all ~20 columns and
        // re-runs withProviderCatalogMetadata's title parse on a row whose content is byte-identical.
        assertTrue(rec.updated.isEmpty())
        // Keeps its local row id, so favorites/history/resume stay linked.
        assertEquals(PositionMove(id = 42L, sortOrder = 7), rec.moved.single())
    }

    @Test
    fun `ten thousand rows shifted by two cost zero content writes`() = runBlocking {
        val rec = Recorder()
        // The measured shape of a real resync: two channels removed near the top of the list shifted
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
        // One contiguous run, one delta — a single range statement, not 10,000 of them.
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

    /**
     * A table that applies each write the way SQL would: a range shifts every row whose position is
     * in its span at the moment it runs, not only the rows the caller had in mind.
     */
    private class SimulatedTable(stored: Map<String, Int>) {
        val positions = HashMap<String, Int>(stored)
        private val idToKey = HashMap<Long, String>()
        val ids = HashMap<String, Long>()
        private var nextId = 1L

        init { stored.keys.sorted().forEach { key -> ids[key] = nextId; idToKey[nextId++] = key } }

        fun snapshot(): StoredRows = StoredRows(
            positions.mapValues { (key, at) -> StoredRow(ids.getValue(key), key.hashCode(), at) },
        )

        val adapter = ContentAdapter<Row>(
            remoteIdOf = { it.remoteId },
            hashOf = { it.remoteId.hashCode() },
            sortOrderOf = { it.sortOrder },
            sourceIdOf = { it.sourceId },
            copyWith = { row, id, hash -> row.copy(id = id ?: 0, hash = hash) },
            updateAll = { rows -> rows.forEach { positions[it.remoteId] = it.sortOrder } },
            insertAll = { rows ->
                rows.forEach { row ->
                    ids[row.remoteId] = nextId; idToKey[nextId++] = row.remoteId
                    positions[row.remoteId] = row.sortOrder
                }
            },
            moveAll = { moves -> moves.forEach { positions[idToKey.getValue(it.id)] = it.sortOrder } },
            // As the DAO's applyPositionRanges: each range parks its rows, one pass takes them back.
            moveRanges = { _, ranges ->
                ranges.forEach { r ->
                    positions.keys.toList().forEach { key ->
                        val at = positions.getValue(key)
                        if (at in r.fromSortOrder..r.toSortOrder) positions[key] = at + r.delta + PARKED_SORT_ORDER
                    }
                }
                positions.keys.toList().forEach { key ->
                    val at = positions.getValue(key)
                    if (at >= PARKED_SORT_ORDER / 2) positions[key] = at - PARKED_SORT_ORDER
                }
            },
            remoteIdsForSource = { emptyList() },
            deleteByRemoteIds = { _, _ -> },
            loadHashes = { emptyList() },
        )
    }

    /**
     * A re-sync is written in chunks, all diffed against the one snapshot taken before it began.
     * New titles in the middle push everything after them down; once the chunks are applied, every
     * title must sit exactly at the provider's position, each on its own. Measured on a TV: 49 new
     * films in a 182k catalogue left 1,129 positions shared by two to four films, and every later
     * re-sync then "moved" those films again.
     */
    @Test
    fun `a chunked re-sync with new titles in the middle ends in provider order`() = runBlocking {
        val oldKeys = (0 until 20).map { "r%02d".format(it) }
        val table = SimulatedTable(oldKeys.withIndex().associate { (i, key) -> key to i })
        val snapshot = table.snapshot()
        val provider = oldKeys.toMutableList().apply { add(3, "n1"); add(11, "n2"); add(17, "n3") }
        val rows = provider.mapIndexed { i, key -> Row(remoteId = key, name = key, sortOrder = i) }

        rows.chunked(5).forEach { SyncSupport.upsertStable(it, snapshot, table.adapter) }

        assertEquals(provider.withIndex().associate { (i, key) -> key to i }, table.positions)
    }

    /**
     * The everyday re-sync: one new title near the top pushes everything below it down by one. Each
     * chunk's first position also holds the previous chunk's last row, so that one row goes on its
     * own and the rest of the chunk stays a single range — not a write per row.
     */
    @Test
    fun `one new title at the top costs a few statements per chunk, not one per row`() = runBlocking {
        val oldKeys = (0 until 1000).map { "r$it" }
        val table = SimulatedTable(oldKeys.withIndex().associate { (i, key) -> key to i })
        val snapshot = table.snapshot()
        val provider = listOf("new") + oldKeys
        val rows = provider.mapIndexed { i, key -> Row(remoteId = key, name = key, sortOrder = i) }

        val stats = rows.chunked(100).map { SyncSupport.upsertStable(it, snapshot, table.adapter) }

        assertEquals(provider.withIndex().associate { (i, key) -> key to i }, table.positions)
        assertEquals(1000, stats.sumOf { it.moved })
        assertTrue("scattered=${stats.sumOf { it.movedScattered }}", stats.sumOf { it.movedScattered } <= 10)
        assertTrue("ranges=${stats.sumOf { it.movedByRange }}", stats.sumOf { it.movedByRange } <= 11)
    }

    /**
     * The same rule over many random re-syncs: titles added, dropped, swapped and changed, written in
     * chunks of random size. Every listed title ends at its provider position; a dropped title keeps
     * the position it had, for the prune to remove.
     */
    @Test
    fun `random chunked re-syncs always end in provider order`() = runBlocking {
        repeat(500) { seed ->
            val random = java.util.Random(seed.toLong())
            val oldKeys = (0 until 30 + random.nextInt(60)).map { "r$it" }
            val table = SimulatedTable(oldKeys.withIndex().associate { (i, key) -> key to i })
            val snapshot = table.snapshot()
            val provider = oldKeys.toMutableList()
            val dropped = HashSet<String>()
            repeat(random.nextInt(6)) { if (provider.size > 1) dropped.add(provider.removeAt(random.nextInt(provider.size))) }
            repeat(random.nextInt(8)) { provider.add(random.nextInt(provider.size + 1), "n$seed-$it") }
            repeat(random.nextInt(3)) {
                val a = random.nextInt(provider.size); val b = random.nextInt(provider.size)
                provider[a] = provider[b].also { provider[b] = provider[a] }
            }
            val changed = provider.filter { random.nextInt(20) == 0 }.toSet()
            val rows = provider.mapIndexed { i, key -> Row(remoteId = key, name = key + (if (key in changed) "*" else ""), sortOrder = i) }

            rows.chunked(1 + random.nextInt(12)).forEach { SyncSupport.upsertStable(it, snapshot, table.adapter) }

            val expected = provider.withIndex().associate { (i, key) -> key to i } +
                dropped.associateWith { snapshot.byRemoteId.getValue(it).sortOrder }
            assertEquals("seed $seed", expected, table.positions)
        }
    }
}
