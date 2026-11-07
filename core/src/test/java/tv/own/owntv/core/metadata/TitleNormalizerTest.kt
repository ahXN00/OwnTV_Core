package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleNormalizerTest {

    private fun q(raw: String) = TitleNormalizer.normalize(raw).query
    private fun y(raw: String) = TitleNormalizer.normalize(raw).year

    @Test
    fun stripsProviderTagsBracketsAndYear() {
        val n = TitleNormalizer.normalize("EN| The Movie Name (2021) [HD] (MULTI-SUB)")
        assertEquals("The Movie Name", n.query)
        assertEquals(2021, n.year)
    }

    @Test
    fun stripsStackedPrefixesAndQualityMarkers() {
        assertEquals("Gangs of London", q("4K-OSN+ - Gangs of London"))
        assertEquals("The Batman", q("EN| 4K| The Batman WEB-DL x265"))
    }

    @Test
    fun stripsAudioAndReleaseNoise() {
        assertEquals("Lupin", q("Lupin VOSTFR"))
        assertEquals("Intouchables", q("Intouchables TRUEFRENCH HDLight"))
        assertEquals("The Raid", q("The Raid DUBBED AC3 5.1"))
        assertEquals("Old Movie", q("Old Movie HDCAM"))
    }

    @Test
    fun stripsTrailingCamReleaseTags() {
        // How some Stalker portals label a cinema recording: " - CAM" after the title.
        assertEquals("Tum Mere Ho", q("Tum Mere Ho - CAM"))
        assertEquals("Jailer 2", q("Jailer 2 - CAM (Hindi)"))
        assertEquals("Drishyam 3", q("Drishyam 3 - HDTS"))
        assertEquals("Some Film", q("Some Film - PRE-DVD"))
        // Only the upper-case tag at the very end: real titles keep the word.
        assertEquals("Cam", q("Cam"))
        assertEquals("Cam Girl", q("Cam Girl"))
        assertEquals("Webcam - Live", q("Webcam - Live"))
    }

    @Test
    fun stripsTrailingSeasonEpisodeTails() {
        assertEquals("Breaking Bad", q("Breaking Bad S05"))
        assertEquals("Loki", q("Loki Season 2"))
        assertEquals("Dark", q("Dark Staffel 1"))
        assertEquals("La Casa de Papel", q("La Casa de Papel Temporada 3"))
        assertEquals("The Office", q("The Office S02E04"))
    }

    @Test
    fun stripsTrailingUppercaseLanguageTags() {
        assertEquals("The Crown", q("The Crown FR"))
        assertEquals("Peaky Blinders", q("Peaky Blinders S01 LAT"))
    }

    @Test
    fun preservesRealTitles() {
        assertEquals("MAD MAX - Fury Road", q("MAD MAX - Fury Road"))
        assertEquals("Ocean's 8", q("Ocean's 8"))
        assertEquals("Se7en", q("Se7en"))
        assertEquals("Mission: Impossible", q("Mission: Impossible"))
        // Title-case words that look like tags stay intact.
        assertEquals("Sub Rosa", q("Sub Rosa"))
        // A pure year title keeps its year but never empties the query.
        assertEquals(2012, y("2012"))
    }

    @Test
    fun seasonTailNeverEmptiesTheQuery() {
        assertEquals("S01E01", q("S01E01"))
    }
}
