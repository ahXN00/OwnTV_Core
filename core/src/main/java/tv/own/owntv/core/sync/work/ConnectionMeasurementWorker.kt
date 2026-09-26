package tv.own.owntv.core.sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.ConnectionLimits
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.WatchSession
import tv.own.owntv.core.timeshift.TimeshiftManager

/**
 * Measures how many streams a provider allows, off the setup path (plan N3b).
 *
 * This used to run *inside* the first sync, before any phase. §4A measured 55.7 s of a fresh Stalker
 * add going nowhere with nothing logged, and N3a's instrumentation attributed **all** of it to this
 * one step: `pre-phase connection measurement ms=55489`. The user sat at "Checking how many channels
 * this provider allows…" for most of a minute before the catalogue even started.
 *
 * It is Stalker-shaped, not universal. An Xtream panel publishes the number, so
 * [ConnectionLimits.needsMeasuring] returns false and the whole thing costs nothing — measured at
 * 700 ms on a phone. Stalker portals and M3U playlists almost never publish it, and for those the
 * only way to know is to open streams and watch, one at a time, until one fails.
 *
 * **Why it could not simply be deferred before.** The measurement works by opening streams, so on a
 * single-connection account it is precisely the thing that cuts the user's picture off — which is
 * why it was pinned to the moment before the first sync, when no channel row exists and there is
 * nothing to interrupt. Moving it later was unsafe because nothing could answer "is the user
 * watching?".
 *
 * [WatchSession] now answers exactly that (plan N1f-3), so the measurement can wait for a quiet
 * moment instead of taking one hostage. If anything is playing or recording on this source, the
 * worker steps aside and comes back later — the same shape as the catalogue drain.
 *
 * The answer is only needed by Multiview and recordings, both of which ask the budget before they
 * tune. Until it arrives `ConnectionLimits.known()` reports "nobody knows", which those callers
 * already handle — so nothing is broken by it being late, and a minute of setup is given back.
 */
class ConnectionMeasurementWorker(
    context: Context,
    params: WorkerParameters,
    private val sourceDao: SourceDao,
    private val connectionLimits: ConnectionLimits,
    private val watchSession: WatchSession,
    private val openStreams: OpenStreamRegistry,
    private val timeshift: TimeshiftManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId < 0) return Result.success()
        val source = sourceDao.getById(sourceId) ?: return Result.success()

        // Re-checked here rather than trusted from the enqueue: the user may have pressed Re-test in
        // between, which answers the question and makes this run pointless.
        if (!connectionLimits.needsMeasuring(source)) {
            Log.i(TAG, "measurement not needed sourceId=$sourceId — provider published it or it already ran")
            return Result.success()
        }

        if (watchSession.isWatching(sourceId) || openStreams.openOn(sourceId).total > 0 || timeshift.isSaving(sourceId)) {
            // Opening a probe stream now is what would cut the picture off. Come back later.
            Log.i(TAG, "measurement deferred sourceId=$sourceId — this playlist is in use")
            return Result.retry()
        }

        runCatching { connectionLimits.measureAndStore(source) }
            .onSuccess { Log.i(TAG, "measurement done sourceId=$sourceId streams=${it.streams} measured=${it.measured}") }
            // Never a failure the user can act on: a playlist that imports perfectly well must not
            // be reported as broken because a measurement could not be taken.
            .onFailure { Log.w(TAG, "measurement failed sourceId=$sourceId", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "ConnectionMeasurement"
        const val KEY_SOURCE_ID = "sourceId"
        const val WORK_TAG = "connection-measurement"

        fun workName(sourceId: Long) = "connection-measurement-source-$sourceId"
    }
}
