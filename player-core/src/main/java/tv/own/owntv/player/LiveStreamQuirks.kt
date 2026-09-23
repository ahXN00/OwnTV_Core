package tv.own.owntv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider live-stream quirks learned at runtime, shared by both engines.
 *
 * Everything here is **in-memory for the session only** — so a provider that fixes its panel is back
 * to stock behaviour after the next app start. The one exception is the archive-decode quirk, which
 * is also written to [tv.own.owntv.core.player.ArchiveDecodeStore] because re-learning it costs a
 * failed archive open rather than a few seconds. Keyed by `host:port` so a
 * lesson learned on one channel applies immediately to every other channel of the same panel
 * (these faults are panel-wide, not per-channel).
 *
 * Three quirks are tracked:
 *
 *  1. **`.ts` that is really HLS.** Some Xtream panels advertise `/live/user/pass/ID.ts` but
 *     HTTP-redirect it to an `.m3u8` manifest. ExoPlayer picks its media source from the URL
 *     *before* that redirect and hands a text manifest to the progressive extractor
 *     (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`); mpv/FFmpeg treats the manifest response's EOF as
 *     a broken raw stream and reconnects to the same 1.8 KB body forever (permanent black screen).
 *     Once either engine has seen the redirect land on a manifest we remember it here.
 *
 *  2. **Per-segment signed URLs that Media3 cannot keep fresh.** The panel this was traced on hands
 *     out segment URLs carrying a short-lived signed token
 *     (`/serve/<id>/<token>/<token>/…/136875_2559.ts`) and answers **every** one of them with
 *     `403 "Invalid token 2"` — the playlist itself keeps returning 200, and not a single segment ever
 *     succeeds. Media3's chunk pipeline can only re-issue the *identical* URL it resolved from the
 *     playlist snapshot, so once a token has aged out the channel can never recover; FFmpeg (mpv, VLC)
 *     re-reads the playlist and fetches with a fresh token, which is why the same channel plays there,
 *     lagging but alive. There is nothing to tune — the fix is to recognise the pattern quickly and
 *     hand the panel to mpv, rather than grinding ExoPlayer's reconnect ladder on a dead channel.
 *
 *  3. **A panel that blocklists the default User-Agent.** Some hosts sit behind a WAF that refuses
 *     player identities by name. Traced on a Cloudflare-fronted panel that answers every request
 *     carrying `VLC/3.0.20 LibVLC/3.0.20` with a `403` challenge page ("Just a moment…") and serves the
 *     identical URL `200` to a neutral UA. Both engines send the VLC identity, so such a channel fails
 *     on ExoPlayer, walks the whole fallback ladder, and fails on mpv too — indistinguishable from a
 *     dead provider. Once either engine has been refused this way we remember it here, so every other
 *     channel on the panel opens under [tv.own.owntv.core.network.HttpClient.FALLBACK_USER_AGENT]
 *     first time instead of repeating the failure.
 */
object LiveStreamQuirks {

    /**
     * How many distinct live segments a provider must refuse before we stop trying on ExoPlayer.
     *
     * Two, not one: a single 403 can be a genuine one-off (a segment rolled out of the window mid-flight),
     * and the load-error policy already absorbs that with one quick retry. Two *different* segments
     * refused in the same load is the signature of a URL-signing scheme Media3 structurally cannot
     * satisfy, and every further attempt just costs the user another dead spinner.
     */
    const val REFUSALS_BEFORE_HANDOFF = 2

    /** HTTP statuses a panel uses to refuse a segment outright. Never worth hammering the same URL for. */
    fun isEdgeRefusal(responseCode: Int): Boolean =
        responseCode == 403 || responseCode == 404 || responseCode == 410

    /**
     * The non-standard status a panel returns when the account's one allowed session is already in use.
     *
     * Traced on a panel whose edge answers `458` with an empty `text/html` body and no redirect, while a
     * permitted request is redirected through to the origin. It is not an HTTP standard code — Xtream
     * panels invent codes in this range for "max connections" — so it is matched exactly rather than by
     * class, and it means something very different from [isEdgeRefusal]: the stream is fine, *we* are the
     * second client.
     *
     * `407` joins it for the same reason. A panel fronted by a gateway answers the *reconnect* with
     * "Proxy Authentication Required" while the dying connection is still counted against the
     * account, even though no proxy is in play — traced on a television whose channel had played for
     * twenty seconds with zero dropped frames, then burned all eight reconnects on `407` in six
     * seconds and gave up. The stream was never the problem: the previous socket had not finished
     * closing. Waiting once is the answer here exactly as it is for `458`.
     */
    fun isSessionLimit(responseCode: Int): Boolean =
        responseCode == 458 || responseCode == PlayerErrors.HTTP_PROXY_AUTH

    /**
     * Statuses a WAF uses to refuse a request on *who is asking* rather than what was asked for — worth
     * one retry under a different identity before the stream is written off.
     *
     * `403` is the Cloudflare managed-challenge answer traced on the panel in the quirk-3 note above;
     * `503` is the same product's "checking your browser" variant. Only ever consulted **before the
     * first frame** (see the caller): once a channel has played, a mid-stream `403` is a stale segment
     * token, which is [isEdgeRefusal]'s business and a new UA cannot help it.
     */
    fun isIdentityRefusal(responseCode: Int): Boolean = responseCode == 403 || responseCode == 503

    /** `host:port` of [url], lowercased; the whole URL when it can't be parsed (still a stable key). */
    fun hostKey(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringAfterLast('@')
        return authority.ifBlank { url }.lowercase()
    }

    // --- learned state ---------------------------------------------------------------------------

    /**
     * The most URL-keyed lessons any one of the sets below will hold.
     *
     * Host-keyed sets are naturally bounded — a user has a handful of panels — but a URL-keyed one grows
     * with every channel ever tuned, and nothing here ever evicted. A long zapping session on a big
     * playlist is the case that matters. 512 is far beyond any real session: reaching it means learning
     * a format lesson about five hundred DIFFERENT streams without restarting the app, at which point
     * the oldest are of no further interest anyway.
     */
    private const val MAX_URL_LESSONS = 512

    /**
     * A URL-keyed lesson set that forgets its oldest entries rather than growing without limit.
     *
     * Insertion-ordered, so eviction drops the stream longest ago written off. A lesson recorded during
     * the tune currently on screen is therefore the newest entry in the set and cannot be evicted unless
     * five hundred further streams are written off before the channel is consulted again — which cannot
     * happen inside one tune.
     */
    private fun urlLessonSet(): MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(64, 0.75f) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                    size > MAX_URL_LESSONS
            },
        ),
    )

    private val hlsRedirectHosts = ConcurrentHashMap.newKeySet<String>()
    private val dashRedirectHosts = ConcurrentHashMap.newKeySet<String>()
    private val segmentRefusingHosts = ConcurrentHashMap.newKeySet<String>()
    private val singleSessionHosts = ConcurrentHashMap.newKeySet<String>()
    private val brokenTimestampStreams = urlLessonSet()
    private val noHlsVariantStreams = urlLessonSet()
    private val noHlsVariantMpvStreams = urlLessonSet()
    private val tolerantDemuxStreams = urlLessonSet()
    private val prerollDefeatedStreams = urlLessonSet()
    private val softwareArchiveHosts = ConcurrentHashMap.newKeySet<String>()
    private val uaBlockingHosts = ConcurrentHashMap.newKeySet<String>()
    private val providerMessages = ConcurrentHashMap<String, Pair<Int, String>>()

    /** Record that [url]'s host serves HLS even when its advertised URL says `.ts`. */
    fun rememberHlsRedirect(url: String) { hlsRedirectHosts += hostKey(url) }

    fun isKnownHlsHost(url: String): Boolean = hostKey(url) in hlsRedirectHosts

    /**
     * Record that [url]'s host serves DASH manifests from URLs that do not say `.mpd` (v43).
     *
     * The DASH twin of [rememberHlsRedirect], and it matters for the same reason: the traced proxy
     * publishes every channel as `…/live/mpd/{id}` and only redirects to `…/render.mpd` once asked, so
     * without a panel-wide lesson EVERY channel on it pays its own failed progressive attempt and
     * retry before playing.
     */
    fun rememberDashRedirect(url: String) { dashRedirectHosts += hostKey(url) }

    fun isKnownDashHost(url: String): Boolean = hostKey(url) in dashRedirectHosts

    /**
     * True when [url] itself asks for HLS — it ends in `.m3u8` — ignoring anything learned about its
     * panel.
     *
     * The distinction from [isHlsUrl] matters when deciding whether an HLS *discovery* is worth
     * remembering: a URL we deliberately requested as `.m3u8` proves nothing about what the panel does
     * with the `.ts` endpoint, and recording it as a redirect poisons every other channel there.
     */
    fun isExplicitHlsUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /**
     * True when [url] should be treated as HLS regardless of its extension: either it already ends in
     * `.m3u8`, or its panel has been caught redirecting `.ts` to a manifest.
     */
    fun isHlsUrl(url: String): Boolean = isExplicitHlsUrl(url) || isKnownHlsHost(url)

    /** Rewrite an Xtream-style `.ts` live URL to its `.m3u8` sibling; other URLs are returned as-is. */
    fun toHlsUrl(url: String): String {
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        if (!path.endsWith(".ts", ignoreCase = true)) return url
        val rewritten = path.dropLast(3) + ".m3u8"
        return if (query.isEmpty()) rewritten else "$rewritten?$query"
    }

    /**
     * The `.ts` ⇄ `.m3u8` sibling of [url], or null when there is no extension to swap.
     *
     * The extension is tested on the PATH only, and any query/fragment is carried over untouched. An
     * M3U live URL like `…/stream-output.m3u8?mode=hls` ends in a *parameter* rather than an extension,
     * so testing the whole string skips the swap entirely; and the provider's own `mode` is usually
     * required, so dropping it yields a 400 instead of the sibling.
     *
     * Shared by both engines, so a channel gets the same alternate whichever one is asking.
     */
    fun alternateFormatUrl(url: String): String? {
        val cut = url.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: url.length
        val path = url.substring(0, cut)
        val suffix = url.substring(cut)
        val from = when {
            path.endsWith(".m3u8", ignoreCase = true) -> ".m3u8"
            path.endsWith(".ts", ignoreCase = true) -> ".ts"
            else -> return null
        }
        val to = if (from == ".m3u8") ".ts" else ".m3u8"
        return path.dropLast(from.length) + to + suffix
    }

    /**
     * Statuses where the provider is refusing the *request* rather than describing a broken stream.
     *
     * Guards the format swap above, which can only ever fix a *container* problem. Traced on a panel
     * that answers `429 "Channel limit has been reached"`: the ladder read that as a format failure,
     * went off to an invented `.ts` URL that 404s, retried the invented URL six times over ~45 s and
     * ended on an error screen blaming the channel — while the original URL worked the moment the
     * account's other stream closed. A different file extension cannot answer any of these.
     */
    fun isRequestRefusal(responseCode: Int): Boolean =
        responseCode == 401 || responseCode == 402 || responseCode == 403 || responseCode == 429 ||
            responseCode == 503 || responseCode == 509 || isSessionLimit(responseCode)

    /**
     * Record that this panel refuses its own signed segment URLs, so the *next* channel on it opens
     * straight on mpv instead of repeating ExoPlayer's dead spinner. Panel-wide because the signing
     * scheme is a property of the panel, not of one channel.
     */
    fun rememberSegmentRefusal(url: String) { segmentRefusingHosts += hostKey(url) }

    fun refusesSegments(url: String): Boolean = hostKey(url) in segmentRefusingHosts

    /**
     * Record that this panel allows only one session at a time (it answered [isSessionLimit]).
     *
     * On such a panel the two engines must never be connected at once: whichever one holds the session
     * keeps playing and the other is locked out until the holder's socket is really gone, which is the
     * whole "mpv works but ExoPlayer doesn't" (and the reverse) symptom. Panel-wide, because the limit is
     * on the *account*, not the channel.
     */
    fun rememberSessionLimit(url: String) { singleSessionHosts += hostKey(url) }

    fun isSingleSession(url: String): Boolean = hostKey(url) in singleSessionHosts

    /**
     * Record that mpv can't trust this stream's video timestamps, so its next open starts on free-running
     * video timing (`correct-pts=no` + `video-sync=desync` + `framedrop=no`).
     *
     * Keyed by the **whole URL**, not the panel: a broken mux is a property of one feed, and its
     * healthy neighbours on the same panel must keep mpv's accurate audio-synced timing — free-running
     * timing drifts sound away from picture on a stream whose PTS were fine all along.
     */
    fun rememberBrokenTimestamps(url: String) { brokenTimestampStreams += url }

    fun hasBrokenTimestamps(url: String): Boolean = url in brokenTimestampStreams

    /**
     * Record that this channel has no `.m3u8` sibling **the given engine can play**, so "Prefer HLS"
     * must be ignored for it there.
     *
     * The playlist setting rewrites every Xtream `.ts` to `.m3u8` blindly, but a panel does not
     * necessarily remux *every* channel: the odd one answers its `.m3u8` with a 404, an empty body, or a
     * playlist whose segments never arrive — and the channel that played perfectly in TS mode turns into
     * a black screen or a spinner that never resolves. The fix is to fall back to the `.ts` the panel
     * does serve.
     *
     * **Scoped per engine, because "unplayable" is not the same verdict for both.** Traced on a 4K
     * channel whose `.m3u8` is served perfectly well and plays on mpv, while ExoPlayer's audio renderer
     * never produces a sample from it (`audio=false` with the buffer full). A single shared flag made
     * ExoPlayer's verdict push mpv onto `.ts` too, costing the one combination that actually worked. So
     * each engine only ever learns its own failures, and the fallback ladder gives the other engine the
     * HLS variant a fair try before giving up on it.
     *
     * Keyed by the **channel's own `.ts` URL**, and never widened to the panel: on the very same provider
     * all the other channels keep their HLS variant, which is the reason the user turned the setting on.
     * Session-only, so a panel that finishes remuxing is back to HLS after the next app start.
     *
     * **A panel-wide verdict used to exist here and was removed deliberately.** Three channels failing
     * their `.m3u8` wrote off the whole provider for the session, and the failures that reached this
     * function were not necessarily about format at all — an account-busy lockout (HTTP 458) walked three
     * channels onto their `.ts` rung in under two minutes and cost every remaining channel its HLS start
     * for the rest of the session. One channel's evidence now only ever condemns that channel.
     */
    fun rememberNoHlsVariant(tsUrl: String) {
        noHlsVariantStreams += tsUrl
    }

    fun lacksHlsVariant(tsUrl: String): Boolean = tsUrl in noHlsVariantStreams

    /** As [rememberNoHlsVariant], for mpv. */
    fun rememberNoHlsVariantMpv(tsUrl: String) {
        noHlsVariantMpvStreams += tsUrl
    }

    fun lacksHlsVariantMpv(tsUrl: String): Boolean = tsUrl in noHlsVariantMpvStreams

    /**
     * Record that this stream only opens with FFmpeg's error tolerance turned on.
     *
     * mpv runs FFmpeg's demuxers at their strict defaults, which is why a re-streamed feed with lost
     * packets, malformed PSI tables or jumping timestamps ends as an `END_FILE` here while VLC — whose TS
     * demuxer is its own, and forgiving — plays it. The tolerant options ([OwnTVPlayer.demuxerLavfOptionsFor])
     * close most of that gap, but they are a *retry*, never a default: dropping corrupt packets and
     * synthesising timestamps costs accuracy on a stream whose data was fine all along.
     *
     * Keyed by the **whole URL**: a broken mux belongs to one feed, and its healthy neighbours on the same
     * panel must keep the accurate path. Session-only.
     */
    fun rememberNeedsTolerantDemux(url: String) { tolerantDemuxStreams += url }

    fun needsTolerantDemux(url: String): Boolean = url in tolerantDemuxStreams

    /**
     * Record that this stream can never satisfy the "Pre-buffer" threshold, so it must open without one.
     *
     * ExoPlayer's `DefaultLoadControl` starts (and *resumes*) playback once **either** the requested
     * amount of media or the byte cap is reached. On a very high-bitrate feed — a 4K live channel is the
     * traced case — the byte cap is hit long before 10 s of media exists, so the time threshold is
     * unreachable: playback starts on the byte rule, drains a few frames, drops back under the cap, and
     * re-buffers immediately. The result is a `READY`/`BUFFERING` oscillation several times a second that
     * looks exactly like a frozen picture with a stuck spinner — and it defeats every stall watchdog,
     * because each `READY` cancels them before they can fire.
     *
     * Keyed by the **whole URL**: it is a property of one feed's bitrate, not of the panel, and the same
     * provider's HD channels satisfy the pre-roll fine. Session-only.
     */
    fun rememberPrerollDefeated(url: String) { prerollDefeatedStreams += url }

    fun defeatsPreroll(url: String): Boolean = url in prerollDefeatedStreams

    /**
     * Record that this panel's catch-up archive needs a SOFTWARE video decoder.
     *
     * Archive (timeshift) segments start mid-GOP, and some TV-class hardware decoders can't resync from
     * that: the decoder accepts the format, audio plays, and no video frame is ever emitted (Realtek OMX:
     * "setPortMode … DynamicANWBuffer failed", "BAD CODEC: stride 1920 -> 64"). A software decoder picks
     * up cleanly at the next keyframe.
     *
     * Catch-up used to be pinned to software *unconditionally* for that reason, which cost every panel
     * hardware decoding — including the majority whose decoders cope fine. So archives now open in
     * hardware and drop to software only once this panel has actually been caught failing; the cost of a
     * panel that does fail is one silent retry on the session's first catch-up.
     *
     * Panel-wide, because the mid-GOP archive mux is a property of the panel's timeshift server, not of
     * one channel. Unlike every other quirk here this one is also **persisted** (see
     * [installArchivePersistence]): re-learning it costs a whole failed archive open — audio with no
     * picture until the watchdog fires — which is too expensive to repeat every app start.
     */
    fun rememberArchiveNeedsSoftware(url: String) {
        val host = hostKey(url)
        if (softwareArchiveHosts.add(host)) archivePersistence?.invoke(host)
    }

    /**
     * Seed the archive-decode quirk from the previous run and start persisting new ones.
     *
     * Called once at startup off the main thread. [known] hosts are applied immediately; [save] is
     * invoked only for a host learned *now* that wasn't already remembered, so the store is written
     * at most once per panel per install.
     */
    fun installArchivePersistence(known: Set<String>, save: (String) -> Unit) {
        softwareArchiveHosts += known
        archivePersistence = save
    }

    @Volatile private var archivePersistence: ((String) -> Unit)? = null

    fun archiveNeedsSoftware(url: String): Boolean = hostKey(url) in softwareArchiveHosts

    /**
     * Record that this panel refuses [tv.own.owntv.core.network.HttpClient.DEFAULT_USER_AGENT], so every
     * later request to it — either engine, any channel — starts on the fallback identity.
     *
     * Panel-wide, because a WAF rule is configured for the site, not per stream. Session-only like the
     * rest: a provider that drops the rule is back to the default identity after the next app start,
     * which matters because the default is the one most panels actually want.
     *
     * Only ever recorded when the user has NOT configured a User-Agent for the source. An explicit
     * setting is the user's decision and is never second-guessed or overwritten.
     */
    fun rememberBlocksDefaultUserAgent(url: String) { uaBlockingHosts += hostKey(url) }

    fun blocksDefaultUserAgent(url: String): Boolean = hostKey(url) in uaBlockingHosts

    /**
     * Keep the panel's own explanation of a refusal, so an error screen can quote it verbatim.
     *
     * "Channel limit has been reached. Stop one of your active streams before opening a new channel."
     * says in one line what a status code can only hint at, and it is the difference between the user
     * closing the TV in the other room and the user assuming the channel is dead. Only ExoPlayer can
     * capture it — mpv/FFmpeg never exposes a response body, it just reports "HTTP error 429" — so it
     * is kept panel-wide here and mpv's error screen reads it back.
     *
     * Stored with the status it explained: a stale "channel limit" line quoted under a later 404 would
     * be worse than no message at all.
     */
    fun rememberProviderMessage(url: String, responseCode: Int, message: String) {
        providerMessages[hostKey(url)] = responseCode to message
    }

    /** The panel's own words for [responseCode], if it gave any this session; null otherwise. */
    fun providerMessage(url: String, responseCode: Int): String? =
        providerMessages[hostKey(url)]?.takeIf { it.first == responseCode }?.second

    /**
     * N15 — Settings → "Forget learned stream fixes": drop every lesson of this session and the stored
     * catch-up decode list, so channels open the standard way again and relearn only what a provider
     * still needs. The persistence hook stays installed, so a lesson learned afterwards is saved again.
     */
    suspend fun forgetLearned(archiveStore: tv.own.owntv.core.player.ArchiveDecodeStore) {
        hlsRedirectHosts.clear(); dashRedirectHosts.clear(); segmentRefusingHosts.clear(); singleSessionHosts.clear()
        brokenTimestampStreams.clear(); noHlsVariantStreams.clear(); noHlsVariantMpvStreams.clear()
        tolerantDemuxStreams.clear(); prerollDefeatedStreams.clear(); softwareArchiveHosts.clear()
        uaBlockingHosts.clear(); providerMessages.clear()
        archiveStore.clear()
    }

    /** Test hook — clears everything including the persistence hook. */
    internal fun clearForTest() {
        hlsRedirectHosts.clear(); segmentRefusingHosts.clear(); singleSessionHosts.clear()
        brokenTimestampStreams.clear(); softwareArchiveHosts.clear(); archivePersistence = null
        noHlsVariantStreams.clear(); noHlsVariantMpvStreams.clear(); prerollDefeatedStreams.clear()
        tolerantDemuxStreams.clear()
        uaBlockingHosts.clear(); providerMessages.clear()
    }
}
