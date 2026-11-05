package tv.own.owntv.core.companion

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Editing one playlist from a browser ([CompanionMode.EDIT_SOURCE]).
 *
 * The page travels over plain HTTP on the home network, so it is filled only with details that are
 * safe to show there: the name, the server or portal address, the login name, the user agent and the
 * guide address. A password, a MAC address, the Stalker device identity and an M3U address (which
 * often carries the account in its query) are never sent; on the page those fields start empty, and a
 * field left empty keeps what the TV has. [merge] turns a submission back into the playlist's full
 * details, which pre-fill the TV's own edit form — the user still presses Save there.
 */
internal object CompanionSourceEdit {

    /** What the edit page shows for [source]. */
    fun prefill(source: SourceEntity): CompanionPayload = when (source.type) {
        SourceType.M3U -> CompanionPayload(
            type = SourceType.M3U,
            name = source.name,
            userAgent = source.userAgent.orEmpty(),
            epgUrl = source.epgUrl.orEmpty(),
        )
        SourceType.STALKER -> CompanionPayload(
            type = SourceType.STALKER,
            name = source.name,
            portalUrl = source.url,
            user = source.username.orEmpty(),
            userAgent = source.userAgent.orEmpty(),
        )
        SourceType.XTREAM, SourceType.LOCAL_BACKUP -> CompanionPayload(
            type = SourceType.XTREAM,
            name = source.name,
            server = source.url,
            user = source.username.orEmpty(),
            userAgent = source.userAgent.orEmpty(),
            epgUrl = source.epgUrl.orEmpty(),
        )
    }

    /**
     * [sent] with every empty required or secret field taken from [source]. The user agent, the guide
     * address and a Stalker login are optional, so an empty one stays empty: that is how the page
     * clears them.
     */
    fun merge(source: SourceEntity, sent: CompanionPayload): CompanionPayload {
        fun keep(value: String, current: String?) = value.ifBlank { current.orEmpty() }
        val name = keep(sent.name, source.name)
        return when (source.type) {
            SourceType.M3U -> CompanionPayload(
                type = SourceType.M3U,
                name = name,
                server = keep(sent.server, source.url),
                userAgent = sent.userAgent,
                epgUrl = sent.epgUrl,
            )
            SourceType.STALKER -> CompanionPayload(
                type = SourceType.STALKER,
                name = name,
                portalUrl = keep(sent.portalUrl, source.url),
                mac = keep(sent.mac, source.mac),
                user = sent.user,
                pass = keep(sent.pass, source.password),
                serialNumber = keep(sent.serialNumber, source.stalkerSerialNumber),
                deviceId = keep(sent.deviceId, source.stalkerDeviceId),
                deviceId2 = keep(sent.deviceId2, source.stalkerDeviceId2),
                signature = keep(sent.signature, source.stalkerSignature),
                userAgent = sent.userAgent,
            )
            SourceType.XTREAM, SourceType.LOCAL_BACKUP -> CompanionPayload(
                type = SourceType.XTREAM,
                name = name,
                server = keep(sent.server, source.url),
                user = keep(sent.user, source.username),
                pass = keep(sent.pass, source.password),
                userAgent = sent.userAgent,
                epgUrl = sent.epgUrl,
            )
        }
    }
}
