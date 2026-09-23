package tv.own.owntv.player

import tv.own.owntv.core.player.EnginePreference

/**
 * Which engine a live tune opens on, and which ladder it is armed with.
 *
 * Both apps used to make this decision in their own copy of the same twenty lines, and the copies had
 * drifted (the phone once ignored ExoPlayer pins). It is pure so the order of authority is pinned by a
 * unit test rather than by two code reviews.
 */
object LiveRouting {

    data class Route(
        val onMpv: Boolean,
        /** What the ladder is armed with — not always the setting; see [decide]. */
        val preference: EnginePreference,
        /** One line for the support log: which engine, and which rule chose it. */
        val why: String,
    )

    /**
     * The inputs in descending authority:
     *
     *  1. **A protected channel is ExoPlayer's, always.** mpv ships no CDM, so it cannot fetch a key
     *     from a licence server; handing one over can only produce a failure. It outranks the pin as
     *     well as the setting, because it is a fact about the stream.
     *  2. **The per-channel pin** ("compatibility mode") — the user saying so about this one channel.
     *  3. **A learned lesson**: the panel was caught refusing its own signed segment URLs, which
     *     ExoPlayer can never satisfy. The caller only reports it while the setting allows both
     *     engines and the channel is unpinned — a lesson the app taught itself may not overturn a
     *     choice the user made.
     *  4. **The setting** — this playlist's own, else the global one (resolved by the caller).
     *
     * A pin that contradicts an "only" setting re-opens the handover for that one channel. Otherwise
     * the exception channel would be locked to the engine the user just said cannot play it, with the
     * ladder forbidden from ever reaching the one that can — a dead end of the app's own making.
     */
    fun decide(
        setting: EnginePreference,
        pin: Boolean?,
        drmProtected: Boolean,
        panelRefusesSegments: Boolean,
    ): Route {
        val refusing = pin == null && setting.allowsHandover && panelRefusesSegments
        val onMpv = !drmProtected && (pin ?: (refusing || setting.startsOnMpv))
        val preference = when {
            drmProtected -> EnginePreference.EXO_ONLY
            setting.allowsHandover -> EnginePreference.firstOn(onMpv)
            onMpv == setting.startsOnMpv -> setting
            else -> EnginePreference.firstOn(onMpv)
        }
        val engine = if (onMpv) "mpv" else "exoplayer"
        val why = when {
            drmProtected -> "exoplayer (drm)"
            pin != null -> "$engine (pinned)"
            refusing -> "mpv (panel refuses segments)"
            else -> "$engine (setting)"
        }
        return Route(onMpv, preference, why)
    }
}
