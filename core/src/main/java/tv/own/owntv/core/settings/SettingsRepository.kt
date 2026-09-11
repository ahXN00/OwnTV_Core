package tv.own.owntv.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.util.Pin
import tv.own.owntv.core.model.ContentMenu
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.PopupFontScale
import tv.own.owntv.core.theme.PopupSizeScale
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.core.theme.UiZoom

/** Per-profile startup landing (Phase 3 / v4.0.0). LAST_CHANNEL also covers "auto-play my channel" since
 *  it's always the one you last watched. */
enum class StartupMode {
    HOME, LAST_CHANNEL, FAVORITES, SPECIFIC_CHANNEL
}

data class StartupChannelRef(
    val sourceId: Long,
    val remoteId: String?,
    val name: String,
    val itemId: Long,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("sourceId", sourceId)
        .putOpt("remoteId", remoteId)
        .put("name", name)
        .put("itemId", itemId)

    companion object {
        fun fromJson(raw: String?): StartupChannelRef? = runCatching {
            val o = org.json.JSONObject(raw ?: return null)
            StartupChannelRef(
                sourceId = o.getLong("sourceId").takeIf { it > 0L } ?: return null,
                remoteId = o.optString("remoteId").takeIf { it.isNotBlank() },
                name = o.getString("name").takeIf { it.isNotBlank() } ?: return null,
                itemId = o.optLong("itemId", -1L),
            )
        }.getOrNull()
    }
}

/**
 * Per-source playlist auto-refresh mode. The interval entries are **staleness thresholds**, not strict
 * timers: a source is refreshed when `now - lastSyncAt >= thresholdMs`. OFF disables auto-refresh;
 * STARTUP refreshes on cold app start only; interval modes are checked on cold start and on resume.
 * [thresholdMs] has a default of null so OFF/STARTUP can be declared without an explicit value.
 *
 * Anything longer than 12 hours is [MANUAL], where the user names the number of days — the fixed
 * 24h/48h/7-day entries it replaced are still understood on read (see [PlaylistRefresh.parse]) so an
 * existing selection or an old backup keeps working.
 */
enum class PlaylistAutoRefresh(val thresholdMs: Long? = null) {
    OFF,
    STARTUP,
    HOURS_6(6 * 3600_000L),
    HOURS_12(12 * 3600_000L),

    /** A whole number of days chosen by the user; the count lives in [PlaylistRefresh.manualDays]. */
    MANUAL;

    /** Interval (staleness-threshold) mode — checked on cold start AND on resume when threshold is exceeded. */
    val isInterval: Boolean get() = this != OFF && this != STARTUP
}

/**
 * A playlist's auto-refresh selection: the mode, plus the day count that only [PlaylistAutoRefresh.MANUAL]
 * uses. Stored as one string per source so nothing else — backup, restore, the companion form — has to
 * learn about a second value: `"OFF"`, `"HOURS_6"`, or `"MANUAL:14"`.
 */
data class PlaylistRefresh(
    val mode: PlaylistAutoRefresh = PlaylistAutoRefresh.OFF,
    val manualDays: Int = DEFAULT_MANUAL_DAYS,
) {
    /** How stale the source may get before it is refreshed; null when it never auto-refreshes on a timer. */
    val thresholdMs: Long?
        get() = if (mode == PlaylistAutoRefresh.MANUAL) {
            manualDays.coerceIn(MIN_MANUAL_DAYS, MAX_MANUAL_DAYS) * 24 * 3600_000L
        } else {
            mode.thresholdMs
        }

    fun serialize(): String =
        if (mode == PlaylistAutoRefresh.MANUAL) "${mode.name}$SEPARATOR$manualDays" else mode.name

    companion object {
        const val MIN_MANUAL_DAYS = 1
        const val MAX_MANUAL_DAYS = 99
        const val DEFAULT_MANUAL_DAYS = 7
        private const val SEPARATOR = ':'

        val OFF = PlaylistRefresh(PlaylistAutoRefresh.OFF)

        /**
         * Reads a stored value, translating the retired fixed entries into the day counts that mean the
         * same thing. Nothing rewrites the stored string for them — the translation happens on every read,
         * so a user who never opens the picker again keeps the schedule they chose, forever.
         */
        fun parse(raw: String?): PlaylistRefresh = when (val value = raw?.trim().orEmpty()) {
            "HOURS_24" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 1)
            "HOURS_48" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 2)
            "DAYS_7" -> PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 7)
            else -> {
                val mode = runCatching {
                    PlaylistAutoRefresh.valueOf(value.substringBefore(SEPARATOR))
                }.getOrDefault(PlaylistAutoRefresh.OFF)
                val days = value.substringAfter(SEPARATOR, "").toIntOrNull()
                    ?.coerceIn(MIN_MANUAL_DAYS, MAX_MANUAL_DAYS)
                    ?: DEFAULT_MANUAL_DAYS
                PlaylistRefresh(mode, days)
            }
        }
    }
}

/** Per-EPG-source auto-refresh mode. Same staleness-threshold semantics as [PlaylistAutoRefresh]. */
enum class EpgAutoRefresh(val thresholdMs: Long? = null) {
    OFF,
    STARTUP,
    HOURS_1(1 * 3600_000L),
    HOURS_3(3 * 3600_000L),
    HOURS_6(6 * 3600_000L),
    HOURS_12(12 * 3600_000L),
    HOURS_24(24 * 3600_000L),
    HOURS_48(48 * 3600_000L);

    val isInterval: Boolean get() = thresholdMs != null && this != STARTUP
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_settings")

/** CH+- key paging limits. Top-level so any caller (VM, UI) can reference them via the class. */
object ChNavLimits {
    /** Hard cap for the CH+- skip counts — protects against typos (e.g. 999999) overloading slow TVs. */
    const val HARD_MAX = 1000
    /** Above this value the settings UI shows an advisory warning (high skips overshoot short lists). */
    const val WARN_THRESHOLD = 50
    /** Default per-direction skip (single CH+/- press moves this many items). */
    const val DEFAULT_SKIP = 10
}

/** Seek/rewind step choices. Top-level so the settings UI and the defaults share one list. */
object SeekSteps {
    /** Rewind/forward inside a movie or episode — the player's long-standing 10 s. */
    const val DEFAULT_SEEK_STEP_SEC = 10
    val SEEK_CHOICES = listOf(5, 10, 15, 30, 60)

    /** Stepping through a live channel's catch-up archive — the long-standing 30 s. */
    const val DEFAULT_LIVE_REWIND_STEP_SEC = 30
    val LIVE_REWIND_CHOICES = listOf(10, 15, 30, 60, 120)
}

/**
 * Persists app-level preferences. Phase 1 only needs the theme selection; this will grow to hold
 * UI zoom, custom user-agent, refresh-on-start, etc. in later phases.
 */
class SettingsRepository(private val context: Context, private val localeStore: LocaleStore) {

    /**
     * Scope for the warm settings snapshots below. Lives as long as this singleton — deliberately never
     * cancelled, because the alternative is paying a DataStore read on every metadata resolve.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Result of importing the optional locale field without allowing bad backup data to abort the restore. */
    data class SettingsImportResult(
        val localePresent: Boolean = false,
        val localeTag: String? = null,
        val invalidLocale: Boolean = false,
    )

    /**
     * Every settings flow below is derived through this (audit ST2, step 2).
     *
     * DataStore emits the **whole** `Preferences` object to **every** collector on **every** write,
     * so without the `distinctUntilChanged` a single toggle re-ran ~100 `map { }` lambdas and pushed
     * ~100 "new" StateFlow values app-wide — each one a potential recomposition — even though only
     * one key had actually changed. One operator here stops that propagation for all of them at
     * once, which is why this is a helper rather than 74 hand-edited call sites.
     */
    private fun <T> prefsFlow(transform: (Preferences) -> T): Flow<T> =
        context.dataStore.data.map(transform).distinctUntilChanged()

    // Glass effect defaults: OFF (empty scope) — the glass look is strictly opt-in, the app looks
    // unchanged until the user enables it in Settings → Glass Effect. Alpha/blur defaults are the
    // "nice preset" applied once glass is turned on.
    private val GLASS_SCOPE_DEFAULT_BITS: Int = 0
    private val GLASS_ALPHA_DEFAULT_PCT: Int = 56
    private val GLASS_BLUR_DEFAULT_PCT: Int = 78
    private val GLASS_HIGHLIGHT_DEFAULT_PCT: Int = 55

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UI_ZOOM_PCT = intPreferencesKey("ui_zoom_percent")
        val FONT_SIZE_PCT = intPreferencesKey("font_size_percent")
        val POPUP_FONT_SIZE_PCT = intPreferencesKey("popup_font_size_percent")
        val POPUP_SIZE_PCT = intPreferencesKey("popup_size_percent")
        val MAIN_FONT_FAMILY = stringPreferencesKey("main_font_family")
        val POPUP_FONT_FAMILY = stringPreferencesKey("popup_font_family")
        val ACCENT = stringPreferencesKey("accent_color")
        val ACCENT_CUSTOM = stringPreferencesKey("accent_custom")
        val FOCUS_HIGHLIGHT = stringPreferencesKey("focus_highlight_color")
        val FOCUS_HIGHLIGHT_WIDTH = intPreferencesKey("focus_highlight_width")
        val AVATAR_ID = intPreferencesKey("avatar_id")
        val ACTIVE_PROFILE = longPreferencesKey("active_profile_id")
        val DEFAULT_SOURCE = longPreferencesKey("default_source_id")
        val DOWNLOAD_ROOT = stringPreferencesKey("download_root")
        val REFRESH_SOURCE_IDS = stringSetPreferencesKey("refresh_source_ids")
        // Per-source auto-refresh selections (JSON maps: { "<sourceId>": "<EnumName>" }). Replace the
        // binary refresh-on-startup set with Off/Startup + staleness thresholds. Migration-safe: the legacy
        // REFRESH_SOURCE_IDS set is read once (see migrateLegacyRefreshFlags) then ignored.
        val PLAYLIST_AUTO_REFRESH = stringPreferencesKey("playlist_auto_refresh")
        val EPG_AUTO_REFRESH = stringPreferencesKey("epg_auto_refresh")
        // Per-EPG-source: use that feed's own <icon src> channel logos instead of the playlist's.
        val EPG_USE_LOGOS = stringPreferencesKey("epg_use_logos")
        val REFRESH_MIGRATED = booleanPreferencesKey("refresh_migration_done")
        val EPG_REFILL_CHECKED = booleanPreferencesKey("epg_refill_checked")
        // Set while a backup restore is applying, cleared only when it completes (B2). A value that
        // survives to the next launch means the restore was interrupted and may be half-applied.
        val RESTORE_IN_PROGRESS = stringPreferencesKey("restore_in_progress")
        val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
        val LIVE_PREVIEW_AUDIO = booleanPreferencesKey("live_preview_audio")
        // Whether the expanded Home hero plays its video. Unset means "device default" — see
        // heroPreviewEnabled.
        val HERO_PREVIEW = booleanPreferencesKey("hero_preview")
        // Docked mini-player: size (% of screen width) and screen corner/edge.
        val MINI_PLAYER_SIZE_PCT = intPreferencesKey("mini_player_size_pct")
        val MINI_PLAYER_POSITION = stringPreferencesKey("mini_player_position")
        // Live TV latency: preset name + the custom seconds used when the preset is CUSTOM.
        val LIVE_LATENCY_MODE = stringPreferencesKey("live_latency_mode")
        val LIVE_LATENCY_CUSTOM_SECS = intPreferencesKey("live_latency_custom_secs")
        val LIVE_PREROLL_SECS = intPreferencesKey("live_preroll_secs")
        // How long a live channel may take to produce a picture before it is called dead. 0 = never.
        val LIVE_TUNE_TIMEOUT_SECS = intPreferencesKey("live_tune_timeout_secs")
        // v4.1.6 one-shot: reset live latency to the safe Balanced preset. Subsequent user changes are
        // preserved across every later update.
        val LIVE_LATENCY_RESET_416 = booleanPreferencesKey("live_latency_reset_416")
        val HDR_ENABLED = booleanPreferencesKey("hdr_enabled")
        val AUTO_FRAME_RATE = booleanPreferencesKey("auto_frame_rate")
        // v4.1.6 one-shot: AFR caused visible HDMI re-handshakes on some TVs. Existing installs are
        // forced Off once; subsequent user changes are preserved across every later update.
        val AUTO_FRAME_RATE_RESET_416 = booleanPreferencesKey("auto_frame_rate_reset_416")
        // v4.2.0 one-shot: below Android 12 the app cannot tell a seamless refresh-rate switch from one
        // that blanks the panel, so AFR is forced Off once on those devices. Set on every device (so the
        // migration and the [autoFrameRate] gate below both settle), but only clears AFR on pre-12.
        val AUTO_FRAME_RATE_RESET_PRE12 = booleanPreferencesKey("auto_frame_rate_reset_pre12")

        /** The one-time "this stream judders at your TV's refresh rate" suggestion has been answered. */
        val AUTO_FRAME_RATE_PROMPTED = booleanPreferencesKey("auto_frame_rate_prompted")
        val ANDROID_TV_HOME = booleanPreferencesKey("android_tv_home")
        // Video Player Settings
        val HW_DECODING = booleanPreferencesKey("hw_decoding")
        val VOD_PREFER_EXO = booleanPreferencesKey("vod_prefer_exo") // legacy; read for migration only
        val LIVE_ENGINE = stringPreferencesKey("live_engine")
        val VOD_ENGINE = stringPreferencesKey("vod_engine")
        val MEASURED_STREAM_STATS = booleanPreferencesKey("measured_stream_stats")
        val DETAILED_DIAGNOSTICS = booleanPreferencesKey("detailed_diagnostics")
        val DIRECT_TUNE = booleanPreferencesKey("direct_tune")
        val SURROUND_SOUND = booleanPreferencesKey("surround_sound")
        val SURROUND_MODE = stringPreferencesKey("surround_mode")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        /** Legacy single external-player toggle (movies + series + downloads). Superseded by the three
         *  per-section keys below but still read as their default, so an existing setting survives. */
        val EXTERNAL_PLAYER = booleanPreferencesKey("external_player")
        val EXTERNAL_PLAYER_LIVE = booleanPreferencesKey("external_player_live")
        val EXTERNAL_PLAYER_MOVIES = booleanPreferencesKey("external_player_movies")
        val EXTERNAL_PLAYER_SERIES = booleanPreferencesKey("external_player_series")
        val DEFAULT_ZOOM = stringPreferencesKey("default_zoom")
        val DEFAULT_VOLUME = intPreferencesKey("default_volume")
        val DEINTERLACE = booleanPreferencesKey("deinterlace")
        val SEEK_STEP_SEC = intPreferencesKey("seek_step_sec")
        val LIVE_REWIND_STEP_SEC = intPreferencesKey("live_rewind_step_sec")
        /** Legacy single subtitle size, superseded by the two per-engine keys below but still read as
         *  their default so an existing size survives an upgrade — and still written by nothing. */
        val SUB_SCALE = floatPreferencesKey("sub_scale")
        val SUB_SCALE_MPV = floatPreferencesKey("sub_scale_mpv")
        val SUB_SCALE_EXO = floatPreferencesKey("sub_scale_exo")
        // Subtitle appearance (#96): off by default so every renderer keeps its stock look —
        // notably the embedded broadcaster styling of Live TV CEA-608/teletext cues.
        val SUB_STYLE_ENABLED = booleanPreferencesKey("sub_style_enabled")
        val SUB_FONT = stringPreferencesKey("sub_font")
        val SUB_COLOR = stringPreferencesKey("sub_color")
        val SUB_POSITION = stringPreferencesKey("sub_position")
        val SUB_BG_OPACITY = intPreferencesKey("sub_bg_opacity")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val PREF_AUDIO_LANG = stringPreferencesKey("pref_audio_lang")
        val PREF_SUB_LANG = stringPreferencesKey("pref_sub_lang")
        // OpenSubtitles online-search language filter. Separate from PREF_SUB_LANG (embedded tracks) —
        // off by default, so a search returns every language OpenSubtitles has for the title.
        val SUB_SEARCH_FILTER = booleanPreferencesKey("sub_search_filter")
        val SUB_SEARCH_LANGS = stringPreferencesKey("sub_search_langs")
        // Settings rows pinned to the Quick group, comma-joined, in display order.
        val QUICK_PINNED = stringPreferencesKey("settings_quick_pinned")
        // Per-section list sorting ("PLAYLIST" or "ALPHA")
        val SORT_LIVE = stringPreferencesKey("sort_live")
        val SORT_GUIDE = stringPreferencesKey("sort_guide")
        val SORT_MOVIES = stringPreferencesKey("sort_movies")
        val SORT_SERIES = stringPreferencesKey("sort_series")
        val RESUME_MODE = stringPreferencesKey("resume_mode")
        val UPDATE_CHECK_ON_START = booleanPreferencesKey("update_check_on_start")
        val CATCHUP_TZ = stringPreferencesKey("catchup_timezone")
        val CATCHUP_PLAYER = stringPreferencesKey("catchup_player")
        val CATCHUP_OFFSET_MIN = intPreferencesKey("catchup_offset_minutes")
        val EPG_OFFSET_MIN = intPreferencesKey("epg_offset_minutes")
        val ANIMATION_LEVEL = stringPreferencesKey("animation_level")
        val AMBIENT_GLOW_ENABLED = booleanPreferencesKey("ambient_glow_enabled")
        val AMBIENT_GLOW_PULSE = booleanPreferencesKey("ambient_glow_pulse")
        val RESUME_LAST_CHANNEL = booleanPreferencesKey("resume_last_channel")
        val LAST_LIVE_CATEGORY = stringPreferencesKey("last_live_category")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val LAST_LIVE_CHANNEL = androidx.datastore.preferences.core.longPreferencesKey("last_live_channel")
        val VOD_GRID_COLUMNS = intPreferencesKey("vod_grid_columns")
        val VOD_VIEW_MODE = stringPreferencesKey("vod_view_mode")
        val GUIDE_VIEW = stringPreferencesKey("guide_view")
        val GUIDE_DENSITY_PCT = intPreferencesKey("guide_density_pct")
        val EPISODE_VIEW_MODE = stringPreferencesKey("episode_view_mode")
        // Touch-host settings. A television has no home button to shrink a video into, no metered
        // connection to save, and no swipe to calibrate — so all four default to the behaviour the TV
        // app already has, and only the phone app ever shows a row for them.
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val GESTURE_SENSITIVITY_PCT = intPreferencesKey("gesture_sensitivity_pct")
        val DOWNLOADS_WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        // The mobility layer, all of it touch-host only: a window that floats over another app, a
        // screen that switches itself off, and a data plan that gets billed. A television has none of
        // the three, so every default below is what a TV does today — nothing.
        val MINI_PLAYER_STYLE = stringPreferencesKey("mini_player_style")
        val PIP_ON_BACK = booleanPreferencesKey("pip_on_back")
        val PIP_SIZE = stringPreferencesKey("pip_size")
        val PIP_SNAP = booleanPreferencesKey("pip_snap")
        val AUDIO_ON_SCREEN_OFF = booleanPreferencesKey("audio_on_screen_off")
        val AUDIO_ON_MOBILE_DATA = booleanPreferencesKey("audio_on_mobile_data")
        val AUDIO_PER_CHANNEL = booleanPreferencesKey("audio_per_channel")
        // Global proxy (Approach 1 — one app-wide HTTP proxy). HTTP only; no per-source override yet.
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val PROXY_PASS = stringPreferencesKey("proxy_pass")
        // Global custom DNS — one app-wide DNS server (plain UDP or DoH). Sibling to global proxy.
        val DNS_ENABLED = booleanPreferencesKey("dns_enabled")
        val DNS_HOST = stringPreferencesKey("dns_host")
        val DNS_PORT = intPreferencesKey("dns_port")
        val DNS_DOH_URL = stringPreferencesKey("dns_doh_url")
        // Weather chip: show/hide + manual location override (blank = auto-detect from public IP).
        val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val WEATHER_LOCATION = stringPreferencesKey("weather_location")
        val WEATHER_FAHRENHEIT = booleanPreferencesKey("weather_fahrenheit")
        // TMDB metadata enrichment (see extras/future-plan/tmdb-metadata-plan.md). Master toggle + the two
        // advanced tiers (own key / self-host URL). Blank tier fields = use the default caching Worker.
        val METADATA_ENABLED = booleanPreferencesKey("metadata_enabled") // legacy; migrated to METADATA_MODE
        val METADATA_MODE = stringPreferencesKey("metadata_mode")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val METADATA_SERVER_URL = stringPreferencesKey("metadata_server_url")
        val OPEN_SUBTITLES_API_KEY = stringPreferencesKey("open_subtitles_api_key")
        val OPEN_SUBTITLES_SERVER_URL = stringPreferencesKey("open_subtitles_server_url")
        // TMDB content language (ISO 639-1, optionally with region — e.g. "el", "pt-BR"). Blank = the
        // TMDB default (en-US), which is what every install used before this setting existed, so leaving
        // it blank keeps existing users' metadata exactly as it was. "auto" = follow the device locale.
        val METADATA_LANGUAGE = stringPreferencesKey("metadata_language")
        // Bumped whenever a matcher fix invalidates previously cached "no match" rows, so existing
        // installs drop them once instead of waiting out the 7-day negative TTL. See
        // MetadataRepository.MATCH_HEURISTICS_VERSION.
        val METADATA_MATCH_HEAL_VERSION = intPreferencesKey("metadata_match_heal_version")
        // Nav menu customization (v4.3.0): DYNAMIC auto-adapts the side icons to what the active playlist
        // offers; STATIC lets the user hide specific icons. NAV_HIDDEN holds MainSection.name values the
        // user has hidden (STATIC mode only — DYNAMIC ignores it).
        val NAV_MENU_MODE = stringPreferencesKey("nav_menu_mode")
        val NAV_MENU_HIDDEN = stringSetPreferencesKey("nav_menu_hidden")
        // CH+- key paging for browse panels (Live/Movies/Series: category rail + item list/grid).
        // Master toggle + a per-direction skip count (CH+ toward first, CH− toward last). Counts are
        // clamped to [1, CH_NAV_HARD_MAX] on write; the UI warns above CH_NAV_WARN_THRESHOLD.
        val CH_NAV_ENABLED = booleanPreferencesKey("ch_nav_enabled")
        val CH_NAV_UP_SKIP = intPreferencesKey("ch_nav_up_skip")
        val CH_NAV_DOWN_SKIP = intPreferencesKey("ch_nav_down_skip")
        val REMOTE_SHORTCUT_BINDINGS = stringSetPreferencesKey("remote_shortcut_bindings")
        // Manual panel-width adjustment (v4.3.x): per section (Live/Movies/Series) a master toggle plus
        // one percentage per panel (category rail · item list/grid · preview). 100 = stock width; the
        // three are normalized across the row, so they always fill the screen. See PanelWidths.kt.
        val PANEL_W_LIVE_ON = booleanPreferencesKey("panel_w_live_on")
        val PANEL_W_LIVE_CAT = intPreferencesKey("panel_w_live_cat")
        val PANEL_W_LIVE_LIST = intPreferencesKey("panel_w_live_list")
        val PANEL_W_LIVE_PREVIEW = intPreferencesKey("panel_w_live_preview")
        val PANEL_W_MOVIES_ON = booleanPreferencesKey("panel_w_movies_on")
        val PANEL_W_MOVIES_CAT = intPreferencesKey("panel_w_movies_cat")
        val PANEL_W_MOVIES_LIST = intPreferencesKey("panel_w_movies_list")
        val PANEL_W_MOVIES_PREVIEW = intPreferencesKey("panel_w_movies_preview")
        val PANEL_W_SERIES_ON = booleanPreferencesKey("panel_w_series_on")
        val PANEL_W_SERIES_CAT = intPreferencesKey("panel_w_series_cat")
        val PANEL_W_SERIES_LIST = intPreferencesKey("panel_w_series_list")
        val PANEL_W_SERIES_PREVIEW = intPreferencesKey("panel_w_series_preview")
        // Guide's two-column split (pinned channels · scrollable EPG timeline).
        val GUIDE_WIDTH_ON = booleanPreferencesKey("guide_width_on")
        val GUIDE_WIDTH_CHANNELS = intPreferencesKey("guide_width_channels")
        val GUIDE_WIDTH_EPG = intPreferencesKey("guide_width_epg")
        // "Browsing & lists" — two independent per-section toggles (Live TV / Movies / Series).
        //
        // REMEMBER_LAST_*  = remember last ITEM. OFF (default) = switching category resets the browse list
        //                    to the top; ON = each category keeps its own scroll position. The Live one
        //                    also gates lastLiveChannelId (the focused-channel restore on re-entry).
        // REMEMBER_CAT_*   = remember last CATEGORY. ON (default) = reopening the section lands on the
        //                    category you left; OFF = always start on All. Live TV has always behaved this
        //                    way; Movies/Series gained the same persistence alongside the toggle.
        val REMEMBER_LAST_LIVE = booleanPreferencesKey("remember_last_live")
        val REMEMBER_LAST_MOVIES = booleanPreferencesKey("remember_last_movies")
        val REMEMBER_LAST_SERIES = booleanPreferencesKey("remember_last_series")
        val REMEMBER_CAT_LIVE = booleanPreferencesKey("remember_cat_live")
        val REMEMBER_CAT_MOVIES = booleanPreferencesKey("remember_cat_movies")
        val REMEMBER_CAT_SERIES = booleanPreferencesKey("remember_cat_series")
        val LAST_MOVIES_CATEGORY = stringPreferencesKey("last_movies_category")
        val LAST_SERIES_CATEGORY = stringPreferencesKey("last_series_category")
        // Background image (Glass effect). bg_image_path holds the absolute path of the image we
        // COPIED into app-private storage (so a USB unplug or source-folder delete never blanks it);
        // blank = no background (feature off, panels stay solid). glass_scope is the bitmask of which
        // surfaces go translucent (GlassConfig.fromBitmask); glass_alpha is the fill alpha in 0..100;
        // glass_blur is the backdrop frost strength in 0..100 (Phase 4 — real backdrop blur; 0 keeps
        // the older Tier-1 translucency-only look).
        val BG_IMAGE_PATH = stringPreferencesKey("bg_image_path")
        val GLASS_SCOPE = intPreferencesKey("glass_scope")
        val GLASS_ALPHA = intPreferencesKey("glass_alpha")
        val GLASS_BLUR = intPreferencesKey("glass_blur")
        val GLASS_HIGHLIGHT = intPreferencesKey("glass_highlight")
        val GLASS_ALLOW_FULL_TRANSPARENCY = booleanPreferencesKey("glass_allow_full_transparency")
        val GLASS_DEPTH_EFFECTS = booleanPreferencesKey("glass_depth_effects")
        val GLASS_GLINT = booleanPreferencesKey("glass_glint")
        val GLASS_PRESET = stringPreferencesKey("glass_preset")

        // When the last successful backup was written, how big it was, and whether it was encrypted.
        // Nothing recorded this before: the Backup screen could say what a backup *would* contain but
        // never when one last happened, so "am I backed up?" had no answer anywhere in either app.
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_BYTES = longPreferencesKey("last_backup_bytes")
        val LAST_BACKUP_ENCRYPTED = booleanPreferencesKey("last_backup_encrypted")
        val LAST_BACKUP_PATH = stringPreferencesKey("last_backup_path")
    }

    /**
     * The last successful backup — `null` until one has been written on this device.
     *
     * Deliberately not derived from the file on disk: the file lives wherever the user chose, often a
     * USB drive that is not plugged in, so its absence says nothing about whether a backup was taken.
     */
    data class LastBackup(val at: Long, val bytes: Long, val encrypted: Boolean, val path: String)

    val lastBackup: Flow<LastBackup?> = prefsFlow { prefs ->
        prefs[Keys.LAST_BACKUP_AT]?.takeIf { it > 0 }?.let { at ->
            LastBackup(
                at = at,
                bytes = prefs[Keys.LAST_BACKUP_BYTES] ?: 0L,
                encrypted = prefs[Keys.LAST_BACKUP_ENCRYPTED] ?: false,
                // Blank for a backup written before the path was recorded — the date is still true,
                // so the row is simply left out rather than the whole record thrown away.
                path = prefs[Keys.LAST_BACKUP_PATH].orEmpty(),
            )
        }
    }

    /** Called by [tv.own.owntv.core.backup.BackupManager] only after an export has actually landed. */
    suspend fun recordBackup(at: Long, bytes: Long, encrypted: Boolean, path: String) {
        context.dataStore.edit {
            it[Keys.LAST_BACKUP_AT] = at
            it[Keys.LAST_BACKUP_BYTES] = bytes
            it[Keys.LAST_BACKUP_ENCRYPTED] = encrypted
            it[Keys.LAST_BACKUP_PATH] = path
        }
    }

    // --- Live TV: remember the last focused channel so reopening lands focus back on it ---
    val lastLiveChannelId: Flow<Long> = prefsFlow { it[Keys.LAST_LIVE_CHANNEL] ?: -1L }
    suspend fun setLastLiveChannelId(id: Long) {
        context.dataStore.edit { it[Keys.LAST_LIVE_CHANNEL] = id }
    }

    // --- Startup: per-profile landing (v4.0.0). Falls back to the legacy global resume toggle for existing
    //     users (so "Resume last channel = On" keeps working until they pick a per-profile mode). ---
    fun startupMode(profileId: Long): Flow<StartupMode> = prefsFlow { prefs ->
        prefs[stringPreferencesKey("startup_mode_$profileId")]?.let { runCatching { StartupMode.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.RESUME_LAST_CHANNEL] == true) StartupMode.LAST_CHANNEL else StartupMode.HOME
    }
    suspend fun setStartupMode(profileId: Long, mode: StartupMode) {
        context.dataStore.edit { it[stringPreferencesKey("startup_mode_$profileId")] = mode.name }
    }

    fun startupChannel(profileId: Long): Flow<StartupChannelRef?> = prefsFlow { prefs ->
        StartupChannelRef.fromJson(prefs[stringPreferencesKey("startup_channel_$profileId")])
    }

    suspend fun setStartupChannel(profileId: Long, channel: StartupChannelRef?) {
        context.dataStore.edit { prefs ->
            val key = stringPreferencesKey("startup_channel_$profileId")
            if (channel == null) prefs.remove(key) else prefs[key] = channel.toJson().toString()
        }
    }

    suspend fun setSpecificStartupChannel(profileId: Long, channel: StartupChannelRef) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("startup_channel_$profileId")] = channel.toJson().toString()
            prefs[stringPreferencesKey("startup_mode_$profileId")] = StartupMode.SPECIFIC_CHANNEL.name
        }
    }

    // --- Customize Categories & Items: optional per-profile PIN lock on the screen (so hidden items can't
    //     be unhidden by someone else). Exported/imported in backups as a salted SHA-256 hash (see
    //     exportCustomizePins / importCustomizePins → BackupManager `customizePins`), so the PIN value
    //     itself never travels in a readable form. ---
    fun customizePin(profileId: Long): Flow<String?> = prefsFlow { prefs ->
        prefs[stringPreferencesKey("customize_pin_$profileId")]?.takeIf { it.isNotBlank() }
    }

    /** null/blank clears the lock. */
    suspend fun setCustomizePin(profileId: Long, pin: String?) {
        context.dataStore.edit { prefs ->
            val k = stringPreferencesKey("customize_pin_$profileId")
            if (pin.isNullOrBlank()) prefs.remove(k) else prefs[k] = Pin.hash(pin.trim())
        }
    }

    /** Whether a category the provider adds on a later resync is hidden automatically. Same across
     *  Live/Movies/Series for a profile — there's no reason to want it to differ by section. */
    fun hideNewCategoriesDefault(profileId: Long): Flow<Boolean> = prefsFlow { prefs ->
        prefs[booleanPreferencesKey("hide_new_categories_$profileId")] ?: false
    }

    suspend fun setHideNewCategoriesDefault(profileId: Long, hidden: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("hide_new_categories_$profileId")] = hidden }
    }

    // --- Home: per-profile row order / visibility / hero filters. ---
    private fun homeConfigKey(profileId: Long) = stringPreferencesKey("home_config_$profileId")

    fun homeConfig(profileId: Long): Flow<HomeConfig> = prefsFlow { prefs ->
        HomeConfig.fromJson(prefs[homeConfigKey(profileId)])
    }

    suspend fun updateHomeConfig(profileId: Long, transform: (HomeConfig) -> HomeConfig) {
        context.dataStore.edit { prefs ->
            val key = homeConfigKey(profileId)
            val next = transform(HomeConfig.fromJson(prefs[key]))
            if (next == HomeConfig()) prefs.remove(key) else prefs[key] = next.toJson().toString()
        }
    }

    // --- Startup: auto-open the last-watched live channel (default OFF) — legacy, now migrated to startupMode ---
    val resumeLastChannel: Flow<Boolean> = prefsFlow { it[Keys.RESUME_LAST_CHANNEL] ?: false }
    suspend fun setResumeLastChannel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESUME_LAST_CHANNEL] = enabled }
    }

    // --- Remember the last selected category so reopening a section lands where you left off.
    //     Written by each section's view model (debounced), read once on restore. ---
    val lastLiveCategory: Flow<String> = prefsFlow { it[Keys.LAST_LIVE_CATEGORY] ?: "" }
    suspend fun setLastLiveCategory(key: String) {
        context.dataStore.edit { it[Keys.LAST_LIVE_CATEGORY] = key }
    }
    val lastMoviesCategory: Flow<String> = prefsFlow { it[Keys.LAST_MOVIES_CATEGORY] ?: "" }
    suspend fun setLastMoviesCategory(key: String) {
        context.dataStore.edit { it[Keys.LAST_MOVIES_CATEGORY] = key }
    }
    val lastSeriesCategory: Flow<String> = prefsFlow { it[Keys.LAST_SERIES_CATEGORY] ?: "" }
    suspend fun setLastSeriesCategory(key: String) {
        context.dataStore.edit { it[Keys.LAST_SERIES_CATEGORY] = key }
    }

    // --- Per-section "remember last CATEGORY" (default ON each — Live TV's long-standing behaviour,
    //     now also available for Movies/Series). OFF = the section always opens on All. ---
    val rememberCategoryLive: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_LIVE] ?: true }
    suspend fun setRememberCategoryLive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_LIVE] = enabled }
    }
    val rememberCategoryMovies: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_MOVIES] ?: true }
    suspend fun setRememberCategoryMovies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_MOVIES] = enabled }
    }
    val rememberCategorySeries: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_CAT_SERIES] ?: true }
    suspend fun setRememberCategorySeries(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_CAT_SERIES] = enabled }
    }

    // --- Per-section "remember last ITEM per category" (default OFF each).
    //     OFF = switching category resets the browse list to the top; ON = each category keeps its own
    //     scroll position. The Live toggle additionally gates the lastLiveChannelId restore on re-entry. ---
    val rememberLastLive: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_LIVE] ?: false }
    suspend fun setRememberLastLive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_LIVE] = enabled }
    }
    val rememberLastMovies: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_MOVIES] ?: false }
    suspend fun setRememberLastMovies(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_MOVIES] = enabled }
    }
    val rememberLastSeries: Flow<Boolean> = prefsFlow { it[Keys.REMEMBER_LAST_SERIES] ?: false }
    suspend fun setRememberLastSeries(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_LAST_SERIES] = enabled }
    }

    // --- Search: recent search terms (most-recent first, capped). Stored as one newline-joined string
    //     so no schema/table is needed; blank entries are ignored on read. ---
    val recentSearches: Flow<List<String>> = prefsFlow { prefs ->
        prefs[Keys.RECENT_SEARCHES]?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** Push a query to the top of the recents (case-insensitive dedup), capped at 12 entries. */
    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_SEARCHES]?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val next = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(12)
            prefs[Keys.RECENT_SEARCHES] = next.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    }

    // --- Appearance: animation level (perf control for low-end boxes) ---
    val animationLevel: Flow<tv.own.owntv.core.theme.AnimationLevel> = prefsFlow { prefs ->
        prefs[Keys.ANIMATION_LEVEL]?.let { runCatching { tv.own.owntv.core.theme.AnimationLevel.valueOf(it) }.getOrNull() }
            ?: tv.own.owntv.core.theme.AnimationLevel.FULL
    }

    suspend fun setAnimationLevel(level: tv.own.owntv.core.theme.AnimationLevel) {
        context.dataStore.edit { it[Keys.ANIMATION_LEVEL] = level.name }
    }

    // --- Solid UI ambient radiance (the setup-wizard-style aura) ---
    // Opt-in: upgrading users keep the familiar solid appearance until they choose this effect.
    val ambientGlowEnabled: Flow<Boolean> = prefsFlow { it[Keys.AMBIENT_GLOW_ENABLED] ?: false }

    suspend fun setAmbientGlowEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_GLOW_ENABLED] = enabled }
    }

    val ambientGlowPulse: Flow<Boolean> = prefsFlow { it[Keys.AMBIENT_GLOW_PULSE] ?: true }

    suspend fun setAmbientGlowPulse(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_GLOW_PULSE] = enabled }
    }

    // --- Weather chip (top bar): show/hide + manual location override for VPN users ---

    /** Show the weather chip in the top bar (default ON). */
    val weatherEnabled: Flow<Boolean> = prefsFlow { it[Keys.WEATHER_ENABLED] ?: true }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WEATHER_ENABLED] = enabled }
    }

    /**
     * Manual weather location. Blank (default) = auto-detect from public IP. Otherwise a city name
     * (geocoded via Open-Meteo) or a raw "lat,lon" pair. Lets users fix the wrong-city behaviour
     * they see behind a VPN, where IP geolocation resolves to the VPN server's city.
     */
    val weatherLocation: Flow<String> = prefsFlow { it[Keys.WEATHER_LOCATION] ?: "" }

    suspend fun setWeatherLocation(location: String) {
        context.dataStore.edit { it[Keys.WEATHER_LOCATION] = location.trim() }
    }

    /** Show the weather temperature in Fahrenheit instead of Celsius (default °C). */
    val weatherFahrenheit: Flow<Boolean> = prefsFlow { it[Keys.WEATHER_FAHRENHEIT] ?: false }

    suspend fun setWeatherFahrenheit(fahrenheit: Boolean) {
        context.dataStore.edit { it[Keys.WEATHER_FAHRENHEIT] = fahrenheit }
    }

    // --- TMDB metadata enrichment (plan §4) ---
    // One provider, three configs. Enrichment is opt-outable via the master toggle; the two advanced
    // fields (own key / self-host URL) override the default caching Worker when set.

    /** Metadata source mode (plan §4.1). Defaults to Provider+TMDB; back-compat: an old boolean master
     *  toggle maps false→Provider, true→Provider+TMDB when no explicit mode is stored yet. */
    val metadataMode: Flow<tv.own.owntv.core.metadata.MetadataMode> = prefsFlow { p ->
        parseMetadataMode(p)
    }

    private fun parseMetadataMode(p: Preferences): tv.own.owntv.core.metadata.MetadataMode {
        p[Keys.METADATA_MODE]?.let { raw ->
            runCatching { tv.own.owntv.core.metadata.MetadataMode.valueOf(raw) }.getOrNull()?.let { return it }
        }
        // No explicit mode yet — derive from the legacy boolean toggle.
        return if (p[Keys.METADATA_ENABLED] == false) tv.own.owntv.core.metadata.MetadataMode.PROVIDER
        else tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB
    }

    suspend fun setMetadataMode(mode: tv.own.owntv.core.metadata.MetadataMode) {
        context.dataStore.edit {
            it[Keys.METADATA_MODE] = mode.name
            it[Keys.METADATA_ENABLED] = mode.enrich // keep legacy key coherent for older readers
        }
    }

    /** Tier 2 — the user's own TMDB v3 API key; blank = don't call TMDB directly. */
    val tmdbApiKey: Flow<String> = prefsFlow { it[Keys.TMDB_API_KEY] ?: "" }

    suspend fun setTmdbApiKey(key: String) {
        context.dataStore.edit { it[Keys.TMDB_API_KEY] = key.trim() }
    }

    /** Tier 3 — a custom TMDB-shaped metadata server base URL; blank = don't self-host. */
    val metadataServerUrl: Flow<String> = prefsFlow { it[Keys.METADATA_SERVER_URL] ?: "" }

    suspend fun setMetadataServerUrl(url: String) {
        context.dataStore.edit { it[Keys.METADATA_SERVER_URL] = url.trim() }
    }

    val openSubtitlesApiKey: Flow<String> = prefsFlow { it[Keys.OPEN_SUBTITLES_API_KEY] ?: "" }
    suspend fun setOpenSubtitlesApiKey(key: String) { context.dataStore.edit { it[Keys.OPEN_SUBTITLES_API_KEY] = key.trim() } }

    val openSubtitlesServerUrl: Flow<String> = prefsFlow { it[Keys.OPEN_SUBTITLES_SERVER_URL] ?: "" }
    suspend fun setOpenSubtitlesServerUrl(url: String) { context.dataStore.edit { it[Keys.OPEN_SUBTITLES_SERVER_URL] = url.trim() } }

    suspend fun currentOpenSubtitlesApiKey(): String = context.dataStore.data.first()[Keys.OPEN_SUBTITLES_API_KEY] ?: ""
    suspend fun currentOpenSubtitlesServerUrl(): String = context.dataStore.data.first()[Keys.OPEN_SUBTITLES_SERVER_URL] ?: ""

    /**
     * TMDB content language. Blank = TMDB's own default (en-US) — the pre-existing behaviour, so an
     * upgrade never silently changes anyone's metadata. "auto" = follow the device locale, resolved at
     * call time by [tv.own.owntv.core.metadata.MetadataConfig.resolvedLanguage].
     */
    val metadataLanguage: Flow<String> = prefsFlow { it[Keys.METADATA_LANGUAGE] ?: "" }

    suspend fun setMetadataLanguage(code: String) {
        context.dataStore.edit { it[Keys.METADATA_LANGUAGE] = code.trim() }
    }

    /** Matcher generation the cached "no match" rows were written under (0 = never healed). */
    suspend fun metadataMatchHealVersion(): Int =
        context.dataStore.data.first()[Keys.METADATA_MATCH_HEAL_VERSION] ?: 0

    suspend fun setMetadataMatchHealVersion(version: Int) {
        context.dataStore.edit { it[Keys.METADATA_MATCH_HEAL_VERSION] = version }
    }

    /** Live snapshot of the metadata settings as one object (consumed by TmdbProvider). */
    val metadataConfigFlow: Flow<tv.own.owntv.core.metadata.MetadataConfig> = prefsFlow { p ->
        tv.own.owntv.core.metadata.MetadataConfig(
            mode = parseMetadataMode(p),
            tmdbApiKey = p[Keys.TMDB_API_KEY] ?: "",
            customServerUrl = p[Keys.METADATA_SERVER_URL] ?: "",
            language = p[Keys.METADATA_LANGUAGE] ?: "",
        )
    }

    /**
     * Hot snapshot of [metadataConfigFlow], kept warm by one long-lived collector.
     *
     * Measured on the owner's TV: a fresh `first()` on a DataStore flow costs **74–128 ms**, and the
     * metadata layer reads this on every resolve — once per episode focus, once per season switch,
     * once per browsed title. That dominated everything else in the path, database queries included.
     * One collector turns each of those into an in-memory field read.
     */
    private val metadataConfigState: StateFlow<tv.own.owntv.core.metadata.MetadataConfig?> =
        metadataConfigFlow.stateIn(repoScope, SharingStarted.Eagerly, null)

    /** Same treatment, for the other value the metadata path reads on every resolve. */
    private val activeProfileIdState: StateFlow<Long?> =
        prefsFlow { it[Keys.ACTIVE_PROFILE] ?: -1L }.stateIn(repoScope, SharingStarted.Eagerly, null)

    /**
     * One-shot read of the current metadata config (used by TmdbProvider per call). Served from the
     * warm snapshot; falls back to a direct read only before the collector's first emission.
     */
    suspend fun metadataConfig(): tv.own.owntv.core.metadata.MetadataConfig =
        metadataConfigState.value ?: metadataConfigFlow.first()

    /** Cheap counterpart to collecting [activeProfileId], for the same per-resolve hot path. */
    suspend fun activeProfileIdNow(): Long = activeProfileIdState.value ?: activeProfileId.first()


    // --- Catch-up (archive) playback ---

    /** Which timezone to format Xtream timeshift URLs in. Most panels run on the server's local time, which
     *  usually matches the user's region — so **Device** is the default; a manual UTC offset is the fallback. */
    enum class CatchupTimezone { DEVICE, MANUAL }

    /** Manual UTC offset bounds (whole hours), in minutes. */
    val catchupOffsetRangeMinutes: IntRange = -12 * 60..14 * 60

    val catchupTimezone: Flow<CatchupTimezone> = prefsFlow { prefs ->
        prefs[Keys.CATCHUP_TZ]?.let { runCatching { CatchupTimezone.valueOf(it) }.getOrNull() } ?: CatchupTimezone.DEVICE
    }

    /** Manual mode's offset from UTC, in minutes (0 = UTC, the previous default). */
    val catchupOffsetMinutes: Flow<Int> = prefsFlow { it[Keys.CATCHUP_OFFSET_MIN] ?: 0 }

    suspend fun setCatchupTimezone(mode: CatchupTimezone) {
        context.dataStore.edit { it[Keys.CATCHUP_TZ] = mode.name }
    }

    /** Which player takes a catch-up archive programme. Archives are mid-GOP MPEG-TS, the hardest thing
     *  the in-app engines have to swallow, so handing them to VLC/MX is a genuine escape hatch — but the
     *  in-app player keeps the HUD, resume and engine toggle, so **INTERNAL stays the default**. */
    enum class CatchupPlayer { ASK, INTERNAL, EXTERNAL }

    val catchupPlayer: Flow<CatchupPlayer> = prefsFlow { prefs ->
        prefs[Keys.CATCHUP_PLAYER]?.let { runCatching { CatchupPlayer.valueOf(it) }.getOrNull() } ?: CatchupPlayer.INTERNAL
    }

    suspend fun setCatchupPlayer(mode: CatchupPlayer) {
        context.dataStore.edit { it[Keys.CATCHUP_PLAYER] = mode.name }
    }

    suspend fun setCatchupOffsetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.CATCHUP_OFFSET_MIN] = minutes.coerceIn(catchupOffsetRangeMinutes) }
    }

    // --- EPG time offset ---

    /** How far the guide may be shifted, in minutes (±12/14 h, in 30-minute steps from the UI). */
    val epgOffsetRangeMinutes: IntRange = -12 * 60..14 * 60

    /**
     * Global guide shift, in minutes (0 = off). Some feeds publish their XMLTV in a timezone that
     * isn't the one the channels actually air in; this moves every programme by a fixed amount.
     * A per-channel override in the channel's long-press menu wins over this — that's what a
     * mixed East/West lineup on a single guide needs, since one global shift can only fix one half.
     */
    val epgOffsetMinutes: Flow<Int> = prefsFlow { it[Keys.EPG_OFFSET_MIN] ?: 0 }

    suspend fun setEpgOffsetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.EPG_OFFSET_MIN] = minutes.coerceIn(epgOffsetRangeMinutes) }
    }

    /** The timezone catch-up/timeshift URLs are formatted in — device tz, or a manual UTC offset. */
    suspend fun resolveCatchupTimeZone(): java.util.TimeZone = when (catchupTimezone.first()) {
        CatchupTimezone.DEVICE -> java.util.TimeZone.getDefault()
        CatchupTimezone.MANUAL -> java.util.SimpleTimeZone(catchupOffsetMinutes.first() * 60_000, "catchup")
    }

    /** Automatically check GitHub Releases for a newer version shortly after launch. */
    val updateCheckOnStart: Flow<Boolean> = prefsFlow { it[Keys.UPDATE_CHECK_ON_START] ?: true }

    suspend fun setUpdateCheckOnStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UPDATE_CHECK_ON_START] = enabled }
    }

    // --- Resume behavior for movies/episodes with a saved position ---

    enum class ResumeMode { AUTO, ASK, NEVER }

    val resumeMode: Flow<ResumeMode> = prefsFlow { prefs ->
        prefs[Keys.RESUME_MODE]?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: ResumeMode.ASK
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        context.dataStore.edit { it[Keys.RESUME_MODE] = mode.name }
    }

    // --- Nav menu customization (v4.3.0) ---
    // DYNAMIC: the side icons adapt to what the active playlist actually contains (Home & Settings
    // always show; Live/Guide show when there are channels; Movies/Series show when their content
    // exists; Downloads shows when Movies OR Series exist since Live has no download). STATIC: the
    // user picks exactly which icons to hide. Default STATIC (all visible) → existing users see no
    // change until they opt into Dynamic.

    enum class NavMenuMode { DYNAMIC, STATIC }

    val navMenuMode: Flow<NavMenuMode> = prefsFlow { prefs ->
        prefs[Keys.NAV_MENU_MODE]?.let { runCatching { NavMenuMode.valueOf(it) }.getOrNull() } ?: NavMenuMode.STATIC
    }

    suspend fun setNavMenuMode(mode: NavMenuMode) {
        context.dataStore.edit { it[Keys.NAV_MENU_MODE] = mode.name }
    }

    /** Names of the `MainSection` browse items the user has hidden (STATIC mode). */
    val navMenuHidden: Flow<Set<String>> = prefsFlow { it[Keys.NAV_MENU_HIDDEN] ?: emptySet() }

    /** Replace the whole hidden set. Empty = all visible. */
    suspend fun setNavMenuHidden(hidden: Set<String>) {
        context.dataStore.edit { prefs ->
            if (hidden.isEmpty()) prefs.remove(Keys.NAV_MENU_HIDDEN) else prefs[Keys.NAV_MENU_HIDDEN] = hidden
        }
    }

    // --- List sorting (per browse section) ---

    /** How a browse section's lists are ordered. RATING (highest provider rating first) applies to
     *  Movies/Series only; Live/EPG never select it. */
    enum class SortMode { PLAYLIST, ALPHA, RATING, DATE_ADDED }

    /** All three browse sections (Live/Movies/Series) default to the playlist/provider's own order — the
     *  natural grouping a user expects right after a sync. A–Z is one tap away (toggleSort). */
    val sortLive: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_LIVE], SortMode.PLAYLIST) }
    val sortMovies: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_MOVIES], SortMode.PLAYLIST) }
    val sortSeries: Flow<SortMode> = prefsFlow { parseSort(it[Keys.SORT_SERIES], SortMode.PLAYLIST) }

    suspend fun setSortLive(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_LIVE] = mode.name }
    }

    suspend fun setSortMovies(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_MOVIES] = mode.name }
    }

    suspend fun setSortSeries(mode: SortMode) {
        context.dataStore.edit { it[Keys.SORT_SERIES] = mode.name }
    }

    private fun parseSort(raw: String?, default: SortMode): SortMode =
        raw?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: default

    /** The TV Guide's own ordering. LIVE_TV mirrors the Live TV sort; CATCHUP floats archive channels up. */
    enum class GuideSort { ALPHA, PROVIDER, LIVE_TV, CATCHUP, FAVORITES }

    /**
     * How the guide is drawn on a touch screen: the time grid, the "on now" list, or one channel's
     * schedule down the page. Unset means "decide from the screen" — a two-dimensional grid is
     * unusable on a portrait phone and the natural choice on a tablet, so a device that has never
     * been told otherwise picks per orientation rather than being locked to one answer.
     */
    enum class GuideView { GRID, ON_NOW, TIMELINE }
    val guideView: Flow<GuideView?> = prefsFlow { prefs ->
        prefs[Keys.GUIDE_VIEW]?.let { runCatching { GuideView.valueOf(it) }.getOrNull() }
    }
    suspend fun setGuideView(view: GuideView) {
        context.dataStore.edit { it[Keys.GUIDE_VIEW] = view.name }
    }

    /** Guide row height, as a percentage of the standard one. The phone's answer to the TV app's
     *  Guide Column Widths, which has no meaning where the channel column is a fixed strip. */
    val guideDensityPct: Flow<Int> = prefsFlow { it[Keys.GUIDE_DENSITY_PCT] ?: 100 }
    suspend fun setGuideDensityPct(pct: Int) {
        context.dataStore.edit { it[Keys.GUIDE_DENSITY_PCT] = pct.coerceIn(70, 130) }
    }

    /** How Movies & Series browse: the poster wall, or a compact list (more titles at once). */
    enum class VodViewMode { GRID, LIST }
    val vodViewMode: Flow<VodViewMode> = prefsFlow { prefs ->
        prefs[Keys.VOD_VIEW_MODE]?.let { runCatching { VodViewMode.valueOf(it) }.getOrNull() } ?: VodViewMode.GRID
    }
    suspend fun setVodViewMode(mode: VodViewMode) {
        context.dataStore.edit { it[Keys.VOD_VIEW_MODE] = mode.name }
    }

    /**
     * How many posters a row of the Movies/Series grid holds. 0 means "decide from the screen", which
     * is what a device that has never been pinched reports — a phone in portrait, the same phone in
     * landscape and a tablet all want a different number, and one stored count cannot serve all three.
     * A pinch stores the user's answer for the width they pinched at.
     */
    val vodGridColumns: Flow<Int> = prefsFlow { it[Keys.VOD_GRID_COLUMNS] ?: 0 }

    suspend fun setVodGridColumns(columns: Int) {
        context.dataStore.edit { it[Keys.VOD_GRID_COLUMNS] = columns.coerceIn(0, 12) }
    }

    /**
     * How the episode list inside a show is drawn. LIST (default) is the text rows; GRID is a wall of
     * 16:9 episode stills. Global rather than per-series: a layout preference is about how the user
     * likes to browse, and storing it per show would mean setting it again for every show they open.
     */
    val episodeViewMode: Flow<VodViewMode> = prefsFlow { prefs ->
        prefs[Keys.EPISODE_VIEW_MODE]?.let { runCatching { VodViewMode.valueOf(it) }.getOrNull() } ?: VodViewMode.LIST
    }
    suspend fun setEpisodeViewMode(mode: VodViewMode) {
        context.dataStore.edit { it[Keys.EPISODE_VIEW_MODE] = mode.name }
    }

    /** Keep the sound going after the app is left. On by default — it is why the service exists. */
    val backgroundPlayback: Flow<Boolean> = prefsFlow { it[Keys.BACKGROUND_PLAYBACK] ?: true }
    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_PLAYBACK] = enabled }
    }

    /** Shrink the video into a floating window on home. On by default. */
    val pipEnabled: Flow<Boolean> = prefsFlow { it[Keys.PIP_ENABLED] ?: true }
    suspend fun setPipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_ENABLED] = enabled }
    }

    /**
     * Refuse to stream over a metered connection. Off by default: a user who has not asked for it
     * must never find their film blocked on a network that was working a moment ago.
     */
    val dataSaver: Flow<Boolean> = prefsFlow { it[Keys.DATA_SAVER] ?: false }
    suspend fun setDataSaver(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DATA_SAVER] = enabled }
    }

    /** How far a swipe travels before it has moved a slider, as a percentage of the default. */
    val gestureSensitivityPct: Flow<Int> = prefsFlow { it[Keys.GESTURE_SENSITIVITY_PCT] ?: 100 }
    suspend fun setGestureSensitivityPct(pct: Int) {
        context.dataStore.edit { it[Keys.GESTURE_SENSITIVITY_PCT] = pct.coerceIn(50, 200) }
    }

    /** Hold downloads until Wi-Fi. Off by default, for the same reason the data saver is. */
    val downloadsWifiOnly: Flow<Boolean> = prefsFlow { it[Keys.DOWNLOADS_WIFI_ONLY] ?: false }
    suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DOWNLOADS_WIFI_ONLY] = enabled }
    }

    /** Read once, for a caller that has to answer before it can start — the queue and the player. */
    suspend fun downloadsWifiOnlyNow(): Boolean = downloadsWifiOnly.first()
    suspend fun dataSaverNow(): Boolean = dataSaver.first()

    // --- The mobility layer (touch hosts only) ---------------------------------------------------

    /** What keeps playing while the user browses: a window they drag, a bar docked above the tabs, or
     *  nothing at all. */
    enum class MiniPlayerStyle { FLOATING, DOCKED, OFF }

    val miniPlayerStyle: Flow<MiniPlayerStyle> = prefsFlow { prefs ->
        prefs[Keys.MINI_PLAYER_STYLE]?.let { runCatching { MiniPlayerStyle.valueOf(it) }.getOrNull() }
            ?: MiniPlayerStyle.FLOATING
    }
    suspend fun setMiniPlayerStyle(style: MiniPlayerStyle) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_STYLE] = style.name }
    }

    /**
     * Shrink into the little window on Back as well as on Home. Off by default, and deliberately:
     * Back means "leave this screen" far more often than it means "keep watching", and a user who has
     * not asked for it must not have to dismiss a window every time they close the player.
     */
    val pipOnBack: Flow<Boolean> = prefsFlow { it[Keys.PIP_ON_BACK] ?: false }
    suspend fun setPipOnBack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_ON_BACK] = enabled }
    }

    /** How big the floating window starts. The user pinches it from there. */
    enum class PipSize { SMALL, MEDIUM, LARGE }

    val pipSize: Flow<PipSize> = prefsFlow { prefs ->
        prefs[Keys.PIP_SIZE]?.let { runCatching { PipSize.valueOf(it) }.getOrNull() } ?: PipSize.MEDIUM
    }
    suspend fun setPipSize(size: PipSize) {
        context.dataStore.edit { it[Keys.PIP_SIZE] = size.name }
    }

    /** Let go of the floating window and it settles against the nearest edge. On by default — a window
     *  left mid-screen covers what the user is browsing. */
    val pipSnap: Flow<Boolean> = prefsFlow { it[Keys.PIP_SNAP] ?: true }
    suspend fun setPipSnap(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PIP_SNAP] = enabled }
    }

    /**
     * Drop the video decoder and keep the sound when the screen goes off. On by default, because it is
     * the single biggest saving this app can make on a phone battery, and turning it off only means
     * decoding frames nobody can see.
     */
    val audioOnScreenOff: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_ON_SCREEN_OFF] ?: true }
    suspend fun setAudioOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ON_SCREEN_OFF] = enabled }
    }

    /** Start in sound-only when there is no Wi-Fi. Off by default: it changes what the user gets from
     *  a tap, and that is only ever their own choice. */
    val audioOnMobileData: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_ON_MOBILE_DATA] ?: false }
    suspend fun setAudioOnMobileData(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ON_MOBILE_DATA] = enabled }
    }

    /** Remember sound-only per channel, so a radio station opens without a picture next time and a
     *  television channel does not. On by default — the whole point is not having to ask twice. */
    val audioPerChannel: Flow<Boolean> = prefsFlow { it[Keys.AUDIO_PER_CHANNEL] ?: true }
    suspend fun setAudioPerChannel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_PER_CHANNEL] = enabled }
    }

    /** Both read once, on the path that starts a stream and cannot wait on a flow. */
    suspend fun audioOnMobileDataNow(): Boolean = audioOnMobileData.first()
    suspend fun audioPerChannelNow(): Boolean = audioPerChannel.first()

    val sortGuide: Flow<GuideSort> = prefsFlow { prefs ->
        prefs[Keys.SORT_GUIDE]?.let { runCatching { GuideSort.valueOf(it) }.getOrNull() } ?: GuideSort.LIVE_TV
    }

    suspend fun setSortGuide(mode: GuideSort) {
        context.dataStore.edit { it[Keys.SORT_GUIDE] = mode.name }
    }

    // --- Video Player Settings ---

    /** Hardware decoding (mpv hwdec auto-safe). Off = force software decoding for tricky streams. */
    val hwDecoding: Flow<Boolean> = prefsFlow { it[Keys.HW_DECODING] ?: true }

    suspend fun setHwDecoding(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HW_DECODING] = enabled }
    }

    /**
     * Which engine Live TV starts a channel on, and whether it may hand over to the other one.
     *
     * Default is [EnginePreference.EXO_FIRST] — ExoPlayer opens far faster, which is what channel
     * surfing is made of, and it is the only engine with live closed captions; mpv catches what it
     * cannot play. The other three exist because that automatic handover is not always wanted:
     *
     *  - **mpv first** for a panel or a TV where ExoPlayer is the one that usually loses.
     *  - **The two "only" modes** for anyone who has established that the second engine never works for
     *    them. A handover costs a stop, a surface release and a re-open — several seconds of black on
     *    every unplayable channel — so paying it for an engine that was never going to help is pure loss.
     *    "Only" still keeps that engine's own `.m3u8` → `.ts` step; what it drops is the other engine.
     *
     * A channel pinned with the HUD "compatibility mode" toggle ignores this setting — see [ForceMpvStore].
     */
    val liveEnginePreference: Flow<tv.own.owntv.core.player.EnginePreference> = prefsFlow { prefs ->
        prefs[Keys.LIVE_ENGINE]?.let { runCatching { tv.own.owntv.core.player.EnginePreference.valueOf(it) }.getOrNull() }
            ?: tv.own.owntv.core.player.EnginePreference.EXO_FIRST
    }

    suspend fun setLiveEnginePreference(preference: tv.own.owntv.core.player.EnginePreference) {
        context.dataStore.edit { it[Keys.LIVE_ENGINE] = preference.name }
    }

    /**
     * Which engine Movies & Series start on, and whether it may hand over to the other one.
     *
     * Default is [EnginePreference.MPV_FIRST] — the opposite of Live TV's, deliberately: a film is one
     * long open where breadth of codec support beats speed of opening, and mpv has the wider set
     * (DTS/TrueHD audio, odd containers) plus the A/V-sync nudge. ExoPlayer-first is for devices and
     * providers where mpv's path can't open files ExoPlayer plays fine.
     *
     * The two "only" modes drop the automatic handover for anyone whose second engine never works —
     * see [liveEnginePreference] for the reasoning, which is identical. Two caveats specific to VOD:
     * ExoPlayer cannot decode DTS/TrueHD at all (the handoff is refused rather than attempted, so
     * "only ExoPlayer" means those files simply don't play), and the image-subtitle handoff to
     * ExoPlayer is not a fallback — it stays available in both "only" modes, since it is the only way
     * PGS/VOBSUB subtitles are ever rendered.
     *
     * Migrated in place from the older `vod_prefer_exo` switch, which is still read when the new key
     * has never been written: on → [EnginePreference.EXO_FIRST], off → [EnginePreference.MPV_FIRST].
     * Nobody's playback changes on upgrade.
     */
    val vodEnginePreference: Flow<tv.own.owntv.core.player.EnginePreference> = prefsFlow { prefs ->
        prefs[Keys.VOD_ENGINE]?.let { runCatching { tv.own.owntv.core.player.EnginePreference.valueOf(it) }.getOrNull() }
            ?: if (prefs[Keys.VOD_PREFER_EXO] == true) tv.own.owntv.core.player.EnginePreference.EXO_FIRST
            else tv.own.owntv.core.player.EnginePreference.MPV_FIRST
    }

    suspend fun setVodEnginePreference(preference: tv.own.owntv.core.player.EnginePreference) {
        context.dataStore.edit { it[Keys.VOD_ENGINE] = preference.name }
    }

    /** Measure live fps / bitrate / dropped frames for the stream-info overlay. On (default) = the
     *  overlay shows measured values that ExoPlayer doesn't declare for raw MPEG-TS. Off = a hard
     *  escape hatch: no live measuring runs at all (declared values only), for any low-end TV where
     *  the measuring is ever suspected of causing stutter. Never affects the actual playback pipeline. */
    val measuredStreamStats: Flow<Boolean> = prefsFlow { it[Keys.MEASURED_STREAM_STATS] ?: true }

    suspend fun setMeasuredStreamStats(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEASURED_STREAM_STATS] = enabled }
    }

    /** Detailed playback logging. Off (default): the live engine's trace is kept in memory only, as it
     *  always was in a release build. On: the same trace is written to Logcat and to a bounded file, and
     *  it rides along with an exported report — the only way a normal user can produce a live trace at
     *  all, since a release build's diagnostics were previously compile-time off (F18). */
    val detailedDiagnostics: Flow<Boolean> = prefsFlow { it[Keys.DETAILED_DIAGNOSTICS] ?: false }

    suspend fun setDetailedDiagnostics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DETAILED_DIAGNOSTICS] = enabled }
    }

    /** Type a provider channel number on the remote during full-screen live playback to jump straight
     *  to that channel. On (default). Off = number keys are ignored during playback, for anyone whose
     *  remote sends digits accidentally or who doesn't want the keys captured. */
    val directTune: Flow<Boolean> = prefsFlow { it[Keys.DIRECT_TUNE] ?: true }

    suspend fun setDirectTune(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DIRECT_TUNE] = enabled }
    }

    /** Which section a stream belongs to when deciding whether it goes to an external player. */
    enum class ExternalPlayerSection { LIVE_TV, MOVIES, SERIES }

    /** Hand this section's streams to an external player (VLC, MX Player) instead of the in-app engine.
     *  Off by default everywhere.
     *
     *  Movies and Series fall back to [Keys.EXTERNAL_PLAYER], the single global toggle these three keys
     *  replaced — that's the upgrade path, so a user who had it on keeps external playback for exactly
     *  the sections it used to cover. Live TV has no such fallback: the old toggle never routed live
     *  streams out, so inheriting it would silently start sending channels to VLC after an update. */
    val externalPlayerMovies: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_MOVIES] ?: it[Keys.EXTERNAL_PLAYER] ?: false }
    val externalPlayerSeries: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_SERIES] ?: it[Keys.EXTERNAL_PLAYER] ?: false }
    val externalPlayerLive: Flow<Boolean> = prefsFlow { it[Keys.EXTERNAL_PLAYER_LIVE] ?: false }

    fun externalPlayer(section: ExternalPlayerSection): Flow<Boolean> = when (section) {
        ExternalPlayerSection.LIVE_TV -> externalPlayerLive
        ExternalPlayerSection.MOVIES -> externalPlayerMovies
        ExternalPlayerSection.SERIES -> externalPlayerSeries
    }

    /** A download is a movie or an episode, so it follows that section's setting. */
    fun externalPlayerFor(mediaType: tv.own.owntv.core.model.MediaType): Flow<Boolean> =
        if (mediaType == tv.own.owntv.core.model.MediaType.SERIES) externalPlayerSeries else externalPlayerMovies

    suspend fun setExternalPlayer(section: ExternalPlayerSection, enabled: Boolean) {
        val key = when (section) {
            ExternalPlayerSection.LIVE_TV -> Keys.EXTERNAL_PLAYER_LIVE
            ExternalPlayerSection.MOVIES -> Keys.EXTERNAL_PLAYER_MOVIES
            ExternalPlayerSection.SERIES -> Keys.EXTERNAL_PLAYER_SERIES
        }
        context.dataStore.edit { it[key] = enabled }
    }

    /** Surround sound (**off by default — opt-in**). Most users are on TV speakers / 2.0 soundbars, and
     *  forcing a multichannel-LPCM path exposes flaky vendor audio HALs / lying HDMI-ARC chips that claim
     *  5.1 then mis-play it (drained 2× → "fast video, no sound", #25). So default stereo for stability;
     *  users with a real 5.1/7.1 receiver turn this on. On: mpv decodes Dolby/DTS to multichannel LPCM (the
     *  sink picks the layout), with a runaway-detector that auto-falls-back to stereo on a broken output. We
     *  never bitstream/passthrough (its AudioTrack reports no clock and stutters video to a slideshow).
     *  Second, subtler failure mode (confirmed in the field): even when multichannel LPCM plays correctly,
     *  the wider HDMI/ARC buffer adds latency the TV/soundbar doesn't report back, so audio lags video
     *  (lip-sync drift) on VODs. Stereo's small, well-reported buffer stays locked. Hence: default OFF. */
    val surroundSound: Flow<Boolean> = prefsFlow { it[Keys.SURROUND_SOUND] ?: false }


    /**
     * The three-state replacement for [surroundSound] (Auto / Stereo only / Surround).
     *
     * The old boolean was a poor fit for two reasons. It only ever reached **mpv** — Live TV's default
     * engine is ExoPlayer, where "off" changed nothing at all, so a TV that mis-plays multichannel kept
     * mis-playing it however the switch was set. And "off" is the wrong default to *have* to choose: a
     * user with a real receiver should get surround without reading a changelog, and a user whose TV
     * lies about its capabilities should get sound back without knowing why it went.
     *
     * Hence Auto by default — try multichannel, but fall back the instant the output is caught failing
     * (see `AudioOutputPolicy`). The fallback watchdog runs in **all three** modes,
     * including Surround: "no sound" is never what the user picked. Reading falls back to the old
     * boolean so nobody's explicit choice is lost, and nothing is rewritten on upgrade.
     */
    val surroundMode: Flow<SurroundMode> = prefsFlow {
        SurroundMode.of(it[Keys.SURROUND_MODE], it[Keys.SURROUND_SOUND])
    }

    suspend fun setSurroundMode(mode: SurroundMode) {
        context.dataStore.edit {
            it[Keys.SURROUND_MODE] = mode.name
            // Keep the legacy key consistent so a downgrade to 4.1.6 lands somewhere sane.
            it[Keys.SURROUND_SOUND] = mode == SurroundMode.SURROUND
        }
    }

    /** Auto-play the next episode (and roll into the next season) when one finishes. On by default. */
    val autoPlayNext: Flow<Boolean> = prefsFlow { it[Keys.AUTO_PLAY_NEXT] ?: true }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    /** Default zoom/aspect mode applied when playback starts (a `ZoomMode` name). */
    val defaultZoom: Flow<String> = prefsFlow { it[Keys.DEFAULT_ZOOM] ?: "FIT" }

    suspend fun setDefaultZoom(name: String) {
        context.dataStore.edit { it[Keys.DEFAULT_ZOOM] = name }
    }

    /**
     * Volume percent every item starts at (100 = untouched, up to the 150 boost ceiling). A film the
     * user has individually turned up overrides this — see
     * [tv.own.owntv.core.player.PlaybackPrefsStore].
     */
    val defaultVolume: Flow<Int> = prefsFlow { (it[Keys.DEFAULT_VOLUME] ?: 100).coerceIn(0, 150) }

    suspend fun setDefaultVolume(percent: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_VOLUME] = percent.coerceIn(0, 150) }
    }

    /**
     * How far the player's rewind/forward buttons and the seek bar's ◀/▶ jump in a movie or episode.
     *
     * Separate from [liveRewindStepSec] on purpose: skipping an ad break in a film and stepping back
     * through a live channel's archive are different journeys, and one number can't be right for both
     * — which is why the two have always had different hardcoded values (10 s and 30 s).
     */
    val seekStepSec: Flow<Int> = prefsFlow { it[Keys.SEEK_STEP_SEC] ?: SeekSteps.DEFAULT_SEEK_STEP_SEC }

    suspend fun setSeekStepSec(seconds: Int) {
        context.dataStore.edit { it[Keys.SEEK_STEP_SEC] = seconds }
    }

    /**
     * Deinterlacing for interlaced broadcast material (old SD channels that show combing on movement).
     * Off by default, because the TV's own panel processing usually handles it and a filter that isn't
     * needed only costs frames.
     *
     * mpv only, and only while mpv is doing its own rendering — on the direct decoder-to-surface path
     * ([OwnTVPlayer] `vo=mediacodec_embed`) no video filter runs at all, so nothing is inserted there.
     * The setting's description says so rather than pretending otherwise.
     */
    val deinterlace: Flow<Boolean> = prefsFlow { it[Keys.DEINTERLACE] ?: false }

    suspend fun setDeinterlace(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEINTERLACE] = enabled }
    }

    /** How far one press of Rewind/Forward moves inside a live channel's catch-up archive. */
    val liveRewindStepSec: Flow<Int> = prefsFlow { it[Keys.LIVE_REWIND_STEP_SEC] ?: SeekSteps.DEFAULT_LIVE_REWIND_STEP_SEC }

    suspend fun setLiveRewindStepSec(seconds: Int) {
        context.dataStore.edit { it[Keys.LIVE_REWIND_STEP_SEC] = seconds }
    }

    // --- Subtitle appearance (#96): size, text color, screen position, background transparency ---
    // Two levels of opt-in. The master toggle gates everything: while it's off NOTHING here is
    // applied and every renderer keeps its stock look (mpv defaults, the overlay's hardcoded 45%
    // box, and — the case #96 is actually about — SubtitleView's embedded broadcaster styling).
    // Each option then has its own "Default" value, so turning the toggle ON still changes nothing
    // until the user picks something: only the options actually set reach a renderer.

    // Subtitle size is per engine: mpv and ExoPlayer draw the same multiplier at visibly different
    // sizes, so one shared value cannot be right for both. Each new key falls back to the legacy
    // single [Keys.SUB_SCALE] until it is set, so an upgrade keeps the size the user already chose on
    // both engines and nothing changes until they move one of them.

    /** Subtitle scale multiplier for mpv (sub-scale); [SubtitleStyle.SCALE_DEFAULT] = untouched. */
    val subtitleScaleMpv: Flow<Float> = prefsFlow {
        it[Keys.SUB_SCALE_MPV] ?: it[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT
    }

    suspend fun setSubtitleScaleMpv(scale: Float) {
        context.dataStore.edit { it[Keys.SUB_SCALE_MPV] = scale }
    }

    /** Subtitle scale multiplier for the Media3 SubtitleView; [SubtitleStyle.SCALE_DEFAULT] = untouched. */
    val subtitleScaleExo: Flow<Float> = prefsFlow {
        it[Keys.SUB_SCALE_EXO] ?: it[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT
    }

    suspend fun setSubtitleScaleExo(scale: Float) {
        context.dataStore.edit { it[Keys.SUB_SCALE_EXO] = scale }
    }

    /**
     * Master toggle for the custom subtitle look; off = stock rendering everywhere.
     *
     * Unset defaults to *on* for anyone who had already changed the subtitle size back when it was
     * a standalone setting — it lives under this toggle now, so defaulting to off would silently
     * revert their size on upgrade.
     */
    val subtitleStyleEnabled: Flow<Boolean> = prefsFlow { prefs ->
        prefs[Keys.SUB_STYLE_ENABLED] ?: SubtitleStyle.hasScale(prefs[Keys.SUB_SCALE] ?: SubtitleStyle.SCALE_DEFAULT)
    }

    suspend fun setSubtitleStyleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUB_STYLE_ENABLED] = enabled }
    }

    /** Null leaves the renderer's own or embedded subtitle font untouched. */
    val subtitleFont: Flow<AppFontFamily?> = prefsFlow { prefs ->
        prefs[Keys.SUB_FONT]?.let { stored -> AppFontFamily.entries.firstOrNull { it.name == stored } }
    }

    suspend fun setSubtitleFont(font: AppFontFamily?) {
        context.dataStore.edit { prefs ->
            if (font == null) prefs.remove(Keys.SUB_FONT) else prefs[Keys.SUB_FONT] = font.name
        }
    }

    /** Subtitle text color as "#RRGGBB"; blank ([SubtitleStyle.COLOR_DEFAULT]) = untouched. */
    val subtitleColor: Flow<String> = prefsFlow { it[Keys.SUB_COLOR] ?: SubtitleStyle.COLOR_DEFAULT }

    suspend fun setSubtitleColor(hex: String) {
        context.dataStore.edit { it[Keys.SUB_COLOR] = hex.trim() }
    }

    /** One of six fixed screen anchors, or [SubtitleStyle.Position.DEFAULT] = untouched. */
    val subtitlePosition: Flow<SubtitleStyle.Position> =
        prefsFlow { SubtitleStyle.Position.fromKey(it[Keys.SUB_POSITION]) }

    suspend fun setSubtitlePosition(position: SubtitleStyle.Position) {
        context.dataStore.edit { it[Keys.SUB_POSITION] = position.key }
    }

    /** Subtitle background opacity 0..100 (0 = no box, 100 = solid); negative = untouched. */
    val subtitleBgOpacity: Flow<Int> = prefsFlow { it[Keys.SUB_BG_OPACITY] ?: SubtitleStyle.OPACITY_DEFAULT }

    suspend fun setSubtitleBgOpacity(pct: Int) {
        val value = if (pct < SubtitleStyle.OPACITY_MIN) SubtitleStyle.OPACITY_DEFAULT else SubtitleStyle.clampOpacity(pct)
        context.dataStore.edit { it[Keys.SUB_BG_OPACITY] = value }
    }

    /** Audio sync offset in milliseconds (mpv audio-delay); +ve delays audio. */
    val audioDelayMs: Flow<Int> = prefsFlow { it[Keys.AUDIO_DELAY_MS] ?: 0 }

    suspend fun setAudioDelayMs(ms: Int) {
        context.dataStore.edit { it[Keys.AUDIO_DELAY_MS] = ms }
    }

    // --- CH+- key paging (browse panels): master toggle + per-direction skip counts ---
    // Clamped to [1, ChNavLimits.HARD_MAX] on write so an accidental huge value can never persist.
    val chNavEnabled: Flow<Boolean> = prefsFlow { it[Keys.CH_NAV_ENABLED] ?: true }
    suspend fun setChNavEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CH_NAV_ENABLED] = enabled }
    }
    /** CH+ skip count (jumps this many items toward the first item). */
    val chNavUpSkip: Flow<Int> = prefsFlow {
        (it[Keys.CH_NAV_UP_SKIP] ?: ChNavLimits.DEFAULT_SKIP).coerceIn(1, ChNavLimits.HARD_MAX)
    }
    suspend fun setChNavUpSkip(n: Int) {
        context.dataStore.edit { it[Keys.CH_NAV_UP_SKIP] = n.coerceIn(1, ChNavLimits.HARD_MAX) }
    }
    /** CH− skip count (jumps this many items toward the last item). */
    val chNavDownSkip: Flow<Int> = prefsFlow {
        (it[Keys.CH_NAV_DOWN_SKIP] ?: ChNavLimits.DEFAULT_SKIP).coerceIn(1, ChNavLimits.HARD_MAX)
    }
    suspend fun setChNavDownSkip(n: Int) {
        context.dataStore.edit { it[Keys.CH_NAV_DOWN_SKIP] = n.coerceIn(1, ChNavLimits.HARD_MAX) }
    }

    /** Configurable remote shortcuts. An absent key means factory defaults; an empty set means none. */
    val remoteShortcutBindings: Flow<List<RemoteShortcutBinding>> = prefsFlow { prefs ->
        if (Keys.REMOTE_SHORTCUT_BINDINGS in prefs) {
            RemoteShortcutBindings.decode(prefs[Keys.REMOTE_SHORTCUT_BINDINGS].orEmpty())
        } else {
            RemoteShortcutBindings.defaults
        }
    }

    suspend fun setRemoteShortcutBinding(binding: RemoteShortcutBinding) {
        context.dataStore.edit { prefs ->
            val current = if (Keys.REMOTE_SHORTCUT_BINDINGS in prefs) {
                RemoteShortcutBindings.decode(prefs[Keys.REMOTE_SHORTCUT_BINDINGS].orEmpty())
            } else {
                RemoteShortcutBindings.defaults
            }
            prefs[Keys.REMOTE_SHORTCUT_BINDINGS] =
                RemoteShortcutBindings.encode(RemoteShortcutBindings.replace(current, binding))
        }
    }

    suspend fun removeRemoteShortcutBinding(keyCode: Int, press: RemoteShortcutPress) {
        context.dataStore.edit { prefs ->
            val current = if (Keys.REMOTE_SHORTCUT_BINDINGS in prefs) {
                RemoteShortcutBindings.decode(prefs[Keys.REMOTE_SHORTCUT_BINDINGS].orEmpty())
            } else {
                RemoteShortcutBindings.defaults
            }
            prefs[Keys.REMOTE_SHORTCUT_BINDINGS] =
                RemoteShortcutBindings.encode(current.filterNot { it.keyCode == keyCode && it.press == press })
        }
    }

    suspend fun resetRemoteShortcutBindings() {
        context.dataStore.edit { it.remove(Keys.REMOTE_SHORTCUT_BINDINGS) }
    }

    // --- Manual panel widths: per browse section, a master toggle + one percentage per panel ---
    // While the toggle is off the screens keep their stock layout code path entirely, so the feature
    // can't affect anyone who never opens it. Percentages are clamped on both read and write.
    private fun panelOnKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_ON
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_ON
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_ON
    }
    private fun panelCategoryKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_CAT
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_CAT
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_CAT
    }
    private fun panelListKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_LIST
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_LIST
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_LIST
    }
    private fun panelPreviewKey(s: PanelSection) = when (s) {
        PanelSection.LIVE -> Keys.PANEL_W_LIVE_PREVIEW
        PanelSection.MOVIES -> Keys.PANEL_W_MOVIES_PREVIEW
        PanelSection.SERIES -> Keys.PANEL_W_SERIES_PREVIEW
    }

    private fun livePreviewPanelHidden(p: Preferences): Boolean =
        (p[Keys.PANEL_W_LIVE_ON] ?: false) &&
            (p[Keys.PANEL_W_LIVE_CAT] ?: 0) > 0 &&
            (p[Keys.PANEL_W_LIVE_LIST] ?: 0) > 0 &&
            p[Keys.PANEL_W_LIVE_PREVIEW] == 0

    fun panelWidthEnabled(s: PanelSection): Flow<Boolean> = prefsFlow { it[panelOnKey(s)] ?: false }

    /**
     * The section's three shares, or null when nothing has been saved yet (the dialog then seeds
     * itself from the stock layout). Read back through [balanceToTotal] so a value written by a
     * different build can never leave the row over- or under-filled.
     */
    fun panelShares(s: PanelSection): Flow<PanelShares?> = prefsFlow { p ->
        val category = p[panelCategoryKey(s)]
        val list = p[panelListKey(s)]
        val preview = p[panelPreviewKey(s)]
        if (category == null || list == null || preview == null || category <= 0 || list <= 0 || preview < 0) {
            null
        } else {
            balanceToTotal(
                PanelShares(
                    PanelWidthLimits.snap(category),
                    PanelWidthLimits.snap(list),
                    PanelWidthLimits.snapPreview(preview),
                ),
            )
        }
    }

    /** "Okay" in the panel-width dialog — the toggle and all three shares land in one write. */
    suspend fun setPanelWidths(s: PanelSection, enabled: Boolean, shares: PanelShares) {
        val safe = balanceToTotal(shares)
        context.dataStore.edit {
            it[panelOnKey(s)] = enabled
            it[panelCategoryKey(s)] = safe.category
            it[panelListKey(s)] = safe.list
            it[panelPreviewKey(s)] = safe.preview
            if (s == PanelSection.LIVE && enabled && safe.preview == 0) {
                it[Keys.LIVE_PREVIEW] = false
            }
        }
    }

    // --- Guide column widths: toggle + two percentages that must total exactly 100 ---
    val guideWidthEnabled: Flow<Boolean> = prefsFlow { it[Keys.GUIDE_WIDTH_ON] ?: false }

    val guideWidthShares: Flow<GuideWidthShares?> = prefsFlow { prefs ->
        val channels = prefs[Keys.GUIDE_WIDTH_CHANNELS]
        val epg = prefs[Keys.GUIDE_WIDTH_EPG]
        if (channels == null || epg == null) null
        else normalizeGuideWidths(GuideWidthShares(channels, epg))
    }

    suspend fun setGuideWidths(enabled: Boolean, shares: GuideWidthShares) {
        if (!shares.isValid) return
        context.dataStore.edit {
            it[Keys.GUIDE_WIDTH_ON] = enabled
            it[Keys.GUIDE_WIDTH_CHANNELS] = shares.channels
            it[Keys.GUIDE_WIDTH_EPG] = shares.epg
        }
    }

    /** Preferred audio language (ISO code, mpv alang); blank = no preference. */
    val preferredAudioLang: Flow<String> = prefsFlow { it[Keys.PREF_AUDIO_LANG] ?: "" }

    suspend fun setPreferredAudioLang(lang: String) {
        context.dataStore.edit { it[Keys.PREF_AUDIO_LANG] = lang }
    }

    /** Preferred subtitle language (ISO code, mpv slang); blank = no preference. */
    val preferredSubLang: Flow<String> = prefsFlow { it[Keys.PREF_SUB_LANG] ?: "" }

    suspend fun setPreferredSubLang(lang: String) {
        context.dataStore.edit { it[Keys.PREF_SUB_LANG] = lang }
    }

    // --- OpenSubtitles search language filter ---
    // Deliberately its own setting rather than reusing [preferredSubLang]: that one picks an EMBEDDED
    // track inside the stream (a 15-language mpv list that has no Greek, among others), which is a
    // different question from "which languages should an online search return". Default OFF = show
    // everything OpenSubtitles has for the title and let the user choose.

    /** Whether OpenSubtitles results are restricted to [subSearchLanguages]. Off = all languages. */
    val subSearchFilterEnabled: Flow<Boolean> = prefsFlow { it[Keys.SUB_SEARCH_FILTER] ?: false }

    suspend fun setSubSearchFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SUB_SEARCH_FILTER] = enabled }
    }

    /** Chosen OpenSubtitles language codes (ISO 639-1, e.g. "el,en"). Only applied when the filter is on. */
    val subSearchLanguages: Flow<String> = prefsFlow { it[Keys.SUB_SEARCH_LANGS] ?: "" }

    suspend fun setSubSearchLanguages(codes: String) {
        context.dataStore.edit { it[Keys.SUB_SEARCH_LANGS] = codes.trim() }
    }

    /**
     * The Settings rows the user pinned to the Quick group, in the order they should appear.
     * Stored as a comma-joined list of row keys. Unknown keys are kept here but ignored when the list
     * is drawn, so a pin that belongs to a row hidden by the current theme/profile survives.
     */
    val quickPinnedKeys: Flow<List<String>> = prefsFlow { prefs ->
        (prefs[Keys.QUICK_PINNED] ?: DEFAULT_QUICK_PINNED.joinToString(","))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun setQuickPinnedKeys(keys: List<String>) {
        context.dataStore.edit { it[Keys.QUICK_PINNED] = keys.joinToString(",") }
    }

    /**
     * The order the user arranged one long-press content menu into, as a comma-joined list of action
     * keys. Empty means "as shipped". Stored per menu — Live, Movies, Series and Episodes are four
     * independent lists. Keys that no longer exist are ignored on read and actions the list has never
     * heard of are appended, which is what lets a later release add an action without it vanishing.
     */
    fun menuOrder(menu: String): Flow<List<String>> = prefsFlow { prefs ->
        (prefs[menuOrderKey(menu)] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun setMenuOrder(menu: String, keys: List<String>) {
        context.dataStore.edit { it[menuOrderKey(menu)] = keys.joinToString(",") }
    }

    private fun menuOrderKey(menu: String) = stringPreferencesKey("settings_menu_order_$menu")

    // --- Per-source auto-refresh (Off / Startup / staleness threshold) ---
    // Stored as a JSON map { "<sourceId>": "<EnumName>" } in the owntv_settings DataStore — migration-safe
    // (Room uses destructive migrations, so anything that must survive a schema bump lives here). Reuses the
    // existing lastSyncAt columns (SourceEntity.lastSyncAt for playlists, EpgSource.lastSyncAt for EPG) as the
    // "last successful sync" timestamp; nothing new is stored for that.

    /** Per-source playlist auto-refresh selection. Missing ids default to [PlaylistRefresh.OFF]. */
    val playlistAutoRefresh: Flow<Map<Long, PlaylistRefresh>> =
        prefsFlow { prefs ->
            readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]).entries.mapNotNull { (key, value) ->
                key.toLongOrNull()?.let { it to PlaylistRefresh.parse(value) }
            }.toMap()
        }

    /** Per-source EPG auto-refresh selection. Missing ids default to [EpgAutoRefresh.OFF]. */
    val epgAutoRefresh: Flow<Map<Long, EpgAutoRefresh>> =
        prefsFlow { prefs -> parseRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]) { EpgAutoRefresh.valueOf(it) } }

    /**
     * EPG sources whose own `<icon src>` channel logos should replace the playlist's logos. Per source,
     * so one feed can supply logos while another only supplies programmes. Missing ids default to off.
     */
    val epgUseLogos: Flow<Set<Long>> = prefsFlow { prefs ->
        parseRefreshMap(prefs[Keys.EPG_USE_LOGOS])
            .filterValues { it.toBoolean() }
            .keys.mapNotNullTo(LinkedHashSet()) { it.toLongOrNull() }
    }

    suspend fun setEpgUseLogos(sourceId: Long, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_USE_LOGOS] = writeRefreshMap(readRefreshMap(prefs[Keys.EPG_USE_LOGOS]), sourceId, enabled.toString())
        }
    }

    suspend fun setPlaylistAutoRefresh(sourceId: Long, refresh: PlaylistRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]), sourceId, refresh.serialize())
        }
    }

    suspend fun setEpgAutoRefresh(sourceId: Long, mode: EpgAutoRefresh) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_AUTO_REFRESH] = writeRefreshMap(readRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]), sourceId, mode.name)
        }
    }

    /**
     * One-time migration of the legacy binary `refresh_source_ids` set → per-source `STARTUP` entries.
     * Idempotent (guarded by [Keys.REFRESH_MIGRATED]) and non-overwriting: if the new
     * [playlistAutoRefresh] map is already non-empty (user picked a mode in the new UI, or a prior migration
     * ran), we only flip the flag and return — never clobbering existing selections.
     */
    suspend fun migrateLegacyRefreshFlags() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.REFRESH_MIGRATED] == true) return@edit
            val existing = readRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH])
            val legacyIds = prefs[Keys.REFRESH_SOURCE_IDS].orEmpty()
            // Only migrate into an empty map — never overwrite selections already made in the new UI.
            if (existing.isEmpty() && legacyIds.isNotEmpty()) {
                val migrated = legacyIds.associate { it to PlaylistAutoRefresh.STARTUP.name }
                prefs[Keys.PLAYLIST_AUTO_REFRESH] =
                    org.json.JSONObject(migrated).toString()
            }
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    /**
     * One-shot guard for the post-migration EPG refill (audit D4).
     *
     * `MIGRATION_8_9` deletes every row in `epg_programmes` (it adds `contentHash` and a natural-key
     * unique index, which the old rows can't satisfy) and nothing schedules a re-fetch — so an
     * upgrading user was left with an empty guide until they happened to re-sync EPG by hand. This
     * runs the detection exactly once per install, which also covers users who passed through 8→9
     * long ago and are still sitting on an empty guide.
     */
    val epgRefillChecked: Flow<Boolean> = prefsFlow { it[Keys.EPG_REFILL_CHECKED] == true }

    suspend fun markEpgRefillChecked() {
        context.dataStore.edit { prefs -> prefs[Keys.EPG_REFILL_CHECKED] = true }
    }

    /**
     * Interrupted-restore marker (B2). A restore writes to the database *and* to several DataStore
     * files; only the row writes can share a transaction, so a crash or a pulled plug part-way
     * through leaves a half-applied merge that nothing would otherwise notice. The marker is written
     * before the first write and removed after the last one, so a value still present at the next
     * launch means "that restore didn't finish". The value is the backup file name plus the sections
     * that were being applied — enough to tell the user what to re-run, and never a secret.
     */
    val restoreInProgress: Flow<String?> = prefsFlow { it[Keys.RESTORE_IN_PROGRESS] }

    suspend fun markRestoreStarted(description: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RESTORE_IN_PROGRESS] = description }
    }

    suspend fun clearRestoreMarker() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.RESTORE_IN_PROGRESS) }
    }

    private inline fun <reified E : Enum<E>> parseRefreshMap(raw: String?, valueOf: (String) -> E): Map<Long, E> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Long, E>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val name = obj.optString(key)
            val mode = runCatching { valueOf(name) }.getOrNull() ?: continue
            out[id] = mode
        }
        return out
    }

    private fun readRefreshMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return LinkedHashMap()
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    private fun writeRefreshMap(map: MutableMap<String, String>, sourceId: Long, value: String): String {
        map[sourceId.toString()] = value
        return org.json.JSONObject(map.toMap()).toString()
    }

    /** Whether focusing a channel auto-plays it in the Live preview pane. */
    val livePreviewEnabled: Flow<Boolean> = prefsFlow { it[Keys.LIVE_PREVIEW] ?: true }

    /** False only while a saved, enabled Live layout has intentionally hidden its preview panel. */
    val livePreviewPanelActive: Flow<Boolean> = prefsFlow { !livePreviewPanelHidden(it) }

    suspend fun setLivePreviewEnabled(enabled: Boolean) {
        context.dataStore.edit {
            if (!enabled || !livePreviewPanelHidden(it)) it[Keys.LIVE_PREVIEW] = enabled
        }
    }

    /**
     * Whether the expanded Home hero plays its video. The hero preview holds a live decoder for as
     * long as the user browses Home, which is the one piece of background playback that had no off
     * switch. Defaults on, but off on a low-RAM device — the same test [PlayerBudget] already uses to
     * decide the player's memory budget, and the devices where a second video pipeline hurts most.
     */
    val heroPreviewEnabled: Flow<Boolean> = prefsFlow { it[Keys.HERO_PREVIEW] ?: heroPreviewDefault }

    /** The value [heroPreviewEnabled] reports until the user picks one; also the settings row's
     *  initial value, so the chip does not read "On" for a frame on a device where it is off. */
    val heroPreviewDefault: Boolean get() = !lowSpecDevice

    suspend fun setHeroPreviewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HERO_PREVIEW] = enabled }
    }

    private val lowSpecDevice: Boolean by lazy { tv.own.owntv.core.player.PlayerBudget.of(context).lowSpec }

    /** Whether the Live preview plays audio (off by default so browsing stays quiet). */
    val livePreviewAudio: Flow<Boolean> = prefsFlow { it[Keys.LIVE_PREVIEW_AUDIO] ?: false }

    suspend fun setLivePreviewAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_PREVIEW_AUDIO] = enabled }
    }

    /** Use HDR output when the video and display support it. */
    val hdrEnabled: Flow<Boolean> = prefsFlow { it[Keys.HDR_ENABLED] ?: true }

    suspend fun setHdrEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HDR_ENABLED] = enabled }
    }

    /**
     * Switch the display's refresh rate to match the video frame rate (24/25/30/50/60 fps) during
     * full-screen playback, and restore it on exit. Applies to both engines and to Live TV as well as
     * VOD. Default off; users whose display switches cleanly can opt in.
     */
    val autoFrameRate: Flow<Boolean> = prefsFlow { prefs ->
        // Also report Off before the startup migration coroutines complete, so an auto-resumed channel
        // cannot briefly request a display-mode switch on the first launch after either safety reset.
        val migrated = prefs[Keys.AUTO_FRAME_RATE_RESET_416] == true &&
            prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] == true
        if (migrated) prefs[Keys.AUTO_FRAME_RATE] ?: false else false
    }

    /**
     * Whether the one-time Auto-frame-rate suggestion has already been answered (F13).
     *
     * The suggestion only appears when a 24/25/50 fps stream is playing full-screen on a display whose
     * refresh rate is not a multiple of it *and* a matching mode exists — the case where mpv's direct
     * `mediacodec_embed` path judders and AFR is the only cure. Answering it either way (or turning AFR
     * on by hand) sets this for good; it is never shown twice.
     */
    val autoFrameRatePrompted: Flow<Boolean> = prefsFlow { it[Keys.AUTO_FRAME_RATE_PROMPTED] ?: false }

    suspend fun setAutoFrameRatePrompted() {
        context.dataStore.edit { it[Keys.AUTO_FRAME_RATE_PROMPTED] = true }
    }

    suspend fun setAutoFrameRate(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.AUTO_FRAME_RATE] = enabled
            it[Keys.AUTO_FRAME_RATE_RESET_416] = true
            it[Keys.AUTO_FRAME_RATE_RESET_PRE12] = true
            // Someone who has found the setting doesn't need to be told it exists.
            if (enabled) it[Keys.AUTO_FRAME_RATE_PROMPTED] = true
        }
    }

    /** v4.1.6 only: force AFR Off exactly once, including for users who previously enabled it. */
    suspend fun migrateAutoFrameRate416() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.AUTO_FRAME_RATE_RESET_416] == true) return@edit
            prefs[Keys.AUTO_FRAME_RATE] = false
            prefs[Keys.AUTO_FRAME_RATE_RESET_416] = true
        }
    }

    /**
     * v4.2.0 one-shot: force AFR Off exactly once on devices below Android 12.
     *
     * Only from API 31 does a display report [android.view.Display.Mode.getAlternativeRefreshRates] —
     * the set of rates it can switch to *without* blanking. Below that the app is switching blind, so
     * every mode change risks an HDMI re-handshake that blacks the picture out mid-programme; this is
     * what users on older TV boxes report as "the stream pauses when the frame rate changes". Frame-rate
     * snapping and the change cooldown in `FrameRateController` make it rarer, but
     * nothing in the platform can make it seamless there.
     *
     * So the default is corrected once, silently, rather than left on from an earlier install. Turning it
     * back on afterwards is a deliberate choice (Settings warns first on these devices) and is never
     * overridden again — the flag is written on every device, including Android 12+ where nothing is
     * reset, so this can only ever run once.
     */
    suspend fun migrateAutoFrameRatePre12() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] == true) return@edit
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                prefs[Keys.AUTO_FRAME_RATE] = false
            }
            prefs[Keys.AUTO_FRAME_RATE_RESET_PRE12] = true
        }
    }

    /**
     * Mirror continue-watching rows into Android TV home surfaces.
     *
     * Every publish path in `TvHomeRepository` reads this one flow, so it is also where a host that
     * has no business on a TV home screen is stopped: a false [CoreBuildInfo.tvHome] pins it off and
     * the stored preference is never consulted. Without that the phone app published Watch Next rows
     * after every sync — into a content provider that does not exist and permissions it explicitly
     * removes — and only a `runCatching` in the sync worker kept it quiet.
     */
    val androidTvHomeEnabled: Flow<Boolean> =
        if (!CoreBuildInfo.tvHome) flowOf(false) else prefsFlow { it[Keys.ANDROID_TV_HOME] ?: true }

    suspend fun setAndroidTvHomeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANDROID_TV_HOME] = enabled }
    }

    /** The source shown as "active" in the sidebar; -1 = none chosen (fall back to the first source). */
    val defaultSourceId: Flow<Long> = prefsFlow { it[Keys.DEFAULT_SOURCE] ?: -1L }

    suspend fun setDefaultSource(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_SOURCE] = id }
    }

    /** User-chosen download base folder; blank = app-specific storage. */
    val downloadRoot: Flow<String> = prefsFlow { it[Keys.DOWNLOAD_ROOT] ?: "" }

    suspend fun setDownloadRoot(path: String) {
        context.dataStore.edit { it[Keys.DOWNLOAD_ROOT] = path }
    }

    val themeMode: Flow<ThemeMode> = prefsFlow { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val uiZoomPercent: Flow<Int> = prefsFlow { prefs ->
        UiZoom.clamp(prefs[Keys.UI_ZOOM_PCT] ?: UiZoom.DEFAULT)
    }

    suspend fun setUiZoomPercent(percent: Int) {
        context.dataStore.edit { it[Keys.UI_ZOOM_PCT] = UiZoom.clamp(percent) }
    }

    val fontCustomization: Flow<FontCustomization> = prefsFlow { prefs ->
        FontCustomization(
            sizePercent = UiFontScale.clamp(prefs[Keys.FONT_SIZE_PCT] ?: UiFontScale.DEFAULT),
            mainFamily = AppFontFamily.fromStored(
                prefs[Keys.MAIN_FONT_FAMILY],
                AppFontFamily.SYSTEM_SANS,
            ),
            popupFamily = AppFontFamily.fromStored(
                prefs[Keys.POPUP_FONT_FAMILY],
                AppFontFamily.LORA,
            ),
            popupFontSizePercent = PopupFontScale.clamp(
                prefs[Keys.POPUP_FONT_SIZE_PCT] ?: PopupFontScale.DEFAULT,
            ),
            popupSizePercent = PopupSizeScale.clamp(
                prefs[Keys.POPUP_SIZE_PCT] ?: PopupSizeScale.DEFAULT,
            ),
        )
    }

    /** Saves the staged font dialog in one DataStore transaction, so the app never sees half a preset. */
    suspend fun setFontCustomization(value: FontCustomization) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FONT_SIZE_PCT] = UiFontScale.clamp(value.sizePercent)
            prefs[Keys.POPUP_FONT_SIZE_PCT] = PopupFontScale.clamp(value.popupFontSizePercent)
            prefs[Keys.POPUP_SIZE_PCT] = PopupSizeScale.clamp(value.popupSizePercent)
            prefs[Keys.MAIN_FONT_FAMILY] = value.mainFamily.name
            prefs[Keys.POPUP_FONT_FAMILY] = value.popupFamily.name
        }
    }

    /** Docked mini-player size as a percentage of screen width (clamped to the allowed range). */
    val miniPlayerSizePct: Flow<Int> = prefsFlow { prefs ->
        tv.own.owntv.core.player.MiniPlayerSize.clamp(prefs[Keys.MINI_PLAYER_SIZE_PCT] ?: tv.own.owntv.core.player.MiniPlayerSize.DEFAULT)
    }

    suspend fun setMiniPlayerSizePct(percent: Int) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_SIZE_PCT] = tv.own.owntv.core.player.MiniPlayerSize.clamp(percent) }
    }

    /** Docked mini-player screen position (a [tv.own.owntv.core.player.MiniPlayerPosition] name). */
    val miniPlayerPosition: Flow<String> = prefsFlow { prefs ->
        prefs[Keys.MINI_PLAYER_POSITION] ?: tv.own.owntv.core.player.MiniPlayerPosition.DEFAULT.name
    }

    suspend fun setMiniPlayerPosition(name: String) {
        context.dataStore.edit { it[Keys.MINI_PLAYER_POSITION] = name }
    }

    /** Live TV latency preset (a [LiveLatency] name). */
    val liveLatencyMode: Flow<String> = prefsFlow { prefs ->
        // Also report Balanced before the startup migration coroutine completes, so an auto-resumed
        // channel cannot briefly reuse an unsafe low/custom latency on the first 4.1.6 launch.
        if (prefs[Keys.LIVE_LATENCY_RESET_416] == true) {
            prefs[Keys.LIVE_LATENCY_MODE] ?: LiveLatency.DEFAULT.name
        } else {
            LiveLatency.BALANCED.name
        }
    }

    suspend fun setLiveLatencyMode(name: String) {
        context.dataStore.edit {
            it[Keys.LIVE_LATENCY_MODE] = name
            it[Keys.LIVE_LATENCY_RESET_416] = true
        }
    }

    /** v4.1.6 only: force live latency to Balanced exactly once, including existing custom choices. */
    suspend fun migrateLiveLatency416() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.LIVE_LATENCY_RESET_416] == true) return@edit
            prefs[Keys.LIVE_LATENCY_MODE] = LiveLatency.BALANCED.name
            prefs[Keys.LIVE_LATENCY_RESET_416] = true
        }
    }

    /** Custom live buffer seconds, used when the preset is [LiveLatency.CUSTOM]. */
    val liveLatencyCustomSecs: Flow<Int> = prefsFlow { prefs ->
        LiveBuffer.clampCustom(prefs[Keys.LIVE_LATENCY_CUSTOM_SECS] ?: LiveBuffer.CUSTOM_DEFAULT)
    }

    suspend fun setLiveLatencyCustomSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_LATENCY_CUSTOM_SECS] = LiveBuffer.clampCustom(secs) }
    }

    /**
     * "Pre-buffer" (F07): how many seconds to fill before a live channel starts playing,
     * and how many to refill after a rebuffer. 0 = Off, which keeps the engines' existing 1 s / 2 s
     * start thresholds. A per-playlist override lives on `SourceEntity.livePrerollSecs`.
     */
    val livePrerollSecs: Flow<Int> = prefsFlow { prefs ->
        (prefs[Keys.LIVE_PREROLL_SECS] ?: LiveBuffer.PREROLL_OFF).coerceIn(0, 30)
    }

    suspend fun setLivePrerollSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_PREROLL_SECS] = secs.coerceIn(0, 30) }
    }

    /**
     * "Give up after": how long a live channel may take to produce a picture before OwnTV stops trying
     * and shows the error, however many engine/format combinations the fallback ladder has left.
     *
     * 0 = Never, the behaviour before this setting existed: each rung keeps its own timeout and nothing
     * bounds their sum, which on a removed channel is about a minute and a half of black screen. Kept as
     * an escape hatch for a genuinely slow panel. A provider's own `Retry-After` countdown is never
     * charged against this.
     */
    val liveTuneTimeoutSecs: Flow<Int> = prefsFlow { prefs ->
        (prefs[Keys.LIVE_TUNE_TIMEOUT_SECS] ?: tv.own.owntv.core.player.PlaybackDefaults.LIVE_TUNE_BUDGET_SECS)
            .coerceIn(0, 60)
    }

    suspend fun setLiveTuneTimeoutSecs(secs: Int) {
        context.dataStore.edit { it[Keys.LIVE_TUNE_TIMEOUT_SECS] = secs.coerceIn(0, 60) }
    }

    /** Effective live buffer in seconds the engines apply (null = keep engine defaults, i.e. Balanced). */
    val liveBufferSeconds: Flow<Int?> = combine(liveLatencyMode, liveLatencyCustomSecs) { mode, custom ->
        LiveBuffer.effectiveSeconds(LiveLatency.fromName(mode), custom)
    }

    val accent: Flow<AccentColor> = prefsFlow { prefs ->
        prefs[Keys.ACCENT]?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
            ?: AccentColor.TEAL
    }

    /** Picking a preset clears any custom accent so the preset takes effect. */
    suspend fun setAccent(accent: AccentColor) {
        context.dataStore.edit {
            it[Keys.ACCENT] = accent.name
            it[Keys.ACCENT_CUSTOM] = ""
        }
    }

    /** Custom accent as a hex string ("#52DBC8"); blank = use the [accent] preset. */
    val customAccent: Flow<String> = prefsFlow { it[Keys.ACCENT_CUSTOM] ?: "" }

    suspend fun setCustomAccent(hex: String) {
        context.dataStore.edit { it[Keys.ACCENT_CUSTOM] = hex.trim() }
    }

    // --- Focus highlight (#121): the ring around whatever the remote is pointing at ---
    /** Focus ring color as a hex string; blank = follow the accent (the shipped behaviour). */
    val focusHighlight: Flow<String> = prefsFlow { it[Keys.FOCUS_HIGHLIGHT] ?: "" }

    /** Focus ring width in dp; 2 dp is the shipped default. */
    val focusHighlightWidth: Flow<Int> = prefsFlow { it[Keys.FOCUS_HIGHLIGHT_WIDTH] ?: 2 }

    suspend fun setFocusHighlight(hex: String) {
        context.dataStore.edit { it[Keys.FOCUS_HIGHLIGHT] = hex.trim() }
    }

    suspend fun setFocusHighlightWidth(dp: Int) {
        context.dataStore.edit { it[Keys.FOCUS_HIGHLIGHT_WIDTH] = dp }
    }

    // --- Glass effect: background image + which surfaces go translucent + how translucent ---
    /** Absolute path to the user's background image (copied into app-private storage); blank = off. */
    val bgImagePath: Flow<String> = prefsFlow { it[Keys.BG_IMAGE_PATH] ?: "" }

    /** Glass scope as a [GlassConfig] bitfield. Empty scope = feature off. */
    val glassConfig: Flow<tv.own.owntv.core.theme.GlassConfig> = prefsFlow { p ->
        val bits = p[Keys.GLASS_SCOPE] ?: GLASS_SCOPE_DEFAULT_BITS
        val alphaPct = p[Keys.GLASS_ALPHA] ?: GLASS_ALPHA_DEFAULT_PCT
        val blurPct = p[Keys.GLASS_BLUR] ?: GLASS_BLUR_DEFAULT_PCT
        val highlightPct = p[Keys.GLASS_HIGHLIGHT] ?: GLASS_HIGHLIGHT_DEFAULT_PCT
        val allowFullTransparency = p[Keys.GLASS_ALLOW_FULL_TRANSPARENCY] ?: false
        val depthEffects = p[Keys.GLASS_DEPTH_EFFECTS] ?: true
        val glint = p[Keys.GLASS_GLINT] ?: true
        val preset = tv.own.owntv.core.theme.GlassPreset.fromStored(
            name = p[Keys.GLASS_PRESET],
            customAlpha = alphaPct / 100f,
            customBlur = blurPct / 100f,
        )
        tv.own.owntv.core.theme.GlassConfig.fromBitmask(
            bits,
            alpha = alphaPct / 100f,
            blurStrength = blurPct / 100f,
            preset = preset,
            highlightStrength = highlightPct / 100f,
            allowFullTransparency = allowFullTransparency,
            depthEffects = depthEffects,
            glint = glint,
        )
    }

    /** Persist the background image path. Pass "" to clear (turn glass off). */
    suspend fun setBgImagePath(path: String) {
        context.dataStore.edit { it[Keys.BG_IMAGE_PATH] = path.trim() }
    }

    /** Persist the glass scope bitfield (see [tv.own.owntv.core.theme.GlassConfig.toBitmask]). */
    suspend fun setGlassScopeBitmask(bits: Int) {
        context.dataStore.edit { it[Keys.GLASS_SCOPE] = bits }
    }

    /** Select a tuned preset. Custom values remain stored so returning to CUSTOM restores them. */
    suspend fun setGlassPreset(preset: tv.own.owntv.core.theme.GlassPreset) {
        context.dataStore.edit { it[Keys.GLASS_PRESET] = preset.name }
    }

    /** Persist glass alpha as an integer 0..100. */
    suspend fun setGlassAlphaPercent(pct: Int, currentBlurPct: Int) {
        context.dataStore.edit {
            it[Keys.GLASS_ALPHA] = pct.coerceIn(0, 100)
            it[Keys.GLASS_BLUR] = currentBlurPct.coerceIn(0, 100)
            it[Keys.GLASS_PRESET] = tv.own.owntv.core.theme.GlassPreset.CUSTOM.name
        }
    }

    /** Persist the backdrop blur ("frost") strength as an integer 0..100. 0 = Tier-1 translucency only. */
    suspend fun setGlassBlurPercent(pct: Int, currentAlphaPct: Int) {
        context.dataStore.edit {
            it[Keys.GLASS_ALPHA] = currentAlphaPct.coerceIn(0, 100)
            it[Keys.GLASS_BLUR] = pct.coerceIn(0, 100)
            it[Keys.GLASS_PRESET] = tv.own.owntv.core.theme.GlassPreset.CUSTOM.name
        }
    }

    /** Focused lens/rim light strength. 55% preserves the original tuned appearance exactly. */
    suspend fun setGlassHighlightPercent(pct: Int) {
        context.dataStore.edit { it[Keys.GLASS_HIGHLIGHT] = pct.coerceIn(0, 100) }
    }

    suspend fun setGlassAllowFullTransparency(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLASS_ALLOW_FULL_TRANSPARENCY] = enabled }
    }

    suspend fun setGlassDepthEffects(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLASS_DEPTH_EFFECTS] = enabled }
    }

    /** The sweep of light a glass pane arrives with. */
    suspend fun setGlassGlint(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLASS_GLINT] = enabled }
    }

    /** Avatar for the current (placeholder) profile until real profiles arrive in the wizard. */
    val avatarId: Flow<Int> = prefsFlow { it[Keys.AVATAR_ID] ?: 0 }

    suspend fun setAvatarId(id: Int) {
        context.dataStore.edit { it[Keys.AVATAR_ID] = id }
    }

    /** Active profile id; -1 means first-run / setup not yet completed. */
    val activeProfileId: Flow<Long> = prefsFlow { it[Keys.ACTIVE_PROFILE] ?: -1L }

    suspend fun setActiveProfile(id: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }
    }

    // --- Global proxy (Approach 1 — one app-wide HTTP proxy) ---
    // Covers all OkHttp traffic (playlist/API/EPG/images/downloads/updates/weather + ExoPlayer) and mpv
    // playback via its http-proxy option. Per-source overrides and SOCKS are future work; the proxy
    // password is intentionally NOT part of settings backup/export — see extras/PROXY_SUPPORT_PLAN.md.

    /** Live snapshot of the proxy settings as a single object (consumed by ProxyConfigHolder). */
    val proxyConfig: Flow<tv.own.owntv.core.network.ProxyConfig> = prefsFlow { p ->
        tv.own.owntv.core.network.ProxyConfig(
            enabled = p[Keys.PROXY_ENABLED] ?: false,
            host = p[Keys.PROXY_HOST] ?: "",
            port = p[Keys.PROXY_PORT] ?: 0,
            username = p[Keys.PROXY_USER] ?: "",
            password = p[Keys.PROXY_PASS] ?: "",
        )
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PROXY_ENABLED] = enabled }
    }

    /** Persist the proxy form in one write (enabled + host/port/user/pass). Blank user/pass = no auth.
     *  Port is clamped to a valid range; 0 means "unset". */
    suspend fun saveProxy(enabled: Boolean, host: String, port: Int, username: String, password: String) {
        context.dataStore.edit {
            it[Keys.PROXY_ENABLED] = enabled
            it[Keys.PROXY_HOST] = host.trim()
            it[Keys.PROXY_PORT] = port.coerceIn(0, 65535)
            it[Keys.PROXY_USER] = username.trim()
            it[Keys.PROXY_PASS] = password
        }
    }

    // --- Backup / restore of pure UI/player preferences (device-agnostic) ---
    // Deliberately EXCLUDES the download folder (a device-specific path) and the profile/source-coupled
    // keys (active profile, default source, refresh-on-startup) — those ride with the sources backup.

    private val backupStringKeys = listOf(
        Keys.THEME_MODE, Keys.ACCENT, Keys.ACCENT_CUSTOM, Keys.FOCUS_HIGHLIGHT, Keys.DEFAULT_ZOOM,
        // Current global engine choices. VOD_PREFER_EXO below is migration-only and cannot represent
        // all four EnginePreference modes, so the two string values must travel themselves.
        Keys.LIVE_ENGINE, Keys.VOD_ENGINE,
        Keys.MAIN_FONT_FAMILY, Keys.POPUP_FONT_FAMILY,
        Keys.PREF_AUDIO_LANG, Keys.PREF_SUB_LANG, Keys.SUB_SEARCH_LANGS, Keys.SORT_LIVE, Keys.SORT_GUIDE, Keys.SORT_MOVIES,
        Keys.SORT_SERIES, Keys.RESUME_MODE, Keys.CATCHUP_TZ, Keys.CATCHUP_PLAYER, Keys.ANIMATION_LEVEL, Keys.VOD_VIEW_MODE, Keys.GUIDE_VIEW,
        Keys.EPISODE_VIEW_MODE,
        Keys.WEATHER_LOCATION, Keys.RECENT_SEARCHES,
        // Global proxy — non-secret fields only. The proxy password (Keys.PROXY_PASS) is NEVER part of
        // this whitelist; it is handled separately by BackupManager (encrypted or omitted).
        Keys.PROXY_HOST, Keys.PROXY_USER,
        // TMDB metadata: source mode + self-host URL. The user's own TMDB API key (Keys.TMDB_API_KEY) is a
        // secret and is deliberately NOT backed up in plaintext (same policy as the proxy password).
        Keys.METADATA_SERVER_URL, Keys.METADATA_MODE, Keys.METADATA_LANGUAGE,
        Keys.OPEN_SUBTITLES_SERVER_URL,
        // Download folder. Backed up so a same-device reinstall keeps the chosen folder; on a different
        // device a path that no longer exists is harmless — StorageAccess.resolveRoot falls back to app
        // storage, so a stale restore never breaks downloads.
        Keys.DOWNLOAD_ROOT,
        // Nav menu mode rides with settings backup so a reinstall keeps the user's DYNAMIC/STATIC choice.
        Keys.NAV_MENU_MODE,
        // Docked mini-player position rides with settings backup (size is an int key, see backupIntKeys).
        Keys.MINI_PLAYER_POSITION,
        // Live TV latency preset (custom seconds is an int key, see backupIntKeys).
        Keys.LIVE_LATENCY_MODE,
        // Glass effect: the background image path + scope/alpha so a reinstall keeps the look.
        // NOTE: only the path string travels — the image bytes live in app-private storage which is
        // wiped on uninstall, so on a new device a stale path is ignored gracefully (falls back to none).
        Keys.BG_IMAGE_PATH,
        Keys.GLASS_PRESET,
        // Subtitle appearance: text color and screen position (toggle is a bool key, size a float
        // key, background transparency an int key).
        Keys.SUB_COLOR,
        Keys.SUB_FONT,
        Keys.SUB_POSITION,
        // Custom DNS — not secret, backed up alongside proxy
        Keys.DNS_HOST, Keys.DNS_DOH_URL,
        // Surround mode (Auto/Stereo only/Surround). The legacy boolean is in backupBoolKeys and stays
        // in sync, but the string is what is read first, so it has to travel too.
        Keys.SURROUND_MODE,
        // The mobility layer's two choices. Same reasoning as the touch-host booleans below: a phone
        // restored from a phone keeps them, a television never reads them.
        Keys.MINI_PLAYER_STYLE, Keys.PIP_SIZE,
        // Settings personalization: Quick pins (including their order) and the independently arranged
        // action order for each of the four long-press content menus.
        Keys.QUICK_PINNED,
    ) + ContentMenu.entries.map { menuOrderKey(it.name.lowercase()) }
    private val backupStringSetKeys = listOf(
        // The STATIC-mode hidden set rides with backup so a reinstall keeps the user's hidden icons.
        Keys.NAV_MENU_HIDDEN,
        Keys.REMOTE_SHORTCUT_BINDINGS,
    )
    private val backupIntKeys = listOf(Keys.FOCUS_HIGHLIGHT_WIDTH, Keys.DEFAULT_VOLUME, Keys.SEEK_STEP_SEC, Keys.LIVE_REWIND_STEP_SEC, Keys.UI_ZOOM_PCT, Keys.FONT_SIZE_PCT, Keys.AUDIO_DELAY_MS, Keys.CATCHUP_OFFSET_MIN, Keys.EPG_OFFSET_MIN, Keys.PROXY_PORT, Keys.DNS_PORT, Keys.CH_NAV_UP_SKIP, Keys.CH_NAV_DOWN_SKIP, Keys.MINI_PLAYER_SIZE_PCT, Keys.LIVE_LATENCY_CUSTOM_SECS, Keys.LIVE_PREROLL_SECS, Keys.LIVE_TUNE_TIMEOUT_SECS, Keys.GLASS_SCOPE, Keys.GLASS_ALPHA, Keys.GLASS_BLUR, Keys.GLASS_HIGHLIGHT, Keys.SUB_BG_OPACITY,
        Keys.PANEL_W_LIVE_CAT, Keys.PANEL_W_LIVE_LIST, Keys.PANEL_W_LIVE_PREVIEW,
        Keys.PANEL_W_MOVIES_CAT, Keys.PANEL_W_MOVIES_LIST, Keys.PANEL_W_MOVIES_PREVIEW,
            Keys.PANEL_W_SERIES_CAT, Keys.PANEL_W_SERIES_LIST, Keys.PANEL_W_SERIES_PREVIEW,
        Keys.GUIDE_WIDTH_CHANNELS, Keys.GUIDE_WIDTH_EPG,
        Keys.POPUP_FONT_SIZE_PCT, Keys.POPUP_SIZE_PCT, Keys.VOD_GRID_COLUMNS, Keys.GUIDE_DENSITY_PCT,
        Keys.GESTURE_SENSITIVITY_PCT)
    private val backupBoolKeys = listOf(
        Keys.LIVE_PREVIEW, Keys.LIVE_PREVIEW_AUDIO, Keys.HERO_PREVIEW, Keys.HDR_ENABLED, Keys.AUTO_FRAME_RATE, Keys.AUTO_FRAME_RATE_PROMPTED, Keys.ANDROID_TV_HOME, Keys.HW_DECODING,
        Keys.VOD_PREFER_EXO, Keys.MEASURED_STREAM_STATS, Keys.DETAILED_DIAGNOSTICS, Keys.DIRECT_TUNE, Keys.EXTERNAL_PLAYER,
        Keys.EXTERNAL_PLAYER_LIVE, Keys.EXTERNAL_PLAYER_MOVIES, Keys.EXTERNAL_PLAYER_SERIES, Keys.UPDATE_CHECK_ON_START, Keys.SURROUND_SOUND, Keys.AUTO_PLAY_NEXT, Keys.PROXY_ENABLED,
        Keys.WEATHER_ENABLED, Keys.WEATHER_FAHRENHEIT, Keys.RESUME_LAST_CHANNEL, Keys.METADATA_ENABLED, Keys.CH_NAV_ENABLED,
        Keys.DNS_ENABLED,
        Keys.REMEMBER_LAST_LIVE, Keys.REMEMBER_LAST_MOVIES, Keys.REMEMBER_LAST_SERIES,
        Keys.REMEMBER_CAT_LIVE, Keys.REMEMBER_CAT_MOVIES, Keys.REMEMBER_CAT_SERIES,
        Keys.SUB_STYLE_ENABLED, Keys.SUB_SEARCH_FILTER, Keys.DEINTERLACE,
            Keys.PANEL_W_LIVE_ON, Keys.PANEL_W_MOVIES_ON, Keys.PANEL_W_SERIES_ON, Keys.GUIDE_WIDTH_ON,
        Keys.AMBIENT_GLOW_ENABLED, Keys.AMBIENT_GLOW_PULSE,
        Keys.GLASS_ALLOW_FULL_TRANSPARENCY, Keys.GLASS_DEPTH_EFFECTS, Keys.GLASS_GLINT,
        // Touch-host settings. They travel even though a television has no row for them: a phone
        // restored from a phone must keep them, and a television simply ignores what it never reads.
        Keys.BACKGROUND_PLAYBACK, Keys.PIP_ENABLED, Keys.DATA_SAVER, Keys.DOWNLOADS_WIFI_ONLY,
        Keys.PIP_ON_BACK, Keys.PIP_SNAP, Keys.AUDIO_ON_SCREEN_OFF, Keys.AUDIO_ON_MOBILE_DATA,
        Keys.AUDIO_PER_CHANNEL,
    )
    private val backupFloatKeys = listOf(Keys.SUB_SCALE, Keys.SUB_SCALE_MPV, Keys.SUB_SCALE_EXO)

    /**
     * "Remember last category" values (see the REMEMBER_CAT_* toggles, which are backed up as plain
     * booleans above). These need a filter rather than a straight whitelist entry, so they live apart:
     * a provider folder is stored as "FOLDER:<Room category id>", and Room content ids are recreated
     * by every sync (the catalog is clear-then-insert), so that value means nothing on another device
     * — restoring it would land the user in an arbitrary category. The stable forms travel:
     * "ALL" / "FAV" / "HIST" and "CUSTOM:<uuid>", a user-created category whose id really is portable.
     *
     * `last_live_channel` is deliberately absent for the same reason and has no stable form at all:
     * it is a Room channel id, so there is nothing here worth carrying.
     */
    private val backupLastCategoryKeys = listOf(
        Keys.LAST_LIVE_CATEGORY, Keys.LAST_MOVIES_CATEGORY, Keys.LAST_SERIES_CATEGORY,
    )

    private fun isPortableCategoryKey(value: String): Boolean =
        value == "ALL" || value == "FAV" || value == "HIST" || value.startsWith("CUSTOM:")

    suspend fun exportSettings(): org.json.JSONObject {
        val p = context.dataStore.data.first()
        return org.json.JSONObject().apply {
            backupStringKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupLastCategoryKeys.forEach { k -> p[k]?.takeIf(::isPortableCategoryKey)?.let { put(k.name, it) } }
            backupStringSetKeys.forEach { k -> p[k]?.let { put(k.name, org.json.JSONArray(it)) } }
            backupIntKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupBoolKeys.forEach { k -> p[k]?.let { put(k.name, it) } }
            backupFloatKeys.forEach { k -> p[k]?.let { put(k.name, it.toDouble()) } }
            // The UI language lives in SharedPreferences (LocaleStore), not DataStore, so it carries as
            // solate, explicitly serialised field rather than pretending it is a DataStore key. `""` means
            // follow system (see docs/internationalization.md 0b, "Backup interaction").
            put(UI_LANGUAGE_KEY, localeStore.currentTag.value)
        }
    }

    suspend fun importSettings(o: org.json.JSONObject): SettingsImportResult {
        context.dataStore.edit { prefs ->
            backupStringKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getString(k.name) }
            // Guarded on read as well as on write: a file written by another build (or edited by hand)
            // must not be able to restore a "FOLDER:<id>" that points at whatever this device's sync
            // happens to have put behind that number.
            backupLastCategoryKeys.forEach { k ->
                if (o.has(k.name)) o.getString(k.name).takeIf(::isPortableCategoryKey)?.let { prefs[k] = it }
            }
            backupStringSetKeys.forEach { k ->
                if (o.has(k.name)) prefs[k] = o.getJSONArray(k.name).let { arr -> buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) } }
            }
            backupIntKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getInt(k.name) }
            backupBoolKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getBoolean(k.name) }
            backupFloatKeys.forEach { k -> if (o.has(k.name)) prefs[k] = o.getDouble(k.name).toFloat() }
            if (livePreviewPanelHidden(prefs)) {
                prefs[Keys.LIVE_PREVIEW] = false
            }
        }
        // Do not publish a locale while the restore is still applying database/DataStore sections.
        // The caller applies this validated value after the restore marker is cleared. Invalid data
        // is reported but ignored so a malformed optional field cannot abort an otherwise valid import.
        if (!o.has(UI_LANGUAGE_KEY)) return SettingsImportResult()
        val raw = o.opt(UI_LANGUAGE_KEY) as? String
        val normalized = raw?.let(localeStore::normalize)
        return SettingsImportResult(
            localePresent = true,
            localeTag = normalized,
            // Only a JSON string is a valid locale field. In particular, JSONObject.NULL and
            // numbers must not silently turn into the empty system-default tag.
            invalidLocale = raw == null || normalized == null,
        )
    }

    /** Applies a locale deferred until a complete backup restore has cleared its marker. */
    suspend fun applyImportedLocale(tag: String) {
        localeStore.set(tag)
    }

    // --- Backup: per-profile Customize PIN lock (dynamic "customize_pin_<id>" keys) ---

    /** Exports all per-profile Customize PINs as { "<profileId>": "<pin>" }. */
    suspend fun exportCustomizePins(): org.json.JSONObject {
        val prefix = "customize_pin_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String && v.isNotBlank()) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Restores Customize PINs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importCustomizePins(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val pin = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                prefs[stringPreferencesKey("customize_pin_$pid")] = normalizeCustomizePin(pin)
            }
        }
    }

    private fun normalizeCustomizePin(value: String): String {
        val trimmed = value.trim()
        return if (CUSTOMIZE_PIN_HASH_REGEX.matches(trimmed)) trimmed else Pin.hash(trimmed)
    }

    private companion object {
        val CUSTOMIZE_PIN_HASH_REGEX = Regex("^[0-9a-fA-F]{16}:[0-9a-fA-F]{64}$")

        /** Backup payload field name for the UI locale tag (read from / written to [LocaleStore]). */
        const val UI_LANGUAGE_KEY = "ui_language"

        /** The six toggles Quick started life with, kept as the out-of-the-box pin list. */
        val DEFAULT_QUICK_PINNED = listOf(
            "quick_live_preview", "quick_preview_sound", "quick_channel_numbers",
            "quick_hdr", "quick_autoplay", "quick_check_update",
        )
    }

    // --- Backup: per-profile startup landing (dynamic "startup_mode_<id>" keys) ---

    /** Exports all per-profile startup-mode keys as { "<profileId>": "<MODE>" }. */
    suspend fun exportStartupModes(): org.json.JSONObject {
        val prefix = "startup_mode_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Exports stable per-profile Live channel startup targets. */
    suspend fun exportStartupChannels(): org.json.JSONObject {
        val prefix = "startup_channel_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                StartupChannelRef.fromJson(v)?.let { out.put(k.name.removePrefix(prefix), it.toJson()) }
            }
        }
        return out
    }

    /** Exports all per-profile Home config blobs as { "<profileId>": { ... } }. */
    suspend fun exportHomeConfigs(): org.json.JSONObject {
        val prefix = "home_config_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is String) {
                val blob = runCatching { org.json.JSONObject(v) }.getOrNull() ?: return@forEach
                out.put(k.name.removePrefix(prefix), blob)
            }
        }
        return out
    }

    /** Restores startup modes only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importStartupModes(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val mode = o.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                if (runCatching { StartupMode.valueOf(mode) }.isSuccess) {
                    prefs[stringPreferencesKey("startup_mode_$pid")] = mode
                }
            }
        }
    }

    /** Restores startup targets after profile/source ids have been remapped by backup import. */
    suspend fun importStartupChannels(
        o: org.json.JSONObject,
        existingProfileIds: Set<Long>,
        sourceIdMap: Map<Long, Long>,
    ) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val raw = o.optJSONObject(key)?.toString() ?: return@forEach
                val ref = StartupChannelRef.fromJson(raw) ?: return@forEach
                val mappedSourceId = sourceIdMap[ref.sourceId] ?: return@forEach
                prefs[stringPreferencesKey("startup_channel_$pid")] =
                    ref.copy(sourceId = mappedSourceId, itemId = -1L).toJson().toString()
            }
        }
    }

    /** A restored specific-channel mode must never point at an absent or unmapped source target. */
    suspend fun repairSpecificStartupModes(profileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            profileIds.forEach { profileId ->
                val modeKey = stringPreferencesKey("startup_mode_$profileId")
                if (prefs[modeKey] == StartupMode.SPECIFIC_CHANNEL.name) {
                    val channel = StartupChannelRef.fromJson(prefs[stringPreferencesKey("startup_channel_$profileId")])
                    if (channel == null) prefs[modeKey] = StartupMode.HOME.name
                }
            }
        }
    }

    /** Restores Home configs only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importHomeConfigs(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                val blob = o.optJSONObject(key) ?: return@forEach
                prefs[homeConfigKey(pid)] = blob.toString()
            }
        }
    }

    // --- Backup: per-profile "hide new categories" preference (dynamic "hide_new_categories_<id>" keys) ---

    /** Exports all per-profile "hide new categories" preferences as { "<profileId>": true/false }. */
    suspend fun exportHideNewCategories(): org.json.JSONObject {
        val prefix = "hide_new_categories_"
        val out = org.json.JSONObject()
        context.dataStore.data.first().asMap().forEach { (k, v) ->
            if (k.name.startsWith(prefix) && v is Boolean) {
                out.put(k.name.removePrefix(prefix), v)
            }
        }
        return out
    }

    /** Restores the preference only for profile ids in [existingProfileIds] (others are dropped safely). */
    suspend fun importHideNewCategories(o: org.json.JSONObject, existingProfileIds: Set<Long>) {
        context.dataStore.edit { prefs ->
            o.keys().forEach { key ->
                val pid = key.toLongOrNull() ?: return@forEach
                if (pid !in existingProfileIds) return@forEach
                prefs[booleanPreferencesKey("hide_new_categories_$pid")] = o.getBoolean(key)
            }
        }
    }

    // --- Backup: per-source auto-refresh maps (ride with the SOURCES section, since source/EPG ids
    //     are preserved on restore). Exported as the raw { "<id>": "<EnumName>" } JSON maps. ---

    /** Exports the per-source playlist auto-refresh map as { "<sourceId>": "<mode>" }. */
    suspend fun exportPlaylistAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.PLAYLIST_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /** Exports the per-EPG-source auto-refresh map as { "<epgSourceId>": "<mode>" }. */
    suspend fun exportEpgAutoRefresh(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.EPG_AUTO_REFRESH]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /**
     * Restores the playlist auto-refresh map. Ids not in [existingSourceIds] are dropped; unknown
     * enum values fall back to OFF. Replaces the whole map (SOURCES restore wipes+recreates sources,
     * so pre-restore selections refer to deleted ids). Also marks the legacy refresh migration done
     * so it can never clobber the restored selections.
     */
    suspend fun importPlaylistAutoRefresh(o: org.json.JSONObject, existingSourceIds: Set<Long>) {
        // parse() also carries a backup written before the fixed 24h/48h/7-day entries were replaced
        // over to the equivalent day count, so restoring an old device does not silently reset it to Off.
        val cleaned = sanitizeRefreshMap(o, existingSourceIds) { PlaylistRefresh.parse(it).serialize() }
        context.dataStore.edit { prefs ->
            // Merge-restore: keep the device's existing per-source choices, backup entries win per key.
            val merged = parseRefreshMap(prefs[Keys.PLAYLIST_AUTO_REFRESH]) + cleaned
            prefs[Keys.PLAYLIST_AUTO_REFRESH] = org.json.JSONObject(merged as Map<*, *>).toString()
            prefs[Keys.REFRESH_MIGRATED] = true
        }
    }

    /** Restores the EPG auto-refresh map; same semantics as [importPlaylistAutoRefresh]. */
    suspend fun importEpgAutoRefresh(o: org.json.JSONObject, existingEpgSourceIds: Set<Long>) {
        val cleaned = sanitizeRefreshMap(o, existingEpgSourceIds) { runCatching { EpgAutoRefresh.valueOf(it) }.getOrDefault(EpgAutoRefresh.OFF).name }
        context.dataStore.edit { prefs ->
            val merged = parseRefreshMap(prefs[Keys.EPG_AUTO_REFRESH]) + cleaned
            prefs[Keys.EPG_AUTO_REFRESH] = org.json.JSONObject(merged as Map<*, *>).toString()
        }
    }

    /** Exports the per-EPG-source "use this feed's logos" map as { "<epgSourceId>": "true" }. */
    suspend fun exportEpgUseLogos(): org.json.JSONObject =
        context.dataStore.data.first()[Keys.EPG_USE_LOGOS]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()

    /** Restores the EPG logo-preference map; same merge semantics as [importEpgAutoRefresh]. */
    suspend fun importEpgUseLogos(o: org.json.JSONObject, existingEpgSourceIds: Set<Long>) {
        val cleaned = sanitizeRefreshMap(o, existingEpgSourceIds) { it.toBoolean().toString() }
        context.dataStore.edit { prefs ->
            val merged = parseRefreshMap(prefs[Keys.EPG_USE_LOGOS]) + cleaned
            prefs[Keys.EPG_USE_LOGOS] = org.json.JSONObject(merged as Map<*, *>).toString()
        }
    }

    private fun parseRefreshMap(raw: String?): Map<String, String> {
        val o = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    private inline fun sanitizeRefreshMap(
        o: org.json.JSONObject,
        existingIds: Set<Long>,
        sanitize: (String) -> String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { key ->
            val id = key.toLongOrNull() ?: return@forEach
            if (id !in existingIds) return@forEach
            out[key] = sanitize(o.optString(key))
        }
        return out
    }

    // --- Backup: default source (SOURCES section — ids are preserved on restore) ---

    /** Currently selected default source id, or null when none chosen. */
    suspend fun currentDefaultSourceId(): Long? =
        context.dataStore.data.first()[Keys.DEFAULT_SOURCE]?.takeIf { it > 0 }

    /** Restores the default source only when that id survived the restore. */
    suspend fun importDefaultSource(id: Long, existingSourceIds: Set<Long>) {
        if (id in existingSourceIds) setDefaultSource(id)
    }

    // --- Backup: proxy password (handled out-of-band by BackupManager: encrypted or omitted) ---

    /** Current proxy password, for the backup layer to encrypt. Never logged. */
    suspend fun currentProxyPassword(): String = context.dataStore.data.first()[Keys.PROXY_PASS] ?: ""

    /** The user's own TMDB API key — a secret; BackupManager exports it encrypted-only (like the proxy password). */
    suspend fun currentTmdbApiKey(): String = context.dataStore.data.first()[Keys.TMDB_API_KEY] ?: ""

    /** Sets only the proxy password (used on restore once decrypted). Blank clears it. */
    suspend fun setProxyPassword(password: String) {
        context.dataStore.edit { it[Keys.PROXY_PASS] = password }
    }

    // --- Global custom DNS — sibling to the global proxy. Supports plain DNS-over-UDP (host + port)
    //     and DNS-over-HTTPS (DoH) via a URL. No auth is needed for DNS; the server field is not a
    //     secret (it's the DNS server the user wants to use). Backed by DnsConfigHolder, same pattern
    //     as ProxyConfigHolder. ---

    /** Live snapshot of the DNS config as a single object (consumed by DnsConfigHolder). */
    val dnsConfig: Flow<tv.own.owntv.core.network.DnsConfig> = prefsFlow { p ->
        tv.own.owntv.core.network.DnsConfig(
            enabled = p[Keys.DNS_ENABLED] ?: false,
            host = p[Keys.DNS_HOST] ?: "",
            port = p[Keys.DNS_PORT] ?: 53,
            dohUrl = p[Keys.DNS_DOH_URL] ?: "",
        )
    }

    suspend fun saveDns(enabled: Boolean, host: String, port: Int, dohUrl: String) {
        context.dataStore.edit {
            it[Keys.DNS_ENABLED] = enabled
            it[Keys.DNS_HOST] = host.trim()
            it[Keys.DNS_PORT] = port.coerceIn(1, 65535)
            it[Keys.DNS_DOH_URL] = dohUrl.trim()
        }
    }
}
