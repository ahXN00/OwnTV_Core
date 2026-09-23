package tv.own.owntv.player

import tv.own.owntv.core.player.PlaybackPrefsStore

/**
 * Remembered audio / subtitle choice for one item (owner decision 11), turned into what an engine
 * should do once it knows the stream's tracks.
 *
 * A remembered choice is stored as a *language*, not a track id — ids change between streams of the
 * same channel, and between episodes of the same series, while "the German track" does not. When the
 * stream has no track in that language nothing changes, so the global preferred language still applies.
 */
internal object TrackMemory {

    /** The audio track to switch to, or null when none matches or a matching one is already playing. */
    fun audioToSelect(tracks: List<TrackOption>, lang: String?): TrackOption? {
        if (lang.isNullOrBlank()) return null
        if (tracks.any { it.selected && subtitleLanguageMatches(lang, it.lang) }) return null
        return tracks.firstOrNull { subtitleLanguageMatches(lang, it.lang) }
    }

    sealed interface SubtitleAction {
        data object None : SubtitleAction
        data object Off : SubtitleAction
        data class Select(val track: TrackOption) : SubtitleAction
    }

    /**
     * What to do with subtitles. [allowImage] is false where a bitmap subtitle would cost an engine
     * handoff (mpv VOD) or cannot be drawn at all (mpv live) — a remembered choice never triggers that.
     */
    fun subtitleAction(tracks: List<TrackOption>, choice: String?, allowImage: Boolean): SubtitleAction {
        if (choice.isNullOrBlank()) return SubtitleAction.None
        if (choice == PlaybackPrefsStore.SUBTITLES_OFF) {
            return if (tracks.any { it.selected }) SubtitleAction.Off else SubtitleAction.None
        }
        if (tracks.any { it.selected && subtitleLanguageMatches(choice, it.lang) }) return SubtitleAction.None
        val track = tracks.firstOrNull { subtitleLanguageMatches(choice, it.lang) && (allowImage || !it.image) }
        return track?.let { SubtitleAction.Select(it) } ?: SubtitleAction.None
    }
}
