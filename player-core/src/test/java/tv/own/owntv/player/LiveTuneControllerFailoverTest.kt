package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HostFailureClass
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/**
 * AC2/AC3/AC4/AC6/AC7/AC8 on the live path, with fake engines on virtual time: one stream at a time,
 * gate → preflight → open, no format rungs on a dead server, one write per switch, a finite round.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTuneControllerFailoverTest {

    /** Every stream open, stop, gate and preflight, in order — the single-connection evidence. */
    private val order = mutableListOf<String>()

    private inner class Engines : LiveEngines {
        override var exoUrl: String? = null
        override var exoIsHls = false
        override var exoFailed = false
        override var mpvHasStream = false
        override var exoHostFailure = HostFailureClass.UNKNOWN
        override var mpvLastError: String? = null
        var exoWatch: CompletableDeferred<String?>? = null
        var mpvWatch: CompletableDeferred<MpvOutcome>? = null
        val exhausted = CompletableDeferred<Unit>()
        /** Streams open right now; must never exceed 1. */
        var open = 0
        var peak = 0
        var lastOpenTimeoutMs = 0L
        var lastMpvTimeoutMs = 0L

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) {
            if (exoUrl != null) open--
            exoUrl = url; open++; peak = maxOf(peak, open)
            order += "open:${HostPool.origin(url)}"
        }
        override fun exoSetMuted(muted: Boolean) {}
        override fun exoStop() { if (exoUrl != null) { open--; order += "stop" }; exoUrl = null }
        override fun exoReleaseUhdDecoder() {}
        override fun exoAbandon(reason: String) { order += "abandon" }
        override suspend fun watchExo(
            channelName: String, stillOurs: () -> Boolean, handOver: suspend (String) -> Unit,
            onOpened: () -> Unit, postponeDeadline: (Long) -> Unit, log: (String) -> Unit,
            openTimeoutMs: Long,
        ) {
            lastOpenTimeoutMs = openTimeoutMs
            val d = CompletableDeferred<String?>().also { exoWatch = it }
            val reason = d.await()
            if (reason == null) onOpened() else if (stillOurs()) handOver(reason)
        }
        override fun mpvPlay(url: String, request: LiveRequest) {
            mpvHasStream = true; open++; peak = maxOf(peak, open)
            order += "mpv-open:${HostPool.origin(url)}"
        }
        override fun mpvStop() { if (mpvHasStream) open--; mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() = mpvStop()
        override fun mpvAbandon(reason: String) { order += "abandon" }
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? {
            lastMpvTimeoutMs = timeoutMs
            val d = CompletableDeferred<MpvOutcome>().also { mpvWatch = it }
            return withTimeoutOrNull(timeoutMs) { d.await() }
        }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
        override suspend fun awaitExoReconnectsExhausted() = exhausted.await()
        override suspend fun awaitMpvLiveLost() = awaitCancellation()
    }

    private val source = SourceEntity(
        id = 1, name = "X", type = SourceType.XTREAM, url = "http://a.example.top", username = "u", password = "p",
        maxConnections = 1,
        backupUrls = HostPool.encode(listOf("http://b.example.top", "http://c.example.top")),
    )
    private val writes = mutableListOf<String?>()
    private val selector = HostSelector { _, url -> writes += url }

    private open inner class Host(private val scope: TestScope) : LiveTuneController.Host {
        /** Preflight answers per server; unlisted = OK. */
        val answers = mutableMapOf<String, HostPreflight.Result>()
        val switched = mutableListOf<Int>()
        var exhaustedTotal: Int? = null
        var refuse: ReleaseGate.Refusal? = null
        var budgetSecs = 0
        var reserved = 0
        var peakReserved = 0
        override fun reserveWatching(sourceId: Long): () -> Unit {
            reserved++
            peakReserved = maxOf(peakReserved, reserved)
            return { reserved-- }
        }
        override suspend fun sourceOf(sourceId: Long) = source
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {}
        override suspend fun globalPreference() = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs() = budgetSecs
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {}
        override fun nowMs(): Long = scope.testScheduler.currentTime
        override val hostSelector get() = selector
        override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result {
            order += "preflight:$server"
            return answers[server] ?: HostPreflight.Result.Ok(10, XtreamClient.XtAccountDetails())
        }
        override suspend fun releaseAllStreams(sourceId: Long): ReleaseGate.Result {
            order += "gate"
            return refuse?.let { ReleaseGate.Result.Refused(it) } ?: ReleaseGate.Result.Released(false, true)
        }
        override fun onServerSwitched(source: SourceEntity, position: Int, total: Int, server: String) { switched += position }
        override fun onServersExhausted(source: SourceEntity, total: Int) { exhaustedTotal = total }
    }

    private val channel = ChannelEntity(id = 7, sourceId = 1, name = "ch", streamUrl = "http://a.example.top/live/u/p/7.ts")

    @Before
    fun clean() = LiveStreamQuirks.clearForTest()

    @Test
    fun `a dead primary switches to the next server with nothing open in between, then persists it once`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        assertEquals(listOf("open:http://a.example.top"), order)
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("ExoPlayer error: connection refused")
        runCurrent()
        engines.exoHostFailure = HostFailureClass.UNKNOWN
        // stop → gate → preflight → open: never two servers at once.
        assertEquals(
            listOf("open:http://a.example.top", "stop", "gate", "preflight:http://b.example.top", "open:http://b.example.top"),
            order,
        )
        assertFalse("no format rung on a dead server", order.any { it.startsWith("mpv-open") })
        assertTrue(writes.isEmpty())
        engines.exoWatch!!.complete(null) // first frame on b
        runCurrent()
        assertEquals(listOf<String?>("http://b.example.top"), writes)
        assertEquals(listOf(2), host.switched)
        assertEquals(1, engines.peak)
        // The next tune of any channel of this source starts on b.
        assertEquals("http://b.example.top", selector.current(source))
    }

    @Test
    fun `a parked domain's HTML is skipped and an all-dead pool ends the round with no automatic retry`() = runTest {
        val engines = Engines()
        val host = Host(this).apply {
            answers["http://b.example.top"] = HostPreflight.Result.Invalid
            answers["http://c.example.top"] = HostPreflight.Result.Down("refused")
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(
            listOf(
                "open:http://a.example.top", "stop", "gate",
                "preflight:http://b.example.top", "preflight:http://c.example.top", "abandon",
            ),
            order,
        )
        assertEquals(3, host.exhaustedTotal)
        assertTrue(writes.isEmpty())
        assertEquals("http://a.example.top", selector.current(source))
        assertEquals(0, engines.open)
    }

    @Test
    fun `a never-class failure walks the ladder as before and never switches`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.NEVER // e.g. 503, a read timeout after headers
        engines.exoWatch!!.complete("HTTP 503")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertFalse(order.any { it == "gate" || it.startsWith("preflight") })
        assertTrue(order.contains("mpv-open:http://a.example.top"))
    }

    @Test
    fun `the account being refused ends the round on the first backup`() = runTest {
        val engines = Engines()
        val host = Host(this).apply { answers["http://b.example.top"] = HostPreflight.Result.AccountEnded(HostPreflight.Reason.EXPIRED) }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("open:http://a.example.top", "stop", "gate", "preflight:http://b.example.top", "abandon"), order)
        assertEquals(null, host.exhaustedTotal)
    }

    @Test
    fun `a busy account is waited out on the same server, never advanced past`() = runTest {
        val engines = Engines()
        val host = Host(this).apply { answers["http://b.example.top"] = HostPreflight.Result.Busy(-1, 1, 1) }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        advanceTimeBy(10_001)
        host.answers.remove("http://b.example.top") // our old session expired
        advanceTimeBy(5_001)
        assertFalse(order.contains("preflight:http://c.example.top"))
        assertEquals("open:http://b.example.top", order.last())
    }

    @Test
    fun `a recording holding the account refuses the switch and shows the error`() = runTest {
        val engines = Engines()
        val host = Host(this).apply { refuse = ReleaseGate.Refusal.RECORDING }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("open:http://a.example.top", "stop", "gate", "abandon"), order)
    }

    @Test
    fun `mid-play, only spent reconnects start a round`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoWatch!!.complete(null) // played on a
        runCurrent()
        assertTrue(writes.isEmpty()) // the primary playing is no switch
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exhausted.complete(Unit)
        runCurrent()
        assertEquals("open:http://b.example.top", order.last())
        assertEquals(1, engines.peak)
    }

    @Test
    fun `without backups nothing changes`() = runTest {
        val engines = Engines()
        val host = object : LiveTuneController.Host by Host(this) {
            override suspend fun sourceOf(sourceId: Long) = source.copy(backupUrls = null)
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        assertFalse(order.any { it == "gate" || it.startsWith("preflight") })
        assertEquals("mpv-open:http://a.example.top", order.last())
    }

    private val channel2 = ChannelEntity(id = 8, sourceId = 1, name = "ch2", streamUrl = "http://a.example.top/live/u/p/8.ts")

    @Test
    fun `a zap during a backoff cancels the round and opens nothing else for the old channel`() = runTest {
        val engines = Engines()
        val host = Host(this).apply { answers["http://b.example.top"] = HostPreflight.Result.Down("refused") }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        engines.exoHostFailure = HostFailureClass.UNKNOWN
        assertEquals("preflight:http://b.example.top", order.last())
        advanceTimeBy(500) // inside the 1 s backoff before c
        c.tune(channel2)
        advanceTimeBy(60_000); runCurrent()
        assertFalse("the cancelled round never checks c", order.contains("preflight:http://c.example.top"))
        assertEquals("open:http://a.example.top", order.last())
        assertTrue(writes.isEmpty())
        assertEquals(1, engines.peak)
    }

    @Test
    fun `a zap before the new server's first frame abandons it and does not confirm it`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        engines.exoHostFailure = HostFailureClass.UNKNOWN
        assertEquals("open:http://b.example.top", order.last())
        c.tune(channel2)
        runCurrent()
        assertEquals("open:http://a.example.top", order.last())
        engines.exoWatch!!.complete(null)
        runCurrent()
        assertTrue(writes.isEmpty())
        assertTrue(host.switched.isEmpty())
        assertEquals(1, engines.peak)
    }

    @Test
    fun `a session limit after the switch waits on that server and never opens the next`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        engines.exoHostFailure = HostFailureClass.WAIT
        engines.exoWatch!!.complete("HTTP 458")
        runCurrent()
        // Stopped and gated, and still inside the wait: the next server is not touched.
        assertEquals("gate", order.last())
        assertFalse(order.contains("preflight:http://c.example.top"))
        advanceTimeBy(ServerRound.SESSION_RECHECK_MS - 1)
        runCurrent()
        assertEquals("gate", order.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals("open:http://b.example.top", order.last())
        assertFalse(order.any { it.contains("c.example") })
        assertTrue(writes.isEmpty())
        assertEquals(1, engines.peak)
        // The whole 45 s can pass and the round still does not move on.
        repeat(20) {
            if ("abandon" in order) return@repeat
            val watch = engines.exoWatch ?: return@repeat
            if (watch.isCompleted) return@repeat
            engines.exoHostFailure = HostFailureClass.WAIT
            watch.complete("HTTP 458")
            runCurrent()
            if ("abandon" in order) return@repeat
            advanceTimeBy(ServerRound.SESSION_RECHECK_MS)
            runCurrent()
        }
        assertFalse(order.any { it.contains("c.example") })
        assertFalse(order.any { it.startsWith("preflight:") && it != "preflight:http://b.example.top" })
        assertTrue(order.contains("abandon"))
        assertEquals(0, engines.open)
        assertEquals(1, engines.peak)
    }

    @Test
    fun `AC19 toggleEngine is ignored during a 458 session wait`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        engines.exoHostFailure = HostFailureClass.WAIT
        engines.exoWatch!!.complete("HTTP 458")
        runCurrent()
        assertEquals("gate", order.last())
        assertFalse(c.toggleEngine())
        advanceTimeBy(ServerRound.SESSION_RECHECK_MS - 1)
        runCurrent()
        assertFalse(order.any { it.startsWith("mpv-open:") })
        assertEquals(1, engines.peak)
        assertEquals("gate", order.last())
    }

    @Test
    fun `toggleEngine is ignored while a failover is suspended in preflight`() = runTest {
        val engines = Engines()
        val hold = CompletableDeferred<Unit>()
        val host = object : Host(this) {
            override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result {
                order += "preflight:$server"
                if (server == "http://b.example.top") hold.await()
                return answers[server] ?: HostPreflight.Result.Ok(10, XtreamClient.XtAccountDetails())
            }
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        assertEquals("preflight:http://b.example.top", order.last())
        assertFalse(c.toggleEngine())
        advanceTimeBy(OwnTVPlayer.SURFACE_HANDOFF_MS + 1)
        runCurrent()
        assertFalse(order.any { it.startsWith("mpv-open:") })
        assertTrue(writes.isEmpty())
        hold.complete(Unit)
        runCurrent()
        assertEquals("open:http://b.example.top", order.last())
    }

    @Test
    fun `AC21 Exo G=30 caps the open at 15s`() = runTest {
        val engines = Engines()
        val host = Host(this).apply { budgetSecs = 30 }
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        assertEquals(15_000L, engines.lastOpenTimeoutMs)
    }

    @Test
    fun `AC22 mpv G=30 caps the open at 15s`() = runTest {
        val engines = Engines()
        val host = object : Host(this) {
            override suspend fun enginePin(channel: ChannelEntity) = true
        }.apply { budgetSecs = 30 }
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        assertEquals(15_000L, engines.lastMpvTimeoutMs)
    }

    @Test
    fun `AC23a G=30 with failover verifies the current server instead of abandoning`() = runTest {
        val engines = Engines()
        val host = Host(this).apply {
            budgetSecs = 30
            answers["http://a.example.top"] = HostPreflight.Result.Down("refused")
        }
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(order.contains("preflight:http://a.example.top"))
        assertTrue(order.contains("preflight:http://b.example.top"))
        assertTrue(order.contains("open:http://b.example.top"))
        assertFalse(order.contains("abandon"))
    }

    @Test
    fun `AC23b G=30 with no backups abandons and does not preflight`() = runTest {
        val engines = Engines()
        val host = object : Host(this) {
            override suspend fun sourceOf(sourceId: Long) = source.copy(backupUrls = null)
        }.apply { budgetSecs = 30 }
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(order.contains("abandon"))
        assertFalse(order.any { it.startsWith("preflight") })
    }

    @Test
    fun `AC24 G=0 never gives up and still caps the open at 15s`() = runTest {
        val engines = Engines()
        val host = Host(this)
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(order.contains("abandon"))
        assertFalse(order.any { it.startsWith("preflight") })
        assertEquals(15_000L, engines.lastOpenTimeoutMs)
    }

    @Test
    fun `AC28 no backups keeps the 12s Exo open timeout`() = runTest {
        val engines = Engines()
        val host = object : Host(this) {
            override suspend fun sourceOf(sourceId: Long) = source.copy(backupUrls = null)
        }
        LiveTuneController(backgroundScope, engines, host).tune(channel)
        runCurrent()
        assertEquals(12_000L, engines.lastOpenTimeoutMs)
    }

    @Test
    fun `L4 cancelFailover in background stops a pending switch from opening`() = runTest {
        val engines = Engines()
        val hold = CompletableDeferred<Unit>()
        val host = object : Host(this) {
            override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result {
                order += "preflight:$server"
                if (server == "http://b.example.top") hold.await()
                return answers[server] ?: HostPreflight.Result.Ok(10, XtreamClient.XtAccountDetails())
            }
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        assertEquals("preflight:http://b.example.top", order.last())
        assertTrue(c.isSwitchingServers)
        c.cancelFailover()
        assertFalse(c.isSwitchingServers)
        hold.complete(Unit)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(order.any { it.startsWith("open:http://b") || it.startsWith("mpv-open:http://b") })
        assertFalse(c.isSwitchingServers)
    }

    @Test
    fun `P1 a switch holds a watching reservation from the gate until the open`() = runTest {
        val engines = Engines()
        val hold = CompletableDeferred<Unit>()
        val host = object : Host(this) {
            override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result {
                order += "preflight:$server"
                if (server == "http://b.example.top") hold.await()
                return answers[server] ?: HostPreflight.Result.Ok(10, XtreamClient.XtAccountDetails())
            }
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        assertEquals("preflight:http://b.example.top", order.last())
        assertEquals(1, host.reserved)
        assertEquals(1, host.peakReserved)
        hold.complete(Unit)
        runCurrent()
        assertEquals("open:http://b.example.top", order.last())
        assertEquals(0, host.reserved)
    }

    @Test
    fun `P4 stop after a switch opened but before it played drops the unconfirmed server`() = runTest {
        val engines = Engines()
        val host = Host(this)
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("ExoPlayer error: connection refused")
        runCurrent()
        assertEquals("open:http://b.example.top", order.last())
        assertTrue(writes.isEmpty())
        c.stop()
        runCurrent()
        assertEquals("http://a.example.top", selector.current(source))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `P1 cancelFailover while preflight is held drops the watching reservation`() = runTest {
        val engines = Engines()
        val hold = CompletableDeferred<Unit>()
        val host = object : Host(this) {
            override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result {
                order += "preflight:$server"
                if (server == "http://b.example.top") hold.await()
                return answers[server] ?: HostPreflight.Result.Ok(10, XtreamClient.XtAccountDetails())
            }
        }
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        engines.exoHostFailure = HostFailureClass.HOST
        engines.exoWatch!!.complete("refused")
        runCurrent()
        assertEquals(1, host.reserved)
        c.cancelFailover()
        runCurrent()
        assertEquals(0, host.reserved)
        hold.complete(Unit)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, host.reserved)
        assertFalse(order.any { it.startsWith("open:http://b") })
    }

    @Test
    fun `gap c resumeAfterBackground re-tunes through start on the in-use server`() = runTest {
        val engines = Engines()
        val host = Host(this)
        assertFalse(LiveTuneController(backgroundScope, engines, host).resumeAfterBackground())
        val c = LiveTuneController(backgroundScope, engines, host)
        c.tune(channel)
        runCurrent()
        c.cancelFailover()
        engines.exoStop()
        assertTrue(c.resumeAfterBackground())
        runCurrent()
        assertEquals("open:http://a.example.top", order.last())
    }
}
