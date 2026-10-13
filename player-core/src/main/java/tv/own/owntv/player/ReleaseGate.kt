package tv.own.owntv.player

/**
 * `stopAllStreamsAndAwait(sourceId)` (host failover, design §8, appendix A6): everything that can hold a
 * stream socket is stopped, and proven stopped, **before** another server is opened. On a one-connection
 * account that is the whole guarantee — the panel counts sessions, and a second socket on the next server
 * while the first still lingers is a 458 on our own account.
 *
 * Order, each step finished before the next:
 * 1. refuse while a recording or a download holds the account, a connection measurement runs, or a
 *    timeshift buffer is saving this source — failover is off then (the error shows as before);
 * 2. ExoPlayer (its own reconnects included), the Multiview tiles and the Home hero preview stop;
 * 3. mpv stops and its executor is drained; if that times out (mpv blocked reading a dead server, which is
 *    the case being handled), its instance is destroyed and the destroy awaited;
 * 4. the stream client has no call running, then its idle sockets are closed.
 *
 * Pure orchestration over [Steps], so the order can be tested with fakes.
 */
class ReleaseGate(private val steps: Steps) {

    interface Steps {
        /** Why a stream may not be stopped for [sourceId] now, or null when it may. */
        fun refusal(sourceId: Long): Refusal?
        /** ExoPlayer full-screen/preview (reconnects off), every Multiview tile, the hero preview. */
        fun stopExoEngines()
        /** mpv stop + executor barrier, bounded; false = timed out. */
        suspend fun releaseMpv(): Boolean
        /** Destroy the mpv instance (hardReset path) and wait for the destroy to finish. */
        suspend fun destroyMpv()
        /** Wait for no running stream call, then evict idle sockets; false = still busy at the bound. */
        suspend fun awaitQuiescent(): Boolean
        fun log(message: String) {}
    }

    enum class Refusal { RECORDING, DOWNLOAD, MEASURING, TIMESHIFT }

    sealed interface Result {
        /** Nothing of ours holds a stream any more; [quiet] = the stream client had no call left. */
        data class Released(val mpvDestroyed: Boolean, val quiet: Boolean) : Result
        data class Refused(val why: Refusal) : Result
    }

    suspend fun stopAllStreamsAndAwait(sourceId: Long): Result {
        steps.refusal(sourceId)?.let {
            steps.log("release gate: refused (${it.name.lowercase()})")
            return Result.Refused(it)
        }
        steps.stopExoEngines()
        var destroyed = false
        if (!steps.releaseMpv()) {
            steps.log("release gate: mpv did not let go in time — destroying the instance")
            steps.destroyMpv()
            destroyed = true
        }
        val quiet = steps.awaitQuiescent()
        steps.log("release gate: released (mpvDestroyed=$destroyed quiet=$quiet)")
        return Result.Released(destroyed, quiet)
    }
}

/**
 * The production [ReleaseGate.Steps]: the app's own engines plus everything that can claim the account
 * outside them. Main thread, like the engines. Built once (a Koin single) by the app's player module.
 */
@androidx.media3.common.util.UnstableApi
class PlaybackReleaseSteps(
    private val player: OwnTVPlayer,
    private val livePreview: LivePreviewEngine,
    private val pool: LiveEnginePool,
    private val heroPreview: HeroPreviewEngine?,
    private val streaming: tv.own.owntv.core.network.StreamingHttpClient,
    private val openStreams: tv.own.owntv.core.live.OpenStreamRegistry,
    private val downloads: tv.own.owntv.core.download.DownloadActivityTracker,
    private val timeshift: tv.own.owntv.core.timeshift.TimeshiftManager?,
    private val hosts: tv.own.owntv.core.network.HostSelector,
) : ReleaseGate.Steps {

    override fun refusal(sourceId: Long): ReleaseGate.Refusal? = when {
        openStreams.openOn(sourceId).recording > 0 -> ReleaseGate.Refusal.RECORDING
        // A download's playlist isn't tracked; one running anywhere may hold this account's only slot.
        downloads.active.value != null -> ReleaseGate.Refusal.DOWNLOAD
        hosts.probeLock.isLocked -> ReleaseGate.Refusal.MEASURING
        timeshift?.isSaving(sourceId) == true -> ReleaseGate.Refusal.TIMESHIFT
        else -> null
    }

    override fun stopExoEngines() {
        livePreview.stop()
        pool.releaseAll()
        heroPreview?.stop()
    }

    override suspend fun releaseMpv(): Boolean = player.stopAndAwaitRelease()

    override suspend fun destroyMpv() {
        player.destroyAndAwait()
    }

    override suspend fun awaitQuiescent(): Boolean = streaming.awaitQuiescent()

    override fun log(message: String) {
        android.util.Log.i(LiveTuneController.ENGINE_TAG, message)
        LiveDiagnosticsLog.event("engine: $message")
    }
}
