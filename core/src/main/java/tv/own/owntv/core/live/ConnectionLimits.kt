package tv.own.owntv.core.live

import android.util.Log
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity

/** What is known about one playlist's stream limit, and how it came to be known. */
data class ConnectionLimit(
    /** Streams allowed at once, or 0 when nobody knows. */
    val streams: Int,
    /** True when the number came from a measurement rather than from the provider. */
    val measured: Boolean,
    /** When it was measured, or 0. */
    val measuredAt: Long,
    /** The measurement stopped at its own ceiling, so the real limit may be higher. */
    val atLeast: Boolean,
) {
    val isKnown: Boolean get() = streams > 0

    /** Measured, and the measurement could not conclude. Not the same as never having looked. */
    val measuredWithoutAnswer: Boolean get() = measuredAt > 0 && streams <= 0
}

/**
 * Decides whether a playlist's stream limit needs measuring, and remembers the answer.
 *
 * **The rule, in one line: ask the provider, and only measure the ones that will not say.** An
 * Xtream panel publishes the number and is authoritative; measuring it would spend a minute and two
 * connections learning what it already told us. Stalker portals and M3U playlists almost never
 * publish it — one measured portal returns 162 profile fields without a connection limit among them
 * — and for those the only way to know is to open streams and watch.
 *
 * **It runs at most once per playlist**, when the playlist is added, plus whenever the user asks for
 * a re-test. [SourceEntity.maxConnectionsProbedAt] is what enforces that: a measurement that
 * concluded nothing is still recorded as having happened, so a playlist the probe cannot answer for
 * is not re-measured on every sync for the rest of its life.
 */
class ConnectionLimits(
    private val sourceDao: SourceDao,
    private val probe: ConnectionProbe,
    private val channels: ProbeChannelSource,
    private val tester: tv.own.owntv.core.repository.SourceTester,
    /** Host failover: measure on the in-use server, and never beside Test all servers or a switch (§9). */
    private val hosts: tv.own.owntv.core.network.HostSelector? = null,
) {

    /** What is already known, with no network and no measuring. */
    fun known(source: SourceEntity): ConnectionLimit = ConnectionLimit(
        streams = source.maxConnections,
        measured = source.maxConnectionsProbedAt > 0,
        measuredAt = source.maxConnectionsProbedAt,
        // A measurement that hit the ceiling stored the ceiling; anything at or above it may be more.
        atLeast = source.maxConnectionsProbedAt > 0 && source.maxConnections >= MAX_PROBE_STREAMS,
    )

    /** Does this playlist still need measuring? False once the provider has told us, or once we tried. */
    fun needsMeasuring(source: SourceEntity): Boolean =
        source.maxConnections <= 0 && source.maxConnectionsProbedAt <= 0

    /**
     * Measure the limit and save it.
     *
     * **Only ever call this when nothing is playing.** On a single-connection account the probe is
     * precisely the thing that cuts the user's channel off — that is what it is detecting.
     *
     * [force] is the user pressing Re-test: it measures even a playlist whose provider published a
     * number, because the user asking outranks the panel.
     */
    suspend fun measureAndStore(
        source: SourceEntity,
        force: Boolean = false,
        onProgress: (ProbeProgress) -> Unit = {},
    ): ConnectionLimit {
        if (!force && !needsMeasuring(source)) return known(source)
        // Ask the provider before measuring anything.
        //
        // This is not redundant with [needsMeasuring]. On a playlist's *first* sync the panel has not
        // been queried yet, so `maxConnections` is still 0 even for an Xtream account that publishes
        // it perfectly well — the Xtream syncer writes it later in the same run, after this point.
        // Without this step every new Xtream playlist would spend two minutes and several connections
        // measuring a number it was about to be told, which is exactly what the design says not to do.
        val published = runCatching { tester.test(source) }.getOrNull()
        val reported = (published as? tv.own.owntv.core.repository.SourceTestResult.Ok)?.maxConnections ?: 0
        if (reported > 0) {
            Log.i(TAG, "source ${source.id} publishes $reported connection(s) — not measuring")
            sourceDao.updateMaxConnections(source.id, reported)
            // Deliberately not stamped as measured: the provider said it, and should be believed
            // again next time it is asked rather than frozen at today's answer.
            return ConnectionLimit(streams = reported, measured = false, measuredAt = 0, atLeast = false)
        }
        val urls = channels.urlsFor(source)
        if (urls.isEmpty()) {
            // Nothing playable to measure with. Recorded as attempted so it is not retried forever,
            // and left unknown rather than guessed at.
            Log.i(TAG, "no playable channels for source ${source.id}")
            return store(source.id, ConnectionProbeResult.Unknown)
        }
        val measure: suspend () -> ConnectionProbeResult = {
            probe.measure(
                channels = { index -> urls.getOrNull(index)?.let { hosts?.forRequest(it, source) ?: it } },
                userAgent = source.userAgent,
                onProgress = onProgress,
            )
        }
        val lock = hosts?.probeLock
        val result = if (lock == null) measure() else lock.withLock { measure() }
        Log.i(TAG, "measured source ${source.id}: $result")
        return store(source.id, result)
    }

    private suspend fun store(sourceId: Long, result: ConnectionProbeResult): ConnectionLimit {
        val now = System.currentTimeMillis()
        val streams = (result as? ConnectionProbeResult.Limit)?.streams ?: 0
        sourceDao.updateProbedConnections(sourceId, streams, now)
        return ConnectionLimit(
            streams = streams,
            measured = true,
            measuredAt = now,
            atLeast = (result as? ConnectionProbeResult.Limit)?.atLeast == true,
        )
    }

    private companion object {
        const val TAG = "ConnectionLimits"
    }
}
