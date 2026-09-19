package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan N1c's queue re-ordering.
 *
 * The drain re-reads the user's chosen category between *every* category, for minutes at a time, so
 * this runs far more often than it looks. A bug here would not throw — it would quietly starve one
 * category while appearing to work, which is exactly the kind of thing that only shows up as "why
 * is this list still empty" weeks later.
 *
 * The contract: re-ordering only. Nothing is added, nothing is dropped, and the set of categories
 * left to fetch is identical before and after.
 */
class BackfillQueueTest {

    private fun queueOf(vararg names: String) = names.toMutableList()

    private fun promote(queue: MutableList<String>, wanted: String?) =
        BackfillQueue.promote(queue, wanted) { it }

    @Test
    fun `the wanted category moves to the front and reports how far it jumped`() {
        val q = queueOf("a", "b", "c", "d")
        assertEquals(2, promote(q, "c"))
        assertEquals(listOf("c", "a", "b", "d"), q)
    }

    @Test
    fun `nothing happens when it is already first`() {
        val q = queueOf("a", "b", "c")
        assertEquals(0, promote(q, "a"))
        assertEquals(listOf("a", "b", "c"), q)
    }

    @Test
    fun `nothing happens with no request`() {
        val q = queueOf("a", "b")
        assertEquals(0, promote(q, null))
        assertEquals(listOf("a", "b"), q)
    }

    @Test
    fun `a category that is not in the queue is ignored`() {
        // The usual reason: the user opened a category the drain has already finished.
        val q = queueOf("a", "b")
        assertEquals(0, promote(q, "zzz"))
        assertEquals(listOf("a", "b"), q)
    }

    @Test
    fun `an empty queue is safe`() {
        val q = queueOf()
        assertEquals(0, promote(q, "a"))
        assertEquals(emptyList<String>(), q)
    }

    @Test
    fun `nothing is lost or duplicated`() {
        // The property that actually matters: this may only re-order.
        val q = queueOf("a", "b", "c", "d", "e")
        promote(q, "e")
        assertEquals(setOf("a", "b", "c", "d", "e"), q.toSet())
        assertEquals(5, q.size)
    }

    @Test
    fun `promoting the last category walks it all the way forward`() {
        val q = queueOf("a", "b", "c", "d", "e")
        assertEquals(4, promote(q, "e"))
        assertEquals(listOf("e", "a", "b", "c", "d"), q)
    }

    @Test
    fun `repeated promotion of the same category is idempotent`() {
        // The drain calls this between every category, so it runs many times for one request.
        val q = queueOf("a", "b", "c")
        promote(q, "b")
        assertEquals(listOf("b", "a", "c"), q)
        assertEquals(0, promote(q, "b"))
        assertEquals(listOf("b", "a", "c"), q)
    }
}
