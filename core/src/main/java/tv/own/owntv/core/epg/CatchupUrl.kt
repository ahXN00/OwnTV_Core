package tv.own.owntv.core.epg

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.TimeZone

/**
 * Builds catch-up / archive playback URLs for a past programme.
 *
 * Two provider conventions:
 *  - **M3U** carries a `catchup-source` URL *template* with placeholders (`${start}`, `{utc}`,
 *    `{Y}-{m}-{d}`, …) that [fromTemplate] fills from the programme's start/end. A `catchup="append"`
 *    channel instead *appends* its template to the live URL — that join is the caller's job.
 *  - **Xtream** has no template; its timeshift URL is built from the source credentials (see
 *    `XtreamClient.timeshiftUrl`).
 *
 * Pure string work, no I/O — easy to unit-test and reuse from any layer.
 */
object CatchupUrl {

    /** Unix-second tokens (both `${name}` and `{name}`, case-insensitive) → their value. */
    private val START_TOKENS = setOf("start", "utc", "start-timestamp", "utcstart")
    private val END_TOKENS = setOf("end", "utcend", "end-timestamp", "stop")

    /** "Now", in unix seconds. `catchup-source="…?utc={utc}&lutc={lutc}"` is one of the most common
     *  templates in the wild, and an unsubstituted `{lutc}` reached the provider verbatim.
     *  `timestamp` is now as well — Kodi documents `${timestamp}` as "same as `{lutc}`", and TiviMate
     *  agrees; it used to be sent as the programme start. */
    private val NOW_TOKENS = setOf("lutc", "now", "timenow", "currenttime", "timestamp")

    /** `{name}` / `${name}`, optionally with an argument after a colon: `{duration:60}`, `{utc:YmdHMS}`. */
    private val TOKEN = Regex("\\$?\\{([A-Za-z_][A-Za-z0-9_-]*)(?::([^}]*))?\\}")

    /** Legacy archive tokens: `${(b)FMT}`, `${(e)FMT}`, optional `:utc`. Must run before [TOKEN]. */
    private val LEGACY = Regex("\\$\\{\\((b|e)\\)([^}]+?)(?::(utc))?\\}")

    /** A run of 2+ Java `SimpleDateFormat` letters (`yyyy`, `MM`, `HH`, …). Single Kodi letters do not match. */
    private val JAVA_PATTERN_RUN = Regex("([yMLwdDFuHkKhmsS])\\1+")

    /**
     * Fill an M3U `catchup-source` [template] for the programme spanning [startMs]..[endMs].
     * Recognised placeholders (UTC), after Kodi IPTV Simple, the de-facto reference:
     *  - unix start/end/now, and duration/offset in seconds;
     *  - `{duration:X}` / `{offset:X}` — the same divided by X seconds (`{duration:60}` = minutes);
     *  - `{utc:FMT}`, `${start:FMT}`, `{utcend:FMT}`, `${end:FMT}`, `{lutc:FMT}`, `${now:FMT}`,
     *    `${timestamp:FMT}` — that instant formatted, the letters Y m d H M S replaced and anything
     *    else kept (`{utc:Ymd-H-M}` → `20210101-00-00`);
     *  - the bare date parts Y/m/d/H/M/S of the start.
     *  - `${(b)FMT}` / `${(e)FMT}` — programme start/end. `timestamp` is unix seconds; a Java pattern
     *    (`yyyy`, `MM`, …) goes through `SimpleDateFormat`; otherwise Kodi letters Y m d H M S.
     *    Those use [tz]; a `:utc` suffix forces UTC. An invalid or unknown FMT is left as-is.
     * Unknown placeholders are left untouched so unusual templates degrade gracefully.
     */
    fun fromTemplate(
        template: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getTimeZone("UTC"),
    ): String {
        val filled = LEGACY.replace(template) { match -> legacyToken(match, startMs, endMs, tz) }
        val startS = startMs / 1000
        val endS = endMs / 1000
        val durationS = ((endMs - startMs) / 1000).coerceAtLeast(0)
        val offsetS = ((nowMs - startMs) / 1000).coerceAtLeast(0)
        return TOKEN.replace(filled) { match ->
            val raw = match.groupValues[1]
            val name = raw.lowercase()
            val arg = match.groups[2]?.value
            val instantMs = when (name) {
                in START_TOKENS -> startMs
                in END_TOKENS -> endMs
                in NOW_TOKENS -> nowMs
                else -> null
            }
            when {
                arg != null -> when {
                    instantMs != null && arg.isNotEmpty() -> formatDateLetters(instantMs, arg)
                    name == "duration" -> divided(durationS, arg) ?: match.value
                    name == "offset" -> divided(offsetS, arg) ?: match.value
                    else -> match.value
                }
                instantMs != null -> (instantMs / 1000).toString()
                name == "duration" -> durationS.toString()
                name == "offset" -> offsetS.toString()
                // Date parts are single-letter and case-sensitive in the wild (M = month/minute), so map
                // by the ORIGINAL token: Y/m/d/H/M/S.
                raw.length == 1 && raw[0] in DATE_LETTERS -> formatDateLetters(startMs, raw)
                else -> match.value // unknown placeholder: leave as-is
            }
        }
    }

    private const val DATE_LETTERS = "YmdHMS"

    /** [seconds] divided by the whole number [arg]; null when [arg] is not a positive number. */
    private fun divided(seconds: Long, arg: String): String? =
        arg.toLongOrNull()?.takeIf { it > 0 }?.let { (seconds / it).toString() }

    /**
     * `${(b)FMT}` / `${(e)FMT}`. Returns the original token when FMT is unknown or not a valid pattern.
     */
    private fun legacyToken(match: kotlin.text.MatchResult, startMs: Long, endMs: Long, tz: TimeZone): String {
        val ms = if (match.groupValues[1] == "b") startMs else endMs
        val fmt = match.groupValues[2]
        val zone = if (match.groups[3] != null) TimeZone.getTimeZone("UTC") else tz
        if (fmt.equals("timestamp", ignoreCase = true)) return (ms / 1000).toString()
        return try {
            when {
                fmt.indexOf('y') >= 0 || JAVA_PATTERN_RUN.containsMatchIn(fmt) ->
                    java.text.SimpleDateFormat(fmt, java.util.Locale.US).apply { timeZone = zone }
                        .format(java.util.Date(ms))
                isKodiFormat(fmt) -> formatDateLetters(ms, fmt, zone)
                else -> match.value
            }
        } catch (e: IllegalArgumentException) {
            match.value
        }
    }

    /** True when every letter is a Kodi date letter and at least one is present. */
    private fun isKodiFormat(fmt: String): Boolean {
        var any = false
        for (c in fmt) {
            if (!c.isLetter()) continue
            if (c !in DATE_LETTERS) return false
            any = true
        }
        return any
    }

    /** [ms] in [tz], with each of Y m d H M S in [format] replaced by that field; other characters kept. */
    private fun formatDateLetters(ms: Long, format: String, tz: TimeZone = TimeZone.getTimeZone("UTC")): String {
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = ms }
        fun two(n: Int) = n.toString().padStart(2, '0')
        return buildString {
            format.forEach { c ->
                append(
                    when (c) {
                        'Y' -> cal.get(java.util.Calendar.YEAR).toString()
                        'm' -> two(cal.get(java.util.Calendar.MONTH) + 1)
                        'd' -> two(cal.get(java.util.Calendar.DAY_OF_MONTH))
                        'H' -> two(cal.get(java.util.Calendar.HOUR_OF_DAY))
                        'M' -> two(cal.get(java.util.Calendar.MINUTE))
                        'S' -> two(cal.get(java.util.Calendar.SECOND))
                        else -> c.toString()
                    },
                )
            }
        }
    }

    /**
     * Build the catch-up URL for a [programme] on [channel] given its resolved [source] and the [tz] the
     * provider expects timeshift timestamps in. Shared by the Guide and Live TV catch-up entry points.
     */
    fun forSource(
        channel: ChannelEntity,
        programme: EpgProgrammeEntity,
        source: SourceEntity,
        tz: TimeZone,
        xtream: XtreamClient,
    ): String? {
        if (!channel.catchup) return null
        return when (source.type) {
            SourceType.XTREAM -> channel.remoteId?.let { streamId ->
                val durationMin = (((programme.stopMs - programme.startMs) / 60_000L).toInt()).coerceAtLeast(1)
                // Always `.ts`, and deliberately NOT subject to "Prefer HLS": that setting describes the
                // live edge, which panels remux to HLS on demand. The timeshift server is a different
                // thing — it serves recordings off disk with no HLS repackager in front of it, so asking
                // it for `.m3u8` reliably produces an error page rather than a playlist. Tying the two
                // together made "Prefer HLS" silently break catch-up for accounts whose live TV was fine.
                xtream.timeshiftUrl(source, streamId, programme.startMs, durationMin, tz, "ts")
            }
            // The catch-up TYPE is now persisted, so `append` (and the templateless styles)
            // finally reach [forM3u]; it used to be hardcoded to null here.
            SourceType.M3U -> forM3u(
                channel.streamUrl,
                channel.catchupType,
                channel.catchupSource,
                programme.startMs,
                programme.stopMs,
                tz,
                channel.catchupCorrectionMin,
            )
            else -> null
        }
    }

    private val TIMESHIFT_PATH = Regex(
        "^(.+?)/timeshift/([^/]+)/([^/]+)/([^/]+)/([^/]+)/([^/.]+)\\.(?:ts|m3u8)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Convert a path-style Xtream catch-up URL
     * (`…/timeshift/user/pass/duration/start/streamId.ts`) into the PHP query form
     * (`…/streaming/timeshift.php?username=…&password=…&stream=…&start=…&duration=…`) that some panels
     * require instead. Returns null if [url] isn't a recognised timeshift path. The player tries this
     * automatically when the path form is rejected (issue: some panels reply with an HTML error page).
     */
    fun timeshiftPhpAlternate(url: String?): String? {
        val g = url?.let { TIMESHIFT_PATH.find(it) }?.groupValues ?: return null
        return "${g[1]}/streaming/timeshift.php?username=${queryValue(g[2])}&password=${queryValue(g[3])}&stream=${g[6]}&start=${g[5]}&duration=${g[4]}"
    }

    /**
     * A path segment re-encoded as a query value. A `+` or `&` is literal in a path but not in a
     * query, so the segment is decoded first (keeping `+` a plus, as a path means it) and encoded again.
     */
    private fun queryValue(pathSegment: String): String {
        val decoded = runCatching { URLDecoder.decode(pathSegment.replace("+", "%2B"), "UTF-8") }.getOrDefault(pathSegment)
        return URLEncoder.encode(decoded, "UTF-8")
    }

    /**
     * Resolve an M3U channel's catch-up URL from its `catchup` [catchupType] and `catchup-source`
     * [catchupSource] template. Returns null when nothing usable can be built.
     *
     * Four conventions, in the order playlists use them:
     *  - **append** — the template is a query fragment joined onto the [liveUrl] (the most common form,
     *    and previously unreachable because the type was never stored).
     *  - **shift / timeshift** — usually typeless: append the standard `?utc=…&lutc=…` pair.
     *  - **flussonic** — no template at all; the archive lives at a different path on the same server.
     *  - **xc** — an Xtream panel behind an M3U playlist: rebuild the URL as `timeshift.php`.
     *
     * A plain template with no type is substituted as-is, exactly as before. A `default` channel with
     * no template appends the `shift` query, as Kodi does.
     *
     * [correctionMin] is the playlist's `catchup-correction`: the provider's archive clock is that far
     * from the guide's, so the programme's times are moved by it before any URL is built.
     */
    fun forM3u(
        liveUrl: String,
        catchupType: String?,
        catchupSource: String?,
        startMs: Long,
        endMs: Long,
        tz: TimeZone = TimeZone.getTimeZone("UTC"),
        correctionMin: Int = 0,
    ): String? {
        val template = catchupSource?.takeIf { it.isNotBlank() }
        val type = catchupType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val shiftMs = correctionMin * 60_000L
        val start = startMs + shiftMs
        val end = endMs + shiftMs
        return when {
            template != null && type == "append" -> joinQuery(liveUrl, fromTemplate(template, start, end, tz = tz))
            template != null -> fromTemplate(template, start, end, tz = tz)
            type == "shift" || type == "timeshift" || type == "default" ->
                joinQuery(liveUrl, fromTemplate(SHIFT_TEMPLATE, start, end))
            type != null && type.startsWith("flussonic") -> flussonic(liveUrl, start, end)
            type == "xc" || type == "xtream" -> xtreamStyle(liveUrl, start, end, tz)
            else -> null
        }
    }

    /** The `shift` convention's fixed query. */
    private const val SHIFT_TEMPLATE = "?utc={utc}&lutc={lutc}"

    /**
     * Join an appended query [fragment] onto [base]. A channel URL that already carries a query (a CDN
     * token, say) would otherwise get a second `?` and be rejected outright, so the separator is
     * corrected — everything else is appended verbatim, as the convention expects.
     */
    private fun joinQuery(base: String, fragment: String): String {
        if (fragment.isEmpty()) return base
        // Playlists write the fragment three ways — "?utc=…", "&utc=…" and a bare "utc=…" — and only the
        // first was handled. A bare fragment concatenated onto a URL that already carried a query produced
        // "…?token=abcutc=1700000000", which the archive answers with a 404.
        val body = fragment.removePrefix("?").removePrefix("&")
        return if (base.contains('?')) "$base&$body" else "$base?$body"
    }

    /**
     * Flussonic: the archive is a sibling of the live playlist —
     * `…/channel/index.m3u8` → `…/channel/timeshift_abs-<start>.m3u8`, and the MPEG-TS forms
     * (`…/channel/mpegts`, `…/channel/index.ts`) → `…/channel/archive-<start>-<duration>.ts`.
     * Any query string is preserved. Returns null when the URL doesn't look like one of those.
     */
    private fun flussonic(liveUrl: String, startMs: Long, endMs: Long): String? {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?')
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        val last = path.substring(slash + 1).lowercase()
        val prefix = path.substring(0, slash + 1)
        val startS = startMs / 1000
        val durationS = ((endMs - startMs) / 1000).coerceAtLeast(1)
        return when {
            last.endsWith(".m3u8") -> "${prefix}timeshift_abs-$startS.m3u8$query"
            last == "mpegts" || last.endsWith(".ts") -> "${prefix}archive-$startS-$durationS.ts$query"
            else -> null
        }
    }

    private val XC_LIVE_URL = Regex(
        "^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.[A-Za-z0-9]+)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `catchup="xc"` — an Xtream panel served through a plain M3U playlist. The live URL carries the
     * credentials (`…/live/user/pass/1234.ts`), so the panel's own timeshift endpoint can be rebuilt
     * from it. The start timestamp is local to the provider, hence [tz]. Null when the URL isn't in
     * that shape.
     */
    private fun xtreamStyle(liveUrl: String, startMs: Long, endMs: Long, tz: TimeZone): String? {
        val g = XC_LIVE_URL.find(liveUrl.substringBefore('?'))?.groupValues ?: return null
        val durationMin = (((endMs - startMs) / 60_000L).toInt()).coerceAtLeast(1)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = tz }
        val start = fmt.format(java.util.Date(startMs))
        return "${g[1]}/streaming/timeshift.php?username=${g[2]}&password=${g[3]}&stream=${g[4]}" +
            "&start=$start&duration=$durationMin"
    }
}
