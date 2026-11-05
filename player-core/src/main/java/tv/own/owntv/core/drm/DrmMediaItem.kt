package tv.own.owntv.core.drm

// Lives in :player-core, in DrmConfig's own package so no call site changes: `DrmConfig` itself is
// a plain JSON model and belongs in :core, but this one extension speaks Media3, and :core carries no
// player dependency.

/**
 * The Media3 licence configuration for this item. `DefaultMediaSourceFactory` reads it off the
 * `MediaItem` and builds the `DrmSessionManager` itself — the CDM comes from the OS, so there is
 * nothing to register and nothing to license.
 *
 * [multiSession] must be true for live: a live stream rotates its content key, and a single session
 * plays for a few minutes and then dies. It stays false for a film, whose key never changes.
 *
 * Inline ClearKey keys have no licence server, so they ride on the item as a `data:` licence URI
 * holding the JWK set the CDM expects back; [InlineClearKeyDrmProvider] answers from it locally.
 */
fun DrmConfig.toMediaDrmConfiguration(multiSession: Boolean): androidx.media3.common.MediaItem.DrmConfiguration {
    val uuid = when (scheme) {
        DrmConfig.Scheme.WIDEVINE -> androidx.media3.common.C.WIDEVINE_UUID
        DrmConfig.Scheme.CLEARKEY -> androidx.media3.common.C.CLEARKEY_UUID
        // Both engines refuse a FairPlay item before building one (no Android CDM); reaching here is a bug.
        DrmConfig.Scheme.FAIRPLAY -> error("FairPlay has no Android CDM")
    }
    val licenseUri = if (clearKeys.isEmpty()) licenseUrl else {
        val jwks = org.json.JSONObject().apply {
            put("keys", org.json.JSONArray(clearKeys.map { (kid, key) ->
                org.json.JSONObject().put("kty", "oct").put("kid", hexToBase64Url(kid)).put("k", hexToBase64Url(key))
            }))
            put("type", "temporary")
        }.toString()
        INLINE_KEYS_PREFIX + android.util.Base64.encodeToString(jwks.toByteArray(), android.util.Base64.NO_WRAP)
    }
    return androidx.media3.common.MediaItem.DrmConfiguration.Builder(uuid)
        .setLicenseUri(licenseUri)
        .setLicenseRequestHeaders(headers)
        .setMultiSession(multiSession)
        .build()
}

/**
 * Set on every ExoPlayer media-source factory that can carry a protected item. A licence-server item
 * goes to Media3's own provider exactly as before; an inline-key item (see [toMediaDrmConfiguration])
 * gets a ClearKey session whose key request is answered from the item itself, with no network call.
 */
object InlineClearKeyDrmProvider : androidx.media3.exoplayer.drm.DrmSessionManagerProvider {
    private val licenceServer = androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider()

    override fun get(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.drm.DrmSessionManager {
        val drm = mediaItem.localConfiguration?.drmConfiguration
        val uri = drm?.licenseUri?.toString()
        if (uri == null || !uri.startsWith(INLINE_KEYS_PREFIX)) return licenceServer.get(mediaItem)
        val jwks = android.util.Base64.decode(uri.removePrefix(INLINE_KEYS_PREFIX), android.util.Base64.NO_WRAP)
        return androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(drm.scheme, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(drm.multiSession)
            .build(androidx.media3.exoplayer.drm.LocalMediaDrmCallback(jwks))
    }
}

private const val INLINE_KEYS_PREFIX = "data:application/json;base64,"

private fun hexToBase64Url(hex: String): String {
    val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    return android.util.Base64.encodeToString(
        bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
    )
}
