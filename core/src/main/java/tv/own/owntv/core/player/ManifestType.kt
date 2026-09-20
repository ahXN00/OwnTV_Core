package tv.own.owntv.core.player

/**
 * The streaming container a playlist entry declares for itself, from Kodi's
 * `#KODIPROP:inputstream.adaptive.manifest_type` property.
 *
 * A stream URL is not always self-describing. The JioTV-Go family of proxies publishes protected
 * channels as `https://host/live/mpd/173` — no extension at all — and only redirects to the real
 * `…/render.mpd` once the request is made. Media3 picks its media source from the URL *before* that
 * redirect, so an undeclared DASH channel is handed to the progressive extractor, which sniffs an XML
 * manifest and fails with `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`. Reading the declaration is what
 * makes the FIRST attempt correct; `LivePreviewEngine`'s response sniff is what rescues the entries
 * (and the whole Xtream/Stalker world) that declare nothing at all.
 *
 * Stored on [tv.own.owntv.core.database.entity.ChannelEntity.manifestType] (and the movie/episode
 * twins) as [key], which is also exactly the token Kodi uses — so the stored value round-trips a
 * playlist verbatim and needs no separate mapping table.
 *
 * Unlike [tv.own.owntv.core.drm.DrmConfig] this is **not** a DRM concept: an unprotected entry may
 * declare `mpd` just as readily, and gets the same routing.
 */
enum class ManifestType(val key: String) {
    /** MPEG-DASH. `media3-exoplayer-dash` is on the classpath, so this one is honoured. */
    MPD("mpd"),

    /** HLS. Already the default route for `.m3u8` URLs; a declaration covers the extensionless ones. */
    HLS("hls"),

    /**
     * Microsoft Smooth Streaming. Stored so a re-sync never loses what the playlist said, but
     * **deliberately not routed**: `media3-exoplayer-smoothstreaming` is not a dependency, so there is
     * nothing to route it to. An `ism` entry keeps exactly the behaviour it has today rather than being
     * handed to a source that cannot open it. Add the dependency here first if that ever changes.
     */
    ISM("ism"),
    ;

    companion object {

        /** Stored string → type; null for absent, blank or unrecognised. Total, like `DrmConfig.decode`. */
        fun decode(serialized: String?): ManifestType? {
            val text = serialized?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.key.equals(text, ignoreCase = true) }
        }

        /** Type → stored string, or null when there is nothing to store. */
        fun encode(type: ManifestType?): String? = type?.key

        /**
         * The `manifest_type` property of ONE playlist entry → a type, or null when the entry declares
         * none or declares one we do not know. Keys arrive lowercased, matching [isManifestProp].
         *
         * Both the `inputstream.adaptive.` and the older bare `inputstream.` spellings reduce to the
         * same short key in the parser, so both are read here — playlists in the wild mix them.
         */
        fun fromKodiProps(props: Map<String, String>): ManifestType? =
            decode(props[PROP_MANIFEST_TYPE])

        /** True for a `#KODIPROP` key this class consumes — the parser only accumulates these. */
        fun isManifestProp(key: String): Boolean = key == PROP_MANIFEST_TYPE

        const val PROP_MANIFEST_TYPE = "manifest_type"
    }
}
