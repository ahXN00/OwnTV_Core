package tv.own.owntv.core.settings

/**
 * What holding OK does in the full-screen player while its controls are hidden. [OFF] keeps OK a
 * plain press, so it costs no waiting time. The live-only choices fall back to the plain press on a
 * film or episode.
 */
enum class PlayerHoldOkAction {
    OFF,
    CHANNEL_LIST,
    GUIDE,
    PROGRAMME_INFO;

    companion object {
        fun fromName(name: String?): PlayerHoldOkAction = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * What a short OK press does in the full-screen player while its controls are hidden. [SHOW_CONTROLS]
 * is the player's own behaviour; the live-only choices fall back to it on a film or episode.
 */
enum class PlayerShortOkAction {
    SHOW_CONTROLS,
    CHANNEL_LIST,
    GUIDE;

    companion object {
        fun fromName(name: String?): PlayerShortOkAction = entries.firstOrNull { it.name == name } ?: SHOW_CONTROLS
    }
}

/**
 * The player's remote-button options (Settings > Remote shortcuts > Player buttons). The defaults are
 * exactly the player's behaviour before the options existed, so an unset value changes nothing.
 */
data class PlayerRemoteOptions(
    val holdOk: PlayerHoldOkAction = PlayerHoldOkAction.OFF,
    val shortOk: PlayerShortOkAction = PlayerShortOkAction.SHOW_CONTROLS,
    /** CH+ / CH− and D-pad Up / Down change channel the other way round. */
    val swapChannelKeys: Boolean = false,
    /** Number keys type a channel number in the live player; off leaves them to remote shortcuts. */
    val numberKeysTune: Boolean = true,
)
