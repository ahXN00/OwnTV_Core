package tv.own.owntv.core.categorylock

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Closes every category the PIN opened, and stops what plays from one that is now sealed: each app
 * calls [leftApp] when it leaves the screen and [profileChanged] when the active profile changes, and
 * sets [guard] while it shows something that plays. Runs in [scope], the app shell's. Main thread only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryRelock(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val unlocks: CategoryUnlockSession,
) {
    private val relockProfile = RelockProfile()

    /**
     * The active profile and whether it has a lock that can seal anything; null until read, or when the
     * read failed. Without one, leaving the app skips the check, so leaving and coming back works
     * exactly as it does with no locks: no wait for the check, and no fail-closed stop.
     */
    private val activeLocks: StateFlow<Pair<Long, Boolean>?> = settings.activeProfileId
        .flatMapLatest { pid -> customize.observeHasLocks(pid).map<Boolean, Pair<Long, Boolean>?> { pid to it } }
        .catch { e -> Log.w(TAG, "category lock state unreadable; relocks check what plays", e); emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Set by the app while it is up: stops what plays from a category a relock seals. */
    var guard: SealedPlaybackGuard? = null

    private var sealCheck: Job? = null
    private var resumeAfterCheck: Job? = null

    /**
     * The app left the screen. Call it before the engines free their streams: what is playing is read
     * first, then stopped if now sealed.
     */
    fun leftApp() = relockFor(relockProfile.current, skipWithoutLocks = true)

    /**
     * The active profile is now [profileId]. What the PIN opened belongs to the profile that entered it.
     * The same profile again is no change: an app that reports it each time its screen is rebuilt (a
     * phone turned on its side) must not close what the PIN opened.
     */
    fun profileChanged(profileId: Long) {
        if (profileId == relockProfile.current) return
        relockFor(relockProfile.changeTo(profileId))
    }

    /** Back in front: [resume] what was freed only once the relock check has stopped what it sealed. */
    fun afterSealCheck(resume: () -> Unit) {
        val pending = sealCheck?.takeIf { it.isActive } ?: return resume()
        resumeAfterCheck = scope.launch { pending.join(); resume() }
    }

    /**
     * Relocks, checking what plays against [opener]'s locks as well as the active profile's. Only leaving
     * the app may [skipWithoutLocks]: on a profile change the lock state can still be the outgoing profile's.
     */
    private fun relockFor(opener: Long?, skipWithoutLocks: Boolean = false) {
        // Leaving again before the last return's resume ran: it must not start the engines in the background.
        resumeAfterCheck?.cancel()
        val guard = guard
        val playing = guard?.snapshot()
        unlocks.relockAll()
        if (guard == null || playing == null || (skipWithoutLocks && nothingToSeal(opener))) return
        val earlier = sealCheck
        sealCheck = scope.launch {
            earlier?.join()
            guard.stopIfSealed(playing) { sealsNow(opener, it) }
        }
    }

    /** The check would read only the active profile, and it has no lock: nothing can be sealed. */
    private fun nothingToSeal(opener: Long?): Boolean {
        val (active, hasLocks) = activeLocks.value ?: return false
        return !hasLocks && (opener == null || opener == active)
    }

    /**
     * Whether [opener], the profile whose PIN may have opened what plays, or the active one seals it.
     * Fails closed: a category whose lock cannot be read is treated as sealed, so its playback stops.
     */
    private suspend fun sealsNow(opener: Long?, categoryId: Long): Boolean = try {
        relockedProfiles(opener, settings.activeProfileId.first()).any { customize.sealsCategory(it, categoryId) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "relock check failed for category $categoryId; stopping its playback", e)
        true
    }

    private companion object {
        const val TAG = "CategoryRelock"
    }
}
