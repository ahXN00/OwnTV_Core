package tv.own.owntv.core.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * One profile's Settings lock as stored: its Settings PIN ([pin], a salted hash, null while the lock
 * is off) and whether the long-press changes ask for it too ([lockMenus]). [loaded] is false until
 * both are read, so Settings never flashes open before the lock is known.
 */
data class SettingsPinLock(
    val loaded: Boolean = false,
    val profileId: Long = -1L,
    val pin: String? = null,
    val lockMenus: Boolean = false,
) {
    val on: Boolean get() = pin != null
}

/**
 * Whether Settings must ask for the PIN before it opens: the lock is on and this visit is still
 * locked. An unlock holds until the visit ends.
 */
fun requiresSettingsPin(hasPin: Boolean, unlocked: Boolean): Boolean = hasPin && !unlocked

fun SettingsPinLock.requiresPin(unlocked: Boolean): Boolean = requiresSettingsPin(hasPin = pin != null, unlocked = unlocked)

/**
 * Whether a page outside Settings that can restore or copy settings (Backup & Restore, Local sync)
 * stays shut: like Settings, and also while the lock is still being read.
 */
fun SettingsPinLock.guardsPage(unlocked: Boolean): Boolean = !loaded || requiresPin(unlocked)

/**
 * Whether a long-press change (hide, move, rename, lock a category, remove from History) must ask for
 * the Settings PIN first. Favourites and Play never call this. Read it from a [loaded] lock.
 */
fun SettingsPinLock.guardsMenuChange(unlocked: Boolean): Boolean = on && lockMenus && !unlocked

/** The active profile's Settings lock, as stored; a new value on a profile switch or a change to either half. */
@OptIn(ExperimentalCoroutinesApi::class)
fun SettingsRepository.activeSettingsPinLock(): Flow<SettingsPinLock> = activeProfileId.flatMapLatest { pid ->
    if (pid < 0) {
        flowOf(SettingsPinLock(loaded = true, profileId = pid))
    } else {
        combine(settingsLockPin(pid), settingsLockMenus(pid)) { pin, menus ->
            SettingsPinLock(loaded = true, profileId = pid, pin = pin, lockMenus = menus)
        }
    }
}
