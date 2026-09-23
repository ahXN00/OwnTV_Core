package tv.own.owntv.core.settings

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.player.EnginePreference
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * The v44 per-playlist overrides (owner decision 12), read the same way everywhere.
 *
 * Every column is nullable and `null` means "follow the global setting", so each function here returns
 * null for "no override" and the caller falls back to its own global value. An unreadable stored value
 * (a name from a newer build, say) also reads as no override rather than as a guess.
 */
object SourceOverrides {

    /** [source]'s own catch-up zone, or null when it follows the global one. */
    fun catchupTimeZoneOf(source: SourceEntity?): TimeZone? {
        val mode = source?.catchupTimezone
            ?.let { runCatching { SettingsRepository.CatchupTimezone.valueOf(it) }.getOrNull() } ?: return null
        return when (mode) {
            SettingsRepository.CatchupTimezone.DEVICE -> TimeZone.getDefault()
            SettingsRepository.CatchupTimezone.MANUAL -> SimpleTimeZone((source.catchupOffsetMin ?: 0) * 60_000, "catchup")
        }
    }

    /** [source]'s own Movies & Series engine, or null when it follows the global one. */
    fun vodEngineOf(source: SourceEntity?): EnginePreference? =
        source?.vodEnginePreference?.let { runCatching { EnginePreference.valueOf(it) }.getOrNull() }

    /** [source]'s own "Give up after" in seconds (0 = never), or null when it follows the global one. */
    fun liveTuneTimeoutSecsOf(source: SourceEntity?): Int? =
        source?.liveTuneTimeoutSecs?.coerceIn(0, MAX_TUNE_TIMEOUT_SECS)

    /**
     * [itemHeaders] (a stream's own `Key: Value` block) with [source]'s Referer added. A Referer the
     * stream declares itself wins — the playlist entry is the more specific statement.
     */
    fun headersWithReferer(itemHeaders: String?, source: SourceEntity?): String? {
        val referer = source?.httpReferer?.trim()?.takeIf { it.isNotEmpty() } ?: return itemHeaders
        val headers = StreamHeaders.decode(itemHeaders)
        if (headers.keys.any { it.equals("Referer", ignoreCase = true) }) return itemHeaders
        return StreamHeaders.encode(headers + ("Referer" to referer))
    }

    /** Same upper bound as the global "Give up after" (see `SettingsRepository.liveTuneTimeoutSecs`). */
    const val MAX_TUNE_TIMEOUT_SECS = 60
}
