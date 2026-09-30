package tv.own.owntv.core.parser

import android.os.SystemClock
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV guide parser. XMLTV files are large (and often gzipped), so this pulls events with
 * [XmlPullParser] and pushes each `<channel>` / `<programme>` to a callback instead of building a
 * tree — the caller stores them in chunks and filters to a time window.
 */
object XmltvParser {
    private const val TAG = "XmltvParser"
    private const val STREAM_LOG_ITEM_STEP = 10_000

    /**
     * Parse an XMLTV stream. [onChannel] gets (id, displayName, iconUrl — the feed's `<icon src>`
     * channel logo, null when absent or not an http(s) URL); [onProgramme] gets
     * (channelId, startMs, stopMs, title, description, details — the optional [EpgDetails]). If
     * [channelFilter] is present, programmes whose normalized channel id is not in the set are skipped
     * before child parsing. Gzip is detected from the magic bytes.
     */
    suspend fun parse(
        input: InputStream,
        onChannel: suspend (id: String, displayName: String?, iconUrl: String?) -> Unit,
        onProgramme: suspend (channelId: String, startMs: Long, stopMs: Long, title: String, description: String?, details: EpgDetails) -> Unit,
        channelFilter: Set<String>? = null,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        var lastLogAt = startedAt
        var lastParseOrReadMs = 0L
        var lastCallbackMs = 0L
        var channels = 0
        var programmes = 0
        val normalizedFilter = channelFilter?.mapTo(HashSet(channelFilter.size)) { it.trim().lowercase() }
        val seenChannelIds = HashSet<String>()
        val metrics = ParseMetrics()
        val ctx = currentCoroutineContext()
        val stream = maybeGunzip(input)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Relaxed mode: real-world XMLTV feeds are frequently malformed (mismatched/unclosed tags, stray or
        // unescaped &/< characters). KXmlParser's relaxed mode tolerates these instead of throwing
        // "END_TAG expected …" and aborting the whole sync over a single bad tag.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(stream, null)
        Log.d(TAG, "parse start channelFilterSize=${normalizedFilter?.size ?: 0}")

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            ctx.ensureActive()
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    // A single bad <channel>/<programme> is skipped, not fatal — keep parsing the rest.
                    "channel" -> if (readChannel(parser, onChannel, metrics, normalizedFilter, seenChannelIds)) channels++
                    "programme" -> if (readProgramme(parser, onProgramme, metrics, normalizedFilter)) programmes++
                }
                val items = channels + programmes + metrics.skippedProgrammes + metrics.duplicateChannelsSkipped
                if (items > 0 && items % STREAM_LOG_ITEM_STEP == 0) {
                    val now = SystemClock.elapsedRealtime()
                    val parseOrReadDelta = metrics.parseOrReadMs - lastParseOrReadMs
                    val callbackDelta = metrics.callbackMs - lastCallbackMs
                    Log.d(
                        TAG,
                        "parse progress items=$items channels=$channels programmes=$programmes skipped=${metrics.skippedProgrammes} " +
                            "duplicateChannelsSkipped=${metrics.duplicateChannelsSkipped} " +
                            "channelFilterHit=${metrics.channelFilterHit} channelFilterMiss=${metrics.channelFilterMiss} " +
                            "programmeFilterHit=${metrics.programmeFilterHit} programmeFilterMiss=${metrics.programmeFilterMiss} " +
                            "deltaMs=${now - lastLogAt} " +
                            "parseOrReadMs=$parseOrReadDelta callbackMs=$callbackDelta " +
                            "totalParseOrReadMs=${metrics.parseOrReadMs} totalCallbackMs=${metrics.callbackMs} " +
                            "totalMs=${now - startedAt}",
                    )
                    lastLogAt = now
                    lastParseOrReadMs = metrics.parseOrReadMs
                    lastCallbackMs = metrics.callbackMs
                }
            }
            ctx.ensureActive()
            event = try { parser.next() } catch (e: Exception) { break } // unrecoverable position → stop, keep what we have
        }
        Log.d(
            TAG,
            "parse end channels=$channels programmes=$programmes skipped=${metrics.skippedProgrammes} " +
                "duplicateChannelsSkipped=${metrics.duplicateChannelsSkipped} " +
                "channelFilterHit=${metrics.channelFilterHit} channelFilterMiss=${metrics.channelFilterMiss} " +
                "programmeFilterHit=${metrics.programmeFilterHit} programmeFilterMiss=${metrics.programmeFilterMiss} " +
                "parseOrReadMs=${metrics.parseOrReadMs} " +
                "callbackMs=${metrics.callbackMs} totalMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    /**
     * Read the text content of the element the parser is currently positioned on (a START_TAG), tolerating
     * child elements (their markup is skipped) until the matching END_TAG. Replaces [XmlPullParser.nextText],
     * which throws "END_TAG expected" the moment an element contains anything other than plain text.
     */
    private suspend fun readText(parser: XmlPullParser): String {
        val ctx = currentCoroutineContext()
        val sb = StringBuilder()
        var depth = 1 // we're inside the element whose START_TAG we're on
        while (depth > 0) {
            ctx.ensureActive()
            val ev = parser.next()
            when (ev) {
                XmlPullParser.TEXT -> if (depth == 1) parser.text?.let { sb.append(it) }
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth-- // hitting 0 consumes this element's own END_TAG
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }

    private suspend fun readChannel(
        parser: XmlPullParser,
        onChannel: suspend (String, String?, String?) -> Unit,
        metrics: ParseMetrics,
        channelFilter: Set<String>?,
        seenChannelIds: MutableSet<String>,
    ): Boolean {
        val parseStart = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        val startDepth = parser.depth
        val id = parser.getAttributeValue(null, "id").orEmpty()
        val key = id.trim().lowercase()
        val trackedKey = key.isNotEmpty()
        if (trackedKey && !seenChannelIds.add(key)) {
            skipElement(parser, startDepth)
            metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
            metrics.duplicateChannelsSkipped++
            return false
        }
        var displayName: String? = null
        // XMLTV `<icon src="…"/>` — the feed's own channel logo. Kept only when it looks like an http(s)
        // URL: some feeds put local paths or junk here, and a non-loadable value would blank the logo.
        var iconUrl: String? = null
        try {
            while (true) {
                ctx.ensureActive()
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "display-name" && displayName == null) {
                            displayName = readText(parser).trim().takeIf { it.isNotBlank() }
                        } else if (parser.name == "icon" && iconUrl == null) {
                            iconUrl = parser.getAttributeValue(null, "src")?.trim()
                                ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "channel") break
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            if (trackedKey) seenChannelIds.remove(key)
            recoverElement(parser, startDepth)
            Log.w(TAG, "Skipping malformed <channel> element", e)
            metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
            return false
        }
        metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
        if (id.isNotBlank()) {
            if (channelFilter != null) {
                if (key in channelFilter) {
                    metrics.channelFilterHit++
                } else {
                    metrics.channelFilterMiss++
                }
            }
            val callbackStart = SystemClock.elapsedRealtime()
            try {
                onChannel(id, displayName, iconUrl)
            } finally {
                metrics.callbackMs += SystemClock.elapsedRealtime() - callbackStart
            }
            return true
        }
        return false
    }

    private suspend fun readProgramme(
        parser: XmlPullParser,
        onProgramme: suspend (String, Long, Long, String, String?, EpgDetails) -> Unit,
        metrics: ParseMetrics,
        channelFilter: Set<String>?,
    ): Boolean {
        val parseStart = SystemClock.elapsedRealtime()
        val ctx = currentCoroutineContext()
        val startDepth = parser.depth
        val channelId = parser.getAttributeValue(null, "channel").orEmpty()
        if (channelFilter != null) {
            val normalizedChannelId = channelId.trim().lowercase()
            if (normalizedChannelId !in channelFilter) {
                skipElement(parser, startDepth)
                metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                metrics.skippedProgrammes++
                metrics.programmeFilterMiss++
                return false
            }
            metrics.programmeFilterHit++
        }
        val startMs = parseTime(parser.getAttributeValue(null, "start"))
        val stopMs = parseTime(parser.getAttributeValue(null, "stop"))
        var title = ""
        var desc: String? = null
        // The details line (G1). Created only when a programme has an element other than title/desc:
        // most feeds carry none, and the guide reader must stay as fast for them as before.
        var details: DetailReader? = null
        try {
            while (true) {
                ctx.ensureActive()
                when (parser.next()) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "title" -> if (title.isBlank()) title = readText(parser).trim()
                        "desc" -> if (desc == null) desc = readText(parser).trim().takeIf { it.isNotBlank() }
                        else -> (details ?: DetailReader().also { details = it }).start(parser)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "programme" -> break
                        "rating" -> details?.inRating = false
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            recoverElement(parser, startDepth)
            Log.w(TAG, "Skipping malformed <programme> element", e)
            metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
            return false
        }
        metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
        if (channelId.isNotBlank() && startMs > 0 && stopMs > startMs) {
            val callbackStart = SystemClock.elapsedRealtime()
            try {
                onProgramme(channelId, startMs, stopMs, title.ifBlank { "—" }, desc, details?.result() ?: EpgDetails.NONE)
            } finally {
                metrics.callbackMs += SystemClock.elapsedRealtime() - callbackStart
            }
            return true
        }
        return false
    }

    /** Collects one programme's optional details; each field keeps the first value the feed gives. */
    private class DetailReader {
        private var categories: ArrayList<String>? = null
        private var year: Int? = null
        private var rating: String? = null
        private var ratingSystem: String? = null
        var inRating = false
        private var lengthMin: Int? = null
        private var episode: String? = null

        suspend fun start(parser: XmlPullParser) {
            when (parser.name) {
                "category" -> {
                    val list = categories ?: ArrayList<String>(2).also { categories = it }
                    if (list.size < EpgDetails.MAX_CATEGORIES * 2) list.add(readText(parser))
                }
                "date" -> if (year == null) year = XmltvDetailRules.year(readText(parser))
                "rating" -> if (rating == null) {
                    inRating = true
                    ratingSystem = parser.getAttributeValue(null, "system")
                }
                // <value> also sits inside <star-rating>; only the age rating's is wanted.
                "value" -> if (inRating && rating == null) rating = XmltvDetailRules.rating(ratingSystem, readText(parser))
                "length" -> if (lengthMin == null) {
                    val units = parser.getAttributeValue(null, "units")
                    lengthMin = XmltvDetailRules.lengthMinutes(readText(parser), units)
                }
                "episode-num" -> {
                    val system = parser.getAttributeValue(null, "system")
                    val parsed = XmltvDetailRules.episode(system, readText(parser))
                    // xmltv_ns is normalised ("S1 E3"), so it wins over a broadcaster's own form.
                    if (parsed != null && (episode == null || system.equals("xmltv_ns", true))) episode = parsed
                }
            }
        }

        fun result(): EpgDetails =
            if (categories == null && year == null && rating == null && lengthMin == null && episode == null) {
                EpgDetails.NONE
            } else {
                EpgDetails(categories?.let(XmltvDetailRules::categories), year, rating, lengthMin, episode)
            }
    }


    private suspend fun skipElement(parser: XmlPullParser, startDepth: Int) {
        val ctx = currentCoroutineContext()
        var depth = parser.depth
        while (depth >= startDepth) {
            ctx.ensureActive()
            val event = try {
                parser.next()
            } catch (_: Exception) {
                return
            }
            when (event) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth < startDepth) return
                }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private suspend fun recoverElement(parser: XmlPullParser, startDepth: Int) {
        val ctx = currentCoroutineContext()
        var depth = parser.depth
        while (depth >= startDepth) {
            ctx.ensureActive()
            val event = try {
                parser.next()
            } catch (_: Exception) {
                return
            }
            when (event) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth < startDepth) return
                }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * XMLTV time is `yyyyMMddHHmmss` optionally followed by a ` +0000` style offset.
     * See [parseXmltvTime] — this used to build a `SimpleDateFormat` per timestamp, which is two
     * per programme and ~200,000 on a large guide (E2).
     */
    private fun parseTime(raw: String?): Long = parseXmltvTime(raw)

    private fun maybeGunzip(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 2)
        val sig = ByteArray(2)
        val n = pb.read(sig, 0, 2)
        if (n > 0) pb.unread(sig, 0, n)
        val gzipped = n == 2 && sig[0] == 0x1f.toByte() && sig[1] == 0x8b.toByte()
        return if (gzipped) GZIPInputStream(pb) else pb
    }

    private data class ParseMetrics(
        var parseOrReadMs: Long = 0L,
        var callbackMs: Long = 0L,
        var skippedProgrammes: Int = 0,
        var channelFilterHit: Int = 0,
        var channelFilterMiss: Int = 0,
        var programmeFilterHit: Int = 0,
        var programmeFilterMiss: Int = 0,
        var duplicateChannelsSkipped: Int = 0,
    )
}
