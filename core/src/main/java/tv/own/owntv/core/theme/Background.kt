package tv.own.owntv.core.theme

import androidx.compose.runtime.Immutable

/** What sits behind every screen: the Stage colours, the user's picture, or a plain dark fill. */
enum class BackgroundStyle { STAGE, PICTURE, PLAIN }

/**
 * How a picture background is drawn. Each look carries its own Darken / Blur / Accent light
 * defaults; the user's own values override them until the look is changed again.
 */
enum class PictureLook(val dimPct: Int, val blurPct: Int, val accentLight: Boolean) {
    SHARP(dimPct = 15, blurPct = 0, accentLight = false),
    SOFT(dimPct = 50, blurPct = 80, accentLight = true),
    DARK(dimPct = 75, blurPct = 30, accentLight = false),
}

/**
 * The resolved background. [dimPct] and [blurPct] are 0..100. With [BackgroundStyle.PICTURE] and no
 * [imagePath] the app draws the Stage colours instead, so a missing file never leaves a black screen.
 */
@Immutable
data class BackgroundConfig(
    val style: BackgroundStyle = BackgroundStyle.STAGE,
    val look: PictureLook = PictureLook.SOFT,
    val dimPct: Int = PictureLook.SOFT.dimPct,
    val blurPct: Int = PictureLook.SOFT.blurPct,
    val accentLight: Boolean = true,
    val imagePath: String = "",
) {
    /** True when a picture is actually drawn. */
    val showsPicture: Boolean get() = style == BackgroundStyle.PICTURE && imagePath.isNotBlank()

    companion object {
        const val DIM_MAX = 90
        const val BLUR_MAX = 100

        /**
         * Resolve stored values. Unset values keep an existing user's look exactly: a picture they had
         * becomes Picture · Sharp (15% darken = the old fixed scrim), Glass off becomes Plain, anything
         * else the Stage colours. A stored [style] / [look] always wins.
         */
        fun resolve(
            style: String?,
            look: String?,
            dimPct: Int?,
            blurPct: Int?,
            accentLight: Boolean?,
            imagePath: String,
            glassOn: Boolean,
        ): BackgroundConfig {
            val resolvedStyle = BackgroundStyle.entries.firstOrNull { it.name == style } ?: when {
                imagePath.isNotBlank() -> BackgroundStyle.PICTURE
                !glassOn -> BackgroundStyle.PLAIN
                else -> BackgroundStyle.STAGE
            }
            val resolvedLook = PictureLook.entries.firstOrNull { it.name == look }
                ?: if (imagePath.isNotBlank()) PictureLook.SHARP else PictureLook.SOFT
            val defaultAccent = when (resolvedStyle) {
                BackgroundStyle.STAGE -> true
                BackgroundStyle.PLAIN -> false
                BackgroundStyle.PICTURE -> resolvedLook.accentLight
            }
            return BackgroundConfig(
                style = resolvedStyle,
                look = resolvedLook,
                dimPct = (dimPct ?: resolvedLook.dimPct).coerceIn(0, DIM_MAX),
                blurPct = (blurPct ?: resolvedLook.blurPct).coerceIn(0, BLUR_MAX),
                accentLight = accentLight ?: defaultAccent,
                imagePath = imagePath,
            )
        }
    }
}
