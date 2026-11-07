package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveGuideQueueTest {

    private fun ArchiveGuideQueue<Long>.drain(): List<Long> = generateSequence { next() }.toList()

    private fun ArchiveGuideQueue<Long>.add(id: Long) = add(id, id)

    @Test fun `the row seen last goes first`() {
        val queue = ArchiveGuideQueue<Long>(max = 24)
        listOf(1L, 2, 3).forEach { queue.add(it) }
        assertEquals(listOf(3L, 2, 1), queue.drain())
        assertNull(queue.next())
    }

    @Test fun `a channel queued again moves to the front and is asked for once`() {
        val queue = ArchiveGuideQueue<Long>(max = 24)
        listOf(1L, 2, 3, 1).forEach { queue.add(it) }
        assertEquals(listOf(1L, 3, 2), queue.drain())
    }

    @Test fun `past the cap the rows scrolled past longest ago are forgotten`() {
        val queue = ArchiveGuideQueue<Long>(max = 3)
        (1L..5).forEach { queue.add(it) }
        assertEquals(3, queue.size)
        assertEquals(listOf(5L, 4, 3), queue.drain())
    }

    @Test fun `clear drops everything pending`() {
        val queue = ArchiveGuideQueue<Long>(max = 24)
        listOf(1L, 2).forEach { queue.add(it) }
        queue.clear()
        assertNull(queue.next())
    }
}
