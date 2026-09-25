package tv.own.owntv.core.timeshift

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.own.owntv.core.recording.DashManifest
import tv.own.owntv.core.recording.DashRecordingPlan
import tv.own.owntv.core.recording.HlsMediaPlaylist
import java.io.InputStream

/**
 * The one connection of a timeshift buffer (N4, decision 25): the channel is downloaded here, once, and
 * every player watches the saved copy. It keeps downloading while the player is paused — that is the
 * whole point — and it is the only thing that talks to the provider while timeshift is on.
 *
 * Three shapes, the same three the recorder meets, reusing its parsers:
 *  - **MPEG-TS** (a plain `.ts` channel, and HLS with TS segments): one continuous stream cut by
 *    [TsCutter] into short pieces that each start on a keyframe with the tables in front.
 *  - **HLS with fragmented MP4** (`#EXT-X-MAP`): one piece per segment, the init segment kept once.
 *  - **DASH** with one Representation carrying picture and sound: one piece per segment.
 *
 * What it cannot turn into one stream — encrypted HLS, separate audio (HLS renditions, two-track DASH),
 * protected DASH, anything that is not TS or fragmented MP4 — is [Result.Unsupported], and the channel
 * plays the ordinary way instead. A refusal on the first connection is [Result.Refused] for the same
 * reason: timeshift must never make a channel less playable than it was without it.
 *
 * Signed segment URLs: the playlist or manifest is re-read every cycle and segments are identified by
 * number, never by URL — the recorder's rule, for the recorder's reason.
 */
class TimeshiftDownloader(
    private val client: OkHttpClient,
    private val session: TimeshiftSession,
    private val request: Target,
    private val windowMs: () -> Long,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where and how to connect. [url] is asked again on every reconnect (a Stalker link is single-use). */
    class Target(
        val url: suspend () -> String?,
        val userAgent: String,
        val headers: Map<String, String>,
        /** Settings → Maximum video quality, for an HLS master; null for none. */
        val maxVideoHeight: Int?,
    )

    sealed interface Result {
        /** This stream cannot be buffered; play it the ordinary way. */
        data class Unsupported(val why: String) : Result
        /** The first connection failed before anything was saved; play it the ordinary way. */
        data class Refused(val why: String) : Result
        /** It played, then dropped more times in a row than [TimeshiftRules.MAX_RECONNECTS]. */
        data class GaveUp(val why: String) : Result
    }

    /** Something was saved at least once — after this, failures are reconnects, not refusals. */
    @Volatile var started = false
        private set

    private class Failure(val why: String) : Exception(why)
    private class NotSupported(val why: String) : Exception(why)

    /** Runs until cancelled (the normal end) or until it has to give up. */
    suspend fun run(): Result = withContext(Dispatchers.IO) {
        // A blocking read ignores coroutine cancellation; cancelling the call is what frees the provider
        // connection the moment the buffer is parked.
        val handle = coroutineContext.job.invokeOnCompletion { current?.cancel() }
        try {
            loop()
        } finally {
            handle.dispose()
        }
    }

    @Volatile private var current: Call? = null

    private suspend fun loop(): Result {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val attemptStart = clock()
            val why = try {
                attempt()
                "the stream ended"
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotSupported) {
                log("timeshift: not buffered — ${e.why}")
                return Result.Unsupported(e.why)
            } catch (e: Failure) {
                e.why
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            // A parked buffer's call is cancelled under it: that is the end asked for, not a failure.
            currentCoroutineContext().ensureActive()
            if (!started) {
                log("timeshift: first connection failed — $why")
                return Result.Refused(why)
            }
            // Earned back by a long run of good downloading, as the live engines' reconnect budget is.
            if (clock() - attemptStart >= TimeshiftRules.HEALTHY_MS) failures = 0
            failures++
            // The piece the drop cut short is closed on the wall clock; the next one follows a hole.
            session.endPiece(durationMs = null)
            session.markGap()
            if (failures > TimeshiftRules.MAX_RECONNECTS) {
                log("timeshift: giving up after $failures drops — $why")
                return Result.GaveUp(why)
            }
            val wait = TimeshiftRules.reconnectDelayMs(failures)
            log("timeshift: connection dropped ($why) — reconnect $failures in ${wait}ms")
            delay(wait)
        }
    }

    private suspend fun attempt() {
        val url = request.url() ?: throw Failure("no stream address")
        call(url).use { response ->
            if (!response.isSuccessful) throw Failure("HTTP ${response.code}")
            val peek = response.peekBody(PEEK_BYTES).string()
            val type = response.header("Content-Type")
            val finalUrl = response.request.url.toString()
            when {
                HlsMediaPlaylist.looksLikePlaylist(type, peek) -> hls(finalUrl, response.body.string())
                DashManifest.looksLikeDashManifest(type, peek) -> dash(finalUrl, response.body.string())
                else -> transportStream(response.body.byteStream())
            }
        }
    }

    // --- MPEG-TS ------------------------------------------------------------------------------------

    private inner class PieceSink : TsCutter.Sink {
        var open = false
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!open) { session.beginPiece(); open = true }
            session.write(bytes, offset, length)
        }
        override fun cut(durationMs: Long) {
            if (open) session.endPiece(durationMs)
            session.trim(windowMs())
            session.beginPiece()
        }
    }

    private suspend fun transportStream(input: InputStream) {
        session.setContainer(TimeshiftSession.Container.TS)
        val cutter = TsCutter(wallClock = clock)
        val sink = PieceSink()
        val buffer = ByteArray(READ_BYTES)
        input.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = it.read(buffer)
                if (n < 0) throw Failure("end of stream")
                cutter.feed(buffer, 0, n, sink)
                if (!cutter.synced && cutter.discardedBytes > NOT_TS_BYTES) throw NotSupported("not an MPEG-TS stream")
                if (sink.open) {
                    session.flush()
                    started = true
                }
            }
        }
    }

    // --- HLS ----------------------------------------------------------------------------------------

    private suspend fun hls(url: String, firstText: String) {
        var playlistUrl = url
        var text = firstText
        if (HlsMaster.isMaster(text)) {
            val variant = HlsMaster.choose(text, request.maxVideoHeight)
                ?: throw NotSupported("every HLS variant has separate sound")
            playlistUrl = absolute(url, variant.uri) ?: throw NotSupported("unreadable HLS variant address")
            text = fetchText(playlistUrl)
        }
        val cutter = TsCutter(wallClock = clock)
        val sink = PieceSink()
        var lastSequence = -1L
        var mapKey: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val playlist = HlsMediaPlaylist.parse(text)
            if (playlist.isEncrypted) throw NotSupported("encrypted HLS")
            val map = HlsMaster.mapUri(text)
            if (map != null) {
                // The init URI may be re-signed every cycle; only its path says whether it changed.
                val key = map.substringBefore('?')
                if (key != mapKey) {
                    val initUrl = absolute(playlistUrl, map) ?: throw NotSupported("unreadable init segment address")
                    session.setContainer(TimeshiftSession.Container.FMP4, fetchBytes(initUrl))
                    mapKey = key
                }
            } else if (mapKey == null) {
                session.setContainer(TimeshiftSession.Container.TS)
            }
            // The window has scrolled past us (a slow cycle): rejoin where it is now.
            if (lastSequence >= 0 && playlist.mediaSequence > lastSequence + 1) {
                lastSequence = playlist.mediaSequence - 1
                session.markGap()
            }
            // First cycle: the newest few segments, as a player starts — not the provider's whole window.
            val due = if (lastSequence < 0) playlist.segments.takeLast(START_SEGMENTS)
                else playlist.segments.filter { it.sequence > lastSequence }
            for (segment in due) {
                currentCoroutineContext().ensureActive()
                val segmentUrl = absolute(playlistUrl, segment.uri) ?: continue
                val ok = if (map != null) {
                    fmp4Piece(segmentUrl, (segment.durationSecs * 1000).toLong())
                } else {
                    tsSegment(segmentUrl, cutter, sink)
                }
                // One refused segment is a hole, not a failure — the recorder's judgement.
                if (!ok) log("timeshift: segment ${segment.sequence} refused")
                lastSequence = segment.sequence
            }
            if (playlist.endList) throw Failure("the playlist ended")
            delay(playlist.pollIntervalMs)
            text = fetchText(playlistUrl)
        }
    }

    private suspend fun tsSegment(url: String, cutter: TsCutter, sink: PieceSink): Boolean =
        call(url).use { response ->
            if (!response.isSuccessful) return false
            val buffer = ByteArray(READ_BYTES)
            response.body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    cutter.feed(buffer, 0, n, sink)
                    if (sink.open) {
                        session.flush()
                        started = true
                    }
                }
            }
            if (!cutter.synced) throw NotSupported("HLS segments are not MPEG-TS")
            true
        }

    /** One fragmented-MP4 segment as one piece, readable while it is still arriving. */
    private suspend fun fmp4Piece(url: String, durationMs: Long): Boolean =
        call(url).use { response ->
            if (!response.isSuccessful) return false
            session.beginPiece()
            val buffer = ByteArray(READ_BYTES)
            response.body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    session.write(buffer, 0, n)
                    session.flush()
                    started = true
                }
            }
            session.endPiece(durationMs)
            session.trim(windowMs())
            true
        }

    // --- DASH ---------------------------------------------------------------------------------------

    private suspend fun dash(url: String, firstText: String) {
        var text = firstText
        var representationId: String? = null
        var lastNumber = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val manifest = DashManifest.parse(text) ?: throw NotSupported("unreadable DASH manifest")
            if (manifest.contentProtected) throw NotSupported("protected DASH")
            val representation = if (representationId == null) {
                val selection = DashRecordingPlan.selectTracks(manifest) ?: throw NotSupported("DASH without tracks")
                if (selection.needsMux) throw NotSupported("DASH with separate audio and video")
                val chosen = selection.tracks.single()
                if (chosen.mimeType?.contains("webm", ignoreCase = true) == true) throw NotSupported("WebM DASH")
                val init = chosen.initializationUrl ?: throw NotSupported("DASH without an init segment")
                session.setContainer(
                    TimeshiftSession.Container.FMP4,
                    fetchBytes(absolute(url, init) ?: throw NotSupported("unreadable init segment address")),
                )
                representationId = chosen.id
                chosen
            } else {
                manifest.representations.firstOrNull { it.id == representationId }
                    ?: throw Failure("the DASH representation went away")
            }
            val plan = DashRecordingPlan.nextSegments(manifest, representation, lastNumber, clock())
            if (plan.unschedulable) throw NotSupported("unschedulable DASH manifest")
            for (segment in plan.segments) {
                currentCoroutineContext().ensureActive()
                val segmentUrl = absolute(url, segment.url) ?: continue
                if (!fmp4Piece(segmentUrl, segment.durationMs)) log("timeshift: DASH segment ${segment.number} refused")
            }
            lastNumber = plan.lastNumber
            if (!manifest.dynamic) throw Failure("the DASH manifest is not live")
            delay(DashRecordingPlan.pollIntervalMs(manifest, representation))
            text = fetchText(url)
        }
    }

    // --- HTTP ---------------------------------------------------------------------------------------

    private fun call(url: String): Response {
        val builder = Request.Builder().url(url).header("User-Agent", request.userAgent)
        request.headers.forEach { (name, value) -> if (!name.equals("User-Agent", true)) builder.header(name, value) }
        return client.newCall(builder.build()).also { current = it }.execute()
    }

    private fun fetchText(url: String): String = call(url).use { response ->
        if (!response.isSuccessful) throw Failure("HTTP ${response.code}")
        response.body.string()
    }

    private fun fetchBytes(url: String): ByteArray = call(url).use { response ->
        if (!response.isSuccessful) throw Failure("HTTP ${response.code}")
        response.body.bytes()
    }

    private fun absolute(base: String, uri: String): String? =
        runCatching { java.net.URI(base).resolve(uri).toString() }.getOrNull()

    private companion object {
        const val PEEK_BYTES = 1024L
        const val READ_BYTES = 64 * 1024
        /** Half a megabyte without one TS sync pattern is not a transport stream. */
        const val NOT_TS_BYTES = 512L * 1024
        /** How far behind the HLS live edge the buffer starts, in segments — where a player would. */
        const val START_SEGMENTS = 3
    }
}
