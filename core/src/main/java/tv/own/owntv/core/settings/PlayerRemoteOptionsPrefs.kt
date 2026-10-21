package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import tv.own.owntv.core.settings.SettingsRepository.Keys

/** Reads [PlayerRemoteOptions] from the settings file; anything missing or unknown is its default. */
internal fun readPlayerRemoteOptions(prefs: Preferences): PlayerRemoteOptions = PlayerRemoteOptions(
    holdOk = PlayerHoldOkAction.fromName(prefs[Keys.PLAYER_HOLD_OK]),
    shortOk = PlayerShortOkAction.fromName(prefs[Keys.PLAYER_SHORT_OK]),
    swapChannelKeys = prefs[Keys.PLAYER_SWAP_CHANNEL_KEYS] ?: false,
    numberKeysTune = prefs[Keys.PLAYER_NUMBER_KEYS_TUNE] ?: true,
)

/** Writes every field of [options], so a backup carries the whole choice. */
internal fun writePlayerRemoteOptions(prefs: MutablePreferences, options: PlayerRemoteOptions) {
    prefs[Keys.PLAYER_HOLD_OK] = options.holdOk.name
    prefs[Keys.PLAYER_SHORT_OK] = options.shortOk.name
    prefs[Keys.PLAYER_SWAP_CHANNEL_KEYS] = options.swapChannelKeys
    prefs[Keys.PLAYER_NUMBER_KEYS_TUNE] = options.numberKeysTune
}
