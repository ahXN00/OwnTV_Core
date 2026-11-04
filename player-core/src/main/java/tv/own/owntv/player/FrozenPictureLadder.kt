package tv.own.owntv.player

/**
 * What the live engine does about a picture that froze while the stream's clock kept moving.
 *
 * A reconnect keeps the same codec and surface, so a decoder that has silently stopped handing frames to
 * its surface stops again: measured on a Google TV Streamer (`c2.mtk.avc.decoder`), every reconnect
 * rendered exactly three frames and froze. The second freeze on one tune therefore gets a new player and
 * a new surface, and a third hands the channel to the other engine.
 */
internal object FrozenPictureLadder {
    enum class Rung { RECONNECT, FRESH_DECODER, OTHER_ENGINE }

    /** [hits] counts this tune's freezes, the current one included. */
    fun rungFor(hits: Int): Rung = when {
        hits <= 1 -> Rung.RECONNECT
        hits == 2 -> Rung.FRESH_DECODER
        else -> Rung.OTHER_ENGINE
    }
}
