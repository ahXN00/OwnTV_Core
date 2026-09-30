package tv.own.owntv.core.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedDedupeTest {

    private val min = 60_000L

    @Test fun `the case-variant copy of a programme is dropped, the first kept`() {
        val d = FeedDedupe()
        // SkyCinemaClassics.de 13:33:10–14:56:28, then skycinemaclassics.de 13:35–15:00, both lowercased.
        assertTrue(d.accept("skycinemaclassics.de", 813 * min + 10_000, 896 * min + 28_000, "Der Held mit der Maske"))
        assertFalse(d.accept("skycinemaclassics.de", 815 * min, 900 * min, "Der Held mit der Maske"))
    }

    @Test fun `a real repeat later the same day is kept`() {
        val d = FeedDedupe()
        assertTrue(d.accept("c", 0, 60 * min, "News"))
        assertTrue(d.accept("c", 120 * min, 180 * min, "News"))
    }

    @Test fun `overlapping but different titles are both kept`() {
        val d = FeedDedupe()
        assertTrue(d.accept("c", 0, 60 * min, "Film A"))
        assertTrue(d.accept("c", 30 * min, 90 * min, "Film B"))
    }

    @Test fun `back to back is not an overlap`() {
        val d = FeedDedupe()
        assertTrue(d.accept("c", 0, 60 * min, "Loop"))
        assertTrue(d.accept("c", 60 * min, 120 * min, "Loop"))
    }

    @Test fun `channels are independent`() {
        val d = FeedDedupe()
        assertTrue(d.accept("a", 0, 60 * min, "Film"))
        assertTrue(d.accept("b", 0, 60 * min, "Film"))
    }
}
