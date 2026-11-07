package tv.own.owntv.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Hands values to one consumer in [scope], one at a time and in order, keeping only the newest that
 * arrived while it was busy. For a status where only the latest value counts, like a progress
 * notification: a slow update is never overtaken by an older one, and updates never queue up.
 */
class LatestOnly<T>(scope: CoroutineScope, consume: suspend (T) -> Unit) {
    private val values = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch { for (value in values) consume(value) }
    }

    /** Hands [value] on without waiting; one still waiting to be consumed is dropped for it. */
    fun offer(value: T) {
        values.trySend(value)
    }

    /** No more values: the consumer takes the one still waiting, if any, and ends. Later offers are dropped. */
    fun close() {
        values.close()
    }
}
