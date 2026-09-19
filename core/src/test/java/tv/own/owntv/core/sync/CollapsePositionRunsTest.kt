package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan A3's run detection, on its own.
 *
 * This is the part of Phase A most likely to be subtly wrong, because a range statement
 * (`sortOrder = sortOrder + delta WHERE sortOrder BETWEEN lo AND hi`) hits **every** stored row in
 * the span, not only the rows the caller named. Getting it wrong does not throw — it silently
 * renumbers rows nobody asked to move, and the user sees a scrambled channel list.
 *
 * So the contract under test is not "collapse as much as possible". It is: *whatever comes back,
 * applying the ranges and then the singles must equal applying every move individually.*
 * [assertEquivalent] checks exactly that against a simulated table, and every case below runs
 * through it.
 */
class CollapsePositionRunsTest {

    /** moves as (storedSortOrder → new sortOrder), with a distinct row id per entry. */
    private fun movesOf(vararg pairs: Pair<Int, Int>): List<Pair<Int, PositionMove>> =
        pairs.mapIndexed { i, (from, to) -> from to PositionMove(id = i + 1L, sortOrder = to) }

    private fun occupiedOf(vararg sortOrders: Int): Map<Int, Int> =
        sortOrders.toList().groupingBy { it }.eachCount()

    /**
     * Apply the collapsed plan to a table of (id → storedSortOrder) and compare against applying the
     * moves one by one. Any row the ranges dragged along that should not have moved shows up here.
     */
    private fun assertEquivalent(
        moves: List<Pair<Int, PositionMove>>,
        occupied: Map<Int, Int>,
        table: Map<Long, Int>,
    ) {
        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        val byPlan = table.toMutableMap()
        ranges.forEach { r ->
            byPlan.keys.toList().forEach { id ->
                val at = byPlan.getValue(id)
                if (at in r.fromSortOrder..r.toSortOrder) byPlan[id] = at + r.delta
            }
        }
        singles.forEach { byPlan[it.id] = it.sortOrder }

        val byRow = table.toMutableMap()
        moves.forEach { (_, m) -> byRow[m.id] = m.sortOrder }

        assertEquals("collapsed plan diverged from per-row moves", byRow, byPlan)
    }

    /** The table implied by a set of moves, plus any extra rows that are not moving. */
    private fun tableOf(moves: List<Pair<Int, PositionMove>>, extras: Map<Long, Int> = emptyMap()) =
        moves.associate { (from, m) -> m.id to from } + extras

    @Test
    fun `one contiguous run collapses to a single range`() {
        val moves = movesOf(10 to 8, 11 to 9, 12 to 10, 13 to 11)
        val occupied = occupiedOf(10, 11, 12, 13)

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertEquals(listOf(PositionRange(10, 13, -2)), ranges)
        assertTrue(singles.isEmpty())
        assertEquivalent(moves, occupied, tableOf(moves))
    }

    @Test
    fun `two runs with different deltas stay separate`() {
        val moves = movesOf(0 to 1, 1 to 2, 5 to 3, 6 to 4)
        val occupied = occupiedOf(0, 1, 5, 6)

        val (ranges, _) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertEquals(listOf(PositionRange(0, 1, 1), PositionRange(5, 6, -2)), ranges)
        assertEquivalent(moves, occupied, tableOf(moves))
    }

    @Test
    fun `a row inside the span that is NOT moving blocks the range`() {
        // 11 stays put, so a range over 10..12 would drag it along. This is the defect the guard
        // exists to prevent, and the reason `occupied` is passed in at all.
        val moves = movesOf(10 to 8, 12 to 10)
        val occupied = occupiedOf(10, 11, 12)

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertTrue("must not collapse across an unmoved row", ranges.isEmpty())
        assertEquals(2, singles.size)
        assertEquivalent(moves, occupied, tableOf(moves, extras = mapOf(99L to 11)))
    }

    @Test
    fun `a stale row the provider dropped blocks the range`() {
        // Same shape, but the extra row at 11 is not in this pass at all — a row awaiting prune.
        // It must not be renumbered by a drive-by range.
        val moves = movesOf(10 to 8, 11 to 9, 13 to 11)
        val occupied = occupiedOf(10, 11, 12, 13)

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertEquals(listOf(PositionRange(10, 11, -2)), ranges)
        assertEquals(listOf(PositionMove(3L, 11)), singles)
        assertEquivalent(moves, occupied, tableOf(moves, extras = mapOf(99L to 12)))
    }

    @Test
    fun `a delta of zero is never emitted`() {
        val moves = movesOf(4 to 4, 5 to 5)
        val occupied = occupiedOf(4, 5)

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertTrue(ranges.isEmpty())
        assertTrue("a row that did not move must produce no statement at all", singles.isEmpty())
    }

    @Test
    fun `a single moved row is one statement, not a range`() {
        val moves = movesOf(7 to 3)
        val occupied = occupiedOf(7)

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertTrue(ranges.isEmpty())
        assertEquals(listOf(PositionMove(1L, 3)), singles)
        assertEquivalent(moves, occupied, tableOf(moves))
    }

    @Test
    fun `a run at the very start collapses`() {
        val moves = movesOf(0 to 2, 1 to 3, 2 to 4)
        val occupied = occupiedOf(0, 1, 2)

        assertEquals(listOf(PositionRange(0, 2, 2)), SyncSupport.collapsePositionRuns(moves, occupied).first)
        assertEquivalent(moves, occupied, tableOf(moves))
    }

    @Test
    fun `a run at the very end collapses`() {
        val moves = movesOf(98 to 97, 99 to 98)
        val occupied = occupiedOf(0, 98, 99)

        assertEquals(listOf(PositionRange(98, 99, -1)), SyncSupport.collapsePositionRuns(moves, occupied).first)
        assertEquivalent(moves, occupied, tableOf(moves, extras = mapOf(99L to 0)))
    }

    @Test
    fun `rows sharing one stored position move together`() {
        // sortOrder is not unique. Two rows at 5 both shift by -1, so the span is still clean.
        val moves = listOf(
            5 to PositionMove(1L, 4),
            5 to PositionMove(2L, 4),
            6 to PositionMove(3L, 5),
        )
        val occupied = occupiedOf(5, 5, 6)

        assertEquals(listOf(PositionRange(5, 6, -1)), SyncSupport.collapsePositionRuns(moves, occupied).first)
        assertEquivalent(moves, occupied, mapOf(1L to 5, 2L to 5, 3L to 6))
    }

    @Test
    fun `an empty move set produces nothing`() {
        val (ranges, singles) = SyncSupport.collapsePositionRuns(emptyList(), emptyMap())
        assertTrue(ranges.isEmpty())
        assertTrue(singles.isEmpty())
    }

    @Test
    fun `the issue 192 live phase shape is one statement`() {
        // Measured: 30,689 channels, every one shifted by exactly -2 after two removals near the top.
        val n = 30_689
        val moves = (0 until n).map { (it + 2) to PositionMove(it + 1L, it) }
        val occupied = (0 until n).associate { (it + 2) to 1 }

        val (ranges, singles) = SyncSupport.collapsePositionRuns(moves, occupied)

        assertEquals(listOf(PositionRange(2, n + 1, -2)), ranges)
        assertTrue(singles.isEmpty())
    }
}
