package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name rules behind a playlist's "Clean channel names". Names are neutral made-up examples. */
class ChannelNameCleanupTest {

    private val builtIn = ChannelNameCleanup.of(null)

    private fun clean(raw: String) = builtIn.clean(raw)

    @Test
    fun `country prefixes in every tagged form are removed`() {
        assertEquals("News One", clean("UK: News One"))
        assertEquals("News One", clean("UK| News One"))
        assertEquals("News One", clean("|UK| News One"))
        assertEquals("News One", clean("[US] News One"))
        assertEquals("News One", clean("(US) News One"))
        assertEquals("News One", clean("DE | News One"))
        assertEquals("News One", clean("FR:News One"))
    }

    @Test
    fun `a colon after a plain label is not a country prefix`() {
        assertEquals("VIP: News One", clean("VIP: News One"))
        assertEquals("TV: Kids", clean("TV: Kids"))
        assertEquals("NEWS: Tonight", clean("NEWS: Tonight"))
        assertEquals("US - News One", clean("US - News One"))
    }

    @Test
    fun `a dash inside a word is not a prefix`() {
        assertEquals("AB-TV Music", clean("AB-TV Music"))
        assertEquals("News One", clean("News One"))
    }

    @Test
    fun `quality tags are removed from the end, also several and bracketed`() {
        assertEquals("News One", clean("News One HD"))
        assertEquals("Planet Wild", clean("Planet Wild [4K]"))
        assertEquals("Planet Wild", clean("Planet Wild (UHD)"))
        assertEquals("Sports Arena", clean("Sports Arena FHD HEVC"))
        assertEquals("Sports Arena", clean("Sports Arena - HD"))
        assertEquals("Movies Plus", clean("Movies Plus 1080p"))
    }

    @Test
    fun `4K in the middle or at the start of a name is kept`() {
        assertEquals("4K Nature Channel", clean("4K Nature Channel"))
        assertEquals("Nature 4K Channel", clean("Nature 4K Channel"))
        assertEquals("Nature 4K Channel", clean("Nature 4K Channel HD"))
    }

    @Test
    fun `quality letters that are part of a word or bare numbers are kept`() {
        assertEquals("NEWSHD", clean("NEWSHD"))
        assertEquals("Channel 720", clean("Channel 720"))
        assertEquals("Radio SDX", clean("Radio SDX"))
    }

    @Test
    fun `prefix and quality together`() {
        assertEquals("News One", clean("UK| News One HD"))
        assertEquals("news one", clean("uk| news one hd"))
    }

    @Test
    fun `a name that is only a prefix or only a tag keeps the provider's spelling`() {
        assertEquals("UK:", clean("UK:"))
        assertEquals("DE| ", clean("DE| "))
        assertEquals("HD", clean("HD"))
        assertEquals("UK | HD", clean("UK | HD"))
    }

    @Test
    fun `non-Latin names are cleaned around, never inside`() {
        assertEquals("Первый канал", clean("Первый канал HD"))
        assertEquals("Первый канал", clean("Первый канал"))
        assertEquals("قناة الأخبار", clean("AR: قناة الأخبار"))
        assertEquals("中央电视台", clean("中央电视台 4K"))
        assertEquals("ΕΡΤ 1", clean("ΕΡΤ 1"))
        // A prefix in another script is not one of the tagged Latin forms, so it stays.
        assertEquals("ТВ: Новости", clean("ТВ: Новости"))
    }

    @Test
    fun `a name's own punctuation at the end is not trimmed`() {
        assertEquals("Fun Channel!", clean("Fun Channel!"))
        assertEquals("Channel 5.", clean("Channel 5."))
    }

    @Test
    fun `custom words are removed from either end as whole words`() {
        val custom = ChannelNameCleanup.of("VIP; Backup ;[X]")
        assertEquals("News One", custom.clean("VIP News One"))
        assertEquals("News One", custom.clean("VIP | News One"))
        assertEquals("News One", custom.clean("News One Backup"))
        assertEquals("News One", custom.clean("News One [X]"))
        assertEquals("News One", custom.clean("vip News One HD"))
        // Whole words only, and never the middle of a name.
        assertEquals("VIPER Movies", custom.clean("VIPER Movies"))
        assertEquals("News VIP One", custom.clean("News VIP One"))
    }

    @Test
    fun `blank custom text is the built-in rules`() {
        assertEquals("News One", ChannelNameCleanup.of("  ;  ").clean("UK: News One HD"))
        assertEquals("VIP News One", ChannelNameCleanup.of("").clean("VIP News One"))
    }
}
