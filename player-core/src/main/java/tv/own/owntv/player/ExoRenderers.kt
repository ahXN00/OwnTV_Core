package tv.own.owntv.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The one place every in-app ExoPlayer's renderers are configured.
 *
 * Three engines build an ExoPlayer — [LivePreviewEngine] (Live TV's default engine),
 * [ExoSubtitleEngine] (VOD / image-subtitle handoff / mpv fallback) and [HeroPreviewEngine] (the
 * home hero). Each used to configure its own, so a decode-path setting could land on one and not the
 * others: the async-queueing workaround below was live-only, and decoder fallback was on none of
 * them. Anything about *how the device decodes* belongs here so it cannot go missing on one engine
 * again. Per-engine concerns (data source, load control, track selector, listeners) stay at the
 * call site, where they legitimately differ.
 */
@UnstableApi
fun ownTVRenderers(
    context: Context,
    /** Pin the audio sink to stereo PCM — "Stereo only", or a session latch tripped by any engine. */
    forceStereo: Boolean,
    /** Software decoders first — "Hardware decoding = Off", or a rescue retry after a blank picture. */
    softwareFirst: Boolean = false,
    /** The engine's A/V-sync offset ([AudioDelayClock]); null for an engine that offers none. */
    audioDelay: AudioDelayClock? = null,
    /** N8 — false: Dolby/DTS decoded in the app, see [OwnTVRenderersFactory]. */
    passthrough: Boolean = true,
): DefaultRenderersFactory =
    OwnTVRenderersFactory(context, forceStereo = forceStereo, audioDelay = audioDelay, passthrough = passthrough)
        // Media3 runs MediaCodec asynchronously by default on API 31+, and on every Fire TV device
        // (`com.amazon.hardware.tv_screen`) from API 28 up. That async path corrupts (macroblocks)
        // some UHD-HEVC content on Realtek/Amlogic VPUs — the synchronous path is what players like
        // TiviMate use to avoid it. The corruption is a property of the VPU, not of the transport, so
        // a 4K HEVC *file* needs this exactly as much as a 4K HEVC channel does. Content it still
        // can't decode cleanly is handed to mpv (per-channel "force mpv" routing / the VOD fallback).
        .forceDisableMediaCodecAsynchronousQueueing()
        // If the chosen decoder fails to initialise or throws while configuring, try the next one the
        // device offers (usually the software decoder) instead of surfacing the error. A free rung on
        // the rescue ladder: without it one bad vendor decoder ends playback outright.
        .setEnableDecoderFallback(true)
        .apply {
            if (softwareFirst) {
                // Hardware stays in the list as a backstop — Media3 walks the decoder list in order
                // and falls through on failure, so this can only add a route.
                setMediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                        .sortedBy { it.hardwareAccelerated } // false (software) sorts before true
                }
            }
        }
