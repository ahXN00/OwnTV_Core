package tv.own.owntv.core.parser

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import tv.own.owntv.core.network.StreamHeaders
import java.io.BufferedReader
import java.io.InputStream

/** A single entry parsed from an M3U playlist — can be a live channel or a VOD movie/series. */
data class M3uEntry(
    val name: String,
    val streamUrl: String,
    val logo: String?,
    val groupTitle: String?,
    val tvgId: String?,
    val tvgChno: Int?,
    /** `type` attribute — "vod" / "series" / "movie" — tells us whether this is VOD content. */
    val type: String?,
    /** `tvg-type` attribute — alternative VOD type marker used by some playlists. */
    val tvgType: String?,
    /** `catchup` type (e.g. "default"/"append"/"shift") — its presence marks the channel as having archive. */
    val catchup: String?,
    /** `catchup-source` URL template (placeholders like `${start}`/`{utc}` filled at playback). */
    val catchupSource: String?,
    /** `catchup-days` — how many days back the archive goes. */
    val catchupDays: Int?,
    /** Per-channel HTTP request headers (F16) — from `#EXTVLCOPT` / `#EXTHTTP` / `#KODIPROP` or the
     *  `url|Key=Value` pipe suffix. Empty when the entry carries none. */
    val headers: Map<String, String> = emptyMap(),
    /** Widevine/ClearKey licence details from the entry's `#KODIPROP:inputstream.adaptive.license_*`
     *  lines (#115); null for the overwhelming majority of entries, which carry no DRM. */
    val drm: tv.own.owntv.core.drm.DrmConfig? = null,
) {
    /** Tagged as series content — per-episode entries like "Show S01E05" grouped into shows. */
    val isSeries: Boolean get() = type == "series" || tvgType == "series"

    /** True when the entry is explicitly tagged as VOD (movie or series), not a live channel. */
    val isVod: Boolean get() =
        isSeries || type == "vod" || type == "movie" || tvgType == "vod" || tvgType == "movie"
}

/** Header info from the `#EXTM3U` line (notably the `url-tvg` EPG URL). */
data class M3uHeader(val urlTvg: String?)

/**
 * Streaming M3U / M3U8 parser. Reads line-by-line (never loads the whole file) and invokes [onEntry]
 * for each channel, so the sync layer can batch-insert without buffering 340k items in memory.
 * Returns the parsed header.
 *
 * Recognized per-channel attributes on `#EXTINF`: `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`,
 * `group-title`, plus the display name after the comma and the following URL line.
 */
class M3uParser {

    suspend fun parse(input: InputStream, onEntry: suspend (M3uEntry) -> Unit): M3uHeader {
        // Per-line timing costs millions of elapsedRealtime() syscalls on a 100k+ playlist, so the
        // detailed metrics only run when the tag is debuggable (`setprop log.tag.M3uParser DEBUG`).
        val debug = Log.isLoggable(TAG, Log.DEBUG)
        val startedAt = SystemClock.elapsedRealtime()
        var lastLogAt = startedAt
        var lastParseOrReadMs = 0L
        var lastCallbackMs = 0L
        var entries = 0
        val metrics = ParseMetrics()
        var header = M3uHeader(urlTvg = null)
        var pending: PendingExtInf? = null
        // Per-channel HTTP options arrive on their own lines BETWEEN the #EXTINF and the URL, so they
        // are collected separately and consumed by the URL line (F16).
        var pendingHeaders: MutableMap<String, String>? = null
        // Same story for the DRM properties (#115): several `#KODIPROP` lines describe one entry's
        // licence, and only the URL line knows the entry is complete.
        var pendingDrm: MutableMap<String, String>? = null
        if (debug) Log.d(TAG, "parse start")

        input.bufferedReader().forEachLineSafe { raw ->
            val parseStart = if (debug) SystemClock.elapsedRealtime() else 0L
            val line = raw.trim()
            var callbackHandled = false
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U") -> {
                    val attrs = parseAttrs(line)
                    header = M3uHeader(urlTvg = attrs.attr("url-tvg") ?: attrs.attr("x-tvg-url"))
                }

                line.startsWith("#EXTINF") -> {
                    val attrs = parseAttrs(line)
                    pendingHeaders = null // a new entry starts; drop anything the previous one left
                    pendingDrm = null
                    pending = PendingExtInf(
                        name = displayName(line, attrs),
                        logo = attrs.attr("tvg-logo"),
                        groupTitle = attrs.attr("group-title"),
                        tvgId = attrs.attr("tvg-id"),
                        tvgChno = attrs.attr("tvg-chno")?.toIntOrNull(),
                        type = attrs.attr("type"),
                        tvgType = attrs.attr("tvg-type"),
                        catchup = attrs.attr("catchup") ?: attrs.attr("catchup-type"),
                        catchupSource = attrs.attr("catchup-source"),
                        catchupDays = attrs.attr("catchup-days")?.toIntOrNull(),
                    )
                }

                line.startsWith("#") -> {
                    // Per-channel HTTP options (F16). Every other directive (e.g. #EXTGRP) is ignored,
                    // and the three prefixes are checked only inside this branch so a playlist without
                    // them pays one startsWith("#") as before.
                    parseHttpDirective(line)?.let { parsed ->
                        val map = pendingHeaders ?: LinkedHashMap<String, String>(4).also { pendingHeaders = it }
                        map.putAll(parsed)
                    }
                    parseDrmDirective(line)?.let { (key, value) ->
                        val map = pendingDrm ?: LinkedHashMap<String, String>(2).also { pendingDrm = it }
                        map[key] = value
                    }
                }

                else -> {
                    // A URL line completes the pending channel. It may carry a `|Key=Value&Key=Value`
                    // suffix, which belongs to the request, not to the URL.
                    val pipe = pipeSuffixAt(line)
                    val url = if (pipe > 0) line.substring(0, pipe) else line
                    if (pipe > 0) {
                        val fromUrl = parseAmpersandHeaders(line.substring(pipe + 1))
                        if (fromUrl.isNotEmpty()) {
                            // The suffix sits on the URL itself, so it wins over a directive above it.
                            val map = pendingHeaders ?: LinkedHashMap<String, String>(4).also { pendingHeaders = it }
                            map.putAll(fromUrl)
                        }
                    }
                    val p = pending
                    if (p != null && p.name.isNotEmpty()) {
                        if (debug) metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                        val callbackStart = if (debug) SystemClock.elapsedRealtime() else 0L
                        try {
                            onEntry(
                                M3uEntry(
                                    name = p.name,
                                    streamUrl = url,
                                    logo = p.logo,
                                    groupTitle = p.groupTitle,
                                    tvgId = p.tvgId,
                                    tvgChno = p.tvgChno,
                                    type = p.type,
                                    tvgType = p.tvgType,
                                    catchup = p.catchup,
                                    catchupSource = p.catchupSource,
                                    catchupDays = p.catchupDays,
                                    headers = pendingHeaders ?: emptyMap(),
                                    drm = pendingDrm?.let { tv.own.owntv.core.drm.DrmConfig.fromKodiProps(it) },
                                ),
                            )
                        } finally {
                            if (debug) metrics.callbackMs += SystemClock.elapsedRealtime() - callbackStart
                        }
                        entries++
                        callbackHandled = true
                    }
                    pending = null
                    pendingHeaders = null
                    pendingDrm = null
                }
            }

            if (debug) {
                if (!callbackHandled) {
                    metrics.parseOrReadMs += SystemClock.elapsedRealtime() - parseStart
                }

                if (entries > 0 && entries % STREAM_LOG_ITEM_STEP == 0) {
                    val now = SystemClock.elapsedRealtime()
                    val parseOrReadDelta = metrics.parseOrReadMs - lastParseOrReadMs
                    val callbackDelta = metrics.callbackMs - lastCallbackMs
                    Log.d(
                        TAG,
                        "parse progress entries=$entries deltaMs=${now - lastLogAt} " +
                            "parseOrReadMs=$parseOrReadDelta callbackMs=$callbackDelta " +
                            "totalParseOrReadMs=${metrics.parseOrReadMs} totalCallbackMs=${metrics.callbackMs} " +
                            "totalMs=${now - startedAt}",
                    )
                    lastLogAt = now
                    lastParseOrReadMs = metrics.parseOrReadMs
                    lastCallbackMs = metrics.callbackMs
                }
            }
        }
        Log.i(TAG, "parse end entries=$entries totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return header
    }

    private data class PendingExtInf(
        val name: String,
        val logo: String?,
        val groupTitle: String?,
        val tvgId: String?,
        val tvgChno: Int?,
        val type: String?,
        val tvgType: String?,
        val catchup: String?,
        val catchupSource: String?,
        val catchupDays: Int?,
    )

    private data class ParseMetrics(
        var parseOrReadMs: Long = 0L,
        var callbackMs: Long = 0L,
    )

    /**
     * Extracts every `key="value"` attribute from an EXTINF/EXTM3U line in one left-to-right scan
     * (the old per-key `indexOf` re-scanned the line ~10× per entry — the dominant parse cost on huge
     * playlists — and could also mis-match a key that is a suffix of another, e.g. `type` inside
     * `tvg-type="…"`). Keys are matched exactly and case-sensitively, as before.
     */
    private fun parseAttrs(line: String): Map<String, String> {
        var eq = line.indexOf("=\"")
        if (eq < 0) return emptyMap()
        val map = HashMap<String, String>(12)
        while (eq >= 0) {
            val valueEnd = line.indexOf('"', eq + 2)
            if (valueEnd < 0) break
            var keyStart = eq
            while (keyStart > 0) {
                val c = line[keyStart - 1]
                if (c.isLetterOrDigit() || c == '-' || c == '_') keyStart-- else break
            }
            if (keyStart < eq) map[line.substring(keyStart, eq)] = line.substring(eq + 2, valueEnd)
            eq = line.indexOf("=\"", valueEnd + 1)
        }
        return map
    }

    private fun Map<String, String>.attr(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

    /**
     * The display name: everything after the first comma that sits outside a quoted attribute value.
     *
     * This used to be `substringAfterLast(',')`, which cut every name that contains a comma of its
     * own — `Movie, The (1999)` came out as `The (1999)`. The ugly title is the smaller problem: the
     * M3U stable key is derived from the name, so a truncated name re-keys the row and its favourites,
     * history and resume position are orphaned at the next sync. Quoted values are skipped, so a
     * `group-title="News, Politics"` still cannot be mistaken for the separator — the one case the
     * last-comma rule did get right.
     *
     * A line with no separator, or with nothing after it, falls back to `tvg-name` instead of taking
     * the whole `#EXTINF…` line, or an empty string, as the title. An entry with neither is dropped
     * at its URL line as before.
     */
    private fun displayName(line: String, attrs: Map<String, String>): String {
        var inQuote = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return line.substring(i + 1).trim().ifBlank { attrs.attr("tvg-name").orEmpty() }
            }
        }
        // Only an unbalanced quote arrives here with a comma still on the line. That line is
        // malformed either way, so it keeps the last-comma reading it always had rather than being
        // dropped. Without any comma, `tvg-name` is the only name there is.
        val last = line.lastIndexOf(',')
        return if (last >= 0) {
            line.substring(last + 1).trim().ifBlank { attrs.attr("tvg-name").orEmpty() }
        } else {
            attrs.attr("tvg-name").orEmpty()
        }
    }

    /**
     * Per-channel HTTP headers from the three conventions playlists use (F16). Returns null for every
     * other `#` directive, which is the overwhelmingly common case.
     *
     *  - `#EXTVLCOPT:http-user-agent=Foo` / `http-referrer=` / `http-origin=` / `http-cookie=`
     *  - `#EXTHTTP:{"cookie":"a=b","User-Agent":"Foo"}`
     *  - `#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Foo&Referer=Bar`
     *    (also `manifest_headers`, and the legacy `inputstream.adaptive.stream_header`)
     */
    private fun parseHttpDirective(line: String): Map<String, String>? = when {
        line.startsWith(EXTVLCOPT) -> {
            val opt = line.substring(EXTVLCOPT.length).trim()
            val eq = opt.indexOf('=')
            if (eq <= 0) {
                null
            } else {
                val key = opt.substring(0, eq).trim().lowercase()
                val value = opt.substring(eq + 1).trim().trim('"')
                // `http-` options map 1:1 onto request headers; every other VLC option (network-caching,
                // deinterlace, …) is a player setting we deliberately don't honour.
                val name = if (key.startsWith("http-")) StreamHeaders.canonicalName(key.removePrefix("http-")) else null
                if (name == null || value.isEmpty()) null else mapOf(name to value)
            }
        }

        line.startsWith(EXTHTTP) -> runCatching {
            val json = JSONObject(line.substring(EXTHTTP.length).trim())
            val out = LinkedHashMap<String, String>(4)
            json.keys().forEach { key ->
                val name = StreamHeaders.canonicalName(key) ?: return@forEach
                val value = json.optString(key).trim()
                if (value.isNotEmpty()) out[name] = value
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()

        line.startsWith(KODIPROP) -> {
            val prop = line.substring(KODIPROP.length).trim()
            val eq = prop.indexOf('=')
            val key = if (eq > 0) prop.substring(0, eq).trim().lowercase() else ""
            if (eq > 0 && (key.endsWith(".stream_headers") || key.endsWith(".stream_header") || key.endsWith(".manifest_headers"))) {
                parseAmpersandHeaders(prop.substring(eq + 1)).takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }

        else -> null
    }

    /**
     * The `license_type` / `license_key` half of a `#KODIPROP` line (#115), as a short key and its raw
     * value; null for every other line, which is nearly all of them. Kept separate from
     * [parseHttpDirective] because these describe the licence request, not the stream request, and
     * because only [tv.own.owntv.core.drm.DrmConfig] decides whether the pair is usable.
     *
     * The key is matched by suffix so both the `inputstream.adaptive.` and the older bare
     * `inputstream.` spellings work — playlists in the wild mix them.
     */
    private fun parseDrmDirective(line: String): Pair<String, String>? {
        if (!line.startsWith(KODIPROP)) return null
        val prop = line.substring(KODIPROP.length).trim()
        val eq = prop.indexOf('=')
        if (eq <= 0) return null
        val key = prop.substring(0, eq).trim().lowercase().substringAfterLast('.')
        if (!tv.own.owntv.core.drm.DrmConfig.isDrmProp(key)) return null
        val value = prop.substring(eq + 1).trim()
        return if (value.isEmpty()) null else key to value
    }

    /** `User-Agent=Foo&Referer=Bar` (percent-encoded values) → header map. Shared by the KODIPROP
     *  headers property and the `url|…` pipe suffix, which use the same encoding. */
    private fun parseAmpersandHeaders(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(4)
        text.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@forEach
            val name = StreamHeaders.canonicalName(pair.substring(0, eq)) ?: return@forEach
            val value = decodeUrlComponent(pair.substring(eq + 1)).trim()
            if (value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /** Percent-decoding that can never throw: a malformed escape is kept verbatim, because losing the
     *  whole header is worse than one odd character. */
    private fun decodeUrlComponent(value: String): String =
        if (value.indexOf('%') < 0 && value.indexOf('+') < 0) {
            value
        } else {
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }

    /**
     * Index of the `|` that starts a header suffix on a URL line, or -1. Only a pipe *after* the
     * scheme counts — a `|` inside a path or query is left alone, and a line without one costs a
     * single [String.indexOf].
     */
    private fun pipeSuffixAt(line: String): Int {
        val pipe = line.indexOf('|')
        if (pipe <= 0) return -1
        val scheme = line.indexOf("://")
        return if (scheme in 0 until pipe && line.indexOf('=', pipe) > pipe) pipe else -1
    }

    private companion object {
        private const val TAG = "M3uParser"
        private const val STREAM_LOG_ITEM_STEP = 10_000
        private const val EXTVLCOPT = "#EXTVLCOPT:"
        private const val EXTHTTP = "#EXTHTTP:"
        private const val KODIPROP = "#KODIPROP:"
    }
}

private suspend inline fun BufferedReader.forEachLineSafe(action: suspend (String) -> Unit) {
    val ctx = currentCoroutineContext()
    try {
        var line = readLine()
        while (line != null) {
            ctx.ensureActive()
            action(line)
            line = readLine()
        }
    } finally {
        close()
    }
}
