package tv.own.owntv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** AC6 / A6: the release gate's order, its mpv destroy fallback and its refusals. */
class ReleaseGateTest {

    private class Steps(
        var refuse: ReleaseGate.Refusal? = null,
        var mpvLetsGo: Boolean = true,
        var quiet: Boolean = true,
    ) : ReleaseGate.Steps {
        val order = mutableListOf<String>()
        override fun refusal(sourceId: Long) = refuse.also { order += "check" }
        override fun stopExoEngines() { order += "exo" }
        override suspend fun releaseMpv(): Boolean { order += "mpv"; return mpvLetsGo }
        override suspend fun destroyMpv() { order += "mpv-destroy" }
        override suspend fun awaitQuiescent(): Boolean { order += "quiet"; return quiet }
    }

    @Test
    fun `stops every engine in order and proves the stream client quiet last`() = runBlocking {
        val steps = Steps()
        val r = ReleaseGate(steps).stopAllStreamsAndAwait(1)
        assertEquals(listOf("check", "exo", "mpv", "quiet"), steps.order)
        assertEquals(ReleaseGate.Result.Released(mpvDestroyed = false, quiet = true), r)
    }

    @Test
    fun `an mpv that does not let go is destroyed before anything else happens`() = runBlocking {
        val steps = Steps(mpvLetsGo = false)
        val r = ReleaseGate(steps).stopAllStreamsAndAwait(1)
        assertEquals(listOf("check", "exo", "mpv", "mpv-destroy", "quiet"), steps.order)
        assertEquals(ReleaseGate.Result.Released(mpvDestroyed = true, quiet = true), r)
    }

    @Test
    fun `a recording, download, measurement or timeshift refuses without stopping anything`() = runBlocking {
        for (why in ReleaseGate.Refusal.entries) {
            val steps = Steps(refuse = why)
            assertEquals(ReleaseGate.Result.Refused(why), ReleaseGate(steps).stopAllStreamsAndAwait(1))
            assertEquals(listOf("check"), steps.order)
        }
    }
}
