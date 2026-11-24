package tv.own.owntv.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {

    // Real TheIntroDB answers, trimmed to what the parser reads.
    private val breakingBadS5E14 = """
        {"tvdb_id":81189,"type":"tv","season":5,"episode":14,
         "intro":[{"start_ms":290858,"end_ms":311363}],
         "recap":[{"start_ms":0,"end_ms":0}],
         "credits":[{"start_ms":291000,"end_ms":307000},{"start_ms":2786722,"end_ms":null}]}
    """.trimIndent()
    private val gotS1E1 = """
        {"tvdb_id":121361,"type":"tv","season":1,"episode":1,"group_duration_ms":3696273,
         "intro":[{"start_ms":435985,"end_ms":535607,"confidence":0.7,"submission_count":12}],
         "credits":[{"start_ms":3629912,"end_ms":null,"confidence":0.7,"submission_count":13}]}
    """.trimIndent()
    private val fightClub = """
        {"tvdb_id":247,"type":"movie","intro":[{"start_ms":null,"end_ms":119000}],
         "credits":[{"start_ms":8177000,"end_ms":8348000}]}
    """.trimIndent()

    @Test
    fun `missing ends become the start and the end of the file`() {
        val parts = SkipSegments.normalize(SkipSegments.parse(gotS1E1), 3_696_273)
        assertEquals(
            listOf(
                SkipSegment(SkipKind.INTRO, 435_985, 535_607),
                SkipSegment(SkipKind.CREDITS, 3_629_912, 3_696_273),
            ),
            parts,
        )
        val film = SkipSegments.normalize(SkipSegments.parse(fightClub), 8_350_000)
        assertEquals(SkipSegment(SkipKind.INTRO, 0, 119_000), film.first())
    }

    @Test
    fun `a zero-length recap is dropped and opening credits fold into the intro`() {
        val parts = SkipSegments.normalize(SkipSegments.parse(breakingBadS5E14), 2_846_000)
        assertEquals(
            listOf(
                SkipSegment(SkipKind.INTRO, 290_858, 311_363),
                SkipSegment(SkipKind.CREDITS, 2_786_722, 2_846_000),
            ),
            parts,
        )
    }

    @Test
    fun `parts shorter than three seconds are dropped and ends are clamped to the file`() {
        val raw = listOf(
            RawSkipSegment(SkipKind.PREVIEW, 10_000, 12_000),
            RawSkipSegment(SkipKind.RECAP, 0, 90_000),
        )
        assertEquals(listOf(SkipSegment(SkipKind.RECAP, 0, 60_000)), SkipSegments.normalize(raw, 60_000))
    }

    @Test
    fun `overlapping entries of one kind become one part`() {
        val raw = listOf(
            RawSkipSegment(SkipKind.INTRO, 30_000, 90_000),
            RawSkipSegment(SkipKind.INTRO, 120_000, 150_000),
            RawSkipSegment(SkipKind.INTRO, 80_000, 125_000),
        )
        assertEquals(listOf(SkipSegment(SkipKind.INTRO, 30_000, 150_000)), SkipSegments.normalize(raw, 3_000_000))
    }

    @Test
    fun `credits are end credits only when they run to the end of the file`() {
        val duration = 3_000_000L
        val middle = SkipSegment(SkipKind.CREDITS, 2_700_000, 2_900_000)
        val ending = SkipSegment(SkipKind.CREDITS, 2_920_000, 2_995_000)
        assertNull(SkipSegments.endCredits(listOf(middle), duration))
        assertEquals(ending, SkipSegments.endCredits(listOf(middle, ending), duration))
    }

    @Test
    fun `an unknown length gives nothing to act on`() {
        assertTrue(SkipSegments.normalize(SkipSegments.parse(gotS1E1), 0).isEmpty())
    }
}
