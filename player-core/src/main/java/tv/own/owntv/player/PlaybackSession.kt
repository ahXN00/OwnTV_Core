package tv.own.owntv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The app's single point of contact with the rest of the system's audio: **audio focus** and a
 * **MediaSession** (F27). Before this, OwnTV neither requested focus nor published a session, so two
 * apps could play over each other and the TV's transport keys / Assistant "pause" never reached the
 * player.
 *
 * It is engine-agnostic on purpose. OwnTV plays through mpv *and* two ExoPlayer engines, and which one
 * owns the speaker changes with the content; all three already implement [PlaybackEngine], so this class
 * only ever talks to that interface. `OwnTVShell` is the one place that knows which engine is live, so it
 * [attach]es it and passes `null` when the player closes.
 *
 * ### Focus policy: duck, don't pause
 * A TV is not a phone. A navigation prompt, a doorbell camera notification or an Assistant reply must not
 * stop a live channel — a paused live stream falls behind the edge and comes back either late or as a
 * fresh reconnect, and a paused film loses the moment being watched. So:
 *
 * | Focus change | What we do |
 * |---|---|
 * | `LOSS_TRANSIENT_CAN_DUCK` | nothing — `setWillPauseWhenDucked(false)` means the platform attenuates our stream itself, with no HUD-visible volume change |
 * | `LOSS_TRANSIENT` | duck by [DUCK_DB] ourselves and restore on the next gain |
 * | `LOSS` (permanent) | **pause**, and abandon focus. This one is another app taking the speaker for good; continuing is the "two apps playing at once" bug |
 * | `GAIN` | restore the pre-duck volume; playback that we paused stays paused (the user chose the other app) |
 *
 * ### …unless the host is a phone
 * That table is the right answer for a television and the wrong one for a handset: a phone call must
 * **stop** the film, not play it quietly under the caller's voice, and the user expects it back when
 * the call ends. So the policy is a constructor choice — [FocusPolicy.DUCK] is what the TV app has
 * always done and stays the default, [FocusPolicy.PAUSE] is what the mobile app passes. A short
 * notification beep still only ducks under either policy, because that is `CAN_DUCK` and the platform
 * attenuates us without ever telling us.
 *
 * [pauseWhenOutputDisconnects] is the other phone-shaped difference: pulling headphones out mid-film
 * must not carry on through the loudspeaker. It is off by default, because a television's speakers
 * cannot be unplugged from underneath it.
 */
class PlaybackSession(
    private val context: Context,
    private val focusPolicy: FocusPolicy = FocusPolicy.DUCK,
    private val pauseWhenOutputDisconnects: Boolean = false,
) {

    /** What losing audio focus transiently should do to playback. See the class doc. */
    enum class FocusPolicy {
        /** Quieten by [DUCK_DB] and keep playing — a television. */
        DUCK,

        /** Pause, and resume on the next gain unless the user paused by hand — a phone. */
        PAUSE,
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var engine: PlaybackEngine? = null

    /**
     * What "previous" from a headset or the media notification does on a live channel — go back to the
     * channel watched before (N2). A live engine has no previous item of its own, so without it the
     * button did nothing. Assigned once by the app's live screen; null keeps the old no-op.
     */
    var livePrevious: (() -> Unit)? = null
    private var collectJob: Job? = null
    private var session: MediaSession? = null

    private var hasFocus = false
    /** Volume the user had set before we ducked, or null when not ducked. */
    private var preDuckVolume: Int? = null
    /** The level the duck actually set, so [unduck] can tell "still where we left it" from "the user
     *  changed the volume while ducked". */
    private var duckedTo: Int? = null

    /**
     * Make [engine] the one this session represents, or `null` when nothing is playing any more (the
     * player closed). Safe to call repeatedly with the same engine.
     */
    fun attach(engine: PlaybackEngine?) {
        if (this.engine === engine) return
        collectJob?.cancel()
        // A different engine (or none) starts a fresh session as far as controllers are concerned, so the
        // next publish must send the metadata even if the title happens to match.
        lastMetaKey = null
        this.engine = engine
        if (engine == null) {
            unduck()
            abandonFocus()
            unregisterNoisy()
            pausedByUs = false
            runCatching { session?.apply { isActive = false; release() } }
            session = null
            return
        }
        // A dead or refusing MediaSession must never take the player down with it: the session is a
        // convenience for other controllers on the TV, the video is the point. Every other system call
        // in this class is already guarded; these were the last three that were not.
        val s = runCatching { session ?: createSession().also { session = it } }.getOrNull()
        runCatching { s?.isActive = true }
        registerNoisy()
        collectJob = combine(
            engine.isPlaying,
            engine.currentMeta,
            engine.position,
            engine.duration,
        ) { playing, meta, position, duration -> State(playing, meta, position, duration, engine.isLiveContent) }
            .onEach(::publish)
            .launchIn(scope)
    }

    /**
     * The platform session's token, or null while nothing is attached.
     *
     * A host that shows a media notification needs it: hanging `MediaStyle` on this token is what puts
     * the transport controls on the lockscreen and lets the system draw the seek bar from the state
     * this class already publishes. The television has no such notification and never reads it.
     */
    val token: MediaSession.Token?
        get() = runCatching { session?.sessionToken }.getOrNull()

    /** The parts of [State] that actually reach `MediaMetadata`; see [publish]. */
    private data class MetaKey(val title: String, val subtitle: String, val durationMs: Long)

    private var lastMetaKey: MetaKey? = null

    private data class State(
        val playing: Boolean,
        val meta: MediaMeta,
        val positionMs: Long,
        val durationMs: Long,
        val live: Boolean,
    )

    private fun publish(state: State) {
        // Focus is held only while we are actually making sound. Keeping it through a pause left every
        // other app on the TV ducked (or locked out) for as long as the user left the player paused.
        //
        // The one exception is a pause THIS class performed for a call or a navigation prompt: the
        // resume is driven by the AUDIOFOCUS_GAIN callback, and abandoning the request is what stops
        // that callback ever arriving. Dropping focus here would leave the film paused for good.
        if (state.playing) requestFocus() else if (!pausedByUs) abandonFocus()
        val s = session ?: return
        runCatching {
            // Metadata only when it actually changed (A-F10/F-F9). This is driven by a combine() that also
            // carries the position, so it fires on every position tick — but the title, subtitle and
            // duration only change when the ITEM does. Rebuilding and re-publishing MediaMetadata dozens
            // of times a second was pure waste, and every controller on the TV had to process each one.
            // The playback state below still updates every tick: a controller needs the moving position.
            val metaKey = MetaKey(
                title = state.meta.title.orEmpty(),
                subtitle = state.meta.subtitle.orEmpty(),
                // Live has no meaningful duration; -1 tells a controller "not seekable/unknown".
                durationMs = if (state.live) -1L else state.durationMs,
            )
            if (metaKey != lastMetaKey) {
                lastMetaKey = metaKey
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, metaKey.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, metaKey.subtitle)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, metaKey.durationMs)
                        .build(),
                )
            }
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (!state.live) {
                actions = actions or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
            }
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        state.positionMs,
                        if (state.playing) 1f else 0f,
                    )
                    .build(),
            )
        }
    }

    private fun createSession(): MediaSession = MediaSession(context, SESSION_TAG).apply {
        setCallback(object : MediaSession.Callback() {
            // Either transport button is the user speaking for themselves, so any resume this class
            // still owed is cancelled: their choice is newer than the interruption's.
            override fun onPlay() = withEngine { pausedByUs = false; if (!it.isPlaying.value) it.togglePlayPause() }
            override fun onPause() = withEngine { pausedByUs = false; if (it.isPlaying.value) it.togglePlayPause() }
            // Nothing here may tear the player down — this session doesn't own the UI. "Stop" from a
            // system control therefore means "silence it", which is a pause the user can undo.
            override fun onStop() = withEngine { if (it.isPlaying.value) it.togglePlayPause() }
            override fun onSeekTo(pos: Long) = withEngine {
                if (!it.isLiveContent) it.seekBy(pos - it.position.value)
            }
            // Settings → Seek step, the same value the on-screen buttons use: a Bluetooth remote or the
            // system media notification must not move by a different amount than the HUD does.
            override fun onFastForward() = withEngine {
                if (!it.isLiveContent) it.seekBy(it.seekStepMs.value)
            }
            override fun onRewind() = withEngine {
                if (!it.isLiveContent) it.seekBy(-it.seekStepMs.value)
            }
            override fun onSkipToNext() = withEngine { it.next() }
            override fun onSkipToPrevious() = withEngine { if (it.isLiveContent) livePrevious?.invoke() else it.previous() }
        })
    }

    /** Callbacks arrive on a binder thread; every engine here is main-thread-only. */
    private fun withEngine(block: (PlaybackEngine) -> Unit) {
        val e = engine ?: return
        scope.launch { runCatching { block(e) } }
    }

    // --- Audio focus ------------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                unduck()
                resumeAfterInterruption()
            }
            // Transient: duck on a television, pause on a phone — see the class doc. Ducking a live
            // stream costs a quiet moment; pausing one costs the live edge, which is why the TV never
            // does it. A call, on the other hand, is not something to talk over.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> when (focusPolicy) {
                FocusPolicy.DUCK -> duck()
                FocusPolicy.PAUSE -> pauseForInterruption()
            }
            // Under PAUSE, setWillPauseWhenDucked(true) is supposed to turn every duck into the
            // LOSS_TRANSIENT above — but some builds (and some dialers) hand out CAN_DUCK anyway, and
            // the result was a call that only turned the sound down while the film kept running.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> when (focusPolicy) {
                FocusPolicy.DUCK -> {} // the platform attenuates us; nothing to do
                FocusPolicy.PAUSE -> pauseForInterruption()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                unduck()
                // Permanent: another app has taken the speaker for good, so this is the user's own
                // choice and playback must NOT come back by itself on the next gain.
                pausedByUs = false
                withEngine { if (it.isPlaying.value) it.togglePlayPause() }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the platform attenuates us, nothing to do.
        }
    }

    /** Set while playback is paused *by this class* and is owed a resume — never while the user paused. */
    private var pausedByUs = false

    private fun pauseForInterruption() {
        withEngine {
            if (!it.isPlaying.value) return@withEngine // already paused: nothing owed, nothing to do
            pausedByUs = true
            it.togglePlayPause()
        }
    }

    private fun resumeAfterInterruption() {
        if (!pausedByUs) return
        pausedByUs = false
        // Playing already means the user restarted it during the interruption — their newer decision.
        withEngine { if (!it.isPlaying.value) it.togglePlayPause() }
    }

    // --- Headphones and Bluetooth -----------------------------------------------------------------

    /**
     * Unplugging headphones or walking out of Bluetooth range: pause, and do **not** arm a resume.
     * Plugging back in must not blast a film out of a pocket, so this deliberately does not go through
     * [pauseForInterruption].
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            pausedByUs = false
            withEngine { if (it.isPlaying.value) it.togglePlayPause() }
        }
    }

    private var noisyRegistered = false

    private fun registerNoisy() {
        // Same reason as in requestFocus: this device's headphone jack has nothing to do with a
        // stream playing on a television.
        if (!pauseWhenOutputDisconnects || noisyRegistered || engine?.playsLocally == false) return
        noisyRegistered = runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        runCatching { context.unregisterReceiver(noisyReceiver) }
    }

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            // DUCK: false, so the platform attenuates us automatically instead of handing us a callback.
            // PAUSE: true, which is what makes the system send LOSS_TRANSIENT for an incoming call
            // rather than ducking us behind our back and never telling us to stop.
            .setWillPauseWhenDucked(focusPolicy == FocusPolicy.PAUSE)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private fun requestFocus() {
        if (hasFocus) return
        // An engine playing somewhere else is making no sound here, so there is nothing to hold focus
        // for — and holding it would duck or lock out every other app on this device for nothing.
        if (engine?.playsLocally == false) return
        val granted = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // A refusal is not a reason to refuse to play: some TV builds deny focus to background-capable
        // apps and playing silently-unmanaged is still better than not playing.
        hasFocus = granted
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // --- Ducking ----------------------------------------------------------------------------------

    private fun duck() {
        val e = engine ?: return
        if (preDuckVolume != null) return
        val current = e.volume.value
        preDuckVolume = current
        val target = VolumeCurve.shiftedByDb(current, DUCK_DB)
        duckedTo = target
        withEngine { it.adjustVolume(target - current) }
    }

    private fun unduck() {
        val previous = preDuckVolume ?: return
        val ducked = duckedTo
        preDuckVolume = null
        duckedTo = null
        withEngine { e ->
            // If the volume is no longer where the duck left it, the user changed it while we were
            // ducked. That is a newer decision than the level saved before ducking, so restoring the old
            // one would silently undo it.
            if (ducked != null && e.volume.value != ducked) return@withEngine
            e.adjustVolume(previous - e.volume.value)
        }
    }

    private companion object {
        const val SESSION_TAG = "OwnTV"
        /** How far down a manual duck goes — quiet enough to talk over, loud enough not to look broken.
         *  In dB, so it is the same drop on every engine and from any starting volume (a quarter of
         *  the HUD number used to be −12 dB on ExoPlayer and −36 dB, effectively mute, on mpv). */
        const val DUCK_DB = -12.0
    }
}
