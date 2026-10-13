package tv.own.owntv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * An Xtream source's servers: the primary (`SourceEntity.url`) plus the user's ordered backup hosts
 * (`SourceEntity.backupUrls`), all serving the same account. Pure: no I/O, no state.
 *
 * Stored stream URLs always carry the primary's origin (sync builds them from `url`), so nothing that is
 * persisted ever changes when the in-use server does. A request swaps the origin at the last moment
 * ([rewrite]); a comparison maps back ([canonical]).
 *
 * Origins are `scheme://host[:port]`: the host lowercased, a default port (http 80, https 443) dropped,
 * never a path, query, fragment or login. That keeps credentials out of the list (they travel in the
 * stream path, which the source already holds) and makes `host:80` and `host` the same server.
 */
object HostPool {

    /** Most backup servers a source keeps (U5). */
    const val MAX_BACKUPS = 50

    /** Key for lessons that belong to the account rather than to one host (the single-session limit). */
    fun poolKey(sourceId: Long): String = "pool:$sourceId"

    /**
     * One user-typed (or restored) server address as an origin, or null when it isn't one.
     *
     * - A bare host (`pro.example.top:8080`) takes the **primary's scheme**, so an https account is never
     *   sent to a backup over cleartext by accident.
     * - A login (`user:pass@`), a query and a fragment are dropped: the list never stores credentials.
     * - A path is only accepted when it is one of the panel's own endpoints (`get.php`, `player_api.php`,
     *   `/live/…` and so on), which is how providers hand out their links; that path is dropped. Any
     *   other path means a server that lives under a prefix, which a plain origin swap can't serve.
     */
    fun normalize(input: String, primary: String?): String? {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in raw) raw else "${schemeOf(primary)}://$raw"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank() || '.' !in url.host && url.host != "localhost" && !url.host.contains(':')) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.isNotEmpty() && segments.first().lowercase() !in PANEL_ENDPOINTS) return null
        return originOf(url.scheme, url.host, url.port)
    }

    /** The normalised origin of any http(s) URL, path and login ignored; null if it isn't one. */
    fun origin(url: String?): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        return originOf(parsed.scheme, parsed.host, parsed.port)
    }

    /** True when [primary] has a path below the root: failover is off for such a source (A3). */
    fun hasPathPrefix(primary: String?): Boolean {
        val parsed = primary?.trim()?.toHttpUrlOrNull() ?: return false
        return parsed.pathSegments.any { it.isNotEmpty() }
    }

    /** The stored backup list: newline-separated origins, in the user's order. */
    fun decode(stored: String?): List<String> =
        stored?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()

    /** [backups] as stored, or null for none. */
    fun encode(backups: List<String>): String? = backups.takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * [entries] normalised against [primary], deduplicated against the primary and each other, invalid
     * ones dropped, capped at [MAX_BACKUPS]. Used by the editor's save, restore, and anything else that
     * writes the list, so a hand-edited backup file can't inject a login or a junk entry.
     */
    fun sanitize(entries: List<String>, primary: String?): List<String> {
        val primaryOrigin = origin(primary)
        val out = LinkedHashSet<String>()
        for (e in entries) {
            val o = normalize(e, primary) ?: continue
            if (o == primaryOrigin) continue
            out += o
            if (out.size >= MAX_BACKUPS) break
        }
        return out.toList()
    }

    /** What adding a typed or pasted text did; see [add]. */
    data class AddResult(val backups: List<String>, val added: Int, val duplicates: Int, val invalid: List<String>)

    /**
     * Adds every address in [text] (split on spaces, commas, semicolons and new lines) after [existing].
     * A paste of the provider's whole list adds them all in one go.
     */
    fun add(text: String, primary: String?, existing: List<String>): AddResult {
        val primaryOrigin = origin(primary)
        val out = LinkedHashSet(existing)
        var added = 0
        var duplicates = 0
        val invalid = mutableListOf<String>()
        for (token in text.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }) {
            val o = normalize(token, primary)
            when {
                o == null -> invalid += token
                o == primaryOrigin || o in out -> duplicates++
                out.size >= MAX_BACKUPS -> invalid += token
                else -> { out += o; added++ }
            }
        }
        return AddResult(out.toList(), added, duplicates, invalid)
    }

    /** An http backup under an https primary: allowed, but the login then travels unencrypted. */
    fun isNotSecure(backup: String, primary: String?): Boolean =
        schemeOf(primary) == "https" && backup.startsWith("http://")

    /** Every server of [s] in order, the primary's origin first; empty for anything but Xtream. */
    fun pool(s: SourceEntity): List<String> {
        if (s.type != SourceType.XTREAM) return emptyList()
        val primary = origin(s.url) ?: return emptyList()
        return listOf(primary) + sanitize(decode(s.backupUrls), s.url)
    }

    /** Whether [s] may switch servers by itself: Xtream, at least one backup, the switch on, no path prefix. */
    fun failoverActive(s: SourceEntity): Boolean =
        s.type == SourceType.XTREAM && s.failoverEnabled && !hasPathPrefix(s.url) && pool(s).size > 1

    /**
     * The server [s] should use now: its remembered [SourceEntity.activeUrl] while that is still in the
     * pool, otherwise the primary. Null for a source without a pool (M3U, Stalker).
     */
    fun inUse(s: SourceEntity): String? {
        val pool = pool(s)
        if (pool.isEmpty()) return null
        val active = origin(s.activeUrl)
        return if (active != null && active in pool && !hasPathPrefix(s.url)) active else pool.first()
    }

    /**
     * [url] with its origin swapped for [host], only when [url]'s origin is one of [pool]. A URL saved under
     * an older Server URL (stale sync host) is also swapped when [owner] is set and the path is this
     * account's own panel URL — [forRequest] only; [canonical] leaves stale URLs as stored. Everything else
     * — a `directSource`, a logo, a foreign redirect target — comes back untouched. The rest of the URL is
     * kept byte for byte (no re-encoding).
     */
    fun rewrite(url: String, host: String, pool: List<String>, owner: SourceEntity? = null): String {
        val split = splitOrigin(url) ?: return url
        val current = origin(split.first + "/") ?: return url
        if (current !in pool) {
            if (owner != null && isOwnPanelUrl(split.second, owner)) return host.trimEnd('/') + split.second
            return url
        }
        return host.trimEnd('/') + split.second
    }

    /** [url] for the request side: rewritten onto [s]'s in-use server (identity when that is the primary). */
    fun forRequest(url: String, s: SourceEntity, host: String? = inUse(s)): String {
        val pool = pool(s)
        if (host == null || pool.size < 2 || hasPathPrefix(s.url)) return url
        val target = if (host == pool.first()) s.url.trim().trimEnd('/') else host
        return rewrite(url, target, pool, s)
    }

    /** [url] mapped back to the primary exactly as sync wrote it, for comparisons with stored URLs. */
    fun canonical(url: String, s: SourceEntity): String {
        val pool = pool(s)
        if (pool.size < 2) return url
        return rewrite(url, s.url.trim().trimEnd('/'), pool)
    }

    /**
     * The in-use server to keep when a whole edited row ([edited]) is written over [stored]: the stored
     * value (never the editor's possibly stale copy), reset to null — the primary — once Server URL changed
     * or the server left the list (A1 "full-entity writers").
     */
    fun reconcileActive(edited: SourceEntity, stored: SourceEntity?): String? {
        val active = stored?.activeUrl ?: return null
        if (stored.url.trim().trimEnd('/') != edited.url.trim().trimEnd('/')) return null
        return active.takeIf { origin(it) in pool(edited).drop(1) }
    }

    /** The server after [current] in [pool], wrapping round; null for a pool of one. */
    fun next(pool: List<String>, current: String?): String? {
        if (pool.size < 2) return null
        val i = pool.indexOf(origin(current) ?: current)
        return pool[(i + 1).mod(pool.size)]
    }

    /**
     * How the UI writes a server (origins only): `scheme://host:port`, or the bare host for plain
     * http on port 80. Never a path, query or login, since [origin] already dropped them.
     */
    fun display(url: String?): String {
        val o = origin(url) ?: return url.orEmpty()
        val bare = o.removePrefix("http://")
        return if (o.startsWith("http://") && !bare.substringAfterLast(']').contains(':')) bare else o
    }

    /** "server k of N" position of [host] (1-based), for logs and the toast; 0 if it isn't in [pool]. */
    fun position(pool: List<String>, host: String?): Int = pool.indexOf(origin(host) ?: host) + 1

    /**
     * How logs name a server (R13): "server k of N" plus the masked host, never the host itself. The
     * error log can be shared, and the codebase masks hosts everywhere else (`HttpClient.redactHost`).
     */
    fun describe(pool: List<String>, host: String?): String =
        "server ${position(pool, host)} of ${pool.size} (${HttpClient.redactHost(host.orEmpty())})"

    /**
     * True when [pathAndQuery] is this Xtream account's own panel URL: a panel endpoint whose path holds
     * [SourceEntity.username] immediately followed by [SourceEntity.password], or whose query carries both.
     */
    private fun isOwnPanelUrl(pathAndQuery: String, s: SourceEntity): Boolean {
        val user = s.username
        val pass = s.password
        if (s.type != SourceType.XTREAM || user.isNullOrBlank() || pass.isNullOrBlank()) return false
        val parsed = ("http://x" + pathAndQuery).toHttpUrlOrNull() ?: return false
        if (parsed.pathSegments.firstOrNull()?.lowercase() !in PANEL_ENDPOINTS) return false
        val segs = parsed.pathSegments
        val credsInPath = segs.zipWithNext().any { (a, b) -> a == user && b == pass }
        val credsInQuery = parsed.queryParameter("username") == user &&
            parsed.queryParameter("password") == pass
        return credsInPath || credsInQuery
    }

    /** `scheme://authority` and the remainder (path, query, fragment) of [url]; null if it has no scheme. */
    private fun splitOrigin(url: String): Pair<String, String>? {
        val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
        val authorityStart = schemeEnd + 3
        val end = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
        return url.substring(0, end) to url.substring(end)
    }

    private fun schemeOf(primary: String?): String =
        primary?.trim()?.substringBefore("://", "")?.lowercase()?.takeIf { it == "https" } ?: "http"

    private fun originOf(scheme: String, host: String, port: Int): String {
        val h = host.lowercase().let { if (':' in it) "[$it]" else it }
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (defaultPort) "$scheme://$h" else "$scheme://$h:$port"
    }

    private val SEPARATORS = Regex("[\\s,;]+")

    /** First path segments that are the panel's own endpoints: a link to one of them is a server address. */
    private val PANEL_ENDPOINTS = setOf(
        "get.php", "player_api.php", "panel_api.php", "xmltv.php", "live", "movie", "series", "timeshift",
    )
}
