package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.core.timeshift.TimeshiftServer
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * P16c — the controller with local timeshift on: which channels play from a saved copy, that both
 * engines of the ladder are handed the copy (never the provider), and that leaving parks it. Real
 * buffers against a fake provider on loopback, so this runs on real time.
 */
class LiveTuneControllerTimeshiftTest {

    private val provider = ServerSocket(0)
    @Volatile private var open = true
    private val dir = Files.createTempDirectory("ts-controller").toFile()
    private val manager = TimeshiftManager(OkHttpClient(), { dir })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        thread(isDaemon = true) {
            while (open) {
                val s = runCatching { provider.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            while (r.readLine()?.isNotEmpty() == true) Unit
                            val out = it.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                            while (open) {
                                out.write(nullPacket)
                                out.flush()
                                Thread.sleep(1)
                            }
                        }
                    }
                }
            }
        }
    }

    @After
    fun cleanUp() {
        open = false
        scope.cancel()
        manager.closeAll()
        provider.close()
        runCatching { dir.deleteRecursively() }
    }

    private class Engines : LiveEngines {
        val log = mutableListOf<String>()
        override var exoUrl: String? = null
        override val exoIsHls = false
        override var exoFailed = false
        override var mpvHasStream = false
        var exoWatch: CompletableDeferred<String?>? = null

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) { exoUrl = url; log += "exo:$url" }
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
            val reason = CompletableDeferred<String?>().also { exoWatch = it }.await()
            if (reason == null) onOpened() else if (stillOurs()) handOver(reason)
        }
        override fun mpvPlay(url: String, request: LiveRequest) { mpvHasStream = true; log += "mpv:$url" }
        override fun mpvStop() { mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() { mpvHasStream = false }
        override fun mpvAbandon(reason: String) { log += "mpv-abandon" }
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? = withTimeoutOrNull(timeoutMs) {
            CompletableDeferred<MpvOutcome>().await()
        }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
    }

    private inner class Host : LiveTuneController.Host {
        override suspend fun sourceOf(sourceId: Long): SourceEntity? = null
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {}
        override suspend fun globalPreference() = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs() = 0
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {}
        override val timeshift: TimeshiftManager get() = manager
        override suspend fun timeshiftWindowMinutes(): Int? = 15
    }

    private fun channel(id: Long, catchup: Boolean = false) = ChannelEntity(
        id = id,
        sourceId = 1,
        name = "ch$id",
        streamUrl = "http://127.0.0.1:${provider.localPort}/live/$id.ts",
        catchup = catchup,
    )

    private suspend fun awaitLog(engines: Engines, what: (List<String>) -> Boolean) {
        repeat(100) { if (what(engines.log.toList())) return; delay(50) }
    }

    @Test
    fun `a channel without catch-up plays from its saved copy, on both engines of the ladder`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(1))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        val exo = engines.log.first { it.startsWith("exo:") }.removePrefix("exo:")
        assertTrue("ExoPlayer is given the copy, at the live edge: $exo", TimeshiftServer.isLocal(exo) && exo.contains("/live."))
        assertNotNull(c.localTimeshift.value)

        // ExoPlayer fails: mpv is handed the same copy, never the provider.
        engines.exoWatch!!.complete("decoder error")
        awaitLog(engines) { log -> log.any { it.startsWith("mpv:") } }
        val mpv = engines.log.first { it.startsWith("mpv:") }.removePrefix("mpv:")
        assertEquals(exo, mpv)
    }

    @Test
    fun `a catch-up channel keeps the provider's archive and is not saved`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(2, catchup = true))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        assertEquals("exo:${channel(2).streamUrl}", engines.log.first { it.startsWith("exo:") })
        assertNull(c.localTimeshift.value)
    }

    @Test
    fun `browsing to another channel in the pane leaves this one, and its copy is parked`() = runBlocking {
        val engines = Engines()
        val c = LiveTuneController(scope, engines, Host())
        c.tune(channel(3))
        awaitLog(engines) { log -> log.any { it.startsWith("exo:") } }
        val session = c.localTimeshift.value!!.session
        c.detach()
        c.preview(channel(4), muted = true)
        assertNull(c.localTimeshift.value)
        assertFalse("parked, not deleted", session.isClosed)
    }

    private companion object {
        /** A null TS packet: valid MPEG-TS the cutter syncs on, and enough to open a buffer. */
        val nullPacket = ByteArray(188).also {
            it[0] = 0x47; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10
        }
    }
}
