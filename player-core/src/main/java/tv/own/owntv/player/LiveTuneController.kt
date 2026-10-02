package tv.own.owntv.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

        /**
         * Smart Provider (phase 4) — the next playlist that may carry [anchor], for a tune that has run
         * out of engines and formats on the playlist it is on, or null when there is nothing left to try.
         *
         * [triedSourceIds] is every provider this attempt has already spent, the anchor's own first, so an
         * implementation only has to skip them; the controller keeps that set itself. The channel handed
         * back is the candidate's own row on its own playlist, because what follows is a whole new tune —
         * that playlist's URL, headers and engine preference — and not another rung of this one.
         *
         * Both apps default to "no candidates", which is exactly the behaviour before this existed.
         */
        suspend fun nextProviderCandidate(anchor: ChannelEntity, triedSourceIds: Set<Long>): ChannelEntity? = null

        /** A tune on [channel] — [anchor] itself, or a substitute standing in for it — has a picture. */
        fun onProviderOpened(anchor: ChannelEntity, channel: ChannelEntity) {}

        /**
         * A provider attempt on [channel] ran out of engines and formats, with [detail] the player's own
         * text for why. The attempt is over whatever happens next; whether the reason is worth remembering
         * against the provider is the app's judgement — see `ProviderFailureReason` — not the controller's.
         */
        fun onProviderFailed(anchor: ChannelEntity, channel: ChannelEntity, detail: String) {}

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
    }

    /**
     * Smart Provider (phase 4) — one app's answer to the three questions the walk across providers asks:
     * which playlist may carry the channel next, whether a playlist produced a picture, and whether one
     * ran out of engines and formats.
     *
     * The three belong together and to one screen, which is why they are one seam rather than three
     * lambdas: [Host.nextProviderCandidate] finds a substitute, and the two verdicts are what makes the
     * *next* attempt better informed than the last. An app that supplies none of them — [CoreHost] takes
     * an implementation or nothing at all — behaves exactly as it did before this existed.
     *
     * The controller owns the walk: how many playlists a tune may try, the deadline they share, and when
     * the channel is declared lost. A [ProviderFallback] only answers the three questions, from whatever
     * the app keeps — here, the cross-provider memory the discovery pass writes.
     */
    interface ProviderFallback {
        /** As [Host.nextProviderCandidate]: the next playlist's own row for the channel, or null. */
        suspend fun nextCandidate(anchor: ChannelEntity, triedSourceIds: Set<Long>): ChannelEntity?

        /** As [Host.onProviderOpened]: a playlist put a picture on the screen. */
        fun onOpened(anchor: ChannelEntity, channel: ChannelEntity)

        /** As [Host.onProviderFailed]: a playlist ran out of engines and formats, with the player's text. */
        fun onFailed(anchor: ChannelEntity, channel: ChannelEntity, detail: String)
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

    /** Smart Provider (phase 4) — the substitute tune in flight, if any. A substitute is a tune of its
     *  own, never a rung, so it gets a job of its own: the failing watcher that asked for it is cancelled
     *  as it starts. */
    private var providerJob: Job? = null

    /** Smart Provider (phase 4) — the channel's walk across providers while it is being tuned, or null
     *  outside a tune. Created by [start], ended when a picture opens or the channel changes. */
    private var attempt: ProviderAttempt? = null

    // --- Entry points -----------------------------------------------------------------------------

    /**
     * Run [block] as the one tune in flight, cancelling whichever was running. Every playback-changing
     * entry point of an app goes through here — a tune, a catch-up, a retune at the live edge — so the
     * last thing asked for is the thing that happens.
     */
    fun launch(block: suspend LiveTuneController.() -> Unit): Job {
        tuneJob?.cancel()
        // Whatever this is — a channel, a catch-up, a replay — it supersedes a substitute still being
        // tried for the channel before it. A Smart Provider attempt never outlives its own screen.
        providerJob?.cancel()
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
        // Smart Provider (phase 4) — this is the anchor: the channel the user picked, on the playlist
        // they picked it from. Everything else this attempt may end up trying is another provider's copy
        // of the same channel, after that one first, once each. A tune that replaces a tune abandons the
        // previous walk rather than inheriting its spent providers.
        providerJob?.cancel()
        current = channel
        recall.onWatched(channel)
        _previewBlocked.value = false
        attempt = ProviderAttempt(channel)
        beginTune(channel, source, resolved, replacing = false)
    }

    /**
     * One provider's turn in a tune: route it, arm the ladder against it and start an engine.
     *
     * Split out of [start] for Smart Provider, whose substitutes are whole tunes like this one — the
     * same routing, the same ladder, the same kind of budget — differing only in [replacing]: the engine
     * has to be handed over from the previous provider's stream rather than assumed clear, and the
     * saved copy stays parked with the channel the user actually picked.
     */
    private suspend fun beginTune(
        channel: ChannelEntity,
        source: SourceEntity?,
        resolved: String?,
        replacing: Boolean,
    ) {
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
        if (replacing) {
            // The saved copy belongs to the channel the user picked, and a second download is a second
            // connection to a provider already under suspicion: the substitute takes the screen, the
            // copy is parked for the anchor's return.
            parkTimeshift()
        } else {
            openTimeshift(channel, source, resolved)
        }
        arm(channel, source, route.preference)
        // A pinned substitute starts on ExoPlayer anyway: mpv is one shared player, so the safest handover
        // is the one the ladder already uses — ExoPlayer claims the surface, mpv lets go of it first. The
        // ladder is armed as usual, so a substitute that ExoPlayer cannot play still reaches mpv.
        if (route.onMpv && !replacing) startOnMpv(channel, source, route.why, resolved = resolved)
        else switchOrStartOnExo(channel, source, resolved, replacing)
    }

    /** The ExoPlayer start a tune needs: an ordinary first open, or a handover from the previous
     *  provider's engine when this is a substitute. */
    private suspend fun switchOrStartOnExo(
        channel: ChannelEntity,
        source: SourceEntity?,
        resolved: String?,
        replacing: Boolean,
    ) {
        if (replacing) switchToExo(channel, source) else startOnExo(channel, source, resolved)
    }

    /**
     * The HUD's engine button: flip the channel on screen between the two engines and pin the choice.
     *
     * For THIS tune the ladder is armed "only" on the engine the user just named — bouncing them off it
     * seconds later makes the control look broken. The next tune of the channel reads the pin and gets
     * the full ladder back. The app refuses first while a catch-up or a rewind is on screen.
     */
    fun toggleEngine() {
        val channel = current ?: return
        // A protected channel has only one engine that can obtain its key.
        if (channel.drmConfig != null) return
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
    }

    /**
     * The television's preview pane: play [channel] on ExoPlayer, muted or not, with no ladder.
     *
     * Never while promoted to full-screen — a late preview would re-mute the full-screen stream. On a
     * one-session panel a preview while mpv plays would lock the user's own stream out, so the pane
     * stays silent and [previewBlockedSingleSession] says why.
     */
    fun preview(channel: ChannelEntity, muted: Boolean) {
        if (_liveOnExo.value) return
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
        // A substitute is a tune: with the screen gone there is nothing for it to open on.
        providerJob?.cancel()
        attempt = null
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
        // Whatever was being tried next belongs to the tune being cancelled — a lapsed licence must not
        // leave a substitute to open on top of whatever takes the screen.
        providerJob?.cancel()
        attempt = null
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
        // The ladder goes, and the walk across providers with it.
        providerJob?.cancel()
        attempt = null
        cancelLadderJobs()
        current = null
        _liveOnExo.value = false
        stopExo()
    }

    /** Stop everything: both engines, every watcher. */
    fun stop() {
        cancelTune()
        releaseForArchive()
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
                    !stalker -> channel.streamUrl
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
        engines.mpvStop() // mpv lets go of the connection and the decoder before ExoPlayer claims either
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
            !stalker -> channel.streamUrl
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
                onOpened = { onChannelOpened(channel) },
                // A provider back-off is a wait OwnTV agreed to; it is not charged to the budget.
                postponeDeadline = { ladder.postponeDeadline(it) },
                log = ::engineLog,
            )
        }
    }

    /** mpv runs its own retry and format ladder first, so its deadline is looser than ExoPlayer's. */
    private fun watchMpv(channel: ChannelEntity, source: SourceEntity?) {
        mpvOutcomeJob?.cancel()
        mpvOutcomeJob = scope.launch {
            val outcome = engines.awaitMpvOutcome(MPV_OPEN_TIMEOUT_MS)
            if (!isStillMpv(channel)) return@launch
            val reason = when {
                outcome == null -> "mpv never opened it (${MPV_OPEN_TIMEOUT_MS / 1000}s, no picture and no error)"
                outcome.opened -> {
                    engineLog("'${channel.name}' opened on mpv")
                    onChannelOpened(channel)
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
        val ownMs = if (secs <= 0) LiveLadder.NO_BUDGET else secs * 1000L
        val nowMs = host.nowMs()
        // Smart Provider (phase 4): the channel's whole walk — this provider first, then the candidates —
        // is bounded by one deadline, so a chain of providers can never hold the screen black longer than
        // the user agreed to wait. It belongs to the anchor: that is the channel, and the playlist, whose
        // "Give up after" the user set. A substitute inherits it rather than getting a fresh allowance.
        attempt?.openBudget(ownMs, nowMs)
        // Inside that deadline each provider gets its own window — the anchor's, or what is left of it.
        armedBudgetMs = attempt?.budgetFor(ownMs, nowMs) ?: ownMs
        ladder.arm(channel.streamUrl, preference, budgetMs = armedBudgetMs, nowMs = nowMs) {
            // A saved copy is one address; the ladder is only the two engines.
            ts == null && hasHlsAlternative(channel, source)
        }
        startAlarm(channel)
    }

    /**
     * The alarm behind "Give up after", so the budget bounds the black screen rather than only the
     * decision to climb another rung: a rung entered at 24 s with a 35 s timeout of its own would
     * otherwise run to 59 s. The deadline is re-read on each pass, so a postponement moves the alarm.
     * A channel that opens stands the alarm down — later stalls belong to the watchdogs.
     */
    private fun startAlarm(channel: ChannelEntity) {
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
            // Smart Provider (phase 4): the provider on screen has had the window the user's setting gave
            // it. If the channel has another provider and the walk's deadline still has room, the picture
            // is worth chasing there before this tune is called lost.
            if (failOverToNextProvider(channel, detail, outOfTime = false) != ProviderStep.REFUSED) return@launch
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
    private suspend fun advance(channel: ChannelEntity, source: SourceEntity?, reason: String) {
        if (!ladder.owns(channel.streamUrl)) return // a newer tune owns the ladder now
        val nowMs = host.nowMs()
        val outOfTime = ladder.expired(nowMs)
        // A panel refusing the *request* (a busy 458, a 403, a rate limit) says nothing about the format,
        // so nothing may be learned from it.
        val next = ladder.advance(failureWasAboutFormat = !isRequestRefusal(reason), nowMs = nowMs) ?: run {
            // Smart Provider (phase 4): out of engines and formats on THIS provider. The channel itself is
            // not out of options — another playlist may carry it, and the walk's deadline may still have
            // room — so before this tune is declared dead it is worth one whole try elsewhere, serially.
            if (failOverToNextProvider(channel, reason, outOfTime) != ProviderStep.REFUSED) return
            val detail = if (outOfTime) "$reason — gave up after ${armedBudgetMs / 1000}s" else reason
            engineLog("'${channel.name}' — no fallback left ($detail)")
            host.recordLadderEvent(_liveOnExo.value, PlayerFailureReason.LIVE_NO_FALLBACK, "'${channel.name}': $detail")
            // The tune is over, and nothing may act on it again. The alarm outlives this failure — this
            // provider failed before its window expired — and a late one would report the same provider twice
            // and ask for a substitute for a channel the app has already called lost.
            cancelLadderJobs()
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
    }

    // --- Smart Provider: the walk across providers (phase 4) --------------------------------------

    /**
     * A tune has a picture — the only thing that counts as a success, and the only thing that ends the
     * walk across providers. What the ladder does after this (a mid-session stall, a reconnect) stays
     * the engines' and the watchdogs' business, exactly as it was before.
     */
    private fun onChannelOpened(channel: ChannelEntity) {
        // A watcher that outlives its tune can still report an open for a stream nobody is showing, and
        // the channel on screen is the only one whose picture counts.
        if (!isCurrent(channel)) return
        val walk = attempt
        if (walk == null || (walk.anchor.id != channel.id && channel.sourceId !in walk.triedSourceIds)) {
            standDownAlarm()
            return
        }
        // A picture: the walk is over. No provider is tried after one has played.
        attempt = null
        standDownAlarm()
        host.onProviderOpened(walk.anchor, channel)
    }

    /**
     * Try the channel on another provider's copy of it, and say what became of the walk.
     *
     * Called from the only two ways a provider can end without a picture: the ladder running out of engines
     * and formats ([advance]), and the provider's own window expiring ([startAlarm]). The failed provider is
     * reported either way, the next is asked for with everything already spent, and one candidate becomes
     * one whole tune of its own — never a rung of this one. [ProviderStep.REFUSED] means there is nowhere
     * left to go, and the caller's ordinary give-up follows unchanged.
     */
    private suspend fun failOverToNextProvider(
        channel: ChannelEntity,
        reason: String,
        outOfTime: Boolean,
    ): ProviderStep {
        val walk = attempt ?: return ProviderStep.REFUSED
        // A substitute is another playlist for the channel the user picked, never a different channel:
        // only that channel's own rows may hand the walk on — the anchor's, or a substitute whose
        // playlist this walk already spent.
        if (channel.id != walk.anchor.id && channel.sourceId !in walk.triedSourceIds) return ProviderStep.REFUSED
        // One ask at a time. A watchdog landing on an ask that is already in flight must leave the tune to
        // it: putting "no fallback left" on screen for a substitute already on its way would be a lie, and
        // the caller that asked owns what happens next.
        if (walk.asking) return ProviderStep.DECIDING
        // The provider on screen has just failed, whatever happens next, so it is reported before any gate
        // can end the call — the last provider's verdict matters as much as the first one's.
        host.onProviderFailed(walk.anchor, channel, reason)
        if (walk.triedSourceIds.size >= MAX_PROVIDER_TRIES) return ProviderStep.REFUSED
        val nowMs = host.nowMs()
        // A provider with less than a rung's worth of the deadline left is not worth the seconds it needs
        // to open: the walk is over as soon as one cannot be given a fair try.
        if (walk.leftMs(nowMs) < MIN_PROVIDER_BUDGET_MS) return ProviderStep.REFUSED
        walk.asking = true
        val next = try {
            host.nextProviderCandidate(walk.anchor, walk.triedSourceIds.toSet())
        } finally {
            walk.asking = false
        } ?: return ProviderStep.REFUSED
        // The host is expected to skip what it was told had been spent; the controller does not rely on it.
        // A candidate on a provider already tried — the anchor's own included — would be a second tune of a
        // stream already known not to work, so it is refused and the tune gives up rather than loops.
        if (next.sourceId in walk.triedSourceIds) return ProviderStep.REFUSED
        walk.triedSourceIds += next.sourceId
        val label = "playlist ${next.sourceId}"
        val why = if (outOfTime) "out of time" else reason
        engineLog("'${channel.name}' — $label gave nothing ($why); trying '${next.name}' on it")
        // Before the switch, so the record still names the engine that failed.
        host.recordLadderEvent(
            _liveOnExo.value,
            PlayerFailureReason.LIVE_FALLBACK,
            "'${walk.anchor.name}': $label — $reason",
        )
        // A tune of its own, and a job of its own: [beginTune] cancels the watchers and the alarm the failed
        // provider left behind, and the watcher that called this is one of them — run inline it would cancel
        // itself at its first suspension point.
        providerJob?.cancel()
        providerJob = scope.launch {
            current = next
            _previewBlocked.value = false
            cancelLadderJobs()
            beginTune(next, host.sourceOf(next.sourceId), resolved = null, replacing = true)
        }
        return ProviderStep.SUBSTITUTE
    }

    /**
     * What one provider's failure did to the walk: [SUBSTITUTE] a substitute is on its way, [DECIDING] an
     * earlier ask is still in flight and owns this tune's fate, [REFUSED] there is nowhere left to go and
     * the caller's ordinary give-up stands.
     */
    private enum class ProviderStep { SUBSTITUTE, DECIDING, REFUSED }

    /**
     * One channel's walk across providers, from the tune that started it until a picture opens.
     *
     * [anchor] is the channel as the user picked it — the only channel this walk is about, and the one
     * whose playlist's "Give up after" bounds the whole thing. [triedSourceIds] is every provider spent
     * so far, the anchor's included; [deadlineAtMs] is the instant the whole walk must be over by, or
     * [LiveLadder.NO_BUDGET] when the user chose Never.
     */
    private class ProviderAttempt(val anchor: ChannelEntity) {
        val triedSourceIds = mutableSetOf(anchor.sourceId)
        var budgetKnown = false
        var deadlineAtMs = LiveLadder.NO_BUDGET

        /** One ask at a time: a watchdog can fire while the host is being asked what comes next. */
        var asking = false

        /** Fix the walk's deadline from the anchor's own budget — once, on the tune that started it. */
        fun openBudget(ownMs: Long, nowMs: Long) {
            if (budgetKnown) return
            budgetKnown = true
            deadlineAtMs =
                if (ownMs == LiveLadder.NO_BUDGET) LiveLadder.NO_BUDGET
                else nowMs + ownMs * MAX_PROVIDER_TRIES
        }

        fun leftMs(nowMs: Long): Long =
            if (deadlineAtMs == LiveLadder.NO_BUDGET) Long.MAX_VALUE else deadlineAtMs - nowMs

        /** This provider's own window, capped by what the walk has left. */
        fun budgetFor(ownMs: Long, nowMs: Long): Long {
            val left = leftMs(nowMs)
            if (left == Long.MAX_VALUE) return ownMs
            // Never below the floor: a window of zero or less would read as [LiveLadder.NO_BUDGET] and
            // hand a provider the whole screen back with no "Give up after" at all.
            return minOf(if (ownMs == LiveLadder.NO_BUDGET) left else ownMs, left)
                .coerceAtLeast(MIN_PROVIDER_BUDGET_MS)
        }
    }

    // --- Small helpers ----------------------------------------------------------------------------

    private fun isCurrent(channel: ChannelEntity): Boolean = current?.id == channel.id

    private fun isStillExo(channel: ChannelEntity): Boolean = _liveOnExo.value && isCurrent(channel)

    private fun isStillMpv(channel: ChannelEntity): Boolean = !_liveOnExo.value && isCurrent(channel)

    private fun blockedBySingleSession(url: String): Boolean {
        val blocked = engines.mpvHasStream && LiveStreamQuirks.isSingleSession(url)
        _previewBlocked.value = blocked
        return blocked
    }

    /**
     * Which of the channel's two addresses ExoPlayer should ask for: the "Prefer HLS" `.m3u8`, except on
     * the ladder's `.ts` rung or a channel already caught this session having no working `.m3u8`.
     */
    private fun exoUrlFor(channel: ChannelEntity, source: SourceEntity?): String =
        if (forceTsFor == channel.streamUrl || LiveStreamQuirks.lacksHlsVariant(channel.streamUrl)) channel.streamUrl
        else channel.playStreamUrl(source)

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
        /**
         * Smart Provider (phase 4) — the app's cross-provider memory, or null for an app that keeps none.
         * Nothing here knows how a candidate is found or what a verdict is worth: this only hands the three
         * questions to the one object that does.
         */
        private val providerFallback: ProviderFallback? = null,
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

        override suspend fun nextProviderCandidate(
            anchor: ChannelEntity,
            triedSourceIds: Set<Long>,
        ): ChannelEntity? = providerFallback?.nextCandidate(anchor, triedSourceIds)

        override fun onProviderOpened(anchor: ChannelEntity, channel: ChannelEntity) {
            providerFallback?.onOpened(anchor, channel)
        }

        override fun onProviderFailed(anchor: ChannelEntity, channel: ChannelEntity, detail: String) {
            providerFallback?.onFailed(anchor, channel, detail)
        }

        override val timeshift: TimeshiftManager? by lazy {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<TimeshiftManager>()
        }

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

        /**
         * Smart Provider (phase 4) — how many providers one tune may try, the anchor's own playlist
         * included. Three playlists is a channel given every reasonable show of hands; beyond that the
         * seconds spent are the user's, and the only honest thing left is the give-up on screen.
         */
        const val MAX_PROVIDER_TRIES = 3

        /**
         * A provider with less than this of the walk's deadline left is not started: a tune needs seconds
         * to get a picture, and beginning one at the wire is spending them to no purpose.
         */
        const val MIN_PROVIDER_BUDGET_MS = 5_000L
    }
}
