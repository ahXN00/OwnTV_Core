package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page arithmetic shared by the setup sweep and the background drain (N1b/N1d).
 *
 * It has to be exact in both places: a row stored at setup and the same row stored minutes later by
 * the drain must land on the same slot, and `sortOrder` is deliberately not part of the content
 * hash — so a mismatch would never be repaired by a later sync.
 *
 * The page size is the reference portal's: 14.
 */
class StalkerSortKeyTest {

    private fun key(page: Int, indexInPage: Int, base: Int = 0) =
        StalkerSyncer.sortKey(sortBase = base, page = page, maxPageItems = 14, indexInPage = indexInPage)

    @Test
    fun `the first item of a category sits on its base`() {
        assertEquals(0, key(page = 1, indexInPage = 0))
        assertEquals(500, key(page = 1, indexInPage = 0, base = 500))
    }

    @Test
    fun `the last item of page 1 and the first of page 2 are adjacent`() {
        assertEquals(13, key(page = 1, indexInPage = 13))
        assertEquals(14, key(page = 2, indexInPage = 0))
    }

    @Test
    fun `a page in the middle starts where the pages before it end`() {
        // Page 10 follows nine full pages of 14.
        assertEquals(126, key(page = 10, indexInPage = 0))
        assertEquals(139, key(page = 10, indexInPage = 13))
    }

    @Test
    fun `a partial last page leaves a gap, and that is fine`() {
        // 30 items = pages 1, 2 and a 2-item page 3. Keys 30..41 are simply never used; the axis is
        // an ordering, not a dense index, and the category's base already reserves the whole range.
        assertEquals(28, key(page = 3, indexInPage = 0))
        assertEquals(29, key(page = 3, indexInPage = 1))
    }

    @Test
    fun `every category's range starts after the one before it`() {
        // Category A: 30 items over 3 pages reserves 3*14 = 42 slots, so B's base is 42.
        val aLast = key(page = 3, indexInPage = 1)
        val bFirst = key(page = 1, indexInPage = 0, base = 42)
        assertEquals(29, aLast)
        assertEquals(42, bFirst)
        assert(bFirst > aLast)
    }
}
