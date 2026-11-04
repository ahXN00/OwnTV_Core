package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.model.MediaType

class SnapshotPurgeBatchesTest {

    @Test
    fun `rows of one kind, profile and type share batches, in chunks`() {
        val rows = (1L..2_000L).map { SnapshotRow("member", 1L, MediaType.LIVE, it) } +
            SnapshotRow("member", 1L, MediaType.MOVIE, 7L) +
            SnapshotRow("member", 2L, MediaType.LIVE, 8L)
        val batches = snapshotPurgeBatches(rows, setOf("member"), chunk = 900)
        assertEquals(listOf(900, 900, 200, 1, 1), batches.map { it.itemIds.size })
        assertEquals(2_000, batches.filter { it.profileId == 1L && it.type == MediaType.LIVE }.sumOf { it.itemIds.size })
    }

    @Test
    fun `only the kinds asked for, and each id once`() {
        val rows = listOf(
            SnapshotRow("order", 1L, MediaType.LIVE, 5L),
            SnapshotRow("order", 1L, MediaType.LIVE, 5L), // the same item in two folders
            SnapshotRow("fav", 1L, MediaType.LIVE, 6L),
        )
        val batches = snapshotPurgeBatches(rows, setOf("order", "member"), chunk = 900)
        assertEquals(1, batches.size)
        assertEquals("order", batches.single().kind)
        assertEquals(listOf(5L), batches.single().itemIds)
    }
}
