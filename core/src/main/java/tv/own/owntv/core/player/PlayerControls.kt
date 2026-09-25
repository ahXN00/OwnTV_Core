package tv.own.owntv.core.player

import tv.own.owntv.core.R

/** Which apps a control belongs on. */
enum class ControlHost {
    /** Both apps, in the same place, with the same glyph. */
    BOTH,

    /** Television only — the phone has no equivalent, or reaches it another way. */
    TV_ONLY,

    /** Phone and tablet only — meaningless on a television. */
    MOBILE_ONLY,
}

/**
 * Which half of the tool bar a control sits in.
 *
 * The television's HUD has **two clusters** separated by a spacer, and each hugs its own screen edge
 * so the gap between them is the slack a focused button expands into: growth is always toward the
 * centre, and walking either cluster outward-in never moves something already passed. That is a
 * D-pad property, not a decoration — but the *membership* of the two clusters is what makes the two
 * apps read alike, so it is part of the shared definition rather than the television's private
 * business. A phone honours it however its width allows.
 */
enum class ControlCluster { MEDIA, TOOLS }

/**
 * Every control in the player's tool bar, in **the canonical left-to-right order**, with one identity
 * and one label each.
 *
 * This exists because the two apps had drifted into four different kinds of mismatch: a different
 * order, four different glyphs for the same function, one control with different show/hide rules, and
 * genuinely per-platform members mixed in with the rest so nobody could tell which was which. A user
 * moving between the television and the phone had to relearn the bar.
 *
 * **The television is the reference.** It is the older and more considered layout, and the comments in
 * its HUD explain why each control sits where it does; the phone moves to match, except where a
 * control is genuinely touch-only.
 *
 * Pure data: an enum and a string key, no Compose UI. An `ImageVector` deliberately does **not** live
 * here — `compose-ui` is forbidden in core by invariant 3 — so both apps keep their own hand-drawn
 * icon sets and the rule is that they must be drawn to match.
 */
enum class PlayerControl(
    val cluster: ControlCluster,
    val host: ControlHost,
    /** The existing `player_tool_*` label, which both apps already use. */
    val labelRes: Int,
) {
    // --- The media cluster: things about the stream that is playing ---

    /**
     * Back to the live edge. Leads the bar on both apps, and is absent when there is no edge to go
     * back to — on the phone because it is the first thing a thumb reaches on a bar that scrolls, on
     * the television because falling behind inserts it and pushes the rest right.
     */
    GO_LIVE(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_catchup),
    VOLUME(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_volume),

    /**
     * Screen brightness. Phone-only and correctly so: a television's brightness is the television's,
     * and nothing the app does to its own window would change it.
     */
    BRIGHTNESS(ControlCluster.MEDIA, ControlHost.MOBILE_ONLY, R.string.player_tool_brightness),

    /** Playback speed. Was television-only for no reason anybody could name; H3 adds it to the phone. */
    SPEED(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_speed),
    SUBTITLES(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_subtitles),
    AUDIO(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_audio),
    FAVOURITE(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_favorite),

    /** Jump back into this channel's archive. Live catch-up channels only. */
    CATCH_UP(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_tool_catchup),

    /**
     * Back to the channel watched before this one (N2). Live only, and only once there is one. The
     * button is what makes it reachable on a remote without a "last channel" key, and on a phone.
     */
    PREVIOUS_CHANNEL(ControlCluster.MEDIA, ControlHost.BOTH, R.string.player_previous_channel),

    // --- The tools cluster: things about how it is being played and shown ---

    /** Which engine owns playback. A text pill naming it on the television; H3 matches that. */
    ENGINE(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_engine),
    ASPECT(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_aspect),

    /**
     * The picture quality of this stream (N11). Only when it offers more than one — an HLS or DASH
     * channel or film with several variants — beside Aspect, the other "how the picture is shown".
     */
    QUALITY(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_quality),

    /** The channel list over the picture. Phone-only today; the television reaches it with Left. */
    CHANNEL_LIST(ControlCluster.TOOLS, ControlHost.MOBILE_ONLY, R.string.content_channel_overlay_title),

    MINI_PLAYER(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_mini),
    AUDIO_ONLY(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_audio_only),

    /**
     * Turn this channel into the first tile of the grid (Plan D, Feature B). Opt-in, live only.
     *
     * Beside the mini-player button because that is its nearest relative: both take the one picture
     * you are watching and put it somewhere else.
     */
    MULTIVIEW(ControlCluster.TOOLS, ControlHost.BOTH, R.string.multiview_button),

    /** Record the channel already playing (Plan D, D3). Only exists once the setting is on. */
    RECORD(ControlCluster.TOOLS, ControlHost.BOTH, R.string.recording_record),

    /**
     * Stop playing after a while (N17). In the bar on both apps — the phone had it only on the
     * sound-only screen and the floating window, the television not at all.
     */
    SLEEP_TIMER(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_sleep_timer),

    INFO(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_info),

    /**
     * Report the stream. **Shown only while the Info overlay is open**, on both apps.
     *
     * This is the H1 decision, and it is the television's existing rule rather than the phone's
     * "always". The reason is the original one: a report is about what Info is showing, there is
     * nothing to report without it, and the great majority of users never file one — so keeping it
     * out of the bar keeps the bar short. The phone changes to match.
     */
    REPORT(ControlCluster.TOOLS, ControlHost.BOTH, R.string.player_tool_report),
    ;

    /** Does this control appear on a television? */
    val onTv: Boolean get() = host != ControlHost.MOBILE_ONLY

    /** Does this control appear on a phone or tablet? */
    val onMobile: Boolean get() = host != ControlHost.TV_ONLY

    companion object {
        /** The controls one app draws, in order. Everything else about them is the app's own. */
        fun orderFor(tv: Boolean): List<PlayerControl> =
            entries.filter { if (tv) it.onTv else it.onMobile }

        /** One cluster of one app's bar, in order — the two halves the spacer sits between. */
        fun clusterFor(tv: Boolean, cluster: ControlCluster): List<PlayerControl> =
            orderFor(tv).filter { it.cluster == cluster }
    }
}
