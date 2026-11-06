package tv.own.owntv.core.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.repository.EpgRepository

/**
 * Fills the guide's past days for the catch-up channels a guide shows, from each provider's own
 * schedule of the channel ([EpgRepository.fillArchiveGuide]). Both apps' guides call [request] for a
 * row that stays on screen and re-read that one channel when [added] names it.
 *
 * One request at a time, with a pause after each real one, so a page of rows is never a burst of
 * calls to the provider; the row seen last goes first ([ArchiveGuideQueue]). A guide that closes calls
 * [forgetPending], so rows nobody is looking at any more are not asked for.
 */
class ArchiveGuideFiller(private val epg: EpgRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArchiveGuideQueue<Pair<ChannelEntity, SectionCustomizations>>(QUEUE_MAX)

    // Guarded by [queue]; null once the queue ran dry.
    private var worker: Job? = null

    /** Ids of channels whose past programmes just changed. */
    val added: SharedFlow<Long> get() = epg.archiveGuideAdded

    fun request(channel: ChannelEntity, cust: SectionCustomizations) {
        if (!channel.catchup) return
        synchronized(queue) {
            queue.add(channel.id, channel to cust)
            if (worker == null) worker = scope.launch { drain() }
        }
    }

    fun forgetPending() {
        synchronized(queue) { queue.clear() }
    }

    private suspend fun drain() {
        while (true) {
            val (channel, cust) = synchronized(queue) {
                queue.next() ?: run { worker = null; return }
            }
            // Null = the provider was not asked (asked recently, or not an Xtream channel): no pause.
            if (epg.fillArchiveGuide(channel, cust) != null) delay(SPACING_MS)
        }
    }

    private companion object {
        // A pause after each request, and the rows remembered (more than a screen or two scrolled
        // past is dropped).
        const val SPACING_MS = 300L
        const val QUEUE_MAX = 24
    }
}

/**
 * The newest-first queue behind [ArchiveGuideFiller]. An item queued again moves to the front rather
 * than waiting twice, and past [max] the oldest entries are forgotten. Not thread-safe.
 */
internal class ArchiveGuideQueue<T>(private val max: Int) {
    // Insertion order, newest last.
    private val pending = LinkedHashMap<Long, T>()

    val size: Int get() = pending.size

    fun add(id: Long, item: T) {
        pending.remove(id)
        pending[id] = item
        while (pending.size > max) pending.remove(pending.keys.first())
    }

    /** The newest item, removed from the queue; null when it is empty. */
    fun next(): T? = pending.keys.lastOrNull()?.let { pending.remove(it) }

    fun clear() = pending.clear()
}
