package tv.own.owntv.core.drm

import org.json.JSONObject
import tv.own.owntv.core.network.StreamHeaders

/**
 * The DRM a stream needs, as an M3U playlist declares it.
 *
 * Kodi's `#KODIPROP:inputstream.adaptive.*` convention is what playlists in the wild use, and it is
 * how self-hosted proxies (JioTV-Go and friends) publish protected channels:
 *
 * ```
 * #KODIPROP:inputstream.adaptive.manifest_type=mpd
 * #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
 * #KODIPROP:inputstream.adaptive.license_key=https://host/live/key/144
 * https://host/live/mpd/143
 * ```
 *
 * Stored on [tv.own.owntv.core.database.entity.ChannelEntity.drmConfig] (and the movie/episode twins)
 * as a small JSON object — unlike [StreamHeaders] this is structured rather than a header list, and it
 * has to survive round-tripping a URL that may itself contain colons and newline-hostile characters.
 *
 * **Nothing here costs a licence.** On Android the Widevine CDM is part of the OS; the app only has to
 * tell ExoPlayer which scheme to use and where the licence server is. What the *device* provides is a
 * security level (L1 hardware / L3 software), which decides whether the provider will serve HD.
 */
data class DrmConfig(
    val scheme: Scheme,
    /** Licence server URL. An `http(s)` URL, or empty when [clearKeys] carries the keys inline. */
    val licenseUrl: String,
    /** Extra headers for the LICENCE request, which is a separate call from the manifest's. */
    val headers: Map<String, String> = emptyMap(),
    /**
     * ClearKey keys the playlist gives inline, lowercase hex key ID → lowercase hex key. Non-empty
     * means there is no licence server: the player answers the CDM's key request from these itself.
     */
    val clearKeys: Map<String, String> = emptyMap(),
) {
    enum class Scheme(val key: String) {
        WIDEVINE("widevine"),
        CLEARKEY("clearkey"),

        /**
         * Apple's DRM. No Android device has a FairPlay CDM, so nothing can decrypt it; the scheme is
         * stored only so the player can say exactly that instead of failing like a broken stream.
         */
        FAIRPLAY("fairplay"),
    }

    companion object {

        /** JSON object → string, or null when there is nothing to store. */
        fun encode(config: DrmConfig?): String? {
            val c = config ?: return null
            return JSONObject().apply {
                put(KEY_SCHEME, c.scheme.key)
                put(KEY_LICENSE, c.licenseUrl)
                if (c.headers.isNotEmpty()) put(KEY_HEADERS, JSONObject(c.headers))
                if (c.clearKeys.isNotEmpty()) put(KEY_CLEAR_KEYS, JSONObject(c.clearKeys))
            }.toString()
        }

        /**
         * Stored string → config. Total: a malformed or unknown-scheme blob reads as "no DRM" rather
         * than throwing, because a row we cannot interpret must degrade to the pre-DRM behaviour, not
         * break the whole playlist load.
         */
        fun decode(serialized: String?): DrmConfig? {
            val text = serialized?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                val o = JSONObject(text)
                val scheme = Scheme.entries.firstOrNull { it.key == o.optString(KEY_SCHEME) } ?: return null
                val clearKeys = o.optJSONObject(KEY_CLEAR_KEYS)?.let { k ->
                    k.keys().asSequence().associateWith { k.optString(it) }
                }.orEmpty()
                if (scheme != Scheme.CLEARKEY && clearKeys.isNotEmpty()) return null
                if (clearKeys.any { (kid, key) -> !isHex128(kid) || !isHex128(key) }) return null
                val license = o.optString(KEY_LICENSE)
                if (scheme == Scheme.FAIRPLAY) return DrmConfig(scheme, licenseUrl = "")
                if (license.isBlank() && clearKeys.isEmpty()) return null
                val headers = o.optJSONObject(KEY_HEADERS)?.let { h ->
                    val out = LinkedHashMap<String, String>(4)
                    h.keys().forEach { k ->
                        val name = StreamHeaders.canonicalName(k) ?: return@forEach
                        h.optString(k).takeIf { it.isNotBlank() }?.let { out[name] = it }
                    }
                    out
                } ?: emptyMap()
                DrmConfig(scheme, license, headers, clearKeys)
            }.getOrNull()
        }

        /**
         * The `inputstream.adaptive.*` properties of ONE playlist entry → a config, or null when the
         * entry declares no DRM or declares one we cannot honour. Keys arrive lowercased.
         *
         * Deliberately conservative — a config we cannot fully honour is dropped rather than stored
         * half-right, because a broken licence request fails in a way the user cannot diagnose, while
         * a missing config fails exactly as it does today:
         *
         *  - Only Widevine and ClearKey. PlayReady is untestable here and nobody has asked.
         *  - FairPlay (`com.apple.fps…`, `fairplay`): inline `kid:key` or JWK keys under it are the same
         *    key material as ClearKey and are stored as ClearKey. Anything else is real FairPlay, which
         *    no Android device can decrypt; it is stored as [Scheme.FAIRPLAY] so the player names it.
         *  - An `http(s)` licence URL, or — ClearKey only — the keys inline, as Kodi allows: hex
         *    `kid:key` pairs separated by commas, or a JWK set `{"keys":[{"kty":"oct","kid":…,"k":…}]}`.
         *    Every key must be a 128-bit one; a single malformed pair drops the whole declaration.
         *  - Only the plain passthrough request/response templates. Kodi's `license_key` may carry
         *    `url|headers|postDataTemplate|responseTemplate`; anything but an empty or `R{SSM}`/`B`
         *    template means the portal expects a request body we do not build.
         *
         * Newer playlists write the same thing the way inputstream.adaptive 21 documents it, and those
         * are read first: `drm_legacy=<key system>|<licence URL or kid:key pairs>|<headers>`, and
         * `drm={"<key system>":{"license":{"server_url":…,"req_headers":…,"keyids":{kid:key}}}}`. The
         * same rules hold: one we cannot fully honour falls back to the older lines, else to nothing.
         */
        fun fromKodiProps(props: Map<String, String>): DrmConfig? {
            props[PROP_DRM]?.let(::fromDrmJson)?.let { return it }
            props[PROP_DRM_LEGACY]?.let(::fromDrmLegacy)?.let { return it }
            val scheme = schemeOf(props[PROP_LICENSE_TYPE].orEmpty()) ?: return null
            val raw = props[PROP_LICENSE_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            if (scheme == Scheme.FAIRPLAY) {
                raw?.let(::inlineClearKeys)?.let { return DrmConfig(Scheme.CLEARKEY, licenseUrl = "", clearKeys = it) }
                return DrmConfig(Scheme.FAIRPLAY, licenseUrl = "")
            }
            if (raw == null) return null
            if (scheme == Scheme.CLEARKEY) {
                inlineClearKeys(raw)?.let { return DrmConfig(scheme, licenseUrl = "", clearKeys = it) }
            }
            // `|` splits the Kodi licence spec, but an unsplit value is just the URL.
            val parts = raw.split('|')
            val url = parts[0].trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return null
            }
            if (parts.size > 2 && parts.drop(2).any { !isPassthroughTemplate(it) }) return null
            val headers = parts.getOrNull(1)?.let { parseLicenseHeaders(it) } ?: emptyMap()
            return DrmConfig(scheme, url, headers)
        }

        private fun schemeOf(keySystem: String): Scheme? {
            val type = keySystem.lowercase()
            return when {
                type.contains("widevine") -> Scheme.WIDEVINE
                type.contains("clearkey") -> Scheme.CLEARKEY
                type.contains("fairplay") || type.contains("com.apple.fps") -> Scheme.FAIRPLAY
                else -> null
            }
        }

        /**
         * A key system with its licence [target] — an `http(s)` URL, or inline keys — as a config.
         * FairPlay with inline keys is ClearKey key material (see [fromKodiProps]).
         */
        private fun configFor(scheme: Scheme, target: String, headers: Map<String, String>): DrmConfig? {
            if (scheme == Scheme.FAIRPLAY) {
                inlineClearKeys(target)?.let { return DrmConfig(Scheme.CLEARKEY, licenseUrl = "", clearKeys = it) }
                return DrmConfig(Scheme.FAIRPLAY, licenseUrl = "")
            }
            if (scheme == Scheme.CLEARKEY) {
                inlineClearKeys(target)?.let { return DrmConfig(scheme, licenseUrl = "", clearKeys = it) }
            }
            if (!target.startsWith("http://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true)) return null
            return DrmConfig(scheme, target, headers)
        }

        /** `org.w3.clearkey|kid:key,kid:key` or `com.widevine.alpha|https://host/licence|Header=Value&…`. */
        private fun fromDrmLegacy(value: String): DrmConfig? {
            val parts = value.trim().split('|')
            if (parts.size > 3) return null
            val scheme = schemeOf(parts[0].trim()) ?: return null
            val target = parts.getOrNull(1)?.trim().orEmpty()
            val headers = parts.getOrNull(2)?.let(::parseLicenseHeaders).orEmpty()
            return configFor(scheme, target, headers)
        }

        /**
         * `{"<key system>":{"license":{…}}, …}`: the key systems are tried in their own `priority`
         * order (1 first), else as written, as Kodi does; the first one we can honour wins.
         */
        private fun fromDrmJson(value: String): DrmConfig? {
            val root = runCatching { JSONObject(value.trim()) }.getOrNull() ?: return null
            val systems = root.keys().asSequence().toList().mapIndexedNotNull { index, name ->
                val entry = root.optJSONObject(name) ?: return@mapIndexedNotNull null
                Triple(name, entry, entry.optInt("priority", Int.MAX_VALUE) to index)
            }.sortedWith(compareBy({ it.third.first }, { it.third.second }))
            for ((name, entry, _) in systems) {
                val scheme = schemeOf(name) ?: continue
                drmJsonEntry(scheme, entry)?.let { return it }
            }
            return null
        }

        private fun drmJsonEntry(scheme: Scheme, entry: JSONObject): DrmConfig? {
            val license = entry.optJSONObject("license")
            if (license == null) return if (scheme == Scheme.FAIRPLAY) DrmConfig(Scheme.FAIRPLAY, licenseUrl = "") else null
            // A request body, query parameters or a wrapped answer we would have to build or unwrap:
            // the portal expects more than a plain licence request, as with the older templates.
            if (license.optString("req_data").let { it.isNotBlank() && !isPassthroughTemplate(it) && !isPassthroughTemplate(base64Text(it)) }) return null
            if (license.optString("req_params").isNotBlank() || license.optString("unwrapper").isNotBlank()) return null
            val keys = license.optJSONObject("keyids")?.let { k ->
                k.keys().asSequence().joinToString(",") { kid -> "$kid:${k.optString(kid)}" }
            }
            if (!keys.isNullOrEmpty() && (scheme == Scheme.CLEARKEY || scheme == Scheme.FAIRPLAY)) {
                return configFor(scheme, keys, emptyMap())
            }
            // `Key=Value&…` as in the older spec, or an object of header names to values.
            val headerObject = license.optJSONObject("req_headers")
            val headers = if (headerObject != null) {
                buildMap {
                    headerObject.keys().forEach { k ->
                        val name = StreamHeaders.canonicalName(k) ?: return@forEach
                        headerObject.optString(k).trim().takeIf { it.isNotEmpty() }?.let { put(name, it) }
                    }
                }
            } else {
                parseLicenseHeaders(license.optString("req_headers"))
            }
            return configFor(scheme, license.optString("server_url").trim(), headers)
        }

        private fun base64Text(value: String): String =
            runCatching { String(java.util.Base64.getDecoder().decode(value.trim()), Charsets.UTF_8) }.getOrDefault("")

        /**
         * Inline ClearKey keys → hex key ID to hex key, or null when [raw] is not inline key data
         * (a licence URL) or any key in it is malformed. Key IDs written as a UUID lose their dashes.
         */
        private fun inlineClearKeys(raw: String): Map<String, String>? {
            val pairs = if (raw.startsWith("{")) {
                val keys = runCatching { JSONObject(raw).getJSONArray("keys") }.getOrNull() ?: return null
                (0 until keys.length()).map { i ->
                    val jwk = keys.optJSONObject(i) ?: return null
                    val kid = base64UrlToHex(jwk.optString("kid")) ?: return null
                    val key = base64UrlToHex(jwk.optString("k")) ?: return null
                    kid to key
                }
            } else {
                raw.split(',').map { pair ->
                    val colon = pair.indexOf(':')
                    if (colon <= 0) return null
                    pair.substring(0, colon).trim().replace("-", "").lowercase() to
                        pair.substring(colon + 1).trim().lowercase()
                }
            }
            if (pairs.isEmpty() || pairs.any { (kid, key) -> !isHex128(kid) || !isHex128(key) }) return null
            return pairs.toMap()
        }

        private fun base64UrlToHex(value: String): String? {
            val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(value.trimEnd('=')) }.getOrNull()
                ?: return null
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun isHex128(value: String): Boolean =
            value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

        /** `Key=Value&Key=Value`, percent-encoded — the same encoding as `…stream_headers`. */
        private fun parseLicenseHeaders(raw: String): Map<String, String> {
            val text = raw.trim()
            if (text.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>(4)
            text.split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val name = StreamHeaders.canonicalName(pair.substring(0, eq)) ?: return@forEach
                val value = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                    .getOrDefault(pair.substring(eq + 1))
                    .trim()
                if (value.isNotEmpty()) out[name] = value
            }
            return out
        }

        /** An empty template, or one of the two "send it unchanged" markers Kodi documents. */
        private fun isPassthroughTemplate(template: String): Boolean {
            val t = template.trim()
            return t.isEmpty() || t.equals("R{SSM}", ignoreCase = true) || t.equals("B", ignoreCase = true) ||
                t.equals("R", ignoreCase = true) || t.equals("b{SSM}", ignoreCase = true)
        }

        /** True for a KODIPROP key this class consumes — the parser only accumulates these. */
        fun isDrmProp(key: String): Boolean = key in DRM_PROPS

        const val PROP_LICENSE_TYPE = "license_type"
        const val PROP_LICENSE_KEY = "license_key"
        const val PROP_DRM = "drm"
        const val PROP_DRM_LEGACY = "drm_legacy"

        private val DRM_PROPS = setOf(PROP_LICENSE_TYPE, PROP_LICENSE_KEY, PROP_DRM, PROP_DRM_LEGACY)

        private const val KEY_SCHEME = "scheme"
        private const val KEY_LICENSE = "license"
        private const val KEY_HEADERS = "headers"
        private const val KEY_CLEAR_KEYS = "clearKeys"
    }
}
