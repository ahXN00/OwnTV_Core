package tv.own.owntv.core.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * P16d — the rewind on a channel without catch-up, through its own saved copy (N4): the same offsets,
 * counter and live edge the apps already show for a provider archive.
 */
class LiveTimeshiftLocalTest {

    private val channel = ChannelEntity(sourceId = 1, categoryId = null, name = "News", streamUrl = "http://x/1.ts", remoteId = "1")

    private class FakeLocal : LiveTimeshift.Local {
        var active = true
        var depth = 600
        var edge = 5_000_000L
        var watching: Long? = 5_000_000L
        val seeks = mutableListOf<Long?>()
        override fun windowSec(ch: ChannelEntity) = if (active) 1_800 else null
        override fun depthSec() = depth
        override fun watchingWallMs() = if (active) watching else null
        override fun liveEdgeWallMs() = if (active) edge else null
        override fun seek(wallMs: Long?) { seeks += wallMs }
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val local = FakeLocal()
    private val archiveLoads = mutableListOf<Long>()
    private var liveEdge = 0
    private val timeshift = LiveTimeshift(
        scope = scope,
        playback = object : LiveTimeshift.Playback {
            override val positionMs = 0L
            override val hasError = false
            override val hasActiveStream = true
        },
        loadArchive = { _, start, _ -> archiveLoads += start; true },
        onLiveEdge = { liveEdge++ },
        coalesceMs = 10,
        tickMs = 10,
        local = local,
    )

    @After
    fun cleanUp() = scope.cancel()

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        repeat(300) {
            if (condition()) return@runBlocking
            delay(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `a saved channel can be rewound, over the window it is kept to`() {
        assertTrue(timeshift.canRewind(channel))
        assertEquals(1_800, timeshift.windowSec(channel))
        assertTrue(timeshift.jumpOptions(channel).isNotEmpty())
        local.active = false
        assertFalse(timeshift.canRewind(channel))
        assertEquals(0, timeshift.windowSec(channel))
    }

    @Test
    fun `a rewind re-opens the copy, clamped to what it holds, and never touches the provider archive`() {
        timeshift.scrub(channel, 30)
        await("the seek") { local.seeks.isNotEmpty() }
        assertEquals(local.edge - 30_000L, local.seeks.last())
        timeshift.beginAt(channel, 3_600) // an hour back, but only ten minutes are saved
        await("the second seek") { local.seeks.size == 2 }
        assertEquals(local.edge - 600_000L, local.seeks.last())
        assertTrue(archiveLoads.isEmpty())
    }

    @Test
    fun `pausing shows as behind live, and catching up returns to live`() {
        local.watching = local.edge - 42_000L // the picture fell 42 s behind (a pause)
        await("the counter") { timeshift.offsetSec.value == 42 }
        assertEquals(local.watching, timeshift.watchingWallMs.value)
        local.watching = local.edge - 2_000L // back within the slack of the edge
        await("live again") { timeshift.offsetSec.value == null }
        assertNull(timeshift.watchingWallMs.value)
    }

    @Test
    fun `scrubbing forward to the edge hands back to live`() {
        timeshift.scrub(channel, 30)
        timeshift.scrub(channel, -30)
        assertEquals(1, liveEdge)
    }
}
