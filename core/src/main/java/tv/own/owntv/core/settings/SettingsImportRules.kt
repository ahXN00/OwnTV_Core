package tv.own.owntv.core.settings

import tv.own.owntv.core.player.MiniPlayerPosition
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsRefreshPolicy

/**
 * What a restored setting may be. The Settings screens can only ever write values in range, but a
 * backup is a file — written by another build, or edited by hand — and its values used to be applied
 * unchecked. A seek step of 0 broke seeking outright. Null means "skip it, keep this device's value".
 *
 * Keyed by preference name, the same name the backup file uses.
 */
internal object SettingsImportRules {

    /** The player's own audio-delay range (its HUD stepper stops at ±5 s). */
    const val AUDIO_DELAY_LIMIT_MS = 5_000

    /** Sanity bounds for an imported subtitle scale — far wider than any stepper, narrow enough that a
     *  corrupt value cannot draw subtitles invisibly small or larger than the screen. */
    const val SUB_SCALE_MIN = 0.25
    const val SUB_SCALE_MAX = 4.0

    fun int(name: String, v: Int): Int? = when (name) {
        "seek_step_sec" -> v.takeIf { it in SeekSteps.SEEK_CHOICES }
        "live_rewind_step_sec" -> v.takeIf { it in SeekSteps.LIVE_REWIND_CHOICES }
        "audio_delay_ms" -> v.coerceIn(-AUDIO_DELAY_LIMIT_MS, AUDIO_DELAY_LIMIT_MS)
        else -> v
    }

    /** Named presets must name one this build has; the readers would fall back anyway, but silently. */
    fun string(name: String, v: String): String? = when (name) {
        "live_latency_mode" -> v.takeIf { value -> LiveLatency.entries.any { it.name == value } }
        "mini_player_position" -> v.takeIf { value -> MiniPlayerPosition.entries.any { it.name == value } }
        "player_hold_ok" -> v.takeIf { value -> PlayerHoldOkAction.entries.any { it.name == value } }
        "player_short_ok" -> v.takeIf { value -> PlayerShortOkAction.entries.any { it.name == value } }
        else -> v
    }

    /** The subtitle scales: a zero, negative or non-finite multiplier draws nothing (or everything). */
    fun float(v: Double): Float? =
        v.takeIf { it.isFinite() && it > 0 }?.coerceIn(SUB_SCALE_MIN, SUB_SCALE_MAX)?.toFloat()

    // --- Settings → Sports (per-profile `sports_config_<id>`, read through these on every load) ---

    /** Live refresh: 30, 60, 90 or 120 s; anything else is the default. */
    fun sportsLiveRefresh(v: Int): Int = SportsRefreshPolicy.liveSeconds(v)

    fun sportsReminderLead(v: Int): Int =
        v.takeIf { it in SportsConfig.REMINDER_LEAD_CHOICES } ?: SportsConfig.DEFAULT_REMINDER_LEAD_MINUTES

    /** Calendar refresh: one of the stepper's days; anything else is the default. */
    fun sportsCalendarRefresh(v: Int): Int =
        v.takeIf { it in SportsConfig.CALENDAR_REFRESH_CHOICES } ?: SportsConfig.DEFAULT_CALENDAR_REFRESH_DAYS

    /** Only leagues this build can fetch; one from a newer build's catalogue is dropped. */
    fun sportsLeagues(keys: List<String>): List<String> = keys.filter { SportsCatalog.league(it) != null }.distinct()

    /**
     * Play counts: positive, named, and only the [SportsConfig.MAX_PLAYED] most played — a backup
     * cannot grow the blob without bound.
     */
    fun sportsPlayed(map: Map<String, tv.own.owntv.core.sports.Played>): Map<String, tv.own.owntv.core.sports.Played> =
        map.filter { (k, p) -> k.isNotBlank() && p.count > 0 && p.name.isNotBlank() }
            .entries.sortedByDescending { it.value.count }.take(SportsConfig.MAX_PLAYED)
            .associate { it.key to it.value }

    /** Playlist ids: positive only. */
    fun sportsSources(ids: List<Long>): Set<Long> = ids.filter { it > 0 }.toSet()

    /** Language and country codes: letters only, of the given [length], upper-cased. */
    fun sportsCodes(codes: List<String>, length: Int): Set<String> =
        codes.map { it.trim().uppercase() }.filter { c -> c.length == length && c.all { it in 'A'..'Z' } }.toSet()
}
