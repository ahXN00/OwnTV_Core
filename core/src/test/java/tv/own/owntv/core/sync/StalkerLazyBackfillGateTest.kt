package tv.own.owntv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The N1b gate: which portals get "stop after page 1 and finish in the background", and which keep
 * the proven eager walk.
 *
 * The numbers come from §4C's measurement of the reference portal — `max_page_items=14`, 4,593
 * deferred movie pages, 1,503 deferred series pages.
 */
class StalkerLazyBackfillGateTest {

    @Test
    fun `reference portal engages — tiny pages, thousands of them`() {
        assertTrue(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 14, deferredPages = 4593))
        assertTrue(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 14, deferredPages = 1503))
    }

    @Test
    fun `a re-sync never engages — it prunes, and pruning needs a whole pass`() {
        assertFalse(StalkerSyncer.lazyBackfill(freshSource = false, itemsPerPage = 14, deferredPages = 4593))
    }

    @Test
    fun `a portal that pages sanely keeps the eager walk`() {
        // 1,000 items per page: 65,523 movies is 66 requests, which finishes inside setup.
        assertFalse(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 1000, deferredPages = 4593))
    }

    @Test
    fun `a small catalogue keeps the eager walk even on tiny pages`() {
        assertFalse(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 14, deferredPages = 199))
        assertTrue(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 14, deferredPages = 200))
    }

    @Test
    fun `an unknown page size never engages`() {
        // 0 means the portal reported no `max_page_items` and page 1 came back empty. Deferring on a
        // page size we could not read would write a plan whose page count is guesswork.
        assertFalse(StalkerSyncer.lazyBackfill(freshSource = true, itemsPerPage = 0, deferredPages = 4593))
    }
}
