package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget
import tv.own.owntv.core.settings.LiveBuffer

/**
 * The mpv options one load sets before its `loadfile`, worked out without touching mpv: the live/VOD
 * probe profile, the stream's reconnect options, the network and cache sizes, the pre-roll gate, timing
 * and frame dropping, and the archive decode option. [OwnTVPlayer] writes [Result.options] in order.
 */
internal object MpvLoadOptions {

    /** Everything a load's options depend on — read from the player and the session quirks by the caller. */
    data class Input(
        val url: String,
        val live: Boolean,
        /** A live `.ts` endpoint this panel is already known to redirect to an HLS manifest. */
        val knownHlsHost: Boolean,
        val prerollSecs: Int,
        /** The live latency choice in seconds; null = the tier's default. */
        val liveBufferSecs: Int?,
        /** A film's own buffer, network timeout (Settings; 0 = Auto). Ignored for live. */
        val filmBufferSecs: Int,
        val filmNetworkTimeoutSecs: Int,
        /** Null until the device's memory budget is known: the cache sizes are then left alone. */
        val budget: PlayerBudget?,
        /** A low-memory trim has shrunk the cache since this load began. */
        val cacheTrimmed: Boolean,
        /** The feed is already caught sending broken timestamps this session. */
        val brokenTimestamps: Boolean,
        /** Frames go straight to the decoder's surface (`mediacodec_embed`), not through `vo=gpu`. */
        val directRender: Boolean,
        val archive: Boolean,
        val forceFullProbe: Boolean,
        val tolerant: Boolean,
    )

    data class Result(
        /** Property name to value, in the order they are set. */
        val options: List<Pair<String, String>>,
        val effectiveHls: Boolean,
        /** The demuxer probe was trimmed for a fast zap — see [OwnTVPlayer]'s full-probe retry. */
        val trimmed: Boolean,
        /** The buffer line for Logcat (film or live), null when no budget is known yet. */
        val bufferLog: String?,
        /** The `mpv_open` line for the diagnostics log. */
        val diagnostic: String,
    )

    fun of(i: Input): Result {
        val options = mutableListOf<Pair<String, String>>()
        fun set(name: String, value: String) { options += name to value }
        val lower = i.url.lowercase()
        // Raw continuous MPEG-TS (Xtream live `…/id.ts`, catch-up timeshift `.ts` — though an archive is
        // never trimmed, see `trim` below). These probe fast and
        // start mid-stream, so they're the streams fast-zap trimming was built for — and the proven-safe
        // case (Xtream live works). HLS (.m3u8) and other/extensionless live URLs are NOT trimmed: they need
        // the full probe (playlist + a segment) to open cleanly, and a trimmed probe handed mpv incomplete
        // info → the stream opened but the playloop wedged (regression: M3U HLS live hung after the decoder
        // inited, while v2.2.4 — which always full-probed — played). So trim ONLY raw TS, full-probe the rest.
        // A `.ts` URL the provider redirects to a manifest is HLS, not raw TS: trimming its probe wedges
        // mpv's HLS open (see below), so the learned quirk has to disqualify it here too. Scoped to live —
        // the redirect is a live-endpoint behaviour, and a catch-up `.ts` from the same host is still raw.
        val effectiveHls = lower.substringBefore('?').endsWith(".m3u8") || (i.live && i.knownHlsHost)
        val rawTs = (OwnTVPlayer.hasTsPath(i.url) || lower.contains("/timeshift/")) && !effectiveHls
        // Make FFmpeg RECONNECT when a live server closes the HTTP connection (some drop the socket every
        // few seconds; without this mpv hits EOF → the app reconnects → a black/decoder-churn loop). NOT for
        // VOD/catch-up (live=false) — those have a real end and must be allowed to finish.
        // `reconnect_at_eof` keeps a CONTINUOUS stream going across mid-stream EOFs — but it also reconnects
        // on the EOF that ends a *finite* HTTP response (an HLS .m3u8 playlist, a redirect, …), looping
        // forever during OPEN so the stream never starts. [OwnTVPlayer.streamLavfOptionsFor] owns that
        // distinction and is the single place stream-lavf-o is decided, for this path and the loadfile path alike.
        val streamLavfO = OwnTVPlayer.streamLavfOptionsFor(i.url, i.live, effectiveHls)
        set("stream-lavf-o", streamLavfO)
        // A film's own buffer and network timeout (Settings; 0 = Auto = the values used before).
        // Set on every load, live included, so a channel after a film gets the device's values back.
        // demuxer-max-bytes follows the choice too (SmartBuffer): the tier's for live and Auto, a chosen
        // buffer's own size (within the device's film ceiling) for a film. Set on every load, so a channel
        // after a film never inherits the film's bigger cache. A low-memory trim since this load began
        // still wins — the trim is cleared only by the next load's restore.
        val filmBuffer = if (i.live) 0 else i.filmBufferSecs
        val networkTimeout = if (i.live) OwnTVPlayer.LIVE_NETWORK_TIMEOUT_SECS else FilmNetwork.mpvTimeoutSecs(i.filmNetworkTimeoutSecs)
        set("network-timeout", networkTimeout.toString())
        var bufferLog: String? = null
        i.budget?.let { budget ->
            // Live latency: a choice sets mpv's readahead AND its cache — see [MpvLiveBuffer].
            val live = MpvLiveBuffer.resolve(i.liveBufferSecs, i.prerollSecs, budget)
            set("demuxer-readahead-secs", if (i.live) live.readahead else FilmNetwork.readaheadSecs(filmBuffer, budget))
            set("cache-secs", if (i.live) live.cache else FilmNetwork.bufferSecs(filmBuffer, budget).toString())
            // The bitrate isn't known before the open; the film cache is re-sized once it is.
            val demuxerMax = SmartBuffer.mpvDemuxerMaxBytes(i.live, filmBuffer, budget, bitrateBps = null, trimmed = i.cacheTrimmed)
            set("demuxer-max-bytes", demuxerMax)
            bufferLog = if (i.live) {
                "live_buffer preroll=${i.prerollSecs}s readahead=${live.readahead} cache=${live.cache} latency=${i.liveBufferSecs ?: -1} net=${networkTimeout}s"
            } else {
                "film_buffer buffer=${filmBuffer}s cache=$demuxerMax net=${networkTimeout}s"
            }
        }
        // "Pre-buffer": mpv's own pre-roll gate — hold the picture until the cache
        // holds N seconds, and do the same after an underrun (which is what "pause 3-4 s then play
        // makes it smooth" was doing by hand). Live only; 0 restores mpv's defaults.
        if (i.live && i.prerollSecs > 0) {
            set("cache-pause-initial", "yes")
            set("cache-pause-wait", i.prerollSecs.toString())
        } else {
            set("cache-pause-initial", "no")
            // Live resumes after the same 2 s ExoPlayer waits for after a re-buffer: with 1 s in hand, mpv
            // stop-started more often than ExoPlayer on the same jittery line. A film with a chosen buffer
            // refills longer before resuming (Auto: 1 s).
            val resumeWait = if (i.live) LiveBuffer.DEFAULT_RESTART_MS / 1000 else FilmNetwork.mpvResumeWaitSecs(filmBuffer)
            set("cache-pause-wait", resumeWait.toString())
        }
        // Broken-timestamp live streams (some IPTV 4K feeds send non-increasing/duplicate PTS): mpv is
        // strict about PTS and drops nearly every frame ("Invalid video timestamp: X -> X"), which looks
        // like lag even though decode is fine (ExoPlayer tolerates it). The workaround is to stop timing
        // video against the audio master clock — derive it from the container FPS (correct-pts=no),
        // present each frame for its nominal duration (video-sync=desync) and never drop (framedrop=no).
        //
        // It is applied ONLY to feeds mpv has actually complained about (learned in LiveStreamQuirks),
        // never to live as a class. Free-running video timing is by definition unsynced
        // from audio, so on a normal feed — whose PTS are perfectly sound — it slowly drifts the picture
        // away from the sound, which is exactly the "audio early or late" a provider's raw MPEG-TS showed.
        // A healthy stream therefore keeps mpv's accurate, audio-synced default, same as VOD.
        val brokenPts = i.live && i.brokenTimestamps
        set("correct-pts", if (brokenPts) "no" else "yes")
        // On `vo=gpu` (the software/copy rescue paths) mpv DOES control scan-out, so it can align frames
        // to the display's vsync and resample audio to match — which is what removes 24/25 fps judder.
        // On the direct `mediacodec_embed` path it is meaningless: frames go to the decoder's surface and
        // the display picks its own cadence, so the setting there stays `audio` and Auto frame rate is the
        // only cure.
        set(
            "video-sync",
            when {
                brokenPts -> "desync"
                !i.directRender -> "display-resample"
                else -> "audio"
            },
        )
        set("framedrop", if (brokenPts) "no" else "decoder+vo")
        // A catch-up archive starts wherever the panel cut it, mid-GOP: the hardware decoder drops the slices
        // before the first keyframe (engine option) instead of stalling on their missing references, which
        // costs a 3 s stall and a fall to software. Set on every load so it never outlives the archive.
        set("vd-lavc-o", if (i.archive) "wait_keyframe=1" else "")
        // Never for a catch-up archive: it starts wherever the panel cut it, and a 1 MB / 1 s probe can end
        // before the first SPS ("Could not find codec parameters … unspecified size"). The hardware decoder
        // cannot open without it, and mpv then drops to software decode on its own, without a word to us —
        // every catch-up on that panel ran in software. The full probe reads
        // on to the first keyframe; an archive is not zapped, so the extra second is not paid per press.
        val trim = rawTs && !i.forceFullProbe && !i.archive
        val demuxerOptions = OwnTVPlayer.demuxerLavfOptionsFor(trimmedRawTsProbe = trim, tolerant = i.tolerant)
        if (!trim) {
            // Full probe — needed for HDR, complete track lists, and to open HLS/other live cleanly. Capping
            // the analyze time (even to 2.5 s) wedges mpv's HLS open on this hardware — it never reaches the
            // decoder — so the full probe is required. This is the ~3–5 s full-screen startup floor for HLS
            // (vs the instant ExoPlayer preview).
            // NOTE: probesize MUST be a valid value >= 32. "0" is rejected by mpv ("must be >= 32: 0") — it
            // does NOT mean "use default" on this build. FFmpeg's default is 5 MB (5000000), so we set that
            // explicitly. Without a valid probesize, a malformed MP4 (broken UDTA atoms) sends the demuxer
            // into a multi-GB seek + retry loop that eventually kills the video output (blank screen).
            set("demuxer-lavf-probesize", "5000000")
            set("demuxer-lavf-analyzeduration", "0")
        } else {
            set("demuxer-lavf-probesize", "1000000")
            set("demuxer-lavf-analyzeduration", "1.0") // ~1s keeps HDR/colorspace detection safe
        }
        set("demuxer-lavf-o", demuxerOptions)
        val diagnostic = "mpv_open hls=$effectiveHls live=${i.live} probe=${if (trim) "trimmed" else "full"} tolerant=${i.tolerant} " +
            "demuxerLavfO=\"$demuxerOptions\" streamLavfO=\"$streamLavfO\""
        return Result(options, effectiveHls, trim, bufferLog, diagnostic)
    }
}
