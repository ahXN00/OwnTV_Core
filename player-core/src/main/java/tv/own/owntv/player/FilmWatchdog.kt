package tv.own.owntv.player

/**
 * The film and catch-up "no picture" watchdog's decisions, one tick a second — see the stages described
 * where [OwnTVPlayer] starts it. This class only decides; the player carries out each [Step] (hard reset,
 * rescue, hand-over to ExoPlayer, error) exactly as before. One instance per load: it remembers where the
 * picture opened and since when it has been sitting there.
 */
internal class FilmWatchdog {

    /** What the player knows on this tick. */
    data class Tick(
        val now: Long,
        /** Since the load started. */
        val elapsedMs: Long,
        val heightPx: Int,
        val positionMs: Long,
        val playing: Boolean,
        val buffering: Boolean,
        val fileLoaded: Boolean,
        /** The decoder has had [OwnTVPlayer.DECODER_GRACE_AFTER_LOAD_MS] since FILE_LOADED. */
        val decoderHadTime: Boolean,
        /** Audio Mode turned the picture off, or the item has no video codec at all. */
        val noVideoTrack: Boolean,
        /** The panel refused the open as "account in use". */
        val sessionRefused: Boolean,
        val archive: Boolean,
        val triedFrozenReset: Boolean,
        val triedOpenReset: Boolean,
    )

    enum class Step {
        /** Nothing to do yet — check again next tick. */
        WAIT,
        /** The picture opened and playback moved: stand down (an archive is still watched for stalls). */
        PLAYING,
        /** Frozen on the first frame: silent hard reset and reload at [openedAtPos]. */
        FROZEN_RESET,
        /** Frozen again after that reset: an archive goes to ExoPlayer, anything else stops watching. */
        FROZEN_AGAIN,
        /** No video track and audio is playing: stand down. */
        NO_VIDEO_TRACK,
        /** A refused session already ended through END_FILE: stand down. */
        SESSION_REFUSED,
        /** Stage 1, first time: the demuxer never opened — silent hard reset and reload. */
        OPEN_RESET,
        /** Stage 1 again: an archive to ExoPlayer, else a hard reset. */
        OPEN_AGAIN,
        /** Stage 2: loaded, but no height and no bitrate. */
        LOADED_NO_METADATA,
        /** Stage 3: loaded, but no frame. */
        NO_FRAME,
    }

    /** Where the picture opened; -1 until it has. */
    var openedAtPos = -1L
        private set

    /** How long the picture has sat on its first frame while playing, at the last tick. */
    var stillForMs = 0L
        private set

    private var stillSince = 0L

    /** [bitrateKnown] is a blocking mpv read, so it is asked only on the tick that reaches stage 2. */
    suspend fun step(t: Tick, bitrateKnown: suspend () -> Boolean): Step {
        if (t.heightPx > 0) {
            // The picture opened. Stand down once playback has really moved; until then, catch the load
            // that froze on its first frame — no sound, the clock stuck at the start and nothing ever
            // reported.
            if (openedAtPos < 0) openedAtPos = t.positionMs
            if (t.positionMs > openedAtPos + OwnTVPlayer.FROZEN_START_PROOF_MS) return Step.PLAYING
            if (!t.playing || t.buffering) { stillSince = 0L; return Step.WAIT }
            if (stillSince == 0L) stillSince = t.now
            stillForMs = t.now - stillSince
            if (stillForMs < OwnTVPlayer.FROZEN_START_MS) return Step.WAIT
            return if (!t.triedFrozenReset) Step.FROZEN_RESET else Step.FROZEN_AGAIN
        }
        // Nothing below can judge an item that has no video track at all: it loads with no height and no
        // bitrate — stage 2's and stage 3's signature. Only stand down once audio is demonstrably progressing.
        if (t.fileLoaded && t.elapsedMs > 5_000 && t.positionMs > 0 && t.noVideoTrack) return Step.NO_VIDEO_TRACK
        if (!t.fileLoaded && t.sessionRefused) return Step.SESSION_REFUSED
        if (!t.fileLoaded && t.elapsedMs > 10_000) return if (!t.triedOpenReset) Step.OPEN_RESET else Step.OPEN_AGAIN
        if (t.fileLoaded && t.elapsedMs > 6_000 && t.decoderHadTime && !bitrateKnown()) return Step.LOADED_NO_METADATA
        if (t.fileLoaded && t.elapsedMs > 7_000 && t.decoderHadTime) return Step.NO_FRAME
        return Step.WAIT
    }
}
