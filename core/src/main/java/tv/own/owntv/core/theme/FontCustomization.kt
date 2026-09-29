package tv.own.owntv.core.theme

/** The font families users can independently apply to the main UI and popup chrome. */
enum class AppFontFamily {
    LORA,
    SYSTEM_SANS,
    MONOSPACE,
    PLAYFAIR_DISPLAY,
    DANCING_SCRIPT,
    POPPINS,
    PLUS_JAKARTA_SANS;

    companion object {
        fun fromStored(value: String?, fallback: AppFontFamily): AppFontFamily =
            entries.firstOrNull { it.name == value } ?: fallback
    }
}

/** App-only text scaling. Android's system font scale remains the base and is multiplied by this. */
object UiFontScale {
    const val MIN = 60
    const val MAX = 140
    const val DEFAULT = 100
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun factor(percent: Int): Float = clamp(percent) / 100f
}

/** Text scaling inside popups only. Today's popup text size is 100%. */
object PopupFontScale {
    const val MIN = 50
    const val MAX = 120
    const val DEFAULT = 100
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun factor(percent: Int): Float = clamp(percent) / 100f
}

/** Geometry scaling for popup panels and controls. Today's popup dimensions are 100%. */
object PopupSizeScale {
    const val MIN = 50
    const val MAX = 120
    const val DEFAULT = 100
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)
    fun factor(percent: Int): Float = clamp(percent) / 100f
}

data class FontCustomization(
    val sizePercent: Int = UiFontScale.DEFAULT,
    val mainFamily: AppFontFamily = AppFontFamily.PLUS_JAKARTA_SANS,
    val popupFamily: AppFontFamily = AppFontFamily.PLUS_JAKARTA_SANS,
    val popupFontSizePercent: Int = PopupFontScale.DEFAULT,
    val popupSizePercent: Int = PopupSizeScale.DEFAULT,
)
