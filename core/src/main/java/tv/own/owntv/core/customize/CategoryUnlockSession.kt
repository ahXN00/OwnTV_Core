package tv.own.owntv.core.customize

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import tv.own.owntv.core.model.MediaType

/**
 * The locked categories the PIN has opened in this session, per profile and section.
 *
 * Memory only, never persisted: the app relocks everything when it leaves the screen and when the
 * active profile changes. Only one profile's unlocks are ever held — unlocking for one profile drops
 * whatever another had open.
 */
class CategoryUnlockSession {
    private data class Scope(val profileId: Long, val type: MediaType)

    /** [epoch] counts the [relockAll]s, so a PIN checked before one cannot open anything after it. */
    private data class State(val opened: Map<Scope, Set<String>> = emptyMap(), val epoch: Long = 0)

    private val state = MutableStateFlow(State())

    fun unlocked(profileId: Long, type: MediaType): Flow<Set<String>> =
        state.map { it.opened[Scope(profileId, type)].orEmpty() }.distinctUntilChanged()

    fun isUnlocked(profileId: Long, type: MediaType, key: String): Boolean =
        key in state.value.opened[Scope(profileId, type)].orEmpty()

    /** The current relock count. Read it before checking a PIN and pass it to [unlock]. */
    fun epoch(): Long = state.value.epoch

    /**
     * Opens [key] for the rest of the session, unless the session relocked since [since] (the app left
     * the screen or the profile changed while the PIN was being checked). Returns whether it opened.
     */
    fun unlock(profileId: Long, type: MediaType, key: String, since: Long = epoch()): Boolean {
        val scope = Scope(profileId, type)
        val next = state.updateAndGet { current ->
            if (current.epoch != since) return@updateAndGet current
            val sameProfile = current.opened.filterKeys { it.profileId == profileId }
            current.copy(opened = sameProfile + (scope to (sameProfile[scope].orEmpty() + key)))
        }
        return next.epoch == since
    }

    /** Closes one category again — a fresh lock on a category opened earlier in the session. */
    fun relock(profileId: Long, type: MediaType, key: String) {
        val scope = Scope(profileId, type)
        state.update { current ->
            val keys = current.opened[scope] ?: return@update current
            current.copy(opened = current.opened + (scope to (keys - key)))
        }
    }

    fun relockAll() {
        state.update { State(epoch = it.epoch + 1) }
    }
}
