package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

class ChannelRecallTest {

    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, name = "ch$id", streamUrl = "http://recall.invalid/$id.ts")

    @Test
    fun `nothing to go back to before a second channel`() {
        val recall = ChannelRecall()
        assertNull(recall.previous.value)
        recall.onWatched(channel(1))
        assertNull(recall.previous.value)
    }

    @Test
    fun `previous is the channel watched before`() {
        val recall = ChannelRecall()
        recall.onWatched(channel(1))
        recall.onWatched(channel(2))
        recall.onWatched(channel(3))
        assertEquals(2L, recall.previous.value?.id)
    }

    @Test
    fun `tuning the previous channel swaps the two`() {
        val recall = ChannelRecall()
        recall.onWatched(channel(1))
        recall.onWatched(channel(2))
        recall.onWatched(recall.previous.value!!)
        assertEquals(2L, recall.previous.value?.id)
        recall.onWatched(recall.previous.value!!)
        assertEquals(1L, recall.previous.value?.id)
    }

    @Test
    fun `a re-tune of the same channel changes nothing`() {
        val recall = ChannelRecall()
        recall.onWatched(channel(1))
        recall.onWatched(channel(2))
        recall.onWatched(channel(2))
        assertEquals(1L, recall.previous.value?.id)
    }
}
