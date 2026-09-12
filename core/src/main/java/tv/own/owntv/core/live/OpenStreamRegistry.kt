package tv.own.owntv.core.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Who is currently holding a stream on which playlist.
 *
 * Multiview tiles and recordings spend the same provider connections (D11), so neither can count on
 * its own: the only place that knows a playlist has two tiles open *and* something recording is one
 * shared register. Callers claim before they tune and release when they stop; [connectionBudget]
 * turns the counts into an answer.
 *
 * Deliberately not persisted. A claim is only true while the app is running it, and a claim left
 * behind by a crash would refuse tiles for a stream that no longer exists.
 */
class OpenStreamRegistry {

    /** A claim, held by whoever asked for it, until it is released. */
    data class Claim(val id: Long, val sourceId: Long, val purpose: StreamPurpose)

    private val ids = AtomicLong(0)
    private val _claims = MutableStateFlow<List<Claim>>(emptyList())

    /** Every stream currently claimed, so a screen can react as recordings start and stop. */
    val claims: StateFlow<List<Claim>> = _claims.asStateFlow()

    /** What [sourceId] currently has open. Pass this straight to [connectionBudget]. */
    fun openOn(sourceId: Long): OpenStreams {
        val mine = _claims.value.filter { it.sourceId == sourceId }
        return OpenStreams(
            watching = mine.count { it.purpose == StreamPurpose.WATCHING },
            recording = mine.count { it.purpose == StreamPurpose.RECORDING },
        )
    }

    /** Take a connection. The caller keeps the returned claim and hands it back to [release]. */
    fun claim(sourceId: Long, purpose: StreamPurpose): Claim {
        val claim = Claim(ids.incrementAndGet(), sourceId, purpose)
        _claims.value = _claims.value + claim
        return claim
    }

    fun release(claim: Claim) {
        _claims.value = _claims.value.filterNot { it.id == claim.id }
    }
}
