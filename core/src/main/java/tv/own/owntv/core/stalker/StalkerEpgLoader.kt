package tv.own.owntv.core.stalker

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.SourceEntity
import java.io.File
import java.io.IOException

/**
 * The portal's own guide, crawled into the shared EPG tables (report: "with my Stalker portal I
 * cannot see EPG or catch-up — STBEmu and TiviMate show both on the same portal and MAC").
 *
 * They can because they ask the portal for its guide. We only ever asked for the two programmes a
 * channel is showing around now (`get_short_epg`), which is enough for the preview pane and nothing
 * else: the Guide screen stayed empty, and **catch-up stayed unusable**, because picking a programme
 * to replay means picking it out of a guide that was never there.
 *
 * ## Why it is shaped like this
 *
 * Measured against a real 12 000-channel portal: `get_epg_info&period=7` answers in about a second
 * with 9 MB — 2 148 channels and 15 219 programmes. The endpoint is neither slow nor fragile. The
 * first version of this class still failed on it every time, with `unexpected end of stream`, because
 * it parsed the reply as it arrived and wrote each batch to the database with the HTTP response still
 * open. A keep-alive socket left idle while SQLite works gets closed by the far end.
 *
 * So: [download] the whole reply first and close the connection, then parse the file. Between those
 * two the portal is no longer involved, and a slow database cannot break a download. Everything else
 * here is about not giving up too early:
 *
 * 1. a broken or stale connection is **retried**, because that is precisely the failure above;
 * 2. a period the portal cannot serve steps **down** — 7 days, then 3, then 1;
 * 3. a portal with no working bulk endpoint at all falls back to **per-channel** `get_short_epg`,
 *    which every portal supports because it is what the Live preview already uses.
 */
class StalkerEpgLoader(
    private val auth: StalkerAuthManager,
    private val client: StalkerClient,
) {
    /** One programme, already keyed the way the guide tables want it. */
    data class Row(val epgChannelId: String, val title: String, val description: String?, val startMs: Long, val stopMs: Long)

    /** How the guide was obtained, for the log and for deciding whether to try the bulk call again. */
    enum class Method { BULK, PER_CHANNEL }

    data class Outcome(val method: Method, val periodDays: Int, val channels: Int, val programmes: Int)

    /**
     * Crawl [source]'s portal guide, calling [onBatch] with every [BATCH] programmes (and once more
     * with the remainder). Only programmes overlapping [from]..[to] are kept, so a portal serving a
     * longer period than we retain costs nothing extra to store.
     *
     * [channelIds] are the portal ids of this source's channels, used only by the per-channel fallback
     * — the bulk call needs no such list. [onProgress] reports running channel and programme counts so
     * the screen can show the same live numbers an XMLTV feed does.
     *
     * Throws only when every route failed; the caller then reports it and leaves the stored guide be.
     */
    suspend fun crawl(
        source: SourceEntity,
        from: Long,
        to: Long,
        cacheDir: File,
        channelIds: List<String> = emptyList(),
        onProgress: (channels: Int, programmes: Int) -> Unit = { _, _ -> },
        onBatch: suspend (List<Row>) -> Unit,
    ): Outcome {
        val mac = source.mac?.let { StalkerClient.canonicalizeMac(it) }
            ?: throw IOException("Stalker source ${source.id} has no valid MAC")
        val creds = source.stalkerCredentials(mac)
        val sink = Sink(from, to, onProgress, onBatch)

        var lastError: Exception? = null
        for (period in PERIODS) {
            try {
                val programmes = bulk(creds, mac, period, cacheDir, source.id, sink)
                // A portal that answers 200 with an empty guide has told us the bulk route is useless
                // here; fall through to the per-channel one rather than storing nothing.
                if (programmes > 0) {
                    sink.flush()
                    Log.i(TAG, "portal guide sourceId=${source.id} bulk period=$period channels=${sink.channels} programmes=${sink.kept}")
                    return Outcome(Method.BULK, period, sink.channels, sink.kept)
                }
                Log.w(TAG, "portal guide sourceId=${source.id} period=$period returned nothing usable")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "portal guide sourceId=${source.id} period=$period failed: ${e.message}")
            }
        }

        if (channelIds.isEmpty()) throw lastError ?: IOException("Portal returned no guide")
        val programmes = perChannel(creds, mac, channelIds, sink)
        sink.flush()
        if (programmes == 0) throw lastError ?: IOException("Portal returned no guide")
        Log.i(TAG, "portal guide sourceId=${source.id} per-channel channels=${sink.channels} programmes=${sink.kept}")
        return Outcome(Method.PER_CHANNEL, 0, sink.channels, sink.kept)
    }

    /**
     * One bulk attempt at [periodDays], retried on a broken connection. Returns how many programmes
     * were accepted. The downloaded file is always deleted — it is a means, not a cache.
     */
    private suspend fun bulk(
        creds: StalkerCredentials, mac: String, periodDays: Int, cacheDir: File, sourceId: Long, sink: Sink,
    ): Int {
        val dest = File(cacheDir, "portal_epg_$sourceId.json")
        try {
            var attempt = 1
            while (true) {
                try {
                    auth.withAuthRetry(creds) { session ->
                        client.downloadEpgInfo(session.apiBase, mac, session.token, creds.userAgent, periodDays, dest)
                    }
                    break
                } catch (e: IOException) {
                    // The stale keep-alive connection this class exists to survive. The shared HTTP
                    // client has OkHttp's own connection retry turned off (it would interfere with the
                    // syncers' accounting), so the retry has to happen here.
                    if (!isBrokenConnection(e) || attempt >= DOWNLOAD_ATTEMPTS) throw e
                    Log.w(TAG, "guide download attempt $attempt failed (${e.message}) — retrying")
                    delay(RETRY_DELAY_MS * attempt)
                    attempt++
                }
            }
            val before = sink.kept
            client.parseEpgInfoFile(dest) { channelId, entry -> sink.add(channelId, entry) }
            return sink.kept - before
        } finally {
            runCatching { dest.delete() }
        }
    }

    /**
     * The last resort: ask each channel for its own short guide. Every portal serves this — it is what
     * the Live preview already uses — but it is one request per channel, so it is bounded by
     * [MAX_PER_CHANNEL] and only reached when no bulk period worked at all.
     */
    private suspend fun perChannel(
        creds: StalkerCredentials, mac: String, channelIds: List<String>, sink: Sink,
    ): Int {
        val before = sink.kept
        var failures = 0
        channelIds.asSequence().distinct().take(MAX_PER_CHANNEL).forEach { channelId ->
            try {
                val entries = auth.withAuthRetry(creds) { session ->
                    client.getShortEpg(session.apiBase, mac, session.token, creds.userAgent, channelId, PER_CHANNEL_SIZE)
                }
                entries.forEach { sink.add(channelId, it) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // A portal that refuses every channel is not going to start saying yes; stop early
                // rather than spend thousands of requests proving it.
                if (++failures >= PER_CHANNEL_GIVE_UP) {
                    Log.w(TAG, "per-channel guide abandoned after $failures failures: ${e.message}")
                    return sink.kept - before
                }
            }
        }
        return sink.kept - before
    }

    /** Collects programmes, filters them to the retained window, and hands them over in batches. */
    private class Sink(
        private val from: Long,
        private val to: Long,
        private val onProgress: (Int, Int) -> Unit,
        private val onBatch: suspend (List<Row>) -> Unit,
    ) {
        private val batch = ArrayList<Row>(BATCH)
        private val seenChannels = HashSet<String>()
        var kept = 0; private set
        val channels: Int get() = seenChannels.size

        suspend fun add(channelId: String, entry: StalkerClient.ShortEpgEntry) {
            // The portal keys its guide by the channel's PORTAL id, which is what StalkerSyncer stores
            // as each channel's epgChannelId when the portal offers no XMLTV id — normalised the same
            // way the XMLTV path normalises, so lookups hit the index directly.
            val key = channelId.trim().lowercase()
            if (key.isEmpty() || entry.stopMs <= from || entry.startMs >= to) return
            batch.add(Row(key, entry.title, entry.description, entry.startMs, entry.stopMs))
            seenChannels.add(key)
            kept++
            if (batch.size >= BATCH) flush()
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            onBatch(ArrayList(batch))
            batch.clear()
            onProgress(seenChannels.size, kept)
        }
    }

    private fun isBrokenConnection(e: IOException): Boolean = when {
        e is StalkerClient.StalkerAuthException -> false
        e is java.net.SocketException -> true
        e is java.io.InterruptedIOException -> true
        e is javax.net.ssl.SSLException -> true
        // "unexpected end of stream on …" — the keep-alive socket the far end had already closed.
        // It arrives as a plain IOException, so only the wrapped cause identifies it.
        else -> generateSequence(e as Throwable) { it.cause }.take(CAUSE_CHAIN_LIMIT)
            .any { it is java.io.EOFException } || e.message?.contains("unexpected end of stream", ignoreCase = true) == true
    }

    companion object {
        private const val TAG = "StalkerEpg"

        /**
         * Days of guide to ask for, in order. Seven is what a set-top box asks for and what the app
         * retains anyway; the shorter ones exist for panels that cannot build a week in one go.
         */
        val PERIODS = listOf(7, 3, 1)

        /** Programmes per database write. */
        const val BATCH = 2_000

        /** Attempts per bulk download (1 original + 2 retries) — see [isBrokenConnection]. */
        const val DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 750L

        /** Ceiling on the per-channel fallback: it is one request per channel. */
        const val MAX_PER_CHANNEL = 1_500

        /** Programmes asked of each channel in the fallback — a day or so on most portals. */
        const val PER_CHANNEL_SIZE = 20

        /** Consecutive-ish failures after which the per-channel route is abandoned. */
        private const val PER_CHANNEL_GIVE_UP = 25

        private const val CAUSE_CHAIN_LIMIT = 5
    }
}
