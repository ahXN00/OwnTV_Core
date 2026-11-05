package tv.own.owntv.core.stalker

import tv.own.owntv.core.database.entity.SourceEntity

/** One authoritative mapping so every sync/playback re-auth path carries the same device identity. */
fun SourceEntity.stalkerCredentials(canonicalMac: String): StalkerCredentials = StalkerCredentials(
    sourceId = id,
    portalUrl = url,
    mac = canonicalMac,
    userAgent = userAgent,
    deviceIdentity = StalkerDeviceIdentity(
        serialNumber = stalkerSerialNumber,
        deviceId = stalkerDeviceId,
        deviceId2 = stalkerDeviceId2,
        signature = stalkerSignature,
    ),
    login = username,
    password = password,
)
