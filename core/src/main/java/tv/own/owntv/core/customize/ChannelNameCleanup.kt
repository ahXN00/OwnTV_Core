package tv.own.owntv.core.customize

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.live.ProviderTags
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-playlist "Clean channel names" option ([SourceEntity.cleanChannelNames]).
 *
 * Applied at sync time, so every screen, search and the sort order see the clean name without a
 * per-row lookup. The provider's own name is kept in [ChannelEntity.providerName] whenever the
 * cleanup changed it: guide matching reads that one ([ChannelEntity.matchName]), so a cleaner
 * name never makes the guide worse. Nothing keyed on a channel moves either: the M3U stable key is
 * built from the raw entry name before this runs, and Xtream/Stalker key on the provider's id.
 * Turning the option off re-syncs the raw names, and the changed name re-upserts the row.
 *
 * The rules are deliberately narrower than the one-shot bulk "Auto cleanup" in Customize, because
 * these run unattended on every channel at every refresh:
 *  - a country/provider prefix: the one Live TV already hides ([ProviderTags]: `DE|`, and `UK:`
 *    only after a real country code), plus the bulk cleanup's other tagged forms `|UK|`, `[UK]`
 *    and `(UK)` ([RenameRules.COUNTRY_TAG_PATTERN]). Plain labels such as `VIP:` or `TV:` stay;
 *  - quality tags only at the END of a name (`CNN HD`, `Nature [4K]`), never in the middle
 *    (`4K Nature Channel` keeps its 4K), and never bare numbers (`Channel 720`);
 *  - the user's own words, `;`-separated, removed from either end as whole words.
 * A name that would come out blank (a name that is only a prefix) stays as the provider wrote it.
 */
class ChannelNameCleanup private constructor(private val rules: List<RenameRules.Rule>) {

    /** The cleaned name, or [raw] untouched when no rule applies or the result would be blank. */
    fun clean(raw: String): String {
        val cleaned = RenameRules.applyRaw(ProviderTags.withoutCountryPrefix(raw), rules, OPTIONS).trim()
        return if (cleaned.isEmpty() || cleaned == raw.trim()) raw else cleaned
    }

    /** [channel] with its display name cleaned and the provider's name kept for guide matching. */
    fun applyTo(channel: ChannelEntity): ChannelEntity {
        val cleaned = clean(channel.name)
        return if (cleaned == channel.name) channel else channel.copy(name = cleaned, providerName = channel.name)
    }

    companion object {
        /** Edge separators a removed tag leaves behind: "UK | CNN" must not become "| CNN". */
        private const val SEPARATORS = "[\\s|:\\-–—·]*"

        /** The bracketed country forms [ProviderTags] does not take off; see the class KDoc. */
        private const val PREFIX_PATTERN = "${RenameRules.COUNTRY_TAG_PATTERN}$SEPARATORS"

        /** Resolution and codec tags. Bare numbers are left out: "Channel 720" is a name. */
        private const val QUALITY_TOKENS = "4K|8K|UHD|FHD|QHD|HD|SD|HDR|HEVC|H265|H264|2160P|1080P|1080I|720P|576P|480P"

        /** One or more trailing quality tags ("CNN FHD HEVC"), each optionally bracketed. */
        private const val QUALITY_SUFFIX_PATTERN =
            "(?:$SEPARATORS(?<![\\p{L}\\p{N}])[\\[(]?(?:$QUALITY_TOKENS)[\\])]?)+$SEPARATORS$"

        /** Matching is case-insensitive; leftovers are handled by the patterns themselves, so the
         *  engine's edge trim (which would also eat a name's own trailing "." or "-") stays off. */
        private val OPTIONS = RenameRules.Options(trimLeftovers = false, ignoreCase = true)

        /** One compiled cleanup per distinct custom text; a sync builds every channel through it. */
        private const val CACHE_MAX = 32
        private val cache = ConcurrentHashMap<String, ChannelNameCleanup>()

        /** The cleanup [source] asked for, or null when its "Clean channel names" is off. */
        fun forSource(source: SourceEntity): ChannelNameCleanup? =
            if (source.cleanChannelNames) of(source.channelNameStrip) else null

        /** The cleanup with the built-in rules plus [customText] (`;`-separated words to remove). */
        fun of(customText: String?): ChannelNameCleanup {
            val key = customText?.trim().orEmpty()
            if (cache.size > CACHE_MAX) cache.clear()
            return cache.getOrPut(key) { ChannelNameCleanup(rulesFor(key)) }
        }

        /** Sync-time wrapper: [channel] cleaned per [source]'s option, or unchanged when it is off. */
        fun apply(source: SourceEntity, channel: ChannelEntity): ChannelEntity =
            forSource(source)?.applyTo(channel) ?: channel

        private fun rulesFor(customText: String): List<RenameRules.Rule> {
            val words = customText.split(RenameRules.SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("|") { Regex.escape(it) }
            val custom = if (words.isEmpty()) emptyList() else listOf(
                remove("^$SEPARATORS(?:$words)(?![\\p{L}\\p{N}])$SEPARATORS"),
                remove("$SEPARATORS(?<![\\p{L}\\p{N}])(?:$words)$SEPARATORS$"),
            )
            return custom + remove(PREFIX_PATTERN) + remove(QUALITY_SUFFIX_PATTERN)
        }

        private fun remove(pattern: String) =
            RenameRules.Rule(RenameRules.Action.REMOVE, RenameRules.Placement.PREFIX, pattern = pattern)
    }
}
