package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.player.PlaybackPrefsStore

class TrackMemoryTest {

    private fun t(id: Int, lang: String?, selected: Boolean = false, image: Boolean = false) =
        TrackOption(label = "", mpvId = id, selected = selected, image = image, lang = lang)

    @Test fun audioSwitchesToTheRememberedLanguage() {
        val tracks = listOf(t(1, "eng", selected = true), t(2, "deu"))
        assertEquals(2, TrackMemory.audioToSelect(tracks, "de")?.mpvId) // ISO-639-1 vs -2
    }

    @Test fun bibliographicCodesMatchTheirModernForm() {
        assertEquals(true, subtitleLanguageMatches("de", "ger"))
        assertEquals(true, subtitleLanguageMatches("fre", "fr-FR"))
        assertEquals(true, subtitleLanguageMatches("chi", "zho"))
        assertEquals(false, subtitleLanguageMatches("ger", "fre"))
    }

    @Test fun audioLeftAloneWhenAlreadyRightMissingOrNothingRemembered() {
        val tracks = listOf(t(1, "eng", selected = true), t(2, "ger"))
        assertNull(TrackMemory.audioToSelect(tracks, "en"))
        assertNull(TrackMemory.audioToSelect(tracks, "fra"))
        assertNull(TrackMemory.audioToSelect(tracks, null))
    }

    @Test fun subtitlesOffOnlyActsWhenOneIsOn() {
        val on = listOf(t(1, "eng", selected = true))
        val off = listOf(t(1, "eng"))
        assertEquals(TrackMemory.SubtitleAction.Off, TrackMemory.subtitleAction(on, PlaybackPrefsStore.SUBTITLES_OFF, allowImage = false))
        assertEquals(TrackMemory.SubtitleAction.None, TrackMemory.subtitleAction(off, PlaybackPrefsStore.SUBTITLES_OFF, allowImage = false))
    }

    @Test fun bitmapSubtitleIsSkippedUnlessAllowed() {
        val tracks = listOf(t(1, "ger", image = true), t(2, "ger"))
        assertEquals(TrackMemory.SubtitleAction.Select(tracks[1]), TrackMemory.subtitleAction(tracks, "ger", allowImage = false))
        assertEquals(TrackMemory.SubtitleAction.Select(tracks[0]), TrackMemory.subtitleAction(tracks, "ger", allowImage = true))
        assertEquals(TrackMemory.SubtitleAction.None, TrackMemory.subtitleAction(listOf(tracks[0]), "ger", allowImage = false))
    }
}
