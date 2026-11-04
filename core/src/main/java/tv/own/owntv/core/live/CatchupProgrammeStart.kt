package tv.own.owntv.core.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * The start of the programme on air, from the guide: the first row of the player's "Go back to…" list
 * in both apps, next to the fixed offsets of [CatchupJumps].
 *
 * Kept as a wall-clock instant, not an offset: the offset is taken when the row is pressed, so the
 * seconds spent reading the list do not push the picture past the programme's first frame.
 */
data class CatchupProgrammeStart(val title: String, val startMs: Long) {

    /** Seconds behind live at [nowMs]. */
    fun offsetSec(nowMs: Long): Int = ((nowMs - startMs) / 1000L).toInt()

    companion object {
        /** Below this, the programme has only just begun: going back to its start is not worth a row. */
        const val MIN_OFFSET_SEC = 30

        /**
         * How long the list waits for the guide. On a channel with an archive but no stored guide the
         * answer comes from the provider over the network, and the list must not hang on its timeout.
         */
        private const val LOOKUP_WAIT_MS = 800L

        // The lookup outlives a wait that gave up: its answer still lands in the guide reader's
        // cache, so the next open of the list has it at once.
        private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The row for a programme that runs from [startMs] to [stopMs], or null when it cannot be
         * offered: not started yet, started a few seconds ago, already over (a guide answer that
         * outlived its programme), or older than the channel's archive ([windowSec]).
         */
        fun of(title: String?, startMs: Long, stopMs: Long, nowMs: Long, windowSec: Int): CatchupProgrammeStart? {
            if (startMs <= 0L || windowSec <= 0) return null
            if (stopMs in 1..nowMs) return null
            val offset = (nowMs - startMs) / 1000L
            if (offset < MIN_OFFSET_SEC || offset > windowSec) return null
            return CatchupProgrammeStart(title?.trim().orEmpty(), startMs)
        }

        /**
         * The row for [channel] as the list opens: the programme on air from [reader], inside an
         * archive [windowSec] deep. Null when the channel has no archive, the guide has nothing on
         * air, or the guide did not answer within [LOOKUP_WAIT_MS].
         */
        suspend fun lookUp(
            reader: LiveEpgReader,
            channel: ChannelEntity,
            cust: SectionCustomizations,
            globalShiftMinutes: Int,
            windowSec: Int,
        ): CatchupProgrammeStart? {
            if (!channel.catchup || windowSec <= 0) return null
            // await() is cancellable even where the network read inside is not.
            val answer = lookups.async {
                try {
                    reader.nowNext(channel, cust, globalShiftMinutes)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    null
                }
            }
            val now = withTimeoutOrNull(LOOKUP_WAIT_MS) { answer.await() }?.now ?: return null
            return of(now.title, now.startMs, now.stopMs, System.currentTimeMillis(), windowSec)
        }
    }
}
