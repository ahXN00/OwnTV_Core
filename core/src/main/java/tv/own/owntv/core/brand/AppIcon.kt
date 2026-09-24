package tv.own.owntv.core.brand

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tv.own.owntv.core.R

/**
 * The six colours of the OwnTV flip-card icon and logo. The design is future_work's pass-6 mockup;
 * every drawable here is generated from it by `tools/brand/render_brand.py`.
 *
 * Each colour is its own launcher activity in the host app: `MainActivity` + [activitySuffix]. Eggshell, the
 * default, is `MainActivity` itself, so a home-screen icon from before this feature keeps working on upgrade.
 */
enum class AppIcon(
    val activitySuffix: String,
    @StringRes val label: Int,
    /** Flat in-app mark, cropped to the card: the sidebar, lockups, About. */
    @DrawableRes val mark: Int,
    /** The simpler drawing for 32 dp and below. */
    @DrawableRes val markSmall: Int,
    /** 320×180 Android TV banner, also the logo of the TV home-screen channel. */
    @DrawableRes val banner: Int,
    /** ARGB of the wordmark's "TV" on a dark surface (the mockup's `acc`). */
    val accent: Long,
    /** ARGB of the wordmark's "TV" on a light surface (the mockup's `ui`). */
    val accentOnLight: Long,
) {
    PETROL("Petrol", R.string.app_icon_petrol, R.drawable.owntv_mark_petrol, R.drawable.owntv_mark_small_petrol, R.drawable.owntv_banner_petrol, 0xFF5CC2BC, 0xFF1B6E6A),
    SUNFLOWER("Sunflower", R.string.app_icon_sunflower, R.drawable.owntv_mark_sunflower, R.drawable.owntv_mark_small_sunflower, R.drawable.owntv_banner_sunflower, 0xFFFFD24D, 0xFF946500),
    COBALT("Cobalt", R.string.app_icon_cobalt, R.drawable.owntv_mark_cobalt, R.drawable.owntv_mark_small_cobalt, R.drawable.owntv_banner_cobalt, 0xFF8AAAFF, 0xFF3558C8),
    TOMATO("Tomato", R.string.app_icon_tomato, R.drawable.owntv_mark_tomato, R.drawable.owntv_mark_small_tomato, R.drawable.owntv_banner_tomato, 0xFFFF9C84, 0xFFC4412C),
    BOARD("Board", R.string.app_icon_board, R.drawable.owntv_mark_board, R.drawable.owntv_mark_small_board, R.drawable.owntv_banner_board, 0xFF52DBC8, 0xFF13806F),
    EGGSHELL("", R.string.app_icon_eggshell, R.drawable.owntv_mark_eggshell, R.drawable.owntv_mark_small_eggshell, R.drawable.owntv_banner_eggshell, 0xFF86D3DB, 0xFF17616C),
    OLIVE("Olive", R.string.app_icon_olive, R.drawable.owntv_mark_olive, R.drawable.owntv_mark_small_olive, R.drawable.owntv_banner_olive, 0xFFA5CC4E, 0xFF5E7F1B),
    OLIVE_CREAM("OliveCream", R.string.app_icon_olive_cream, R.drawable.owntv_mark_olive_cream, R.drawable.owntv_mark_small_olive_cream, R.drawable.owntv_banner_olive_cream, 0xFFA5CC4E, 0xFF5E7F1B);

    companion object {
        val DEFAULT = EGGSHELL

        fun fromStored(value: String?): AppIcon = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
