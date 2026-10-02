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

        /** The tune's alarm postponement, so a test can drive a provider back-off of its own. */
        var postpone: ((Long) -> Unit)? = null

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
            postpone = postponeDeadline
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

        /** Smart Provider (phase 4) — the substitutes this host is willing to offer, in its own order. */
        val candidates = mutableListOf<ChannelEntity>()

        /** Every ask, with the providers the controller said it had already spent. */
        val asked = mutableListOf<Set<Long>>()

        /** The smart-provider calls in the order they were made, for ordering assertions. */
        val steps = mutableListOf<String>()

        /** Every provider attempt reported as over, with the player's own text for why. */
        val failedProviders = mutableListOf<Pair<ChannelEntity, String>>()

        /** Every tune reported as having a picture. */
        val opened = mutableListOf<ChannelEntity>()

        /** Every playlist row asked for — the anchor's and each substitute's. */
        val sourcesAsked = mutableListOf<Long>()

        /** How long the host takes to answer, for a zap landing on an ask still in flight. */
        var candidateDelayMs = 0L

        /** A host that answers badly on purpose, instead of [candidates]. */
        var candidateAnswer: ((ChannelEntity, Set<Long>) -> ChannelEntity?)? = null

        override suspend fun sourceOf(sourceId: Long): SourceEntity? {
            sourcesAsked += sourceId
            if (sourceDelayMs > 0) delay(sourceDelayMs)
            return null
        }

        override suspend fun nextProviderCandidate(
            anchor: ChannelEntity,
            triedSourceIds: Set<Long>,
        ): ChannelEntity? {
            asked += triedSourceIds
            steps += "ask"
            if (candidateDelayMs > 0) delay(candidateDelayMs)
            candidateAnswer?.let { return it(anchor, triedSourceIds) }
            return candidates.firstOrNull { it.sourceId !in triedSourceIds }
        }

        override fun onProviderOpened(anchor: ChannelEntity, channel: ChannelEntity) {
            steps += "opened:${channel.name}"
            opened += channel
        }

        override fun onProviderFailed(anchor: ChannelEntity, channel: ChannelEntity, detail: String) {
            steps += "failed:${channel.name}"
            failedProviders += channel to detail
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

    private fun channel(id: Long, sourceId: Long = 1) = ChannelEntity(
        id = id,
        sourceId = sourceId,
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

    // --- Smart Provider (phase 4): the walk across providers ---------------------------------------

    @Test
    fun `a channel that opens on its own playlist never leaves it for another provider`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(21, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(20))
        runCurrent()
        engines.exoWatch!!.complete(null) // a picture on the playlist the user picked
        advanceTimeBy(60_000)
        assertTrue("nothing may be asked for once a picture is on screen", host.asked.isEmpty())
        assertEquals(listOf(channel(20)), host.opened)
        assertEquals(listOf("exo:${channel(20).streamUrl}"), engines.log)
        assertFalse(engines.log.contains("exo-abandon"))
    }

    @Test
    fun `an exhausted playlist hands the channel to one candidate as a whole new tune`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(31, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(30))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1) // the ladder climbs to mpv
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1) // the substitute takes the engine over
        assertEquals(
            "the substitute is a tune of its own, not a rung of the anchor's ladder",
            listOf(
                "exo:${channel(30).streamUrl}",
                "mpv:${channel(30).streamUrl}",
                "exo:${channel(31).streamUrl}",
            ),
            engines.log,
        )
        assertEquals(listOf(setOf(1L)), host.asked)
        assertTrue("nothing has a picture yet", host.opened.isEmpty())
        assertTrue(c.liveOnExo.value)
        assertEquals(1, host.failedProviders.size)
    }

    @Test
    fun `the provider that failed is reported before the next one is asked for`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(33, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(32))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals(listOf("failed:ch32", "ask"), host.steps)
        assertEquals(listOf(channel(32) to "mpv couldn't play it: nope"), host.failedProviders)
        assertEquals(
            listOf(PlayerFailureReason.LIVE_FALLBACK, PlayerFailureReason.LIVE_FALLBACK),
            host.events,
        )
    }

    @Test
    fun `a candidate that opens ends the walk where it is`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            candidates += channel(35, sourceId = 2)
            candidates += channel(36, sourceId = 3)
        }
        val c = controller(engines, host)
        c.tune(channel(34))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.exoWatch!!.complete(null) // the substitute has a picture
        runCurrent()
        assertEquals(listOf(channel(35, sourceId = 2)), host.opened)
        assertEquals(1, host.asked.size)
        assertEquals(1, host.failedProviders.size)
        val tuned = engines.log.size
        advanceTimeBy(120_000)
        assertEquals("nothing may be tuned once a picture is up", tuned, engines.log.size)
        assertFalse(engines.log.contains("exo-abandon"))
    }

    @Test
    fun `a substitute takes the engine over rather than stacking a second stream`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(38, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(37))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertTrue("the failed playlist has mpv", engines.mpvHasStream)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals(
            "only the failed playlist has opened anything yet",
            1,
            engines.log.count { it.startsWith("exo:") },
        )
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertFalse("mpv must let go of the surface before ExoPlayer claims it", engines.mpvHasStream)
        assertEquals(2, engines.log.count { it.startsWith("exo:") })
        assertEquals(channel(38).streamUrl, engines.exoUrl)
    }

    @Test
    fun `a substitute that fails an engine climbs its own ladder before another provider is asked`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(40, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(39))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1) // the substitute is on ExoPlayer
        assertEquals(1, host.asked.size)
        assertEquals(1, host.failedProviders.size)
        engines.exoWatch!!.complete("also boom") // the substitute's own first engine fails
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertTrue(
            "the substitute climbs to mpv like any other tune",
            engines.log.contains("mpv:${channel(40).streamUrl}"),
        )
        assertEquals("no provider is asked for while the substitute has an engine left", 1, host.asked.size)
        assertEquals(1, host.failedProviders.size)
    }

    @Test
    fun `a substitute that fails both engines hands the walk on to the next provider`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            candidates += channel(42, sourceId = 2)
            candidates += channel(43, sourceId = 3)
        }
        val c = controller(engines, host)
        c.tune(channel(41))
        runCurrent()
        engines.exoWatch!!.complete("boom") // the anchor's playlist fails both engines
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals(listOf(setOf(1L)), host.asked)
        assertEquals(listOf("failed:ch41", "ask"), host.steps)
        engines.exoWatch!!.complete("boom") // the substitute's playlist fails both engines too
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals("the walk goes on from a substitute, and says what has been spent", listOf(setOf(1L), setOf(1L, 2L)), host.asked)
        assertEquals(
            listOf("failed:ch41", "ask", "failed:ch42", "ask"),
            host.steps,
        )
        assertEquals(
            listOf(
                "exo:${channel(41).streamUrl}",
                "mpv:${channel(41).streamUrl}",
                "exo:${channel(42).streamUrl}",
                "mpv:${channel(42).streamUrl}",
                "exo:${channel(43).streamUrl}",
            ),
            engines.log,
        )
    }

    @Test
    fun `a candidate on a provider already spent is refused and the tune gives up`() = runTest {
        val engines = FakeEngines()
        // A host that hands back the playlist the tune has just given up on.
        val host = FakeHost(this).apply { candidateAnswer = { anchor, _ -> anchor } }
        val c = controller(engines, host)
        c.tune(channel(44))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(
            "no second tune of a playlist already known bad",
            1,
            engines.log.count { it.startsWith("exo:") },
        )
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(PlayerFailureReason.LIVE_NO_FALLBACK, host.events.last())
        assertEquals(1, host.asked.size)
    }

    @Test
    fun `a newer tune cancels a substitute still being asked for`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            candidates += channel(46, sourceId = 2)
            candidateDelayMs = 200 // the host is still looking when the user zaps away
        }
        val c = controller(engines, host)
        c.tune(channel(45))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals(1, host.asked.size)
        c.tune(channel(47)) // the user leaves while the next provider is being looked up
        advanceTimeBy(10_000)
        assertFalse(
            "the substitute must never reach the screen",
            engines.log.any { it.contains(channel(46).streamUrl) },
        )
        assertEquals(channel(47).streamUrl, engines.exoUrl)
        assertEquals(1, host.asked.size)
    }

    @Test
    fun `the give-up alarm hands the channel to another provider before giving up`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            budgetSecs = 10
            candidates += channel(51, sourceId = 2)
        }
        val c = controller(engines, host)
        c.tune(channel(50))
        runCurrent()
        advanceTimeBy(10_001) // the anchor's window expires with no picture on it
        assertEquals(
            "the provider is reported with the length of the window it was given",
            listOf(channel(50) to "no picture within 10s of tuning"),
            host.failedProviders,
        )
        assertEquals(
            listOf("exo:${channel(50).streamUrl}", "exo:${channel(51).streamUrl}"),
            engines.log,
        )
        assertFalse("the tune is not over — it is on the next provider", engines.log.contains("exo-abandon"))
        assertTrue(c.liveOnExo.value)
        assertEquals(1, host.asked.size)
        assertTrue(host.opened.isEmpty())
    }

    @Test
    fun `a substitute is resolved on its own playlist, not the anchor's`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(53, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(52))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals(
            "the substitute's own playlist row is what its URL and Stalker link are built from",
            listOf(1L, 2L),
            host.sourcesAsked,
        )
    }

    @Test
    fun `the walk stops at the provider limit, and at the deadline that limit is worth`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            budgetSecs = 10
            candidates += channel(61, sourceId = 2)
            candidates += channel(62, sourceId = 3)
            candidates += channel(63, sourceId = 4)
        }
        val c = controller(engines, host)
        c.tune(channel(60))
        runCurrent()
        advanceTimeBy(30_002) // three providers × the ten seconds the user allowed
        assertEquals(
            "three playlists get a window between them, no more",
            3,
            engines.log.count { it.startsWith("exo:") },
        )
        assertFalse(
            "the fourth playlist is past the walk's deadline",
            engines.log.any { it.contains(channel(63).streamUrl) },
        )
        assertEquals(2, host.asked.size)
        assertEquals("exo-abandon", engines.log.last())
        assertEquals(PlayerFailureReason.LIVE_NO_FALLBACK, host.events.last())
    }

    @Test
    fun `a provider back-off cannot carry the walk past its deadline`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            budgetSecs = 10
            candidates += channel(65, sourceId = 2)
        }
        val c = controller(engines, host)
        c.tune(channel(64))
        runCurrent()
        // The panel backs this provider off for longer than the walk's whole deadline. OwnTV waits — the
        // wait is one it agreed to — but the walk's clock is the user's, and that one does not move.
        engines.postpone!!(40_000)
        advanceTimeBy(60_000)
        assertTrue("no provider may be given a window past the deadline", host.asked.isEmpty())
        assertEquals(
            "the provider is still reported as having failed",
            listOf(channel(64) to "no picture within 10s of tuning"),
            host.failedProviders,
        )
        assertEquals("the tune gives up on the playlist it is on", "exo-abandon", engines.log.last())
        assertEquals(1, engines.log.count { it.startsWith("exo:") })
        assertEquals(PlayerFailureReason.LIVE_NO_FALLBACK, host.events.last())
    }

    @Test
    fun `a walk already looking up the next provider is not preempted by the alarm`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply {
            budgetSecs = 10
            candidates += channel(67, sourceId = 2)
            candidateDelayMs = 15_000 // the host takes longer to answer than the window it was given
        }
        val c = controller(engines, host)
        c.tune(channel(66))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent() // the ladder is spent, and the host is being asked for the next provider
        advanceTimeBy(11_000) // the anchor's own window is up with the ask still in flight
        assertFalse(
            "no give-up may go on screen for a substitute already on its way",
            engines.log.contains("mpv-abandon"),
        )
        advanceTimeBy(10_000) // the ask lands
        assertTrue(
            "the substitute is tuned once the host answers",
            engines.log.contains("exo:${channel(67).streamUrl}"),
        )
        assertEquals(1, host.asked.size)
        assertEquals("the provider's failure is reported once", 1, host.failedProviders.size)
    }

    @Test
    fun `the engine button after a substitute opened re-arms that provider, not the anchor`() = runTest {
        val engines = FakeEngines()
        val host = FakeHost(this).apply { candidates += channel(71, sourceId = 2) }
        val c = controller(engines, host)
        c.tune(channel(70))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        engines.exoWatch!!.complete(null) // the substitute opens
        runCurrent()
        assertEquals(listOf(channel(71, sourceId = 2)), host.opened)
        c.toggleEngine() // the user asks for the other engine
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertEquals("the substitute's own playlist is the one re-armed", listOf(true), host.pins)
        assertTrue(engines.log.contains("mpv:${channel(71).streamUrl}"))
        assertEquals("the button never asks for another provider", 1, host.asked.size)
        advanceTimeBy(60_000) // and mpv never opens it either
        assertEquals(
            "a picture already ended the walk: no provider is looked for after one",
            1,
            host.asked.size,
        )
        assertEquals(listOf(channel(71, sourceId = 2)), host.opened)
        assertEquals("mpv-abandon", engines.log.last())
    }

    @Test
    fun `a provider that runs out of engines is not asked about again when its window expires`() = runTest {
        val engines = FakeEngines()
        // No candidate at all: the first walk has nowhere to go, and comes back straight away.
        val host = FakeHost(this).apply { budgetSecs = 10 }
        val c = controller(engines, host)
        c.tune(channel(72))
        runCurrent()
        engines.exoWatch!!.complete("boom")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1) // the ladder climbs to mpv
        engines.mpvWatch!!.complete(MpvOutcome(opened = false, error = "nope"))
        runCurrent()
        assertEquals("mpv-abandon", engines.log.last())
        assertEquals(1, host.asked.size)
        advanceTimeBy(60_000) // the window that was still counting down expires on a tune already over
        assertEquals(1, host.asked.size)
        assertEquals("the provider is reported once, not once per clock", 1, host.failedProviders.size)
        assertEquals(1, engines.log.count { it == "mpv-abandon" })
        assertEquals(
            listOf(PlayerFailureReason.LIVE_FALLBACK, PlayerFailureReason.LIVE_NO_FALLBACK),
            host.events,
        )
    }
}
