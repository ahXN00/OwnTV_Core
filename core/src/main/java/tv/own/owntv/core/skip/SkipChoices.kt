package tv.own.owntv.core.skip

/** What happens when a part plays. */
enum class SkipMode {
    OFF, BUTTON, AUTO;

    companion object {
        val DEFAULT = BUTTON
        fun fromName(name: String?): SkipMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** How long the skip button stays on screen: its first seconds (and whenever the controls open), or the whole part. */
enum class SkipButtonShow {
    START, WHOLE;

    companion object {
        val DEFAULT = START
        fun fromName(name: String?): SkipButtonShow = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The user's Skip intro & credits settings. */
data class SkipChoices(
    val intro: SkipMode = SkipMode.DEFAULT,
    val recap: SkipMode = SkipMode.DEFAULT,
    val credits: SkipMode = SkipMode.DEFAULT,
    val preview: SkipMode = SkipMode.DEFAULT,
    val buttonShow: SkipButtonShow = SkipButtonShow.DEFAULT,
) {
    fun modeOf(kind: SkipKind): SkipMode = when (kind) {
        SkipKind.INTRO -> intro
        SkipKind.RECAP -> recap
        SkipKind.CREDITS -> credits
        SkipKind.PREVIEW -> preview
    }

    /** Every part off: no lookup is made at all. */
    val allOff: Boolean get() = SkipKind.entries.all { modeOf(it) == SkipMode.OFF }
}
