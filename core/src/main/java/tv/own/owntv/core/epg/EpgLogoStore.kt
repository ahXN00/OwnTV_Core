package tv.own.owntv.core.epg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.EpgMatchResolver
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * "Use this guide's channel logos" (Settings → EPG Sources → add/edit a feed): channel logos taken from
 * an XMLTV feed's `<channel><icon src>` instead of the ones the playlist carries. Enabled **per EPG
 * source**, so one feed can supply logos while another only supplies programmes.
 *
 * Kept as a process-wide display override rather than being written into `channels.logoUrl` on purpose:
 * the provider logo stays untouched in the database, so turning a feed's toggle off restores it instantly
 * and a catalog re-sync (which REPLACE-upserts every channel row) can't wipe the user's choice.
 *
 * A channel the user matched in Match EPG takes the logo of the guide channel it is matched **to**, not
 * of its own `tvg-id` — the same channel the guide shows for it. The active profile's Live TV matches are
 * followed for that.
 *
 * The map is Compose state, so flipping a toggle or finishing an EPG sync recomposes every logo on
 * screen. Nothing is loaded while no feed has it on — cold start stays free of EPG queries.
 */
object EpgLogoStore {

    /** Normalized EPG channel id → feed icon URL. Empty when no EPG source has logos enabled. */
    var icons by mutableStateOf<Map<String, String>>(emptyMap())
        internal set

    /** The active profile's Live TV matches; empty until [start] has read them. */
    internal var matches by mutableStateOf(EpgMatchResolver(emptyMap()))

    /** Follow the enabled EPG sources; while any is enabled, follow their stored feed icons and the matches. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, settings: SettingsRepository, epgDao: EpgDao, customize: CustomizationStore) {
        scope.launch {
            settings.epgUseLogos.collectLatest { sourceIds ->
                if (sourceIds.isEmpty()) {
                    icons = emptyMap()
                    return@collectLatest
                }
                epgDao.observeChannelIcons(sourceIds.toList()).collectLatest { rows ->
                    // Several enabled feeds can carry the same channel: first one wins, which is stable
                    // because the DAO returns rows in source order.
                    icons = rows.associate { it.epgChannelId to it.iconUrl }
                }
            }
        }
        scope.launch {
            settings.activeProfileId
                .flatMapLatest { profileId -> customize.observe(profileId, MediaType.LIVE) }
                .collectLatest { matches = it.epgMatchResolver }
        }
    }

    /**
     * Match EPG's "Include guide logos": turn "Use this guide's logos" on for every EPG source that has an
     * icon for one of [epgChannelIds], so each of those matched channels shows its guide logo. Never turns
     * anything off.
     */
    suspend fun includeLogosFor(settings: SettingsRepository, epgDao: EpgDao, epgChannelIds: Collection<String>) {
        val ids = epgChannelIds.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return
        // SQLite caps bound parameters; a bulk run can match thousands of channels.
        val sources = ids.chunked(500).flatMap { epgDao.sourcesWithIconsFor(it) }.toSet()
        sources.forEach { settings.setEpgUseLogos(it, true) }
    }

    /** The feed icon for [channel], or null when there is none / no feed has logos enabled. */
    fun iconFor(channel: ChannelEntity): String? {
        if (icons.isEmpty()) return null
        matches.epgIdFor(channel)?.let { id -> icons[id.trim().lowercase()]?.let { return it } }
        val own = channel.epgChannelId
        if (own.isNullOrBlank()) return null
        return icons[own.trim().lowercase()]
    }
}

/**
 * The logo to show for this channel: the EPG feed's icon when "Prefer EPG logos" is on and the feed has
 * one — for the guide channel it is matched to, else its own id — otherwise the provider's own logo. Use
 * this everywhere a channel logo is displayed or handed to the player — `logoUrl` alone always means
 * "what the playlist said".
 */
val ChannelEntity.displayLogoUrl: String?
    get() = EpgLogoStore.iconFor(this) ?: logoUrl
