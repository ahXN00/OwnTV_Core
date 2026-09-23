package tv.own.owntv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * "Previous channel" (N2): the channel watched before the one on screen.
 *
 * Fed by every full-screen tune ([LiveTuneController.start]); a browse preview is not watching and
 * never reaches it. Tuning the previous channel makes the two swap, so the key flips between them the
 * way a TV remote's "last channel" button does. A re-tune of the same channel (retry, reconnect, a
 * catch-up returning to live) changes nothing.
 */
class ChannelRecall {
    private var current: ChannelEntity? = null
    private val _previous = MutableStateFlow<ChannelEntity?>(null)

    /** The channel to go back to, or null before a second channel has been watched. */
    val previous: StateFlow<ChannelEntity?> = _previous.asStateFlow()

    fun onWatched(channel: ChannelEntity) {
        val was = current
        current = channel
        if (was != null && was.id != channel.id) _previous.value = was
    }
}
