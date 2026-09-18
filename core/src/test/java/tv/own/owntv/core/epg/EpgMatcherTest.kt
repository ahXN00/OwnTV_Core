package tv.own.owntv.core.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    @Test
    fun normalize_stripsQualityCountryAndSeparators() {
        assertEquals("fuss tv 3", EpgMatcher.normalizeForEpg("DE| FUSS-TV 3 [HD]"))
        assertEquals("sky sport bundesliga 1", EpgMatcher.normalizeForEpg("Sky Sport Bundesliga 1 FHD"))
        assertEquals("cnn", EpgMatcher.normalizeForEpg("(US) CNN ᴴᴰ"))
        assertEquals("bbc 1", EpgMatcher.normalizeForEpg("BBC.One.UK")) // number words → digits
    }

    @Test
    fun normalize_dropsSpelledOutCountryNamesAtEnds() {
        assertEquals("mtv", EpgMatcher.normalizeForEpg("MTV France"))
        assertEquals("mtv", EpgMatcher.normalizeForEpg("FR| MTV HD"))
        // Mid-name words are never dropped.
        assertEquals("france 24", EpgMatcher.normalizeForEpg("France 24"))
    }

    @Test
    fun normalize_keepsNonLatinScripts() {
        // Stripping non-Latin letters leaves an empty string, and bestEpgMatch bails out on an empty
        // target — so every channel in these scripts silently failed to match any guide entry.
        assertEquals("кинопремьера", EpgMatcher.normalizeForEpg("КИНОПРЕМЬЕРА HD"))
        assertEquals("первый канал", EpgMatcher.normalizeForEpg("Первый канал"))
        assertEquals("ут 1", EpgMatcher.normalizeForEpg("УТ-1 HD"))
        assertEquals("ερτ 1", EpgMatcher.normalizeForEpg("ΕΡΤ 1"))
        assertEquals("中央电视台", EpgMatcher.normalizeForEpg("中央电视台 4K"))
    }

    @Test
    fun normalize_keepsWordsWhole_whenDiacriticsSitMidWord() {
        // A mid-word mark must not split the word in two: compatibility folding has to recompose,
        // or "Чайка" becomes two tokens and stops matching itself.
        assertEquals("чайка", EpgMatcher.normalizeForEpg("Чайка"))
        assertEquals("ёж тв", EpgMatcher.normalizeForEpg("Ёж ТВ"))
        assertEquals("télé", EpgMatcher.normalizeForEpg("Télé"))
        // Halfwidth katakana must keep its voiced sound: パ (pa) must not degrade to ハ (ha).
        assertEquals("スカパー", EpgMatcher.normalizeForEpg("ｽｶﾊﾟｰ"))
    }

    @Test
    fun normalize_foldsDecorativeQualityTags() {
        // "ᴴᴰ" is a modifier-letter spelling of HD and must still be dropped as noise.
        assertEquals("cnn", EpgMatcher.normalizeForEpg("(US) CNN ᴴᴰ"))
        assertEquals("", EpgMatcher.normalizeForEpg("HD"))
    }

    @Test
    fun score_differentChannelNumbersNeverMatch_inAnyDigitScript() {
        // Keeping \p{N} admits Arabic-Indic digits, so the digit guard has to understand them too —
        // otherwise "channel 2" and "channel 3" look identical and auto-apply onto each other.
        val a = EpgMatcher.normalizeForEpg("قناة ٢")
        val b = EpgMatcher.normalizeForEpg("قناة ٣")
        assertTrue("different numbers must stay below auto-apply",
            EpgMatcher.scoreNormalized(a, b) < EpgMatcher.AUTO_THRESHOLD)
        // ...and the same number written in two scripts must still read as the same number.
        assertTrue("٢ and 2 are the same channel number",
            EpgMatcher.scoreNormalized(EpgMatcher.normalizeForEpg("MTV ٢"),
                                       EpgMatcher.normalizeForEpg("MTV 2")) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun bestMatch_findsCyrillicChannelByName() {
        val candidates = listOf(
            EpgMatcher.Candidate("5770", "СТС"),
            EpgMatcher.Candidate("209", "КИНОПРЕМЬЕРА"),
            EpgMatcher.Candidate("101", "Discovery Channel"),
        )
        val result = EpgMatcher.bestEpgMatch("КИНОПРЕМЬЕРА HD", candidates)
        assertEquals("209", result?.epgChannelId)
        assertTrue("score should auto-apply", (result?.score ?: 0.0) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun bestMatch_returnsNullWhenNameIsOnlyNoise() {
        assertNull(EpgMatcher.bestEpgMatch("HD", listOf(EpgMatcher.Candidate("1", "Discovery"))))
    }

    @Test
    fun score_tokenOverlapMatchesReorderedWords() {
        val a = EpgMatcher.normalizeForEpg("MTV France")
        val b = EpgMatcher.normalizeForEpg("FR| MTV")
        assertTrue(EpgMatcher.scoreNormalized(a, b) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun score_differentChannelNumbersNeverReachReview() {
        val a = EpgMatcher.normalizeForEpg("Sky Sports 2")
        val b = EpgMatcher.normalizeForEpg("Sky Sports 3")
        assertTrue(EpgMatcher.scoreNormalized(a, b) < EpgMatcher.REVIEW_THRESHOLD)
    }

    @Test
    fun score_numberOnOneSideStaysBelowAutoApply() {
        val a = EpgMatcher.normalizeForEpg("MTV")
        val b = EpgMatcher.normalizeForEpg("MTV 2")
        assertTrue(EpgMatcher.scoreNormalized(a, b) < EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun bulk_matchesTheSameWayAsTheSequentialScan() = runBlocking {
        // Above PARALLEL_MIN_ITEMS the bulk scan splits across cores; splitting must change wall
        // clock only. Same winners, same order, one entry per input including the misses.
        val candidates = EpgMatcher.prepare(
            (1..300).map { EpgMatcher.Candidate("id$it", "Channel $it") } +
                listOf(EpgMatcher.Candidate("209", "КИНОПРЕМЬЕРА"), EpgMatcher.Candidate("cnn.us", "CNN")),
        )
        val names = (1..500).map { i ->
            when (i % 4) {
                0 -> "Channel $i HD"
                1 -> "КИНОПРЕМЬЕРА HD"
                2 -> "CNN ᴴᴰ"
                else -> "Nothing Like Any Candidate $i"
            }
        }
        val sequential = names.map { EpgMatcher.bestEpgMatchPrepared(it, candidates) }
        val bulk = EpgMatcher.bestEpgMatchBulk(names, candidates)

        assertEquals(names.size, bulk.size)
        assertEquals(sequential.map { it?.epgChannelId }, bulk.map { it?.epgChannelId })
        assertEquals(sequential.map { it?.score }, bulk.map { it?.score })
        assertTrue("the fixture must exercise both hits and misses",
            bulk.any { it != null } && bulk.any { it == null })
    }

    @Test
    fun bulk_handlesEmptyInputs() = runBlocking {
        val candidates = EpgMatcher.prepare(listOf(EpgMatcher.Candidate("cnn.us", "CNN")))
        assertEquals(emptyList<EpgMatcher.Result?>(), EpgMatcher.bestEpgMatchBulk(emptyList(), candidates))
        // No candidates at all still yields one slot per name, so callers can zip by index.
        assertEquals(listOf(null, null), EpgMatcher.bestEpgMatchBulk(listOf("CNN", "MTV"), emptyList()))
    }

    @Test
    fun rankForPicker_putsRelatedChannelsFirstKeepsRestInOrder() {
        val items = listOf("A Channel", "B Channel", "MTV Hits", "MTV France", "Zebra TV")
        val ranked = EpgMatcher.rankForPicker("FR| MTV", items, { it }, { it })
        assertEquals("MTV France", ranked[0]) // country stripped → exact match
        assertEquals("MTV Hits", ranked[1])
        assertEquals(listOf("A Channel", "B Channel", "Zebra TV"), ranked.drop(2))
    }

    @Test
    fun jaroWinkler_identicalAndDisjoint() {
        assertEquals(1.0, EpgMatcher.jaroWinkler("cnn", "cnn"), 0.0)
        assertEquals(0.0, EpgMatcher.jaroWinkler("cnn", ""), 0.0)
        assertTrue(EpgMatcher.jaroWinkler("abcdef", "xyz") < 0.6)
    }

    @Test
    fun bestMatch_picksTopCandidateByNameOrId() {
        val candidates = listOf(
            EpgMatcher.Candidate("fusstv3.de", "FUSS TV 3"),
            EpgMatcher.Candidate("cnn.us", "CNN International"),
            EpgMatcher.Candidate("skybundes1.de", "Sky Sport Bundesliga 1"),
        )
        val result = EpgMatcher.bestEpgMatch("DE| FUSS-TV 3 HD", candidates)
        assertEquals("fusstv3.de", result?.epgChannelId)
        assertTrue("score should be high", (result?.score ?: 0.0) >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun bestMatch_returnsNullWhenNothingClearsThreshold() {
        val candidates = listOf(EpgMatcher.Candidate("cnn.us", "CNN International"))
        assertNull(EpgMatcher.bestEpgMatch("Discovery Channel", candidates))
    }

    // ---- Picker search, through the normalizer (guide plan Phase 2) ----

    @Test
    fun search_findsASpelledOutNumberFromADigit() {
        // The report case: "bbc1" normalizes to `bbc1`, "BBC One" to `bbc 1`. A raw substring finds
        // nothing, while the matcher scores the same pair high enough to auto-apply.
        assertTrue(EpgMatcher.matchesSearch("bbc1", "BBC One", "bbc.one.uk"))
        assertTrue(EpgMatcher.matchesSearch("BBC1", "BBC One", "bbc.one.uk"))
        assertTrue(EpgMatcher.matchesSearch("bbc 1", "BBC One", "bbc.one.uk"))
    }

    @Test
    fun search_seesThroughPunctuationAndNoise() {
        assertTrue(EpgMatcher.matchesSearch("sky sport", "Sky-Sports HD", "sky.sports.uk"))
        assertTrue(EpgMatcher.matchesSearch("skysports", "Sky-Sports HD", "sky.sports.uk"))
    }

    @Test
    fun search_foldsCaseOutsideAscii() {
        // SQLite's LOWER() is ASCII-only, so a lowercase Cyrillic query could never reach an
        // uppercase Cyrillic name. Kotlin's lowercase() is not, which is the whole point of routing
        // the search through the normalizer.
        assertTrue(EpgMatcher.matchesSearch("первый", "ПЕРВЫЙ КАНАЛ", "perviy.ru"))
        assertTrue(EpgMatcher.matchesSearch("αλφα", "ΑΛΦΑ TV", "alpha.gr"))
    }

    @Test
    fun search_stillFindsByIdWhenTheNameDoesNotMatch() {
        assertTrue(EpgMatcher.matchesSearch("itv.one", null, "itv.one.uk"))
    }

    @Test
    fun search_doesNotMatchAnUnrelatedChannel() {
        assertFalse(EpgMatcher.matchesSearch("discovery", "BBC One", "bbc.one.uk"))
        assertFalse(EpgMatcher.matchesSearch("bbc2", "BBC One", "bbc.one.uk"))
    }

    @Test
    fun search_anEmptyQueryMatchesEverything() {
        assertTrue(EpgMatcher.matchesSearch("", "BBC One", "bbc.one.uk"))
        assertTrue(EpgMatcher.matchesSearch("   ", "BBC One", "bbc.one.uk"))
    }

    @Test
    fun search_aQueryThatNormalizesAwayStillFilters() {
        // "HD" is noise to the normalizer, so it normalizes to nothing. It must not therefore match
        // the entire guide — it falls back to a plain substring.
        assertTrue(EpgMatcher.matchesSearch("HD", "Sky Sports HD", "sky.sports.uk"))
        assertFalse(EpgMatcher.matchesSearch("HD", "BBC One", "bbc.one.uk"))
    }
}
