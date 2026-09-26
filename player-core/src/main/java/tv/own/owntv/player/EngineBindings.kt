package tv.own.owntv.player

import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope

/*
 * How both apps build the two engines they bind. `:player-core` ships no Koin module — each app still
 * decides how many engines it holds and when — but the constructor calls live here once, with named
 * arguments, so neither app carries a copy whose `get()` calls silently depend on parameter order.
 */

/** The full player (mpv, with the ExoPlayer film engine behind it). */
fun Scope.ownTVPlayer(): OwnTVPlayer = OwnTVPlayer(
    context = androidContext(),
    settings = get(),
    connectivity = get(),
    streamingHttp = get(),
    diagnostics = get(),
    proxyHolder = get(),
    vodEngineStore = get(),
    localeStore = get(),
    playbackPrefs = get(),
    originalLanguage = get(),
)

/** One ExoPlayer live engine: the preview pane / live engine, or a Multiview tile from the pool. */
fun Scope.livePreviewEngine(): LivePreviewEngine = LivePreviewEngine(
    context = androidContext(),
    streamingHttp = get(),
    diagnostics = get(),
    settings = get(),
    connectivity = get(),
    playbackPrefs = get(),
)
