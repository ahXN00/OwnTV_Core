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
 */
fun DrmConfig.toMediaDrmConfiguration(multiSession: Boolean): androidx.media3.common.MediaItem.DrmConfiguration {
    val uuid = when (scheme) {
        DrmConfig.Scheme.WIDEVINE -> androidx.media3.common.C.WIDEVINE_UUID
        DrmConfig.Scheme.CLEARKEY -> androidx.media3.common.C.CLEARKEY_UUID
    }
    return androidx.media3.common.MediaItem.DrmConfiguration.Builder(uuid)
        .setLicenseUri(licenseUrl)
        .setLicenseRequestHeaders(headers)
        .setMultiSession(multiSession)
        .build()
}
