package tv.own.owntv.player

import tv.own.owntv.core.theme.AppFontFamily

/**
 * The fontconfig family name libass should ask for. A pure mapping over the persisted enum, so it
 * belongs with the engine that consumes it rather than with the app's Compose typography.
 */
val AppFontFamily.mpvFamilyName: String
    get() = when (this) {
        AppFontFamily.LORA -> "Lora"
        AppFontFamily.SYSTEM_SANS -> "sans-serif"
        AppFontFamily.MONOSPACE -> "monospace"
        AppFontFamily.PLAYFAIR_DISPLAY -> "Playfair Display"
        AppFontFamily.DANCING_SCRIPT -> "Dancing Script"
        AppFontFamily.POPPINS -> "Poppins"
        AppFontFamily.PLUS_JAKARTA_SANS -> "Plus Jakarta Sans"
    }

/**
 * The font *files* the engine copies into libass's font directory, supplied by the host app.
 *
 * Deliberately a hook and not a mapping: only one of the faces ships in the shared module, the
 * others are the TV app's own `res/font` assets, and a different shell may bundle a different
 * set. Returning `0` means "no bundled file", which is also the correct answer for the system faces.
 * Assigned from `Application.onCreate`; without it mpv falls back to its built-in font.
 */
object SubtitleFontAssets {
    var resourceOf: (AppFontFamily) -> Int = { 0 }
}
