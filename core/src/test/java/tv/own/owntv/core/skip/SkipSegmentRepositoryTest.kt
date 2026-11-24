package tv.own.owntv.core.skip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.skip.TheIntroDbClient.Lookup

class SkipSegmentRepositoryTest {

    private val key = SkipSegmentRepository.Key(1399, 1, 1)
    private val intro = listOf(RawSkipSegment(SkipKind.INTRO, 0, 60_000))
    private var now = 1_000_000L
    private var calls = 0

    private fun repository(vararg answers: Lookup) = SkipSegmentRepository(
        lookup = { _, _ ->
            val answer = answers[minOf(calls, answers.size - 1)]
            calls++
            answer to TheIntroDbClient.Quota(5000, 5000 - calls, 0, now)
        },
        clock = { now },
    )

    @Test
    fun `a found item is asked once for the session`() = runTest {
        val repo = repository(Lookup.Found(intro))
        assertEquals(intro, repo.segmentsFor(key, 3_000_000))
        now += 10 * 60 * 60_000L
        assertEquals(intro, repo.segmentsFor(key, 3_000_000))
        assertEquals(1, calls)
    }

    @Test
    fun `a miss is remembered for thirty minutes only`() = runTest {
        val repo = repository(Lookup.NotFound, Lookup.Found(intro))
        assertTrue(repo.segmentsFor(key, null).isEmpty())
        now += SkipSegmentRepository.MISS_TTL_MS - 1
        assertTrue(repo.segmentsFor(key, null).isEmpty())
        assertEquals(1, calls)
        now += 2
        assertEquals(intro, repo.segmentsFor(key, null))
        assertEquals(2, calls)
    }

    @Test
    fun `over the limit nothing is asked until the service's reset`() = runTest {
        val repo = repository(Lookup.Limited(untilMs = now + 60_000), Lookup.Found(intro))
        assertTrue(repo.segmentsFor(key, null).isEmpty())
        assertTrue(repo.segmentsFor(SkipSegmentRepository.Key(550, null, null), null).isEmpty())
        assertEquals(1, calls)
        now += 60_000
        assertEquals(intro, repo.segmentsFor(key, null))
        assertEquals(2, calls)
    }

    @Test
    fun `a failed lookup is asked again next time`() = runTest {
        val repo = repository(Lookup.Failed, Lookup.Found(intro))
        assertTrue(repo.segmentsFor(key, null).isEmpty())
        assertEquals(intro, repo.segmentsFor(key, null))
        assertEquals(2, calls)
    }

    @Test
    fun `the last allowance seen is kept`() = runTest {
        val repo = repository(Lookup.Found(intro))
        repo.segmentsFor(key, null)
        assertEquals(4999, repo.quota.value?.remaining)
    }
}
