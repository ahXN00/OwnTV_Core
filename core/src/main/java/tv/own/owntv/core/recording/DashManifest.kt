package tv.own.owntv.core.recording

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.OffsetDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Just enough of an MPEG-DASH manifest to record one, parsed from the XML.
 *
 * The sibling of [HlsMediaPlaylist], and deliberately the same kind of thing: not a general DASH
 * implementation, just the facts a recorder needs — which Representations exist, how their segments
 * are addressed, how long to wait before asking again, and whether the stream is protected or has
 * ended.
 *
 * **Where it differs from HLS, and why that matters.** An HLS media playlist can be recorded by
 * plain concatenation, because each MPEG-TS segment already carries audio and video together. DASH
 * normally keeps them in **separate Representations** — two independent segment streams — so a
 * recorder has to fetch both and mux them afterwards. That is [DashTrackKind]'s whole reason for
 * existing, and the reason a DASH recording has a step HLS never needed.
 *
 * **Unknown elements and attributes are ignored rather than refused**, the same stance
 * [HlsMediaPlaylist] takes: DASH gains features all the time and a recorder that stopped at one it
 * had not seen would be broken by its own strictness.
 *
 * Parsed with `javax.xml.parsers` rather than `android.util.Xml` (which
 * [tv.own.owntv.core.parser.XmltvParser] uses) for two reasons: a manifest is a few kilobytes, so
 * there is nothing to stream, and `DocumentBuilder` has a real implementation on the JVM, which is
 * what lets every rule in here be unit-tested without a device.
 */
data class DashManifest(
    /** Every Representation across every Period and AdaptationSet, flattened. */
    val representations: List<DashRepresentation>,
    /** `type="dynamic"` — a live stream. `static` is a finished window: catch-up, usually. */
    val dynamic: Boolean,
    /** `minimumUpdatePeriod`: how often the provider says the manifest itself is worth re-reading. */
    val minimumUpdatePeriodMs: Long?,
    /** `availabilityStartTime` as epoch millis — the zero point segment numbers are counted from. */
    val availabilityStartTimeMs: Long?,
    /** `timeShiftBufferDepth`: how far back the provider still serves. */
    val timeShiftBufferDepthMs: Long?,
    /** `mediaPresentationDuration`, present on a static manifest. */
    val mediaPresentationDurationMs: Long?,
    /**
     * A `<ContentProtection>` element appeared anywhere in the manifest.
     *
     * The DASH equivalent of HLS's `#EXT-X-KEY`, and refused for the same reason: the CDM decrypts
     * only into a secure decoder, so these bytes never exist in the clear for us to keep. Writing
     * them out unchanged produces a file of exactly the right size that will not play.
     */
    val contentProtected: Boolean,
) {
    /** True when the stream has a fixed end — nothing more will be published, so stop at the last segment. */
    val endList: Boolean get() = !dynamic

    companion object {
        /**
         * Does this look like a manifest rather than a body of video?
         *
         * Checked on the content type *and* the first bytes, exactly as [HlsMediaPlaylist] is and for
         * the same reason: providers label an `.mpd` as everything from `application/dash+xml` to
         * `text/xml` to `video/mp2t`, and the extension disappears behind a redirect. The body itself
         * never lies.
         */
        fun looksLikeDashManifest(contentType: String?, body: String): Boolean {
            val head = body.trimStart().let { if (it.startsWith(XML_DECLARATION)) it.substringAfter("?>").trimStart() else it }
            if (head.startsWith("<MPD") || head.startsWith("<mpd")) return true
            val type = contentType?.lowercase() ?: return false
            return type.contains("dash+xml") || type.contains("vnd.mpeg.dash.mpd")
        }

        /** Parse a manifest, or null when the text is not XML we can read at all. */
        fun parse(text: String): DashManifest? {
            val root = runCatching {
                DocumentBuilderFactory.newInstance()
                    .apply { isNamespaceAware = false }
                    .newDocumentBuilder()
                    .parse(InputSource(StringReader(text)))
                    .documentElement
            }.getOrNull() ?: return null
            if (!root.tagName.equals("MPD", ignoreCase = true)) return null

            val mpdBase = root.baseUrl()
            val representations = mutableListOf<DashRepresentation>()

            root.children("Period").forEach { period ->
                val periodBase = joinUrl(mpdBase, period.baseUrl())
                val periodTemplate = period.templateSpec()
                period.children("AdaptationSet").forEach { set ->
                    val setBase = joinUrl(periodBase, set.baseUrl())
                    val setTemplate = periodTemplate.mergedWith(set.templateSpec())
                    val setList = set.segmentList()
                    set.children("Representation").forEach { rep ->
                        representations += rep.toRepresentation(
                            base = joinUrl(setBase, rep.baseUrl()),
                            inheritedTemplate = setTemplate,
                            inheritedList = setList,
                            setMimeType = set.attr("mimeType"),
                            setCodecs = set.attr("codecs"),
                            setContentType = set.attr("contentType"),
                        )
                    }
                }
            }

            return DashManifest(
                representations = representations,
                dynamic = root.attr("type")?.equals("dynamic", ignoreCase = true) ?: false,
                minimumUpdatePeriodMs = parseDurationMs(root.attr("minimumUpdatePeriod")),
                availabilityStartTimeMs = parseInstantMs(root.attr("availabilityStartTime")),
                timeShiftBufferDepthMs = parseDurationMs(root.attr("timeShiftBufferDepth")),
                mediaPresentationDurationMs = parseDurationMs(root.attr("mediaPresentationDuration")),
                // Searched from the root, so it finds one wherever it sits — manifests declare it at
                // AdaptationSet level far more often than at Representation level.
                contentProtected = root.hasContentProtection(),
            )
        }

        /**
         * Substitute a DASH URL template's `$…$` identifiers.
         *
         * `$RepresentationID$` and `$Bandwidth$` are known when the manifest is read; `$Number$` and
         * `$Time$` are not known until a particular segment is wanted, so this is called twice — once
         * at parse time with nulls for those two, and again per segment. **An identifier we were not
         * given is left in place rather than blanked**, which is what makes the two-pass use safe.
         *
         * Handles the `%0Nd` format modifier (`$Number%05d$`) and the `$$` escape.
         */
        fun expandTemplate(
            template: String,
            representationId: String? = null,
            bandwidthBps: Int? = null,
            number: Long? = null,
            time: Long? = null,
        ): String {
            val out = StringBuilder(template.length + 16)
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c != '$') {
                    out.append(c)
                    i++
                    continue
                }
                val end = template.indexOf('$', i + 1)
                if (end < 0) {
                    // An unpaired '$' is just a character in a URL.
                    out.append(c)
                    i++
                    continue
                }
                val token = template.substring(i + 1, end)
                i = end + 1
                if (token.isEmpty()) {
                    out.append('$') // `$$` — an escaped dollar.
                    continue
                }
                val name = token.substringBefore('%')
                val format = token.substringAfter('%', "")
                val value: Any? = when (name) {
                    "Number" -> number
                    "Time" -> time
                    "RepresentationID" -> representationId
                    "Bandwidth" -> bandwidthBps
                    else -> null
                }
                if (value == null) {
                    out.append('$').append(token).append('$')
                    continue
                }
                out.append(
                    if (format.isEmpty()) {
                        value.toString()
                    } else {
                        // A malformed modifier must not lose the segment; the plain value still works.
                        runCatching { String.format(Locale.ROOT, "%$format", value) }.getOrElse { value.toString() }
                    },
                )
            }
            return out.toString()
        }

        /**
         * `xs:duration` in milliseconds — `PT6S`, `PT1M30.5S`, `P1DT2H`.
         *
         * Hand-parsed rather than `java.time.Duration.parse`, which rejects the year and month fields
         * some panels emit in their zero-padded form (`P0Y0M0DT0H0M6.0S`). Years and months are taken
         * as 365 and 30 days: nothing this is used for is sensitive to the difference, and refusing
         * the whole manifest over it would be the strictness this file avoids.
         */
        fun parseDurationMs(text: String?): Long? {
            val match = DURATION.matchEntire(text?.trim().orEmpty()) ?: return null
            val (years, months, days, hours, minutes, seconds) = match.destructured
            val wholeDays = (years.toLongOrNull() ?: 0L) * 365L +
                (months.toLongOrNull() ?: 0L) * 30L +
                (days.toLongOrNull() ?: 0L)
            return wholeDays * 86_400_000L +
                (hours.toLongOrNull() ?: 0L) * 3_600_000L +
                (minutes.toLongOrNull() ?: 0L) * 60_000L +
                ((seconds.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }

        /** An ISO-8601 instant as epoch millis, accepting both `…Z` and an explicit offset. */
        fun parseInstantMs(text: String?): Long? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        }

        /**
         * Join a relative `BaseURL` onto the one it sits inside.
         *
         * Only string joining: the whole chain is resolved against the manifest's *final* URL when a
         * segment is actually fetched, so a redirect lands the segments where the manifest really
         * came from rather than where we asked.
         */
        internal fun joinUrl(base: String, ref: String): String = when {
            ref.isEmpty() -> base
            base.isEmpty() -> ref
            ref.contains("://") || ref.startsWith("/") -> ref
            base.endsWith("/") -> base + ref
            else -> base.substringBeforeLast('/', "").let { if (it.isEmpty()) ref else "$it/$ref" }
        }

        private const val XML_DECLARATION = "<?xml"

        private val DURATION = Regex(
            """-?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?""",
        )

        /** `@timescale` defaults to 1 — seconds — when the manifest does not say. */
        private const val DEFAULT_TIMESCALE = 1L

        /** What to assume when a manifest gives no segment duration at all. The usual live segment. */
        internal const val DEFAULT_SEGMENT_MS = 6_000L
    }
}

/**
 * What a Representation carries.
 *
 * [MUXED] is the one that costs nothing: a Representation holding audio and video together can be
 * recorded by straight concatenation, exactly as HLS is, with no mux step at all. [UNKNOWN] is a
 * Representation that declared neither — not refused, because a manifest that omits `contentType`
 * and `codecs` is unusual rather than wrong, and a lone one is very likely self-contained.
 */
enum class DashTrackKind { VIDEO, AUDIO, MUXED, UNKNOWN }

/** One media segment, addressed the way DASH addresses it — never by URL. See [DashSegments]. */
data class DashSegment(
    /** Identity. Monotonic within a Representation, and stable across manifest re-reads. */
    val number: Long,
    /** `$Time$` in timescale units when the manifest uses a `SegmentTimeline`; null otherwise. */
    val time: Long?,
    /** The media URL, relative to the manifest's own URL until it is resolved against it. */
    val url: String,
    val durationMs: Long,
)

/**
 * How a Representation's segments are addressed. The three shapes a recorder meets.
 *
 * **Identity is the segment number, never the URL.** Several providers sign each segment
 * individually, so a URL cached for one cycle is a 403 in the next — the cause behind the Live TV
 * black screen recorded in `owntv-live-403-signed-segments`, and the same rule
 * [RecordingEngine.recordHls] already follows for HLS.
 */
sealed interface DashSegments {
    /**
     * The manifest enumerates them: a `SegmentTimeline`, or an explicit `SegmentList`. Everything is
     * known at parse time, including each segment's `$Time$`.
     */
    data class Explicit(val segments: List<DashSegment>) : DashSegments

    /**
     * A `SegmentTemplate` with `$Number$` and a fixed `@duration`, and no timeline. The manifest
     * lists nothing — which segment exists right now is a function of the wall clock and
     * `availabilityStartTime`, so the numbers are worked out when they are wanted, not here.
     */
    data class Numbered(
        /** Still holding `$Number$`/`$Time$`; the other identifiers are already substituted. */
        val mediaTemplate: String,
        val startNumber: Long,
        val durationMs: Long,
    ) : DashSegments

    /** One self-contained file — `SegmentBase`, or a bare `BaseURL`. Fetched whole, like a download. */
    data class Single(val url: String) : DashSegments
}

/** One Representation: a single quality of a single track. */
data class DashRepresentation(
    val id: String,
    val kind: DashTrackKind,
    val bandwidthBps: Int,
    val mimeType: String?,
    val codecs: String?,
    /**
     * The initialisation segment, which carries the codec configuration.
     *
     * **Written first, before any media segment.** Without it the temp file is a stream of media
     * fragments no extractor can read, and the mux at the end has nothing to describe the tracks.
     */
    val initializationUrl: String?,
    val segments: DashSegments,
) {
    /** How long one segment covers, for working out how often to poll. */
    val segmentDurationMs: Long
        get() = when (segments) {
            is DashSegments.Numbered -> segments.durationMs
            is DashSegments.Explicit -> segments.segments.firstOrNull()?.durationMs ?: DashManifest.DEFAULT_SEGMENT_MS
            is DashSegments.Single -> DashManifest.DEFAULT_SEGMENT_MS
        }.coerceAtLeast(1L)
}

// ---------------------------------------------------------------------------------------------
// Parsing internals. Small DOM helpers, kept private so the public surface stays the four facts
// a recorder needs.
// ---------------------------------------------------------------------------------------------

/** A `SegmentTemplate`'s attributes, inherited down Period → AdaptationSet → Representation. */
private data class TemplateSpec(
    val media: String? = null,
    val initialization: String? = null,
    val timescale: Long? = null,
    val duration: Long? = null,
    val startNumber: Long? = null,
    val timeline: List<TimelineEntry>? = null,
) {
    /** DASH inheritance: whatever the child states wins, attribute by attribute. */
    fun mergedWith(child: TemplateSpec) = TemplateSpec(
        media = child.media ?: media,
        initialization = child.initialization ?: initialization,
        timescale = child.timescale ?: timescale,
        duration = child.duration ?: duration,
        startNumber = child.startNumber ?: startNumber,
        timeline = child.timeline ?: timeline,
    )

    val isEmpty: Boolean get() = media == null && initialization == null && timeline == null
}

/** One `<S>` of a `SegmentTimeline`: start, duration, and how many more just like it. */
private data class TimelineEntry(val time: Long?, val duration: Long, val repeat: Int)

private fun Element.children(name: String): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName.equals(name, ignoreCase = true)) {
            out += node
        }
    }
    return out
}

private fun Element.child(name: String): Element? = children(name).firstOrNull()

private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

/** The element's own `<BaseURL>` text, or empty. Only the first is used; alternatives are mirrors. */
private fun Element.baseUrl(): String = child("BaseURL")?.textContent?.trim().orEmpty()

/** `<ContentProtection>` anywhere beneath this element. */
private fun Element.hasContentProtection(): Boolean =
    getElementsByTagName("ContentProtection").length > 0

private fun Element.templateSpec(): TemplateSpec {
    val t = child("SegmentTemplate") ?: return TemplateSpec()
    return TemplateSpec(
        media = t.attr("media"),
        initialization = t.attr("initialization"),
        timescale = t.attr("timescale")?.toLongOrNull(),
        duration = t.attr("duration")?.toLongOrNull(),
        startNumber = t.attr("startNumber")?.toLongOrNull(),
        timeline = t.child("SegmentTimeline")?.children("S")?.map { s ->
            TimelineEntry(
                time = s.attr("t")?.toLongOrNull(),
                duration = s.attr("d")?.toLongOrNull() ?: 0L,
                repeat = s.attr("r")?.toIntOrNull() ?: 0,
            )
        },
    )
}

/** An explicit `<SegmentList>`: its media URLs in order, plus its own initialisation. */
private data class SegmentListSpec(val urls: List<String>, val initialization: String?, val durationMs: Long?)

private fun Element.segmentList(): SegmentListSpec? {
    val list = child("SegmentList") ?: return null
    val timescale = list.attr("timescale")?.toLongOrNull() ?: 1L
    val duration = list.attr("duration")?.toLongOrNull()
    return SegmentListSpec(
        urls = list.children("SegmentURL").mapNotNull { it.attr("media") },
        initialization = list.child("Initialization")?.attr("sourceURL"),
        durationMs = duration?.let { it * 1000L / timescale.coerceAtLeast(1L) },
    )
}

private fun Element.toRepresentation(
    base: String,
    inheritedTemplate: TemplateSpec,
    inheritedList: SegmentListSpec?,
    setMimeType: String?,
    setCodecs: String?,
    setContentType: String?,
): DashRepresentation {
    val id = attr("id").orEmpty()
    val bandwidth = attr("bandwidth")?.toIntOrNull() ?: 0
    val mimeType = attr("mimeType") ?: setMimeType
    val codecs = attr("codecs") ?: setCodecs
    val template = inheritedTemplate.mergedWith(templateSpec())
    val list = segmentList() ?: inheritedList

    // Identifiers known now are substituted now; $Number$ and $Time$ are left for later.
    fun fill(raw: String?) = raw?.let { DashManifest.expandTemplate(it, representationId = id, bandwidthBps = bandwidth) }

    val timescale = (template.timescale ?: 1L).coerceAtLeast(1L)
    val mediaTemplate = fill(template.media)?.let { DashManifest.joinUrl(base, it) }
    val initialization = when {
        template.initialization != null -> fill(template.initialization)?.let { DashManifest.joinUrl(base, it) }
        list?.initialization != null -> DashManifest.joinUrl(base, list.initialization)
        else -> child("SegmentBase")?.child("Initialization")?.attr("sourceURL")
            ?.let { DashManifest.joinUrl(base, it) }
    }

    val segments: DashSegments = when {
        // A timeline enumerates every segment, including its $Time$ — the most precise shape there is.
        template.timeline != null && mediaTemplate != null ->
            DashSegments.Explicit(template.timeline.expand(mediaTemplate, timescale, template.startNumber ?: 1L))
        // A plain $Number$ template: nothing is listed, the clock decides. See [DashSegments.Numbered].
        mediaTemplate != null ->
            DashSegments.Numbered(
                mediaTemplate = mediaTemplate,
                startNumber = template.startNumber ?: 1L,
                durationMs = template.duration
                    ?.let { it * 1000L / timescale }
                    ?.takeIf { it > 0L }
                    ?: DashManifest.DEFAULT_SEGMENT_MS,
            )
        list != null && list.urls.isNotEmpty() ->
            DashSegments.Explicit(
                list.urls.mapIndexed { index, url ->
                    DashSegment(
                        number = index.toLong(),
                        time = null,
                        url = DashManifest.joinUrl(base, url),
                        durationMs = list.durationMs ?: DashManifest.DEFAULT_SEGMENT_MS,
                    )
                },
            )
        // SegmentBase, or nothing at all: the BaseURL *is* the media.
        else -> DashSegments.Single(base)
    }

    return DashRepresentation(
        id = id,
        kind = kindOf(mimeType, setContentType ?: attr("contentType"), codecs),
        bandwidthBps = bandwidth,
        mimeType = mimeType,
        codecs = codecs,
        initializationUrl = initialization,
        segments = segments,
    )
}

/** `S@t`/`@d`/`@r` expanded into one segment per repeat, each carrying the `$Time$` it starts at. */
private fun List<TimelineEntry>.expand(mediaTemplate: String, timescale: Long, startNumber: Long): List<DashSegment> {
    val out = mutableListOf<DashSegment>()
    var time = 0L
    var number = startNumber
    forEach { entry ->
        // `@t` is optional after the first `<S>`: the segment starts where the previous one ended.
        time = entry.time ?: time
        // `@r` counts *additional* repeats, so `r="2"` is three segments. `r="-1"` means "until the
        // next entry or the end of the window" — unknowable from the manifest alone, so it is taken
        // as this one segment and the next poll picks the rest up.
        val count = if (entry.repeat < 0) 1 else entry.repeat + 1
        repeat(count) {
            out += DashSegment(
                number = number,
                time = time,
                url = DashManifest.expandTemplate(mediaTemplate, number = number, time = time),
                durationMs = entry.duration * 1000L / timescale,
            )
            time += entry.duration
            number++
        }
    }
    return out
}

/**
 * Which track this is.
 *
 * `codecs` is consulted before `contentType` because it is the only thing that reveals a **muxed**
 * Representation — one listing both a video and an audio codec, which needs no mux step at all.
 */
private fun kindOf(mimeType: String?, contentType: String?, codecs: String?): DashTrackKind {
    val list = codecs?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
    val hasVideo = list.any { codec -> VIDEO_CODECS.any { codec.startsWith(it) } }
    val hasAudio = list.any { codec -> AUDIO_CODECS.any { codec.startsWith(it) } }
    if (hasVideo && hasAudio) return DashTrackKind.MUXED
    val declared = contentType?.lowercase(Locale.ROOT)
        ?: mimeType?.substringBefore('/')?.lowercase(Locale.ROOT)
    return when {
        declared == "video" -> DashTrackKind.VIDEO
        declared == "audio" -> DashTrackKind.AUDIO
        hasVideo -> DashTrackKind.VIDEO
        hasAudio -> DashTrackKind.AUDIO
        else -> DashTrackKind.UNKNOWN
    }
}

private val VIDEO_CODECS = listOf("avc", "hev", "hvc", "vp8", "vp9", "vp0", "av01", "dvh", "mp4v")
private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "vorbis", "dts", "flac", "alac")
