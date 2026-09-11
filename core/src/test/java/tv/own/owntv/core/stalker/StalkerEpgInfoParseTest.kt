package tv.own.owntv.core.stalker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.repository.EpgRepository
import java.io.File
import java.io.IOException

/**
 * The portal guide crawl: how it is addressed, and the ladder it climbs down when a portal will not
 * serve the whole thing at once.
 *
 * The ladder is the part that matters. Measured against a real 12 000-channel portal,
 * `get_epg_info&period=7` answers in about a second with 9 MB — but the connection that carries it is
 * keep-alive, and the first version of this code lost it every time by parsing and writing to the
 * database with the response still open. Hence: download first, retry a broken connection, then try
 * shorter periods, then fall back to asking channel by channel.
 *
 * The JSON parsing itself sits behind `android.util.JsonReader`, which does not exist on the JVM, so
 * the fake below stands in for it — everything above it is real.
 */
class StalkerEpgInfoParseTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val from = 1_000_000_000_000L
    private val to = from + 48L * 60 * 60 * 1000

    // ---- how a portal guide is addressed where a URL is expected ----

    @Test
    fun guideUrl_roundTripsThePlaylistId() {
        assertEquals(42L, EpgRepository.stalkerSourceIdOf(EpgRepository.stalkerGuideUrl(42L)))
    }

    @Test
    fun guideUrl_ordinaryFeedsAreNotMistakenForPortals() {
        assertEquals(null, EpgRepository.stalkerSourceIdOf("http://host/xmltv.php?username=u"))
        assertEquals(null, EpgRepository.stalkerSourceIdOf("https://example.com/guide.xml.gz"))
        assertEquals(null, EpgRepository.stalkerSourceIdOf(""))
        assertEquals(null, EpgRepository.stalkerSourceIdOf("stalker://"))
        assertEquals(null, EpgRepository.stalkerSourceIdOf("stalker://not-a-number"))
    }

    // ---- the crawl ----

    private fun entry(title: String, startMs: Long, stopMs: Long, desc: String? = null) =
        StalkerClient.ShortEpgEntry(title, desc, startMs, stopMs)

    /**
     * Stands in for a portal. [bulk] is what each period would return — a null value means that period
     * throws [failure]; a missing key means the period is not offered at all.
     */
    private class FakeClient(
        private val bulk: Map<Int, List<Pair<String, ShortEpgEntry>>?> = emptyMap(),
        private val perChannel: Map<String, List<ShortEpgEntry>> = emptyMap(),
        private val failure: () -> IOException = { IOException("unexpected end of stream on http://portal/") },
    ) : StalkerClient(okhttp3.OkHttpClient()) {
        var downloads = 0; private set
        val periodsTried = ArrayList<Int>()
        val channelsAsked = ArrayList<String>()
        private var pending: List<Pair<String, ShortEpgEntry>> = emptyList()

        override suspend fun resolveHandshake(portalUrl: String, mac: String, userAgent: String?) =
            Handshake(apiBase = "http://portal/portal.php", token = "t1")

        override suspend fun getProfile(
            apiBase: String, mac: String, token: String, userAgent: String?, identity: StalkerDeviceIdentity,
        ): Map<String, String> = mapOf("status" to "1")

        override suspend fun downloadEpgInfo(
            apiBase: String, mac: String, token: String, userAgent: String?, periodDays: Int, dest: File,
        ): Long {
            downloads++
            periodsTried += periodDays
            val answer = bulk[periodDays] ?: throw failure()
            pending = answer
            dest.parentFile?.mkdirs()
            dest.writeText("{}")
            return 2
        }

        override suspend fun parseEpgInfoFile(file: File, onEntry: suspend (String, ShortEpgEntry) -> Unit) {
            pending.forEach { (id, e) -> onEntry(id, e) }
        }

        override suspend fun getShortEpg(
            apiBase: String, mac: String, token: String, userAgent: String?, channelId: String, size: Int,
        ): List<ShortEpgEntry> {
            channelsAsked += channelId
            return perChannel[channelId] ?: throw IOException("no guide for $channelId")
        }
    }

    private fun source() = tv.own.owntv.core.database.entity.SourceEntity(
        id = 7L,
        name = "Portal",
        type = tv.own.owntv.core.model.SourceType.STALKER,
        url = "http://portal/c/",
        mac = "00:1A:79:AA:BB:CC",
    )

    private fun loaderFor(client: FakeClient) = StalkerEpgLoader(StalkerAuthManager(client), client)

    private fun crawl(
        client: FakeClient,
        channelIds: List<String> = emptyList(),
        rows: MutableList<StalkerEpgLoader.Row> = ArrayList(),
        progress: MutableList<Pair<Int, Int>> = ArrayList(),
    ) = runBlocking {
        loaderFor(client).crawl(
            source = source(),
            from = from,
            to = to,
            cacheDir = folder.newFolder(),
            channelIds = channelIds,
            onProgress = { c, p -> progress += c to p },
        ) { rows += it }
    }

    @Test
    fun crawl_takesTheFullWeekWhenThePortalServesIt() {
        val client = FakeClient(bulk = mapOf(7 to listOf("1234" to entry("News", from + 1000, from + 2000, "The news"))))
        val rows = ArrayList<StalkerEpgLoader.Row>()
        val outcome = crawl(client, rows = rows)

        assertEquals(StalkerEpgLoader.Method.BULK, outcome.method)
        assertEquals(7, outcome.periodDays)
        assertEquals(listOf(7), client.periodsTried)
        assertEquals("1234", rows.single().epgChannelId)
        assertEquals("The news", rows.single().description)
    }

    /** A portal that cannot build a week is asked for less before anything drastic happens. */
    @Test
    fun crawl_stepsThePeriodDownWhenALongerOneFails() {
        val client = FakeClient(bulk = mapOf(3 to listOf("1" to entry("Show", from + 1, from + 2))))
        val outcome = crawl(client)

        assertEquals(StalkerEpgLoader.Method.BULK, outcome.method)
        assertEquals(3, outcome.periodDays)
        // 7 is attempted (and retried) before 3 is reached.
        assertTrue(client.periodsTried.first() == 7 && client.periodsTried.contains(3))
    }

    /**
     * The failure this class was rewritten around: a keep-alive socket the far end had already closed.
     * It has to be retried rather than treated as "this portal has no guide".
     */
    @Test
    fun crawl_retriesABrokenConnectionBeforeGivingUpOnAPeriod() {
        val client = FakeClient(bulk = emptyMap())
        assertThrows(IOException::class.java) { crawl(client) }

        val sevens = client.periodsTried.count { it == 7 }
        assertEquals("every period gets the full retry budget", StalkerEpgLoader.DOWNLOAD_ATTEMPTS, sevens)
        assertEquals(
            "…and every period is tried",
            StalkerEpgLoader.PERIODS.size * StalkerEpgLoader.DOWNLOAD_ATTEMPTS,
            client.downloads,
        )
    }

    /** Nothing usable from any period, but the channels are known: ask them one at a time. */
    @Test
    fun crawl_fallsBackToAskingChannelByChannel() {
        val client = FakeClient(
            bulk = emptyMap(),
            perChannel = mapOf(
                "aa" to listOf(entry("One", from + 10, from + 20)),
                "bb" to listOf(entry("Two", from + 30, from + 40)),
            ),
        )
        val rows = ArrayList<StalkerEpgLoader.Row>()
        val outcome = crawl(client, channelIds = listOf("aa", "bb"), rows = rows)

        assertEquals(StalkerEpgLoader.Method.PER_CHANNEL, outcome.method)
        assertEquals(2, outcome.programmes)
        assertEquals(listOf("aa", "bb"), client.channelsAsked)
        assertEquals(listOf("One", "Two"), rows.map { it.title })
    }

    /** A portal answering 200 with an empty guide is not a success — try the other routes. */
    @Test
    fun crawl_treatsAnEmptyBulkAnswerAsAFailure() {
        val client = FakeClient(
            bulk = mapOf(7 to emptyList(), 3 to emptyList(), 1 to emptyList()),
            perChannel = mapOf("aa" to listOf(entry("One", from + 10, from + 20))),
        )
        val outcome = crawl(client, channelIds = listOf("aa"))
        assertEquals(StalkerEpgLoader.Method.PER_CHANNEL, outcome.method)
    }

    @Test
    fun crawl_keepsOnlyWhatOverlapsTheRetainedWindow() {
        val client = FakeClient(
            bulk = mapOf(
                7 to listOf(
                    "1" to entry("too old", from - 20_000, from - 10_000),
                    "1" to entry("overlaps the start", from - 5_000, from + 5_000),
                    "1" to entry("inside", from + 10_000, from + 20_000),
                    "1" to entry("too far ahead", to + 10_000, to + 20_000),
                ),
            ),
        )
        val rows = ArrayList<StalkerEpgLoader.Row>()
        val outcome = crawl(client, rows = rows)

        assertEquals(2, outcome.programmes)
        assertEquals(listOf("overlaps the start", "inside"), rows.map { it.title })
    }

    /** Guide lookups normalise the key; a portal that pads or capitalises its ids must still match. */
    @Test
    fun crawl_normalisesTheChannelKey() {
        val client = FakeClient(bulk = mapOf(7 to listOf("  AB12  " to entry("Show", from + 1, from + 2))))
        val rows = ArrayList<StalkerEpgLoader.Row>()
        crawl(client, rows = rows)
        assertEquals("ab12", rows.single().epgChannelId)
    }

    @Test
    fun crawl_skipsEntriesWithNoUsableChannelId() {
        val client = FakeClient(bulk = mapOf(7 to listOf("   " to entry("orphan", from + 1, from + 2))))
        assertThrows(IOException::class.java) { crawl(client) }
    }

    /** Programmes are written away in batches, and each batch reports where the sync has got to. */
    @Test
    fun crawl_handsOverInBatchesAndReportsProgress() {
        val many = (1..StalkerEpgLoader.BATCH + 5).map { i ->
            "ch" to entry("Programme $i", from + i * 10L, from + i * 10L + 5)
        }
        val client = FakeClient(bulk = mapOf(7 to many))
        val batches = ArrayList<StalkerEpgLoader.Row>()
        val progress = ArrayList<Pair<Int, Int>>()
        val sizes = ArrayList<Int>()
        runBlocking {
            loaderFor(client).crawl(
                source = source(), from = from, to = to, cacheDir = folder.newFolder(),
                onProgress = { c, p -> progress += c to p },
            ) { batch -> sizes += batch.size; batches += batch }
        }

        assertEquals(listOf(StalkerEpgLoader.BATCH, 5), sizes)
        assertEquals(many.size, batches.size)
        // One channel throughout, and the programme count climbs — what the screen shows.
        assertEquals(listOf(1 to StalkerEpgLoader.BATCH, 1 to many.size), progress)
    }
}
