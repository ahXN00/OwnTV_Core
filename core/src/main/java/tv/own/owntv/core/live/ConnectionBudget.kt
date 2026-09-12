package tv.own.owntv.core.live

import android.content.res.Resources
import tv.own.owntv.core.R
import tv.own.owntv.core.database.entity.SourceEntity

/** The tile counts the grid offers. Never reduced behind the user's back — see [connectionBudget]. */
const val MIN_MULTIVIEW_TILES = 1
const val MAX_MULTIVIEW_TILES = 4
const val DEFAULT_MULTIVIEW_TILES = 2

/** What a stream is wanted for. A recording outranks a picture when the last connection is contested. */
enum class StreamPurpose { WATCHING, RECORDING }

/** How many of one playlist's connections are already spoken for. */
data class OpenStreams(val watching: Int = 0, val recording: Int = 0) {
    val total: Int get() = watching + recording
}

/** Why one more stream from this playlist cannot start. Each maps to one sentence for the user. */
enum class StreamRefusal {
    /** The playlist allows exactly one stream, and something already has it. */
    SINGLE_CONNECTION,

    /** Every connection this playlist allows is in use, by tiles, recordings or both. */
    ALL_IN_USE,

    /** There is a connection free, but it is being kept so the user can still watch something (D10). */
    RESERVED_FOR_WATCHING,
}

sealed interface StreamGrant {
    data object Allowed : StreamGrant

    data class Refused(
        val reason: StreamRefusal,
        /** What the playlist allows, for the sentence. 0 when unknown — never refused on that. */
        val maxConnections: Int,
        val open: OpenStreams,
    ) : StreamGrant
}

/**
 * May one more stream start on this playlist?
 *
 * The rules are D5, D10 and D11 together, and they are less contradictory than they look. **The
 * feature is never capped**: the tile-count setting always offers up to four and nothing reduces it
 * behind the user's back. **An individual stream is checked before it tunes**, and if there is
 * nothing spare that one tile — or that one recording — is told why, in a sentence, instead of
 * failing into a spinner. The provider's own HTTP 458 remains the backstop for a number that was
 * wrong or stale.
 *
 * Everything here is arithmetic over [SourceEntity.maxConnections]: no Android, no I/O, no clock.
 *
 * @param source the playlist the stream would come from; null or an unknown limit allows everything.
 * @param open the streams that playlist already has running — tiles *and* recordings (D11).
 * @param reserveOneForWatching keeps one connection back so recordings can never take the last one.
 *   The user can give this up in Settings and record on every connection.
 */
fun connectionBudget(
    source: SourceEntity?,
    open: OpenStreams,
    purpose: StreamPurpose,
    reserveOneForWatching: Boolean = true,
): StreamGrant {
    val max = source?.maxConnections ?: 0
    // 0 = the playlist never said. Allow everything and let the provider answer; warning text
    // elsewhere tells the user that is what happened if a tile does fail.
    if (max <= 0) return StreamGrant.Allowed

    fun refuse(reason: StreamRefusal) = StreamGrant.Refused(reason, max, open)

    return when (purpose) {
        StreamPurpose.WATCHING -> when {
            open.total < max -> StreamGrant.Allowed
            max == 1 -> refuse(StreamRefusal.SINGLE_CONNECTION)
            else -> refuse(StreamRefusal.ALL_IN_USE)
        }
        // A live programme is gone forever and a picture is not, so a recording is allowed to take the
        // only connection a one-stream account has (D9). What it may not do is take the *last* one on
        // an account that has several, unless the user has said it may.
        StreamPurpose.RECORDING -> {
            val cap = when {
                max == 1 -> 1
                reserveOneForWatching -> max - 1
                else -> max
            }
            when {
                open.recording < cap -> StreamGrant.Allowed
                max == 1 -> refuse(StreamRefusal.SINGLE_CONNECTION)
                reserveOneForWatching && open.recording < max -> refuse(StreamRefusal.RESERVED_FOR_WATCHING)
                else -> refuse(StreamRefusal.ALL_IN_USE)
            }
        }
    }
}

/**
 * The sentence a refused tile shows in place of a picture. Says what the playlist allows and what is
 * currently using it, because "one of them is recording" is the difference between a fault and a
 * choice the user made.
 */
fun StreamGrant.Refused.displayText(res: Resources): String = when (reason) {
    StreamRefusal.SINGLE_CONNECTION -> if (open.recording > 0) {
        res.getString(R.string.multiview_refused_single_recording)
    } else {
        res.getString(R.string.multiview_refused_single)
    }
    // Not a plural: this reason only exists when the playlist allows two or more, so the sentence
    // never has to agree with the number one.
    StreamRefusal.ALL_IN_USE -> if (open.recording > 0) {
        res.getString(R.string.multiview_refused_all_in_use_recording, maxConnections, open.recording)
    } else {
        res.getString(R.string.multiview_refused_all_in_use, maxConnections)
    }
    StreamRefusal.RESERVED_FOR_WATCHING -> res.getString(R.string.multiview_refused_reserved)
}
