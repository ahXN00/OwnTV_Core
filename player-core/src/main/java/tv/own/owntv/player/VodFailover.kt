package tv.own.owntv.player

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.HostFailover
import tv.own.owntv.core.network.HostFailure
import tv.own.owntv.core.network.HostFailureClass
import tv.own.owntv.core.network.HostPool
import tv.own.owntv.core.network.HostPreflight
import tv.own.owntv.core.network.HostPreflight.isHostClass
import tv.own.owntv.core.network.HostSelector

/**
 * Host failover for films and episodes (design §6, AC5). The player has already spent its own retries
 * when its error reaches the screen; this decides whether that was the server and, if so, which server
 * to reopen on. The caller replays the item there at the position it reached and calls [played] once it
 * plays. One round per item: [reset] when a different item starts.
 */
class VodFailover(
    private val hosts: HostSelector,
    private val gate: suspend (sourceId: Long) -> ReleaseGate.Result,
    private val preflight: suspend (SourceEntity, String) -> HostPreflight.Result,
    private val log: (String) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val isOnline: () -> Boolean = { true },
    private val awaitOnline: suspend () -> Unit = {},
    private val onOffline: () -> Unit = {},
    /** Hold a watching claim from the release gate until the replay opens. Default no-op. */
    private val reserve: (Long) -> (() -> Unit) = { {} },
) {

    sealed interface Outcome {
        /** Replay on [server] ([position] of [total]). */
        data class Reopen(val server: String, val position: Int, val total: Int) : Outcome
        /** Not the server's failure, or failover is off: show the error as before. */
        data object NotServer : Outcome
        data class Exhausted(val total: Int) : Outcome
        data class Refused(val detail: String) : Outcome
    }

    private var round: HostFailover? = null
    private var pending: String? = null
    private var pendingSource: SourceEntity? = null
    private var itemKey: String? = null
    private var held: (() -> Unit)? = null

    private fun dropReservation() {
        held?.invoke()
        held = null
    }

    /** Callers' stop: drop a claim still held between the gate and the reopen. */
    fun releaseReservation() = dropReservation()

    /** A new item: forget the round of the last one. */
    fun reset(key: String?) {
        if (key == itemKey) return
        itemKey = key
        round = null
        pending = null
        pendingSource = null
        dropReservation()
    }

    /** A cancelled replay must not leave [HostSelector.attempt] in memory. */
    suspend fun abandonPending() {
        if (pending == null) return
        pendingSource?.let { hosts.abandon(it) }
        pending = null
        pendingSource = null
        round = null
        dropReservation()
    }

    /**
     * The player failed [source]'s item with [lastError] (mpv's last line; null when unknown). Runs the
     * release gate before any request. [stillCurrent] = the same item is still wanted.
     */
    suspend fun onFailure(source: SourceEntity, lastError: String?, stillCurrent: () -> Boolean): Outcome {
        if (!HostPool.failoverActive(source)) return Outcome.NotServer
        if (round?.exhausted == true) return Outcome.NotServer
        val cls = lastError?.let(HostFailure::classifyMpv) ?: HostFailureClass.UNKNOWN
        if (cls != HostFailureClass.HOST && cls != HostFailureClass.UNKNOWN) return Outcome.NotServer
        val failed = pending ?: hosts.current(source) ?: return Outcome.NotServer
        val gated = gate(source.id)
        if (gated is ReleaseGate.Result.Refused) {
            dropReservation()
            return Outcome.Refused("release gate refused: " + gated.why.name.lowercase())
        }
        dropReservation()
        held = reserve(source.id)
        if (cls == HostFailureClass.UNKNOWN && round == null) {
            // G4: no typed evidence. One check of the server that failed, after everything stopped.
            val check = preflight(source, failed)
            log("VOD ${HostPool.describe(HostPool.pool(source), failed)} check: ${check::class.simpleName}")
            if (!check.isHostClass()) {
                dropReservation()
                return Outcome.NotServer
            }
        }
        val r = round ?: HostFailover(HostPool.pool(source), failed).also { round = it }
        return when (val next = ServerRound.next(r, source, failed, preflight, stillCurrent, { log("VOD $it") }, pause, isOnline, awaitOnline, onOffline)) {
            is ServerRound.Next.Open -> {
                hosts.attempt(source, next.server)
                pending = next.server
                pendingSource = source
                Outcome.Reopen(next.server, next.position, next.total)
            }
            is ServerRound.Next.Exhausted -> { dropReservation(); hosts.abandon(source); Outcome.Exhausted(next.total) }
            is ServerRound.Next.Refused -> { dropReservation(); hosts.abandon(source); Outcome.Refused(next.detail) }
            ServerRound.Next.Superseded -> { dropReservation(); Outcome.NotServer }
        }
    }

    /** The replay played: persist the server once; returns it when this was a switch (for the toast). */
    suspend fun played(source: SourceEntity): String? {
        dropReservation()
        val server = pending ?: return null
        pending = null
        pendingSource = null
        round = null
        hosts.confirm(source, server)
        return server
    }
}
