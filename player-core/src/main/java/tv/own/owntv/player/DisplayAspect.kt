package tv.own.owntv.player

/**
 * The shape a picture is meant to be seen at, width over height, or null with no size yet.
 *
 * [width] × [height] is the frame as stored. A stream may keep a wide film in a narrower frame and flag
 * its pixels as wider than tall (anamorphic: 1920×1080 carrying a 2.39:1 film), and only the display
 * size [displayWidth] × [displayHeight] — the stored size with that flag applied — has the real shape.
 * Sizing the picture from the stored frame stretched such a film to fill a 16:9 screen. The stored
 * size stands in until the engine reports the display size, and for an engine that never does.
 */
internal fun displayAspect(width: Int, height: Int, displayWidth: Int, displayHeight: Int): Float? = when {
    displayWidth > 0 && displayHeight > 0 -> displayWidth.toFloat() / displayHeight
    width > 0 && height > 0 -> width.toFloat() / height
    else -> null
}
