package tv.own.owntv.player

import tv.own.owntv.core.player.PlayerBudget

/**
 * mpv's live buffer for one tune: `demuxer-readahead-secs` and `cache-secs`.
 *
 * With the cache on, mpv reads ahead to the **larger** of the two (manual, `--demuxer-readahead-secs`),
 * so a Live latency choice set on the readahead alone was swallowed by the device's 30–120 s cache and
 * Low, Stable and Custom all behaved alike. A choice therefore sets both to the same value. Balanced
 * (null) keeps the device tier's pair, exactly as before.
 */
internal object MpvLiveBuffer {

    data class Secs(val readahead: String, val cache: String)

    /** [latencySecs] is the Live latency choice (null = Balanced); the pre-roll is a floor, or its gate could never be met. */
    fun resolve(latencySecs: Int?, prerollSecs: Int, budget: PlayerBudget): Secs {
        val secs = latencySecs?.let { maxOf(it, prerollSecs).toString() }
            ?: return Secs(budget.readaheadSecs, budget.cacheSecs)
        return Secs(secs, secs)
    }
}
