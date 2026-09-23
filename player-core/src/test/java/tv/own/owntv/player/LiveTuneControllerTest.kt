package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/**
 * The sequencing both apps used to hand-copy: superseding tunes, the ladder across engines, and the
 * "Give up after" alarm. Driven on virtual time against a fake engine pair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTuneControllerTest {

    private class FakeEngines : LiveEngines {
        val log = mutableListOf<String>()
        override var exoUrl: String? = null
        override var exoIsHls = false
        override var exoFailed = false
        override var mpvHasStream = false

        /** Complete with a reason to fail the ExoPlayer watch, or with null for "opened". */
        var exoWatch: CompletableDeferred<String?>? = null
        var mpvWatch: CompletableDeferred<MpvOutcome>? = null

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) {
            exoUrl = url
            log += "exo:$url"
        }
        override fun exoSetMuted(muted: Boolean) { log += if (muted) "exo-mute" else "exo-unmute" }
        override fun exoStop() { exoUrl = null }
        override fun exoReleaseUhdDecoder() {}
        override fun exoAbandon(reason: String) { log += "exo-abandon" }

        override suspend fun watchExo(
            channelName: String,
            stillOurs: () -> Boolean,
            handOver: suspend (String) -> Unit,
            onOpened: () -> Unit,
            postponeDeadline: (Long) -> Unit,
            log: (String) -> Unit,
        ) {
            val d = CompletableDeferred<String?>().also { exoWatch = it }
            val reason = d.await()
            if (reason == null) onOpened() else if (stillOurs()) handOver(reason)
        }

        override fun mpvPlay(url: String, request: LiveRequest) {
            mpvHasStream = true
            log += "mpv:$url"
        }
        override fun mpvStop() { mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() { mpvHasStream = false }
        override fun mpvAbandon(reason: String) { log += "mpv-abandon" }
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? {
            val d = CompletableDeferred<MpvOutcome>().also { mpvWatch = it }
            return withTimeoutOrNull(timeoutMs) { d.await() }
        }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
    }

    private class FakeHost(private val scope: TestScope) : LiveTuneController.Host {
        var preference = EnginePreference.EXO_FIRST
        var budgetSecs = 30
        var sourceDelayMs = 0L
        val pins = mutableListOf<Boolean>()
        val events = mutableListOf<PlayerFailureReason>()

        override suspend fun sourceOf(sourceId: Long): SourceEntity? {
            if (sourceDelayMs > 0) delay(sourceDelayMs)
            return null
        }
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) { pins += onMpv }
        override suspend fun globalPreference() = preference
        override suspend fun globalBudgetSecs() = budgetSecs
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {
            events += reason
        }
        override fun nowMs(): Long = scope.testScheduler.currentTime
    }

    private fun channel(id: Long) = ChannelEntity(
        id = id,
        sourceId = 1,
        name = "ch$id",
        streamUrl = "http://tune-controller-test.invalid/live/$id.ts",
    )

    private fun TestScope.controller(engines: FakeEngines, host: FakeHost) =
        LiveTuneController(backgroundScope, engines, host)

    @Test
    fun `a newer tune supersedes one still waiting out the decoder release`() = runTest {
        val engines = FakeEngines().apply { exoUrl = "http://preview.invalid/x" }
        val host = FakeHost(this).apply { preference = EnginePreference.MPV_FIRST }
        val c = controller(engines, host)
        c.tune(channel(1))
        runCurrent() // channel 1 is now waiting for ExoPlayer's decoder before mpv
        host.preference = EnginePreference.EXO_FIRST
        c.tune(channel(2))
        advanceTimeBy(5_000)
        assertFalse("the superseded tune must never reach mpv", engines.log.any { it.startsWith("mpv:") })
        assertEquals(listOf("exo:${channel(2).streamUrl}"), engines.log)
    }

    @Test
    fun `an ExoPlayer failure climbs to mpv, and mpv failing too ends the tune`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(3))
        runCurrent()
        assertTrue(c.liveOnExo.value)
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertFalse(c.liveOnExo.value)
        assertTrue(engines.log.contains("mpv:${channel(3).streamUrl}"))
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(
            listOf(PlayerFailureReason.LIVE_FALLBACK, PlayerFailureReason.LIVE_NO_FALLBACK),
            host.events,
        )
    }

    @Test
    fun `the give-up alarm ends a tune that never opens`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(4))
        advanceTimeBy(9_000)
        assertFalse(engines.log.contains("exo-abandon"))
        advanceTimeBy(1_001)
        assertEquals("exo-abandon", engines.log.last())
        assertEquals(listOf(PlayerFailureReason.LIVE_NO_FALLBACK), host.events)
    }

    @Test
    fun `a channel that opens stands the alarm down`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(5))
        runCurrent()
        engines.exoWatch!!.complete(null)
        advanceTimeBy(60_000)
        assertFalse(engines.log.contains("exo-abandon"))
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `a late failure of a replaced tune changes nothing`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(6))
        runCurrent()
        val oldWatch = engines.exoWatch!!
        c.tune(channel(7))
        runCurrent()
        oldWatch.complete("late failure")
        advanceTimeBy(5_000)
        assertFalse(engines.log.any { it.startsWith("mpv:") })
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `handing the player to an archive cancels a live tune still in flight`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { sourceDelayMs = 100 }
        val c = controller(engines, host)
        c.tune(channel(8))
        advanceTimeBy(50)
        var archiveStarted = false
        c.launch {
            releaseForArchive()
            delay(10) // a catch-up resolving its archive URL
            archiveStarted = true
        }
        advanceTimeBy(5_000)
        assertTrue("the catch-up must survive its own release", archiveStarted)
        assertTrue("the live stream must not start over the archive", engines.log.isEmpty())
    }

    @Test
    fun `the engine button pins the choice and stays on that engine`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.tune(channel(9))
        runCurrent()
        c.toggleEngine()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals(listOf(true), host.pins)
        assertTrue(engines.log.contains("mpv:${channel(9).streamUrl}"))
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        advanceTimeBy(5_000)
        // "mpv only" for this tune: no hand-back to ExoPlayer, the failure stays on screen.
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(1, engines.log.count { it.startsWith("exo:") })
    }

    @Test
    fun `a tune on mpv with nothing on ExoPlayer does not wait for a decoder`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { preference = EnginePreference.MPV_FIRST }
        val c = controller(engines, host)
        c.tune(channel(10))
        runCurrent()
        assertTrue(engines.log.contains("mpv:${channel(10).streamUrl}"))
    }

    @Test
    fun `a tune started before the settings are read waits for them instead of using defaults`() = runTest {
        val engines = FakeEngines()
        val stored = CompletableDeferred<EnginePreference>()
        val host = object : LiveTuneController.Host by FakeHost(this) {
            override suspend fun globalPreference() = stored.await()
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel(12))
        advanceTimeBy(1_000)
        assertTrue("nothing may open on a default while the store is unread", engines.log.isEmpty())
        stored.complete(EnginePreference.MPV_ONLY) // the store's first read lands: the user chose mpv
        runCurrent()
        assertEquals(listOf("mpv:${channel(12).streamUrl}"), engines.log)
    }

    @Test
    fun `pressing OK on the channel being previewed promotes it instead of rebuilding`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this)
        val c = controller(engines, host)
        c.preview(channel(11), muted = true)
        runCurrent()
        c.tune(channel(11))
        runCurrent()
        assertEquals(listOf("exo:${channel(11).streamUrl}", "exo-unmute"), engines.log)
    }
}
