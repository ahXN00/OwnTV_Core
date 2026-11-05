package tv.own.owntv.core.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * One profile's Settings lock as stored: its Customize PIN ([pin], a salted hash, null when none is
 * set) and whether opening Settings asks for it ([askOnOpen]). [loaded] is false until both are read,
 * so Settings never flashes open before the lock is known.
 */
data class SettingsPinLock(
    val loaded: Boolean = false,
    val profileId: Long = -1L,
    val pin: String? = null,
    val askOnOpen: Boolean = false,
)

/**
 * Whether Settings must ask for the PIN before it opens. The switch counts only while there is a
 * PIN to ask for, and an unlock holds until the visit ends.
 */
fun requiresSettingsPin(hasPin: Boolean, enabled: Boolean, unlocked: Boolean): Boolean =
    hasPin && enabled && !unlocked

fun SettingsPinLock.requiresPin(unlocked: Boolean): Boolean =
    requiresSettingsPin(hasPin = pin != null, enabled = askOnOpen, unlocked = unlocked)

/**
 * Whether a page outside Settings that can restore or copy settings (Backup & Restore, Local sync)
 * stays shut: like Settings, and also while the lock is still being read.
 */
fun SettingsPinLock.guardsPage(unlocked: Boolean): Boolean = !loaded || requiresPin(unlocked)

/** The active profile's Settings lock, as stored; a new value on a profile switch or a change to either half. */
@OptIn(ExperimentalCoroutinesApi::class)
fun SettingsRepository.activeSettingsPinLock(): Flow<SettingsPinLock> = activeProfileId.flatMapLatest { pid ->
    if (pid < 0) {
        flowOf(SettingsPinLock(loaded = true, profileId = pid))
    } else {
        combine(customizePin(pid), settingsPinEnabled(pid)) { pin, ask ->
            SettingsPinLock(loaded = true, profileId = pid, pin = pin, askOnOpen = ask)
        }
    }
}
