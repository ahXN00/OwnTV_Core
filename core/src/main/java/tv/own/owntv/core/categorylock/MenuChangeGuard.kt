package tv.own.owntv.core.categorylock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.customize.CategoryUnlockSession
import tv.own.owntv.core.settings.SettingsPinLock
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.activeSettingsPinLock
import tv.own.owntv.core.settings.guardsMenuChange

/**
 * The long-press menu actions that change how a list is organised. With the Settings lock's
 * "also lock long-press menu changes" on, each asks for the Settings PIN first. Favourites, Play and
 * everything else never ask.
 */
val GUARDED_MENU_ACTIONS: Set<String> = setOf("rename", "hide", "remove_history", "move", "move_to_category", "remove_from_category")

/**
 * Asks before a long-press change when the active profile's Settings lock says so. One right PIN
 * opens every change until the app is left or the profile changes ([CategoryUnlockSession.relockAll]).
 *
 * Decided without waiting, from the lock as last read in [scope]: a menu's action runs in the same
 * click that chose it, as it did before there was anything to ask.
 */
class MenuChangeGuard(
    settings: SettingsRepository,
    private val session: CategoryUnlockSession,
    scope: CoroutineScope,
) {
    private val lock: StateFlow<SettingsPinLock> = settings.activeSettingsPinLock()
        .stateIn(scope, SharingStarted.Eagerly, SettingsPinLock())

    /** What to ask for before a change: the profile and its Settings PIN, or null to go ahead now. */
    fun pinToAsk(): Ask? {
        val current = lock.value
        val pin = current.pin ?: return null
        if (!current.guardsMenuChange(unlocked = session.changesUnlocked(current.profileId))) return null
        return Ask(current.profileId, pin, session.epoch())
    }

    /** The PIN asked by [ask] was right. False when the session relocked while it was typed. */
    fun unlocked(ask: Ask): Boolean = session.unlockChanges(ask.profileId, since = ask.epoch)

    data class Ask(val profileId: Long, val pin: String, val epoch: Long)
}
