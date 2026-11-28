package tv.own.owntv.player

import tv.own.owntv.core.skip.SkipKind
import tv.own.owntv.core.skip.SkipMode
import tv.own.owntv.core.skip.SkipSegment

/** What the skip button offers. */
enum class SkipAction { SKIP_INTRO, SKIP_RECAP, SKIP_CREDITS, SKIP_PREVIEW, NEXT_EPISODE }

/** The part the playhead is in and what its button does. [enteredAtMs] is where the playhead met it. */
data class ActiveSkip(val segment: SkipSegment, val action: SkipAction, val enteredAtMs: Long)

/**
 * When the next-episode card appears ([cardAtMs]) and when the episode moves on ([advanceAtMs]).
 * [notice] = the short "Next episode…" notice instead of the card. [coreAdvances] = the skip
 * controller moves on at [advanceAtMs] itself; otherwise the end of the file does, as it always has.
 */
data class UpNext(val cardAtMs: Long, val advanceAtMs: Long, val notice: Boolean, val coreAdvances: Boolean)

/**
 * Every skip decision, kept free of the player so each row of the end-credits table is unit-tested.
 *
 * End credits never seek to the end of the file — that shows a black end and only then the next
 * episode. Where a next episode follows and Autoplay next is on, the credits start is where the next
 * episode is offered (BUTTON: the card with a 10 s countdown) or begins (AUTO: a 3 s notice). With
 * Autoplay next off the button plays the next episode on request. A film, a season's last episode, or
 * an episode the sleep timer ends on is finished the way its natural end would finish it, and never
 * automatically.
 */
internal object SkipPlanner {
    const val CARD_COUNTDOWN_MS = 10_000L
    const val NOTICE_MS = 3_000L
    /** The card's lead before an advance at the end of the file — what both HUDs always used. */
    const val CARD_LEAD_MS = 30_000L
    /** A playhead that reaches a part within this of its previous position got there by playing. */
    const val PLAYED_INTO_MS = 3_000L

    data class Situation(val nextFollows: Boolean, val autoPlayNext: Boolean, val stopsAtItemEnd: Boolean) {
        val movesOnByItself: Boolean get() = nextFollows && autoPlayNext && !stopsAtItemEnd
    }

    fun upNext(endCredits: SkipSegment?, durationMs: Long, creditsMode: SkipMode, s: Situation): UpNext {
        if (endCredits == null || creditsMode == SkipMode.OFF || !s.movesOnByItself) {
            val advance = durationMs - OwnTVPlayer.END_TOLERANCE_MS
            return UpNext(advance - CARD_LEAD_MS, advance, notice = false, coreAdvances = false)
        }
        val start = endCredits.startMs
        return if (creditsMode == SkipMode.AUTO) {
            UpNext(start, start + NOTICE_MS, notice = true, coreAdvances = true)
        } else {
            UpNext(start, start + CARD_COUNTDOWN_MS, notice = false, coreAdvances = true)
        }
    }

    /** The button for [segment], or null when it shows none (part off, or the card handles the end credits). */
    fun action(segment: SkipSegment, isEndCredits: Boolean, mode: SkipMode, s: Situation): SkipAction? {
        if (mode == SkipMode.OFF) return null
        if (!isEndCredits) {
            return when (segment.kind) {
                SkipKind.INTRO -> SkipAction.SKIP_INTRO
                SkipKind.RECAP -> SkipAction.SKIP_RECAP
                SkipKind.CREDITS -> SkipAction.SKIP_CREDITS
                SkipKind.PREVIEW -> SkipAction.SKIP_PREVIEW
            }
        }
        return when {
            s.movesOnByItself -> null
            s.nextFollows && !s.autoPlayNext && !s.stopsAtItemEnd -> SkipAction.NEXT_EPISODE
            else -> SkipAction.SKIP_CREDITS
        }
    }

    /**
     * Whether an automatic skip may fire on reaching [segment]: only when the playhead played into it
     * (or the item opened at or just before it — an intro from 0:00). A seek or a resume that lands
     * inside a part is the viewer choosing to be there.
     */
    fun playedInto(previousMs: Long?, positionMs: Long, segment: SkipSegment): Boolean =
        if (previousMs == null) positionMs < segment.startMs + PLAYED_INTO_MS
        else previousMs < segment.startMs && positionMs - previousMs in 0..PLAYED_INTO_MS

    /**
     * Whether the playhead played past [atMs] — the moment the next episode starts. A seek that lands
     * beyond it is the viewer choosing to watch the credits (or a scene after them), so only playing
     * through it moves on; the end of the file still does as it always has.
     */
    fun playedPast(previousMs: Long?, positionMs: Long, atMs: Long): Boolean =
        previousMs != null && previousMs < atMs && positionMs >= atMs && positionMs - previousMs in 0..PLAYED_INTO_MS

    /** The position to save: inside the end credits the item counts as finished. */
    fun savedPosition(positionMs: Long, durationMs: Long, endCredits: SkipSegment?): Long =
        if (endCredits != null && durationMs > 0 && positionMs >= endCredits.startMs) durationMs else positionMs
}
