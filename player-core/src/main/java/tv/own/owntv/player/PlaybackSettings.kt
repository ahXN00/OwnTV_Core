package tv.own.owntv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.player.AudioOutputWatch
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.OutputAudioDelays
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.core.theme.AppFontFamily

/**
 * Every setting the playback engines and the live tuner read, as one value.
 *
 * The engines used to subscribe to each setting separately — some thirty DataStore pipelines — and
 * each started on a hard-coded default until its own first read landed. A tune that began in that
 * window (Last-channel autoplay at a cold start) opened with defaults: hardware decoding on, no
 * pre-buffer, the default engine. Now there is one process-wide snapshot, started from
 * [PlaybackStartup] in `Application.onCreate`; an engine built after it has a value takes that value
 * during construction, and the live tuner waits for it ([await]) before its first tune.
 *
 * Only the plumbing changed: each engine still reacts to each field exactly as it reacted to that
 * setting, through [field].
 */
data class PlaybackSettings(
    /** HLS with SDR and HDR variants plays the SDR ones — see [SdrVariants]. */
    val preferSdr: Boolean,
    val hwDecoding: Boolean,
    /** Software decoding allowed above 1080p — see [SettingsRepository.softwareDecodeAbove1080p]. */
    val softwareDecodeAbove1080p: Boolean,
    val surroundMode: SurroundMode,
    val autoPlayNext: Boolean,
    val vodEnginePreference: EnginePreference,
    val measuredStreamStats: Boolean,
    val livePrerollSecs: Int,
    val liveBufferSeconds: Int?,
    val subtitleStyleEnabled: Boolean,
    val subtitleScaleMpv: Float,
    val subtitleFont: AppFontFamily?,
    val subtitleColor: String,
    val subtitlePosition: SubtitleStyle.Position,
    val subtitleBgOpacity: Int,
    val audioDelayMs: Int,
    /** Extra delay per output kind, on top of [audioDelayMs]. */
    val outputAudioDelays: OutputAudioDelays,
    val preferredAudioLang: String,
    val preferredSubLang: String,
    val defaultZoom: String,
    val defaultVolume: Int,
    val seekStepSec: Int,
    /** Auto frame rate for films and series; Live TV has [autoFrameRateLive]. */
    val autoFrameRateFilms: Boolean,
    val liveEnginePreference: EnginePreference,
    val liveTuneTimeoutSecs: Int,
    /** Films only; 0 = Auto for the first two. See [FilmNetwork]. */
    val vodBufferSecs: Int,
    val vodNetworkTimeoutSecs: Int,
    val vodReconnects: Int,
    /** Passthrough (ExoPlayer), night mode, volume levelling. See [AudioDynamics]. */
    val audioPassthrough: Boolean,
    val nightMode: Boolean,
    val volumeLevelling: Boolean,
    /** Quality limits (0 = none; see [VideoQuality.cap]) and tunneled playback. */
    val maxVideoHeight: Int,
    val mobileDataMaxVideoHeight: Int,
    val tunneledPlayback: Boolean,
    /** Keep CPU and Wi-Fi awake while ExoPlayer plays. */
    val backgroundPlayback: Boolean,
    val autoFrameRateLive: Boolean,
) {
    companion object {
        @Volatile private var shared: Pair<SettingsRepository, StateFlow<PlaybackSettings?>>? = null

        /** The process-wide snapshot for [settings]; null until the store has been read once. */
        fun of(settings: SettingsRepository): StateFlow<PlaybackSettings?> {
            shared?.takeIf { it.first === settings }?.let { return it.second }
            return synchronized(this) {
                shared?.takeIf { it.first === settings }?.second
                    ?: snapshotOf(settings).also { shared = settings to it }
            }
        }

        /** The snapshot, waiting for the store's first read if it has not landed yet. */
        suspend fun await(settings: SettingsRepository): PlaybackSettings = of(settings).filterNotNull().first()

        @Suppress("UNCHECKED_CAST")
        private fun snapshotOf(s: SettingsRepository): StateFlow<PlaybackSettings?> {
            val sources: List<Flow<Any?>> = listOf(
                s.preferSdr, s.hwDecoding, s.surroundMode, s.autoPlayNext,
                s.vodEnginePreference, s.measuredStreamStats, s.livePrerollSecs, s.liveBufferSeconds,
                s.subtitleStyleEnabled, s.subtitleScaleMpv, s.subtitleFont, s.subtitleColor,
                s.subtitlePosition, s.subtitleBgOpacity, s.audioDelayMs, s.preferredAudioLang,
                s.preferredSubLang, s.defaultZoom, s.defaultVolume, s.seekStepSec, s.autoFrameRateFilms,
                s.liveEnginePreference, s.liveTuneTimeoutSecs,
                s.vodBufferSecs, s.vodNetworkTimeoutSecs, s.vodReconnects,
                s.audioPassthrough, s.nightMode, s.volumeLevelling,
                s.maxVideoHeight, s.mobileDataMaxVideoHeight, s.tunneledPlayback, s.backgroundPlayback,
                s.softwareDecodeAbove1080p, s.outputAudioDelays, s.autoFrameRateLive,
            )
            return combine(sources) { v ->
                PlaybackSettings(
                    preferSdr = v[0] as Boolean,
                    hwDecoding = v[1] as Boolean,
                    surroundMode = v[2] as SurroundMode,
                    autoPlayNext = v[3] as Boolean,
                    vodEnginePreference = v[4] as EnginePreference,
                    measuredStreamStats = v[5] as Boolean,
                    livePrerollSecs = v[6] as Int,
                    liveBufferSeconds = v[7] as Int?,
                    subtitleStyleEnabled = v[8] as Boolean,
                    subtitleScaleMpv = v[9] as Float,
                    subtitleFont = v[10] as AppFontFamily?,
                    subtitleColor = v[11] as String,
                    subtitlePosition = v[12] as SubtitleStyle.Position,
                    subtitleBgOpacity = v[13] as Int,
                    audioDelayMs = v[14] as Int,
                    preferredAudioLang = v[15] as String,
                    preferredSubLang = v[16] as String,
                    defaultZoom = v[17] as String,
                    defaultVolume = v[18] as Int,
                    seekStepSec = v[19] as Int,
                    autoFrameRateFilms = v[20] as Boolean,
                    liveEnginePreference = v[21] as EnginePreference,
                    liveTuneTimeoutSecs = v[22] as Int,
                    vodBufferSecs = v[23] as Int,
                    vodNetworkTimeoutSecs = v[24] as Int,
                    vodReconnects = v[25] as Int,
                    audioPassthrough = v[26] as Boolean,
                    nightMode = v[27] as Boolean,
                    volumeLevelling = v[28] as Boolean,
                    maxVideoHeight = v[29] as Int,
                    mobileDataMaxVideoHeight = v[30] as Int,
                    tunneledPlayback = v[31] as Boolean,
                    backgroundPlayback = v[32] as Boolean,
                    softwareDecodeAbove1080p = v[33] as Boolean,
                    outputAudioDelays = v[34] as OutputAudioDelays,
                    autoFrameRateLive = v[35] as Boolean,
                )
            }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)
        }
    }
}

/**
 * One field of the snapshot as its own flow: emitted once the store has been read, then only when that
 * field changes — the same shape each engine's per-setting subscription had.
 */
internal fun <T> StateFlow<PlaybackSettings?>.field(pick: (PlaybackSettings) -> T): Flow<T> =
    filterNotNull().map(pick).distinctUntilChanged()

/** The extra audio delay for the output media plays to right now; follows both the setting and the device. */
internal fun StateFlow<PlaybackSettings?>.outputAudioDelayMs(context: android.content.Context): Flow<Int> =
    combine(field { it.outputAudioDelays }, AudioOutputWatch.current(context)) { delays, kind -> delays.offsetFor(kind) }
        .distinctUntilChanged()
