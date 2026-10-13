package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.entity.playStreamUrl
import tv.own.owntv.core.database.entity.resolveStreamUrl
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.ForceMpvStore
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.network.HostFailover
import tv.own.owntv.core.network.HostFailure
import tv.own.owntv.core.network.HostFailureClass
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight
import tv.own.owntv.core.network.HostPreflight.isHostClass
import tv.own.owntv.core.network.HostSelector
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.timeshift.TimeshiftDownloader
import tv.own.owntv.core.timeshift.TimeshiftManager
import tv.own.owntv.core.timeshift.TimeshiftServer
import tv.own.owntv.core.timeshift.TimeshiftSession

/**
 * One live channel from "tune" to "playing" or "gave up", for both apps.
 *
 * This is the orchestration the television's `LiveViewModel` and the phone's `LiveTuner` each carried
 * in their own ~600 lines, and which had drifted apart: engine routing ([LiveRouting]), the fallback
 * [LiveLadder] and its "Give up after" alarm, the ExoPlayer ⇄ mpv handover with its decoder-release
 * wait, the Prefer-HLS `.ts` rung, HLS-redirect learning, the Stalker reconnect link and the
 * per-playlist overrides. Each app now keeps only presentation — which channel is on screen, the HUD,
 * history, casting.
 *
 * **Every tune is one job, and a newer one cancels it** ([launch]). Two quick picks can no longer
 * finish in the wrong order, and a catch-up started while a live tune is still resolving is not then
 * overtaken by it.
 *
 * Everything is main-thread: [scope] must be a Main-dispatched scope, as the engines require.
 */
class LiveTuneController(
    private val scope: CoroutineScope,
    private val engines: LiveEngines,
    private val host: Host,
) {

    /** What the controller needs from outside the engines. [CoreHost] is the one both apps use. */
    interface Host {
        suspend fun sourceOf(sourceId: Long): SourceEntity?
        /** A Stalker playlist stores a portal command, minted into a URL per play. */
        fun needsResolve(source: SourceEntity?): Boolean
        suspend fun resolve(source: SourceEntity, cmd: String): String?
        /** true = pinned to mpv, false = pinned to ExoPlayer, null = follow the setting. */
        suspend fun enginePin(channel: ChannelEntity): Boolean?
        suspend fun pin(channel: ChannelEntity, onMpv: Boolean)
        suspend fun globalPreference(): EnginePreference
        /** Settings → "Give up after", in seconds; 0 is Never. */
        suspend fun globalBudgetSecs(): Int
        fun meta(channel: ChannelEntity): MediaMeta
        /** Mirror a ladder decision into the user-visible playback error log. */
        fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String)
        fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
        /** An engine has just taken the stream (the phone publishes its media session here). */
        fun onEngineStarted() {}
        /** A handover is re-opening the channel at its live edge, so any rewind is over. */
        fun onBackToLiveEdge() {}
        /** N4 — the saved-copy buffers; null where there are none (tests). */
        val timeshift: TimeshiftManager? get() = null
        /** N4 — Settings → the window in minutes while local timeshift is on; null while it is off. */
        suspend fun timeshiftWindowMinutes(): Int? = null
        /** N4 — Settings → what coming back to a kept copy does ("Continue where you left off?"). */
        suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = SettingsRepository.ResumeMode.ASK
        /** N11 — Settings → Maximum video quality, for the variant a buffer saves; null for none. */
        suspend fun maxVideoHeight(): Int? = null

        // --- Host failover (design §4-§8). Defaults = no failover, exactly the old behaviour. ---

        /** The one owner of each source's in-use server; null = never switch, never rewrite. */
        val hostSelector: HostSelector? get() = null
        /** One bounded `player_api` call against [server] (an origin), after the release gate. */
        suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result = HostPreflight.Result.Other(null)
        /** Everything outside this controller that can hold a stream (A6), stopped and awaited. */
        suspend fun releaseAllStreams(sourceId: Long): ReleaseGate.Result = ReleaseGate.Result.Released(mpvDestroyed = false, quiet = true)
        /** A new server played: the one toast per switch ("Switched to server k of N · host"). */
        fun onServerSwitched(source: SourceEntity, position: Int, total: Int, server: String) {}
        /** Every server failed in one round ("All N servers failed"); no automatic retry follows. */
        fun onServersExhausted(source: SourceEntity, total: Int) {}
        /** False while the device has no path out: the failover round pauses and spends no host (L1). */
        fun isOnline(): Boolean = true
        /** Suspends until [isOnline] is true again. */
        suspend fun awaitOnline() {}
        /** Called once when a failover round pauses because the device is offline. */
        fun onNoNetwork() {}
        /** Live failover stayed off because a local timeshift buffer is on (L2). Once per tune. */
        fun onFailoverOffForTimeshift() {}
        /**
         * Hold a [StreamPurpose.WATCHING] claim from the release gate until the engine opens.
         * A recording must not take the only connection in that gap. Returns the release; no-op by default.
         */
        fun reserveWatching(sourceId: Long): () -> Unit = { {} }
    }

    /**
     * N4 — the channel on screen is playing from its saved copy ([session]: how far back it reaches, its
     * gaps; [timeshiftWatchingWallMs]: what is on screen). [resumeAtWallMs] is set when the user came back
     * to a channel they had left: where they were, for "Resume from buffer / Go live".
     */
    class LocalTimeshift(
        val session: TimeshiftSession,
        val resumeAtWallMs: Long?,
    )

    private val _liveOnExo = MutableStateFlow(false)

    /**
     * Whether live is on ExoPlayer right now — false is mpv. The *actual* engine rather than the pin:
     * an automatic handover leaves a channel on mpv while still unpinned.
     */
    val liveOnExo: StateFlow<Boolean> = _liveOnExo.asStateFlow()

    private val _previewBlocked = MutableStateFlow(false)

    /** True while the preview pane is held back because its panel allows one stream and mpv has it. */
    val previewBlockedSingleSession: StateFlow<Boolean> = _previewBlocked.asStateFlow()

    private val recall = ChannelRecall()

    private var ts: TimeshiftSession? = null
    /** The piece the engine's stream starts at; null is the live edge. */
    private var tsFromIndex: Long? = null
    private var tsRequest: LiveRequest? = null
    private var tsWindowSec = 0
    private val _localTimeshift = MutableStateFlow<LocalTimeshift?>(null)

    /** N4 — non-null while the channel plays from its saved copy (see [LocalTimeshift]). */
    val localTimeshift: StateFlow<LocalTimeshift?> = _localTimeshift.asStateFlow()

    init {
        host.timeshift?.let { manager ->
            scope.launch {
                manager.gaveUp.collect { (token, why) -> if (ts?.token == token) onTimeshiftLost(why) }
            }
        }
    }

    /** The channel watched before the one on screen — the "previous channel" key's target (N2). The app
     *  still vets it (profile, playlists, adult filter) before tuning it. */
    val previousChannel: StateFlow<ChannelEntity?> = recall.previous

    /** A channel the user is watching that did not go through [start] — the phone's cast hand-off, where
     *  the receiver plays it. Without this, casting would leave "previous channel" pointing at the past. */
    fun noteWatched(channel: ChannelEntity) = recall.onWatched(channel)

    /** The channel this controller is tuning or playing full-screen; null when it is not driving one. */
    private var current: ChannelEntity? = null

    private val ladder = LiveLadder()
    private var armedBudgetMs: Long = LiveLadder.NO_BUDGET
    /** Resolved "Give up after" seconds from [arm]; 0 or less is Never. Non-suspending so [serverCapMs] can read it. */
    private var armedBudgetSecs: Int = 0

    /**
     * The channel whose ExoPlayer rung is the explicit `.ts` one. Keyed by channel, not a bare flag: the
     * preview tunes through [exoUrlFor] too, so a flag left set by one channel's TS rung would send every
     * other channel's preview to `.ts` until the next tune.
     */
    private var forceTsFor: String? = null

    /** The Stalker command whose minted URL ExoPlayer holds, so a re-focus or a promote can tell "same
     *  channel" without minting again — the engine's own URL is the minted link, not the command. */
    private var exoStalkerCmd: String? = null

    /** When this controller last stopped ExoPlayer, for [awaitExoRelease]. */
    private var exoStoppedAtMs: Long? = null

    private var tuneJob: Job? = null
    private var previewJob: Job? = null
    private var exoResolveJob: Job? = null
    private var exoWatchJob: Job? = null
    private var mpvOutcomeJob: Job? = null
    private var mpvHandoffJob: Job? = null
    private var deadlineJob: Job? = null

    // --- Host failover state: one round per tune, the server waiting for its first frame. ---
    private var failoverJob: Job? = null
    private var midPlayJob: Job? = null
    private var round: HostFailover? = null
    private var pendingServer: String? = null
    private var pendingSourceId: Long? = null
    /** How long a post-switch 458/407/509 has already been waited out on [pendingServer] (R4). */
    private var sessionWaitedMs = 0L
    /** Set while a failover re-opens the channel on the next server, so [start] keeps the round. */
    private var restartingOnServer = false

    /** A cancelled switch must not leave [HostSelector.attempt] in memory. */
    private fun dropPendingServer() {
        val id = pendingSourceId
        val sel = host.hostSelector
        if (pendingServer != null && id != null && sel != null) {
            scope.launch { host.sourceOf(id)?.let { sel.abandon(it) } }
        }
        pendingServer = null
        pendingSourceId = null
    }

    /** Same as [dropPendingServer], but [HostSelector.abandon] finishes before the next URL resolve. */
    private suspend fun dropPendingServerNow() {
        val id = pendingSourceId
        val sel = host.hostSelector
        if (pendingServer != null && id != null && sel != null) {
            host.sourceOf(id)?.let { sel.abandon(it) }
        }
        pendingServer = null
        pendingSourceId = null
    }
    /** The timeshift-blocks-failover toast already fired for this tune. */
    private var toldFailoverOffForTimeshift = false

    // --- Entry points -----------------------------------------------------------------------------

    /**
     * Run [block] as the one tune in flight, cancelling whichever was running. Every playback-changing
     * entry point of an app goes through here — a tune, a catch-up, a retune at the live edge — so the
     * last thing asked for is the thing that happens.
     */
    fun launch(block: suspend LiveTuneController.() -> Unit): Job {
        tuneJob?.cancel()
        return scope.launch { block() }.also { tuneJob = it }
    }

    /** Tune [channel] full-screen, as the one tune in flight. */
    fun tune(channel: ChannelEntity): Job = launch { start(channel, host.sourceOf(channel.sourceId)) }

    /**
     * Route [channel], arm its ladder and start the first engine. Called from inside [launch].
     *
     * [resolved] is a URL already minted for a Stalker channel by this tune (the phone mints one before
     * offering the channel to a cast receiver): minting ends the portal's previous session, so the first
     * rung reuses it and only later rungs mint again.
     */
    suspend fun start(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        previewJob?.cancel()
        cancelLadderJobs()
        if (!restartingOnServer) {
            // A new tune (or Retry) starts a fresh round from the in-use server (AC8).
            failoverJob?.cancel()
            round = null
            sessionWaitedMs = 0L
            toldFailoverOffForTimeshift = false
            // A zap before the first frame is a cancelled switch: abandon the unconfirmed server.
            dropPendingServerNow()
        }
        source?.let { s -> HostPool.pool(s).takeIf { it.size > 1 }?.let { LiveStreamQuirks.registerPool(HostPool.poolKey(s.id), it + s.url) } }
        current = channel
        recall.onWatched(channel)
        _previewBlocked.value = false
        val setting = source?.liveEnginePreference
            ?.let { name -> EnginePreference.entries.firstOrNull { it.name == name } }
            ?: host.globalPreference()
        val route = LiveRouting.decide(
            setting = setting,
            pin = host.enginePin(channel),
            drmProtected = channel.drmConfig != null,
            panelRefusesSegments = panelRefusesSegments(channel, source),
        )
        engineLog("tune '${channel.name}' -> ${route.why} [${route.preference.name}]")
        openTimeshift(channel, source, resolved)
        arm(channel, source, route.preference)
        when {
            route.onMpv -> startOnMpv(channel, source, route.why, resolved = resolved)
            // The preview already found that ExoPlayer can't open this channel. Promoting it put the
            // preview's error on screen for ~1 s while the ladder moved on to mpv: go there directly.
            previewedChannelId == channel.id && engines.exoFailed -> {
                engineLog("'${channel.name}' — its ExoPlayer preview had already failed; trying the next engine")
                advance(channel, source, "the ExoPlayer preview had already failed", aboutFormat = false)
            }
            else -> startOnExo(channel, source, resolved)
        }
    }

    /** The channel the preview pane last asked ExoPlayer for (see [expectPromotion]). */
    private var previewedChannelId: Long? = null

    /**
     * The user is opening [channel] full screen. When ExoPlayer is already previewing it, say so at once,
     * before the full-screen player is first drawn: it then shows ExoPlayer's surface from the start
     * instead of building mpv's and swapping it out when [start] gets there. Measured on a 4K channel:
     * ~4 s of black on opening, with the engine pill reading "MPV" meanwhile. [start] still decides the
     * engine, and turns this off again if that is mpv.
     */
    fun expectPromotion(channel: ChannelEntity) {
        if (previewedChannelId == channel.id && engines.exoUrl != null && !engines.exoFailed) _liveOnExo.value = true
    }

    /** True while a server switch or a 458 session wait runs; the engine toggle is ignored then. */
    val isSwitchingServers: Boolean get() = failoverJob?.isActive == true

    /** App went invisible mid-switch: drop the round so it cannot open a stream later. */
    fun cancelFailover() {
        val active = failoverJob?.isActive == true || midPlayJob?.isActive == true
        dropPendingServer()
        if (!active) return
        failoverJob?.cancel()
        midPlayJob?.cancel()
        failoverJob = null
        midPlayJob = null
        engineLog("server switch cancelled: app in background")
    }

    /**
     * Television came back: re-tune the channel on screen through [start], the one entry that cancels
     * stale jobs. Failover state and the in-use server then apply. False when nothing is on screen.
     */
    fun resumeAfterBackground(): Boolean {
        val ch = current ?: return false
        engineLog("resume: re-tuning '${ch.name}'")
        launch { start(ch, host.sourceOf(ch.sourceId)) }
        return true
    }

    /**
     * The HUD's engine button: flip the channel on screen between the two engines and pin the choice.
     * Returns false when there is no channel, the channel is protected, or a server switch is in flight.
     *
     * Ignored mid-switch: cancelling could leave the old socket open mid-gate, and awaiting would replay
     * a stale press.
     *
     * For THIS tune the ladder is armed "only" on the engine the user just named — bouncing them off it
     * seconds later makes the control look broken. The next tune of the channel reads the pin and gets
     * the full ladder back. The app refuses first while a catch-up or a rewind is on screen.
     */
    fun toggleEngine(): Boolean {
        val channel = current ?: return false
        // A protected channel has only one engine that can obtain its key.
        if (channel.drmConfig != null) return false
        if (failoverJob?.isActive == true) {
            engineLog("engine toggle ignored: switching servers")
            return false
        }
        val goToMpv = _liveOnExo.value
        // On a saved copy the other engine continues at the moment on screen, not at the live edge.
        ts?.let { session ->
            if (behindLive()) timeshiftWatchingWallMs()?.let { tsFromIndex = session.pieceAt(it) }
        }
        engineLog("engine toggle '${channel.name}' -> ${if (goToMpv) "mpv" else "exoplayer"}")
        launch {
            host.pin(channel, goToMpv)
            val source = host.sourceOf(channel.sourceId)
            arm(channel, source, EnginePreference.onlyOn(goToMpv))
            if (goToMpv) startOnMpv(channel, source, "user chose compatibility mode")
            else switchToExo(channel, source)
        }
        return true
    }

    /**
     * The television's preview pane: play [channel] on ExoPlayer, muted or not, with no ladder.
     *
     * Never while promoted to full-screen — a late preview would re-mute the full-screen stream. On a
     * one-session panel a preview while mpv plays would lock the user's own stream out, so the pane
     * stays silent and [previewBlockedSingleSession] says why.
     */
    fun preview(channel: ChannelEntity, muted: Boolean) {
        if (_liveOnExo.value) {
            if (current != null) return
            // An [expectPromotion] no tune followed (an external player, a refused channel): the pane is
            // still the pane, and must not stay frozen on the last channel.
            _liveOnExo.value = false
        }
        previewedChannelId = channel.id
        ts?.let { session ->
            // The pane already shows this channel from its saved copy (Back from full screen): it keeps
            // saving, because the user has not left the channel.
            if (session.channelKey == timeshiftKey(channel) && TimeshiftServer.isLocal(engines.exoUrl) && !engines.exoFailed) {
                previewJob?.cancel()
                engines.exoSetMuted(muted)
                return
            }
            // Browsing to another channel is leaving this one.
            parkTimeshift()
        }
        previewJob?.cancel()
        previewJob = scope.launch {
            val source = host.sourceOf(channel.sourceId)
            if (host.needsResolve(source)) {
                if (exoStalkerCmd == channel.streamUrl && !engines.exoFailed) {
                    engines.exoSetMuted(muted)
                    return@launch
                }
                val url = host.resolve(source!!, channel.streamUrl) ?: return@launch
                if (_liveOnExo.value) return@launch // promoted to full-screen while resolving
                if (blockedBySingleSession(url)) return@launch
                exoStalkerCmd = channel.streamUrl
                setReconnect(channel, source)
                engines.exoPlay(url, muted, request(channel, source))
                return@launch
            }
            val url = exoUrlFor(channel, source)
            if (blockedBySingleSession(url)) return@launch
            if (engines.exoUrl == url && !engines.exoFailed) {
                engines.exoSetMuted(muted) // already previewing it (a re-focus) — just re-apply the mute
                return@launch
            }
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted, request(channel, source))
        }
    }

    /**
     * Tune [channel] into a Multiview tile's own [engine] — same URL, headers, overrides and Stalker
     * minting as a full-screen tune, so the grid can never drift from it. No ladder: a tile that fails
     * shows its failure.
     */
    suspend fun playTile(engine: LivePreviewEngine, channel: ChannelEntity, muted: Boolean) {
        val source = host.sourceOf(channel.sourceId)
        val url = if (host.needsResolve(source)) {
            host.resolve(source!!, channel.streamUrl) ?: return
        } else {
            exoUrlFor(channel, source)
        }
        engine.play(url, muted, request(channel, source))
    }

    /**
     * Stop driving the channel but leave the stream playing — leaving full-screen for the Live screen,
     * where the pane re-takes ExoPlayer as a preview. Watchers and the alarm stand down: a preview has
     * no ladder.
     */
    fun detach() {
        tuneJob?.cancel()
        failoverJob?.cancel()
        dropPendingServer()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
    }

    /** Stop ExoPlayer (the preview, or a detached stream) without touching mpv. */
    fun stopExo() {
        previewJob?.cancel()
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        exoStalkerCmd = null
        setReconnect(null, null)
        stopExoEngine()
    }

    /** Cancel the tune in flight, from outside [launch]. */
    fun cancelTune() {
        tuneJob?.cancel()
    }

    /**
     * Give playback to mpv for something that is not the live stream — a catch-up programme, a rewind
     * into the archive, a film. ExoPlayer lets go of its connection and decoder first, or the live
     * stream plays on underneath with a second sound. The whole ladder goes too: an alarm left armed
     * would stop a replay that plays perfectly well.
     *
     * Does not cancel the tune in flight, because a catch-up calls this from inside its own [launch] —
     * which is what cancels a live tune still resolving, so it cannot start over the archive. A caller
     * outside [launch] uses [cancelTune] first.
     */
    fun releaseForArchive() {
        parkTimeshift()
        failoverJob?.cancel()
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
        stopExo()
    }

    /** Stop everything: both engines, every watcher. */
    fun stop() {
        cancelTune()
        releaseForArchive()
        dropPendingServer()
        engines.mpvStop()
        host.timeshift?.watching(null)
    }

    // --- Local timeshift (N4) ---------------------------------------------------------------------

    /**
     * Show the saved copy from wall-clock instant [wallMs], or from the live edge when null. The engine
     * on screen re-opens the copy at the piece holding that instant — the same "open the stream at a
     * point" a catch-up rewind does. No-op while the channel is not playing from a copy.
     */
    fun seekTimeshift(wallMs: Long?) {
        val session = ts ?: return
        val request = tsRequest ?: return
        if (current == null) return
        tsFromIndex = if (wallMs == null) null else session.pieceAt(wallMs) ?: return
        publishTimeshift(resumeAtWallMs = null)
        val url = localUrl() ?: return
        if (_liveOnExo.value) engines.exoPlay(url, muted = false, request) else engines.mpvPlay(url, request)
    }

    /**
     * N4 — the channel's saved copy as a rewind source for [tv.own.owntv.core.live.LiveTimeshift]: the
     * apps' existing rewind bar, counter, "Go back to…" list and "Go to live" work on it unchanged.
     */
    val localRewind: tv.own.owntv.core.live.LiveTimeshift.Local = object : tv.own.owntv.core.live.LiveTimeshift.Local {
        override fun windowSec(ch: ChannelEntity): Int? =
            ts?.takeIf { it.channelKey == timeshiftKey(ch) && current?.id == ch.id }?.let { tsWindowSec }
        override fun depthSec(): Int {
            val session = ts ?: return 0
            val oldest = session.oldestWallMs() ?: return 0
            val edge = session.playableEdgeWallMs() ?: return 0
            return ((edge - oldest) / 1000).toInt().coerceAtLeast(0)
        }
        override fun watchingWallMs(): Long? = if (current == null) null else timeshiftWatchingWallMs()
        override fun liveEdgeWallMs(): Long? = ts?.playableEdgeWallMs()
        override fun seek(wallMs: Long?) = seekTimeshift(wallMs)
    }

    /** The "Resume from buffer / Go live" question has been answered (or ignored). */
    fun dismissResumeOffer() = publishTimeshift(resumeAtWallMs = null)

    /** N4 — the wall-clock spans the saved copy has no picture for (the connection dropped); empty without a copy. */
    fun localGaps(): List<LongRange> = ts?.gaps().orEmpty()

    /** What is on screen on the wall clock, while playing from a copy. */
    fun timeshiftWatchingWallMs(): Long? {
        val session = ts ?: return null
        return session.readingFromWallMs?.plus(engines.positionMs(_liveOnExo.value))
    }

    /**
     * Start (or wake, or keep) [channel]'s saved copy when local timeshift applies: Settings on, a live
     * channel with no provider archive (catch-up channels rewind into that instead), not protected. The
     * buffer is the only connection to the provider from here on, so nothing of ours may still hold the
     * channel when it connects. Returns false — and the channel plays the ordinary way — whenever the
     * copy cannot be made.
     */
    private suspend fun openTimeshift(channel: ChannelEntity, source: SourceEntity?, resolved: String?): Boolean {
        val manager = host.timeshift ?: return false
        val key = timeshiftKey(channel)
        // The channel being left, parked by the manager with what was on screen — see [TimeshiftManager.open].
        val leaving = ts?.takeIf { it.channelKey != key }
        val leavingWallMs = leaving?.let { timeshiftWatchingWallMs() }
        val window = if (channel.catchup || channel.drmConfig != null) null else host.timeshiftWindowMinutes()
        if (window == null) {
            if (leaving != null) parkTimeshift()
            manager.watching(key)
            clearTimeshift()
            return false
        }
        if (engines.exoUrl != null && !TimeshiftServer.isLocal(engines.exoUrl)) stopExoEngine()
        if (engines.mpvHasStream) engines.mpvStopAndAwaitRelease()
        val stalker = host.needsResolve(source)
        var minted = resolved
        val headers = StreamHeaders.decode(SourceOverrides.headersWithReferer(channel.httpHeaders, source))
        val target = TimeshiftDownloader.Target(
            url = {
                val first = minted
                when {
                    !stalker -> serverUrl(channel.streamUrl, source)
                    // The tune's own link first; every reconnect mints a fresh one (a link is single-use).
                    first != null -> first.also { minted = null }
                    else -> host.resolve(source!!, channel.streamUrl)
                }
            },
            userAgent = StreamHeaders.userAgentOf(headers)
                ?: source?.userAgent?.takeIf { it.isNotBlank() }
                ?: StalkerClient.DEFAULT_MAG_USER_AGENT.takeIf { stalker }
                ?: HttpClient.FALLBACK_USER_AGENT.takeIf { LiveStreamQuirks.blocksDefaultUserAgent(channel.streamUrl) }
                ?: HttpClient.DEFAULT_USER_AGENT,
            headers = headers,
            maxVideoHeight = host.maxVideoHeight(),
            sourceId = channel.sourceId,
        )
        val opened = manager.open(key, target, window, TIMESHIFT_FIRST_PIECE_MS, leavingWallMs)
        if (opened == null) {
            engineLog("timeshift: '${channel.name}' cannot be saved — playing it directly")
            clearTimeshift()
            return false
        }
        ts = opened.session
        val resumeAt = opened.resumeAtWallMs
        val mode = if (resumeAt == null) SettingsRepository.ResumeMode.ASK else host.timeshiftResumeMode()
        // AUTO opens the copy where the user was; NEVER plays it from the live edge. Neither asks.
        tsFromIndex = if (mode == SettingsRepository.ResumeMode.AUTO && resumeAt != null) opened.session.pieceAt(resumeAt) else null
        tsWindowSec = window * 60
        // The copy is plain TS or fragmented MP4 on loopback: no container hint, no fallback address,
        // no licence, and the provider's headers stay with the downloader.
        tsRequest = request(channel, source).copy(httpHeaders = null, drmConfig = null, manifestType = null, directSource = null)
        publishTimeshift(resumeAtWallMs = resumeAt.takeIf { mode == SettingsRepository.ResumeMode.ASK })
        engineLog("timeshift: '${channel.name}' plays from its saved copy")
        return true
    }

    /** The user left the channel: stop saving it, but keep it for a return. */
    private fun parkTimeshift() {
        val session = ts ?: return
        host.timeshift?.park(session, timeshiftWatchingWallMs())
        clearTimeshift()
    }

    private fun clearTimeshift() {
        ts = null
        tsRequest = null
        _localTimeshift.value = null
    }

    private fun publishTimeshift(resumeAtWallMs: Long?) {
        val session = ts ?: return
        _localTimeshift.value = LocalTimeshift(session = session, resumeAtWallMs = resumeAtWallMs)
    }

    /** The copy on screen is further behind its live edge than a player keeps buffered (a pause). */
    private fun behindLive(): Boolean {
        val watching = timeshiftWatchingWallMs() ?: return false
        val edge = ts?.playableEdgeWallMs() ?: return false
        return edge - watching > LIVE_SLACK_MS
    }

    private fun localUrl(): String? = ts?.takeIf { !it.isClosed }?.let { TimeshiftServer.urlFor(it, tsFromIndex) }

    /** The copy could not reconnect: the channel is lost, said on screen like any other give-up. */
    private fun onTimeshiftLost(why: String) {
        val channel = current
        clearTimeshift()
        if (channel == null || (!isStillExo(channel) && !isStillMpv(channel))) return
        val detail = "the connection was lost ($why)"
        engineLog("'${channel.name}' — giving up: $detail")
        host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
        cancelLadderJobs()
        abandon(channel, detail)
    }

    private fun timeshiftKey(channel: ChannelEntity): String = "${channel.sourceId}:${channel.id}"

    // --- Engines ----------------------------------------------------------------------------------

    /**
     * Open [channel] on ExoPlayer and watch it. If ExoPlayer already holds this channel — the preview
     * the user just pressed OK on — it is promoted (unmuted) instead of rebuilt.
     */
    private suspend fun startOnExo(channel: ChannelEntity, source: SourceEntity?, resolved: String? = null) {
        mpvOutcomeJob?.cancel() // ExoPlayer owns the channel now
        _liveOnExo.value = true
        if (engines.mpvHasStream && singleSessionPanel(channel, source)) {
            // mpv's stop is asynchronous: on a one-connection panel ExoPlayer opening while mpv still holds
            // the previous channel's socket is the app competing with itself for the only session (458).
            // Wait for mpv to let go first; bounded by mpv's release timeout, and only on such a panel.
            engines.mpvStopAndAwaitRelease()
            if (!isCurrent(channel)) return // zapped on while mpv released
        } else {
            engines.mpvStop() // mpv lets go of the connection and the decoder before ExoPlayer claims either
        }
        val local = localUrl()
        if (local != null) {
            // The same address is not the same picture after a pause: "live" then means re-opening it.
            if (engines.exoUrl == local && !behindLive()) {
                engines.exoSetMuted(false)
            } else {
                engines.exoReleaseUhdDecoder()
                exoStalkerCmd = null
                setReconnect(null, null)
                engines.exoPlay(local, muted = false, tsRequest ?: request(channel, source))
            }
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        if (host.needsResolve(source)) {
            startOnExoStalker(channel, source!!, resolved)
            return
        }
        val url = exoUrlFor(channel, source)
        if (engines.exoUrl == url) {
            engines.exoSetMuted(false) // promote — instant if already playing, otherwise keeps loading
        } else {
            // Leaving a UHD channel: release its 4K decoder before the rebuild (no-op for SD/HD).
            engines.exoReleaseUhdDecoder()
            exoStalkerCmd = null
            setReconnect(null, null)
            engines.exoPlay(url, muted = false, request(channel, source))
        }
        host.onEngineStarted()
        watchExo(channel, source)
    }

    private suspend fun startOnExoStalker(channel: ChannelEntity, source: SourceEntity, resolved: String?) {
        if (exoStalkerCmd == channel.streamUrl && resolved == null) {
            engines.exoSetMuted(false) // promote the preview that already holds this command
            setReconnect(channel, source)
            host.onEngineStarted()
            watchExo(channel, source)
            return
        }
        exoResolveJob?.cancel()
        engines.exoReleaseUhdDecoder()
        if (resolved != null) {
            // Already minted by this tune: start now, so the caller's next line finds the stream there.
            playExoStalker(channel, source, resolved)
            return
        }
        // Detached: minting is a network call, and a ladder step that got here from a watcher must not
        // die with that watcher when [watchExo] replaces it.
        exoResolveJob = scope.launch {
            val url = host.resolve(source, channel.streamUrl) ?: return@launch
            if (!isCurrent(channel)) return@launch // zapped away while minting
            playExoStalker(channel, source, url)
        }
    }

    private fun playExoStalker(channel: ChannelEntity, source: SourceEntity, url: String) {
        exoStalkerCmd = channel.streamUrl
        setReconnect(channel, source)
        engines.exoPlay(url, muted = false, request(channel, source))
        host.onEngineStarted()
        watchExo(channel, source)
    }

    /** Put [channel] on ExoPlayer, releasing mpv first when it holds the stream — mpv's stop is
     *  asynchronous, and handing over too early makes the app its own competitor for a session. */
    private suspend fun switchToExo(channel: ChannelEntity, source: SourceEntity?) {
        host.onBackToLiveEdge()
        if (!_liveOnExo.value) {
            engines.mpvStopAndAwaitRelease()
            delay(OwnTVPlayer.SURFACE_HANDOFF_MS)
            if (!isCurrent(channel)) return
        }
        startOnExo(channel, source)
    }

    /**
     * Open [channel] on mpv — a pinned channel, "mpv first", or ExoPlayer having given up.
     *
     * [forceTs] marks the `.ts` rung: "Prefer HLS" must not rewrite the URL. [resolved] is the first
     * rung's already-minted Stalker link; later rungs mint afresh, because a spent link is not reusable.
     */
    private suspend fun startOnMpv(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        forceTs: Boolean = false,
        resolved: String? = null,
    ) {
        engineLog("starting mpv for '${channel.name}' — reason=$reason")
        host.onBackToLiveEdge()
        // What ExoPlayer discovered before it let go: some panels redirect their advertised `.ts` to HLS,
        // and handing mpv that misleading URL traps FFmpeg at the manifest EOF. "Discovered" strictly
        // means ExoPlayer asked for something that was NOT HLS and got HLS anyway — an `.m3u8` we
        // requested teaches nothing about the `.ts` endpoint, and recording it branded a whole panel.
        val exoTuned = engines.exoUrl
        val exoDiscoveredHls = _liveOnExo.value && engines.exoIsHls &&
            exoTuned != null && !LiveStreamQuirks.isExplicitHlsUrl(exoTuned)
        exoWatchJob?.cancel() // mpv owns the channel now
        exoResolveJob?.cancel()
        mpvOutcomeJob?.cancel()
        _liveOnExo.value = false
        exoStalkerCmd = null
        stopExoEngine()
        awaitExoRelease()
        if (!isCurrent(channel)) {
            // The only exit that leaves the screen on mpv's surface with nothing loaded. Normal when the
            // user zapped during the release wait; in a support log it tells "abandoned" from "vanished".
            engineLog("mpv handoff for '${channel.name}' abandoned — the channel changed while ExoPlayer released")
            return
        }
        localUrl()?.let { local ->
            setReconnect(null, null)
            engines.mpvPlay(local, tsRequest ?: request(channel, source))
            host.onEngineStarted()
            watchMpv(channel, source)
            return
        }
        val stalker = host.needsResolve(source)
        val raw = when {
            !stalker -> serverUrl(channel.streamUrl, source)
            resolved != null -> resolved
            else -> host.resolve(source!!, channel.streamUrl) ?: return
        }
        // Same "Prefer HLS" opt-out as the ExoPlayer URL, but keyed to mpv's OWN verdict: ExoPlayer
        // failing this channel's `.m3u8` says nothing about whether mpv can play it.
        val preferred = if (forceTs || LiveStreamQuirks.lacksHlsVariantMpv(channel.streamUrl)) raw
            else resolveStreamUrl(raw, source)
        // Against the PANEL: the redirect is the provider's, so every later channel starts out knowing.
        if (exoDiscoveredHls) LiveStreamQuirks.rememberHlsRedirect(preferred)
        val url = if (LiveStreamQuirks.isKnownHlsHost(preferred)) LiveStreamQuirks.toHlsUrl(preferred) else preferred
        if (!isCurrent(channel)) return // zapped away while minting
        setReconnect(if (stalker) channel else null, source)
        engines.mpvPlay(url, request(channel, source))
        host.onEngineStarted()
        watchMpv(channel, source)
    }

    /**
     * Let ExoPlayer's decoder go before mpv initialises. Nothing exposes "the MediaCodec is released",
     * so the only alternative to waiting is guessing, and guessing short reproduces the 0x80001000
     * codec-claim failure. Measured at ≈500 ms on a Realtek box.
     *
     * Only the part of the wait that has not already passed since ExoPlayer was last stopped: a tune
     * that starts on mpv with nothing on ExoPlayer for a while has no decoder to wait for, and half a
     * second of black on each of those would be a handover cost charged to a tune that never handed over.
     */
    private suspend fun awaitExoRelease() {
        val stoppedAt = exoStoppedAtMs ?: return
        val left = OwnTVPlayer.SURFACE_HANDOFF_MS - (host.nowMs() - stoppedAt)
        if (left > 0) delay(left)
    }

    private fun stopExoEngine() {
        if (engines.exoUrl != null) exoStoppedAtMs = host.nowMs()
        engines.exoStop()
    }

    // --- Watchers ---------------------------------------------------------------------------------

    private fun watchExo(channel: ChannelEntity, source: SourceEntity?) {
        exoWatchJob?.cancel()
        exoWatchJob = scope.launch {
            engines.watchExo(
                channelName = channel.name,
                // A watchdog outlives the tune that armed it; firing after that would stop a stream
                // nobody complained about.
                stillOurs = { isStillExo(channel) },
                // Into the ladder rather than straight to mpv: the ladder decides what "next" means.
                handOver = { reason -> advance(channel, source, reason) },
                onOpened = {
                    standDownAlarm()
                    onServerPlayed(channel, source, onExo = true)
                },
                // A provider back-off is a wait OwnTV agreed to; it is not charged to the budget.
                postponeDeadline = { ladder.postponeDeadline(it) },
                log = ::engineLog,
                openTimeoutMs = if (failoverApplies(source) == null) LiveExoWatchdog.OPEN_TIMEOUT_MS
                    else HostFailover.capMs(armedBudgetSecs),
            )
        }
    }

    /** mpv runs its own retry and format ladder first, so its deadline is looser than ExoPlayer's. */
    private fun watchMpv(channel: ChannelEntity, source: SourceEntity?) {
        mpvOutcomeJob?.cancel()
        mpvOutcomeJob = scope.launch {
            val timeoutMs = serverCapMs(source)?.let { minOf(MPV_OPEN_TIMEOUT_MS, it) } ?: MPV_OPEN_TIMEOUT_MS
            val outcome = engines.awaitMpvOutcome(timeoutMs)
            if (!isStillMpv(channel)) return@launch
            val reason = when {
                outcome == null -> "mpv never opened it (${timeoutMs / 1000}s, no picture and no error)"
                outcome.opened -> {
                    engineLog("'${channel.name}' opened on mpv")
                    standDownAlarm()
                    onServerPlayed(channel, source, onExo = false)
                    return@launch
                }
                else -> "mpv couldn't play it: ${outcome.error}"
            }
            advance(channel, source, reason)
        }
    }

    // --- The ladder -------------------------------------------------------------------------------

    private suspend fun arm(channel: ChannelEntity, source: SourceEntity?, preference: EnginePreference) {
        forceTsFor = null
        val secs = SourceOverrides.liveTuneTimeoutSecsOf(source) ?: host.globalBudgetSecs()
        armedBudgetSecs = secs
        armedBudgetMs = if (secs <= 0) LiveLadder.NO_BUDGET else secs * 1000L
        ladder.arm(channel.streamUrl, preference, budgetMs = armedBudgetMs, nowMs = host.nowMs()) {
            // A saved copy is one address; the ladder is only the two engines.
            ts == null && hasHlsAlternative(channel, source)
        }
        startAlarm(channel, source)
    }

    /**
     * The alarm behind "Give up after", so the budget bounds the black screen rather than only the
     * decision to climb another rung: a rung entered at 24 s with a 35 s timeout of its own would
     * otherwise run to 59 s. The deadline is re-read on each pass, so a postponement moves the alarm.
     * A channel that opens stands the alarm down — later stalls belong to the watchdogs.
     *
     * Re-armed by [arm] on every [start], including a server switch, so the budget is per server.
     * With failover still open, a spent budget verifies this server and switches instead of giving up.
     */
    private fun startAlarm(channel: ChannelEntity, source: SourceEntity?) {
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            while (ladder.owns(channel.streamUrl)) {
                val left = (ladder.deadlineAt() ?: return@launch) - host.nowMs()
                if (left <= 0) break
                delay(left)
            }
            // Both gates matter: the ladder can still own a channel the user walked away from, and this
            // alarm stops an engine — on mpv's side the shared player, which may be showing a film.
            if (!ladder.owns(channel.streamUrl)) return@launch
            if (!isStillExo(channel) && !isStillMpv(channel)) return@launch
            val detail = "no picture within ${armedBudgetMs / 1000}s of tuning"
            val sel = failoverApplies(source)
            if (sel != null && source != null && round?.exhausted != true) {
                val failed = sel.current(source)
                if (failed != null) {
                    engineLog("'${channel.name}' — $detail; checking the server")
                    failoverJob?.cancel()
                    failoverJob = scope.launch {
                        runFailover(channel, source, sel, failed, verify = true, reason = detail, aboutFormat = false)
                    }
                    return@launch
                }
            }
            engineLog("'${channel.name}' — giving up: $detail")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            exoWatchJob?.cancel()
            exoResolveJob?.cancel()
            mpvOutcomeJob?.cancel()
            mpvHandoffJob?.cancel()
            abandon(channel, detail)
        }
    }

    private fun standDownAlarm() {
        deadlineJob?.cancel()
        deadlineJob = null
    }

    /**
     * Move to the next untried rung after a failure, or give up when there is nothing left. The only
     * place the ladder is climbed, from either engine's watcher — which is what makes "each rung at most
     * once" hold, and that finiteness is what stops a channel bouncing between the engines for ever.
     */
    private suspend fun advance(
        channel: ChannelEntity,
        source: SourceEntity?,
        reason: String,
        /** Whether the failure says anything about the stream's format; by default, unless it was a refusal. */
        aboutFormat: Boolean = !isRequestRefusal(reason),
    ) {
        if (!ladder.owns(channel.streamUrl)) return // a newer tune owns the ladder now
        // A dead server is not a format problem: switch servers instead of walking the format rungs (AC3).
        if (startFailover(channel, source, reason, aboutFormat)) return
        climbLadder(channel, source, reason, aboutFormat)
    }

    private suspend fun climbLadder(channel: ChannelEntity, source: SourceEntity?, reason: String, aboutFormat: Boolean) {
        if (!ladder.owns(channel.streamUrl)) return
        val nowMs = host.nowMs()
        // ExoPlayer played this channel, then found its content unplayable (not a connection problem).
        // The opening budget is long spent by then, which would refuse mpv, the engine most likely to
        // play it: give this handover a fresh one.
        if (_liveOnExo.value && engines.exoContentBroken) ladder.restartDeadline(nowMs, armedBudgetMs)
        val outOfTime = ladder.expired(nowMs)
        // A panel refusing the *request* (a busy 458, a 403, a rate limit) says nothing about the format,
        // so nothing may be learned from it.
        val next = ladder.advance(failureWasAboutFormat = aboutFormat, nowMs = nowMs) ?: run {
            val detail = if (outOfTime) "$reason — gave up after ${armedBudgetMs / 1000}s" else reason
            // Nowhere to go, but ExoPlayer is still reconnecting a channel that played. Abandoning here
            // sets its gaveUp flag, which stops those reconnects: measured on an upstream outage of ~50s
            // with the link still up (so no "network restored" to resume on), the next connect failure
            // left a black screen until the user pressed Retry. The engine reports "Lost connection"
            // itself once its own ladder is spent.
            if (_liveOnExo.value && engines.exoStillReconnecting) {
                engineLog("'${channel.name}' — no fallback left ($detail); leaving ExoPlayer's own reconnects running")
                return
            }
            engineLog("'${channel.name}' — no fallback left ($detail)")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            abandon(channel, detail)
            return
        }
        val label = ladder.label(next)
        engineLog("'${channel.name}' falling back to $label ($reason)")
        // Before the switch, so the record still names the engine that failed.
        host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_FALLBACK, "'${channel.name}': $label — $reason")
        if (next.onMpv) {
            // Detached on purpose. Every automatic rung is dispatched from inside a watcher job, and the
            // handoff cancels those watchers the moment it takes over — run inline it would cancel itself
            // at its first suspension point: mpv's surface up, mpv never asked to load, a black screen.
            mpvHandoffJob?.cancel()
            mpvHandoffJob = scope.launch { startOnMpv(channel, source, reason, forceTs = !next.isHls) }
        } else {
            forceTsFor = if (next.isHls) null else channel.streamUrl
            switchToExo(channel, source)
        }
    }

    /** Put the failure on screen: a stream that delivers no segment produces no frame AND no error, so
     *  the honest answer is written by whoever decided to stop trying — on the engine the HUD reads. */
    private fun abandon(channel: ChannelEntity, detail: String) {
        val reason = "'${channel.name}': $detail"
        if (_liveOnExo.value) engines.exoAbandon(reason) else engines.mpvAbandon(reason)
    }

    private fun cancelLadderJobs() {
        exoResolveJob?.cancel()
        exoWatchJob?.cancel()
        mpvOutcomeJob?.cancel()
        mpvHandoffJob?.cancel()
        deadlineJob?.cancel()
        midPlayJob?.cancel()
    }

    // --- Host failover ----------------------------------------------------------------------------

    /** What the engine on screen says about the server (A5). An mpv load with no error line is unknown. */
    private fun currentHostFailure(): HostFailureClass =
        if (_liveOnExo.value) engines.exoHostFailure
        else engines.mpvLastError?.let(HostFailure::classifyMpv) ?: HostFailureClass.UNKNOWN

    /**
     * Per-server open cap when failover is on: [HostFailover.capMs] plus this playlist's pre-buffer.
     * Null when failover does not apply.
     */
    private fun serverCapMs(source: SourceEntity?): Long? {
        if (failoverApplies(source) == null) return null
        val preBufferMs = (source?.livePrerollSecs ?: 0).coerceAtLeast(0) * 1000L
        return HostFailover.capMs(armedBudgetSecs) + preBufferMs
    }

    /** Failover is on for this tune: an Xtream pool with the switch on, and no timeshift buffer saving it (§4). */
    private fun failoverApplies(source: SourceEntity?): HostSelector? {
        val sel = host.hostSelector ?: return null
        if (source == null || !HostPool.failoverActive(source)) return null
        if (ts != null) return null
        return sel
    }

    /**
     * Hand a failure to host failover when it is the server's (true = handled). Host-class goes straight
     * to the next server; an unclassified mpv failure gets one preflight of the current server after the
     * release gate and switches only if that says the server is down (G4). Everything else — refusals,
     * 503, session limits, format and decoder trouble — is the ladder's, exactly as before.
     */
    private fun startFailover(channel: ChannelEntity, source: SourceEntity?, reason: String, aboutFormat: Boolean): Boolean {
        val sel = failoverApplies(source)
        if (sel == null) {
            // Off only because a timeshift buffer is saving this channel (L2): say so once, then the ladder.
            val cls = currentHostFailure()
            if (ts != null && host.hostSelector != null && source != null && HostPool.failoverActive(source) &&
                (cls == HostFailureClass.HOST || cls == HostFailureClass.UNKNOWN) &&
                !toldFailoverOffForTimeshift
            ) {
                toldFailoverOffForTimeshift = true
                host.onFailoverOffForTimeshift()
            }
            return false
        }
        val cls = currentHostFailure()
        // A session-limit answer on the server we just opened waits there (R4). It never climbs the
        // format ladder and never opens the next server: both would be a second socket on the account.
        if (cls == HostFailureClass.WAIT && pendingServer != null && source != null) {
            failoverJob?.cancel()
            failoverJob = scope.launch { waitOutSession(channel, source, reason) }
            return true
        }
        val verify = when {
            cls == HostFailureClass.HOST -> false
            cls == HostFailureClass.UNKNOWN && !_liveOnExo.value -> true
            else -> return false
        }
        if (round?.exhausted == true) return false
        val failed = sel.current(source!!) ?: return false
        failoverJob?.cancel()
        // Detached, like the mpv handoff: the release gate cancels the watcher this was called from.
        failoverJob = scope.launch { runFailover(channel, source, sel, failed, verify, reason, aboutFormat) }
        return true
    }

    /**
     * The new server answered 458/407/509 (the panel still counts the socket we just closed). Stop,
     * wait [ServerRound.SESSION_RECHECK_MS], and open the **same** server again, up to
     * [HostFailover.SESSION_WAIT_MS]. Nothing else is opened.
     */
    private suspend fun waitOutSession(channel: ChannelEntity, source: SourceEntity, reason: String) {
        val pool = HostPool.pool(source)
        if (sessionWaitedMs >= HostFailover.SESSION_WAIT_MS) {
            cancelLadderJobs()
            stopExoEngine()
            engines.mpvStop()
            engineLog(
                "'${channel.name}' — session still held on ${HostPool.describe(pool, pendingServer)} " +
                    "after ${HostFailover.SESSION_WAIT_MS / 1000}s",
            )
            abandon(channel, reason)
            return
        }
        cancelLadderJobs()
        setReconnect(null, null)
        stopExoEngine()
        engines.mpvStop()
        val gate = host.releaseAllStreams(source.id)
        if (gate is ReleaseGate.Result.Refused) {
            engineLog("'${channel.name}' — not retrying ${HostPool.describe(pool, pendingServer)}: ${gate.why.name.lowercase()} in progress")
            abandon(channel, reason)
            return
        }
        val release = host.reserveWatching(source.id)
        try {
            if (!isCurrent(channel)) return
            ladder.postponeDeadline(ServerRound.SESSION_RECHECK_MS)
            delay(ServerRound.SESSION_RECHECK_MS)
            sessionWaitedMs += ServerRound.SESSION_RECHECK_MS
            if (!isCurrent(channel)) return
            engineLog("'${channel.name}' — retrying ${HostPool.describe(pool, pendingServer)} (session still held)")
            restartingOnServer = true
            try {
                start(channel, source)
            } finally {
                restartingOnServer = false
            }
        } finally {
            release()
        }
    }

    /** Arm the mid-play watch: once the engine's own reconnects are spent (A5), the server may be at fault. */
    private fun watchMidPlay(channel: ChannelEntity, source: SourceEntity?, onExo: Boolean) {
        val sel = failoverApplies(source) ?: return
        midPlayJob?.cancel()
        midPlayJob = scope.launch {
            if (onExo) engines.awaitExoReconnectsExhausted() else engines.awaitMpvLiveLost()
            if (!isCurrent(channel) || _liveOnExo.value != onExo) return@launch
            val cls = currentHostFailure()
            if (cls != HostFailureClass.HOST && !(cls == HostFailureClass.UNKNOWN && !onExo)) return@launch
            val failed = sel.current(source!!) ?: return@launch
            engineLog("'${channel.name}' — reconnects spent; checking the server")
            round = null // a mid-play outage starts its own round from the server that was playing
            failoverJob?.cancel()
            failoverJob = scope.launch {
                runFailover(channel, source, sel, failed, verify = cls == HostFailureClass.UNKNOWN, reason = "lost connection", aboutFormat = false)
            }
        }
    }

    /**
     * One switch (design §7): release gate → backoff → preflight → open, repeated over the round until a
     * server answers as a panel. Never two servers at once: the gate proves every stream of ours is closed
     * before the first preflight, and nothing opens again until a preflight said OK.
     */
    private suspend fun runFailover(
        channel: ChannelEntity,
        source: SourceEntity,
        sel: HostSelector,
        failedServer: String,
        verify: Boolean,
        reason: String,
        aboutFormat: Boolean,
    ) {
        val pool = HostPool.pool(source)
        cancelLadderJobs()
        setReconnect(null, null)
        stopExoEngine()
        val gate = host.releaseAllStreams(source.id)
        if (gate is ReleaseGate.Result.Refused) {
            // A recording, a download or a measurement holds the account: the error shows as before.
            engineLog("'${channel.name}' — not switching servers: ${gate.why.name.lowercase()} in progress")
            abandon(channel, reason)
            return
        }
        val release = host.reserveWatching(source.id)
        try {
            if (!isCurrent(channel)) return
            if (verify) {
                val check = host.preflight(source, failedServer)
                engineLog("'${channel.name}' — ${HostPool.describe(pool, failedServer)} check: ${check::class.simpleName}")
                if (!check.isHostClass()) {
                    // The server answers: not the server's failure. Back to the ladder, engines already stopped.
                    climbLadder(channel, source, reason, aboutFormat)
                    return
                }
            }
            val r = round ?: HostFailover(pool, failedServer).also { round = it }
            val next = ServerRound.next(
                round = r,
                source = source,
                failed = failedServer,
                preflight = host::preflight,
                stillCurrent = { isCurrent(channel) },
                log = { engineLog("'${channel.name}' — $it") },
                isOnline = host::isOnline,
                awaitOnline = host::awaitOnline,
                onOffline = host::onNoNetwork,
            )
            when (next) {
                ServerRound.Next.Superseded -> return
                is ServerRound.Next.Exhausted, is ServerRound.Next.Refused -> {
                    sel.abandon(source)
                    val detail = if (next is ServerRound.Next.Exhausted) "all ${next.total} servers failed" else (next as ServerRound.Next.Refused).detail
                    engineLog("'${channel.name}' — giving up: $detail")
                    host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
                    if (next is ServerRound.Next.Exhausted) host.onServersExhausted(source, next.total)
                    abandon(channel, detail)
                }
                is ServerRound.Next.Open -> {
                    try {
                        sel.attempt(source, next.server)
                        pendingServer = next.server
                        pendingSourceId = source.id
                        engineLog("'${channel.name}' — opening on ${HostPool.describe(pool, next.server)}")
                        host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_FALLBACK, "'${channel.name}': ${HostPool.describe(pool, next.server)}")
                        restartingOnServer = true
                        try {
                            start(channel, source)
                        } finally {
                            restartingOnServer = false
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        withContext(NonCancellable) { dropPendingServerNow() }
                        throw e
                    }
                }
            }
        } finally {
            release()
        }
    }

    /** First frame on a server: persist it once if it was a switch, toast it, then watch for a later outage. */
    private fun onServerPlayed(channel: ChannelEntity, source: SourceEntity?, onExo: Boolean) {
        val sel = host.hostSelector
        val server = pendingServer
        if (sel != null && source != null && server != null && source.id == pendingSourceId) {
            pendingServer = null
            pendingSourceId = null
            if (sel.current(source) != server) {
                // Something else moved the server since (Use first, a VOD round): nothing to confirm here.
                watchMidPlay(channel, source, onExo)
                return
            }
            round = null
            sessionWaitedMs = 0L
            val pool = HostPool.pool(source)
            engineLog("'${channel.name}' — playing on ${HostPool.describe(pool, server)}")
            scope.launch { sel.confirm(source, server) }
            host.onServerSwitched(source, HostPool.position(pool, server), pool.size, server)
        }
        watchMidPlay(channel, source, onExo)
    }

    // --- Small helpers ----------------------------------------------------------------------------

    private fun isCurrent(channel: ChannelEntity): Boolean = current?.id == channel.id

    private fun isStillExo(channel: ChannelEntity): Boolean = _liveOnExo.value && isCurrent(channel)

    private fun isStillMpv(channel: ChannelEntity): Boolean = !_liveOnExo.value && isCurrent(channel)

    /** The panel allows one stream at a time: it said so at sync (`max_connections = 1`) or was caught
     *  refusing a second session this run. */
    private fun singleSessionPanel(channel: ChannelEntity, source: SourceEntity?): Boolean =
        source?.maxConnections == 1 || LiveStreamQuirks.isSingleSession(channel.streamUrl)

    private fun blockedBySingleSession(url: String): Boolean {
        val blocked = engines.mpvHasStream && LiveStreamQuirks.isSingleSession(url)
        _previewBlocked.value = blocked
        return blocked
    }

    /**
     * Which of the channel's two addresses ExoPlayer should ask for: the "Prefer HLS" `.m3u8`, except on
     * the ladder's `.ts` rung or a channel already caught this session having no working `.m3u8`.
     */
    private fun exoUrlFor(channel: ChannelEntity, source: SourceEntity?): String = serverUrl(
        if (forceTsFor == channel.streamUrl || LiveStreamQuirks.lacksHlsVariant(channel.streamUrl)) channel.streamUrl
        else channel.playStreamUrl(source),
        source,
    )

    /**
     * [url] (as stored, on the primary) moved onto the source's in-use server for the request. Identity
     * without backups. Every lesson and key stays on the stored URL (R7: never persist a rewritten one).
     */
    private fun serverUrl(url: String, source: SourceEntity?): String {
        val sel = host.hostSelector ?: return url
        return if (source == null) url else sel.forRequest(url, source)
    }

    /** Whether "Prefer HLS" rewrites this channel's URL at all; if not, HLS and TS rungs are one
     *  attempt and the ladder drops the HLS ones. A Stalker command is not a URL. */
    private fun hasHlsAlternative(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && channel.playStreamUrl(source) != channel.streamUrl

    /** Whether this channel's panel was caught refusing its own signed segment URLs. Stalker is excluded:
     *  its command has no host to key on until minted — a network call routing must not make. */
    private fun panelRefusesSegments(channel: ChannelEntity, source: SourceEntity?): Boolean =
        !host.needsResolve(source) && LiveStreamQuirks.refusesSegments(channel.playStreamUrl(source))

    private fun isRequestRefusal(reason: String): Boolean =
        PlayerErrors.httpStatusIn(reason)?.let { LiveStreamQuirks.isRequestRefusal(it) } == true

    /**
     * Install the Stalker reconnect link on both engines while a Stalker channel plays, so a mid-session
     * stream death mints a fresh `create_link` instead of looping on the expired one; cleared otherwise.
     */
    private fun setReconnect(channel: ChannelEntity?, source: SourceEntity?) {
        if (channel == null || source == null || !host.needsResolve(source)) {
            engines.setReconnectProvider(null)
            return
        }
        engines.setReconnectProvider(
            ReconnectUrlProvider {
                runCatching { host.resolve(source, channel.streamUrl) }
                    .onFailure { android.util.Log.w(ENGINE_TAG, "stalker reconnect re-resolve failed '${channel.name}'", it) }
                    .getOrNull()
            },
        )
    }

    private fun request(channel: ChannelEntity, source: SourceEntity?) = LiveRequest(
        meta = host.meta(channel),
        userAgent = source?.userAgent,
        prerollSecs = source?.livePrerollSecs?.takeIf { it >= 0 },
        liveBuffer = source?.liveLatencyMode?.let { mode ->
            LiveBuffer.Override(LiveBuffer.effectiveSeconds(LiveLatency.fromName(mode), source.liveLatencyCustomSecs))
        },
        httpHeaders = SourceOverrides.headersWithReferer(channel.httpHeaders, source),
        drmConfig = channel.drmConfig,
        manifestType = channel.manifestType,
        directSource = channel.directSource,
    )

    /** Routing decisions go to logcat unconditionally (`adb logcat -s LiveEngine`) and to the
     *  diagnostics ring. Channel names only — never a stream URL. */
    private fun engineLog(message: String) {
        android.util.Log.i(ENGINE_TAG, message)
        LiveDiagnosticsLog.event("engine: $message")
    }

    /**
     * The [Host] both apps use: pins from [ForceMpvStore], the settings store, the playlist row, and
     * Stalker minting through [StreamUrlResolver]. [label] is the line under the channel name (the
     * television's "#123"); [sourceOf] lets the television read its warm playlist map.
     */
    class CoreHost(
        private val context: Context,
        private val settings: SettingsRepository,
        private val sourceDao: SourceDao,
        private val resolver: StreamUrlResolver,
        private val forceMpvStore: ForceMpvStore,
        private val label: (ChannelEntity) -> String? = { null },
        private val sourceLookup: (suspend (Long) -> SourceEntity?)? = null,
        private val engineStarted: () -> Unit = {},
        private val backToLiveEdge: () -> Unit = {},
        /** Host failover: a new server played (k of N, the server's origin, for the toast). */
        private val serverSwitched: (SourceEntity, Int, Int, String) -> Unit = { _, _, _, _ -> },
        /** Host failover: every server failed in one round. */
        private val serversExhausted: (SourceEntity, Int) -> Unit = { _, _ -> },
        /** Host failover: the round paused because the device is offline (L1). */
        private val noNetwork: () -> Unit = {},
        /** Host failover stayed off because local timeshift is on (L2). */
        private val failoverOffForTimeshift: () -> Unit = {},
    ) : Host {
        override suspend fun sourceOf(sourceId: Long): SourceEntity? =
            sourceLookup?.invoke(sourceId) ?: sourceDao.getById(sourceId)

        override fun needsResolve(source: SourceEntity?): Boolean = resolver.needsResolve(source)

        override suspend fun resolve(source: SourceEntity, cmd: String): String? =
            runCatching { resolver.resolve(source, cmd) }
                .onFailure { android.util.Log.w(ENGINE_TAG, "stalker resolve failed", it) }
                .getOrNull()

        override suspend fun enginePin(channel: ChannelEntity): Boolean? =
            forceMpvStore.pinFor(pinKey(channel), channel.streamUrl)

        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {
            // The stable key, with any legacy URL-keyed entry cleared so an older pin cannot contradict it.
            val key = pinKey(channel)
            forceMpvStore.pin(key ?: channel.streamUrl, onMpv)
            if (key != null) forceMpvStore.forget(channel.streamUrl)
        }

        // Both wait for the settings snapshot (S15), so a tune started at a cold start — Last-channel
        // autoplay — is routed and budgeted by the stored settings, never by defaults.
        override suspend fun globalPreference(): EnginePreference =
            PlaybackSettings.await(settings).liveEnginePreference

        override suspend fun globalBudgetSecs(): Int = PlaybackSettings.await(settings).liveTuneTimeoutSecs

        override fun meta(channel: ChannelEntity): MediaMeta = MediaMeta(
            title = channel.name,
            subtitle = label(channel),
            logoUrl = channel.displayLogoUrl,
            contentKey = pinKey(channel),
        )

        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) =
            PlaybackErrorLog.event(
                context = context,
                engine = if (onExo) "ExoPlayer" else "mpv",
                live = true,
                reason = reason,
                detail = detail,
            )

        override fun onEngineStarted() = engineStarted()
        override fun onBackToLiveEdge() = backToLiveEdge()

        override val timeshift: TimeshiftManager? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<TimeshiftManager>()
        }

        override val hostSelector: HostSelector? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<HostSelector>()
        }

        private val xtream: tv.own.owntv.core.parser.XtreamClient? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<tv.own.owntv.core.parser.XtreamClient>()
        }

        private val releaseGate: ReleaseGate? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<ReleaseGate>()
        }

        private val openStreams: OpenStreamRegistry? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<OpenStreamRegistry>()
        }

        override fun reserveWatching(sourceId: Long): () -> Unit {
            val registry = openStreams ?: return {}
            val claim = registry.claim(sourceId, StreamPurpose.WATCHING)
            return { registry.release(claim) }
        }

        override suspend fun preflight(source: SourceEntity, server: String): HostPreflight.Result =
            xtream?.let { HostPreflight.run(it, source, server) } ?: HostPreflight.Result.Other(null)

        override suspend fun releaseAllStreams(sourceId: Long): ReleaseGate.Result =
            releaseGate?.stopAllStreamsAndAwait(sourceId) ?: ReleaseGate.Result.Released(mpvDestroyed = false, quiet = true)

        override fun onServerSwitched(source: SourceEntity, position: Int, total: Int, server: String) =
            serverSwitched(source, position, total, server)

        override fun onServersExhausted(source: SourceEntity, total: Int) = serversExhausted(source, total)

        private val connectivity by lazy { tv.own.owntv.core.network.ConnectivityObserver(context) }

        override fun isOnline(): Boolean = connectivity.isOnlineNow()

        override suspend fun awaitOnline() {
            connectivity.isOnline.first { it }
        }

        override fun onNoNetwork() = noNetwork()

        override fun onFailoverOffForTimeshift() = failoverOffForTimeshift()

        override suspend fun timeshiftWindowMinutes(): Int? =
            if (settings.timeshiftEnabled.first()) settings.timeshiftWindowMinutes.first() else null

        override suspend fun timeshiftResumeMode(): SettingsRepository.ResumeMode = settings.timeshiftResumeMode.first()

        override suspend fun maxVideoHeight(): Int? =
            PlaybackSettings.await(settings).maxVideoHeight.takeIf { it > 0 }

        private fun pinKey(channel: ChannelEntity): String? =
            enginePinKey(channel.sourceId, tv.own.owntv.core.model.MediaType.LIVE.name, channel.remoteId)
    }

    companion object {
        /** Tag for the engine decisions, so a support log can be filtered to just them. */
        const val ENGINE_TAG = "LiveEngine"

        /**
         * How long mpv gets to produce a picture before the ladder moves on. Looser than ExoPlayer's:
         * mpv walks its own internal retry and format ladder first.
         */
        const val MPV_OPEN_TIMEOUT_MS = 35_000L

        /** How long a new saved copy gets to receive its first bytes before the channel plays directly. */
        const val TIMESHIFT_FIRST_PIECE_MS = 12_000L

        /** Behind the copy's edge by more than this is not live any more (the rewind counter's own slack). */
        const val LIVE_SLACK_MS = 8_000L
    }
}
