package tv.own.owntv.core.menu

import androidx.annotation.StringRes
import tv.own.owntv.core.R
import tv.own.owntv.core.model.ContentMenu

/**
 * One arrangeable action in a long-press content menu.
 *
 * [key] is a stable identifier and is deliberately NOT derived from the label: labels are translated,
 * and several of them flip between two texts. [labelRes] is the *neutral* wording of an action that
 * flips — the menu says "Remove from favourites" on a favourite, this catalogue always says "Add to
 * favourites", because it describes what can appear rather than what a particular item shows.
 */
data class MenuActionRef(val key: String, @param:StringRes val labelRes: Int)

/**
 * What *can* appear in each menu, in its shipped order.
 *
 * Here rather than in an app because the saved order is user data keyed by these strings: a second
 * copy of the key list in the second app is a rename away from silently dropping a user's
 * arrangement. Both apps build their menus from this and both arrange them with [applyMenuOrder].
 */
fun catalogue(menu: ContentMenu): List<MenuActionRef> = when (menu) {
    ContentMenu.LIVE -> LIVE_ACTIONS
    ContentMenu.MOVIE -> MOVIE_ACTIONS
    ContentMenu.SERIES -> SERIES_ACTIONS
    ContentMenu.EPISODE -> EPISODE_ACTIONS
}

private val LIVE_ACTIONS = listOf(
    MenuActionRef("favourite", R.string.content_add_favourite),
    MenuActionRef("rename", R.string.content_rename),
    MenuActionRef("match_epg", R.string.content_match_epg),
    MenuActionRef("epg_offset", R.string.content_epg_time_offset),
    MenuActionRef("catchup", R.string.content_catchup),
    // Record this channel from now. It belongs in the list rather than being added by an app,
    // because this list is what both apps arrange by and what Customize lets the user reorder and
    // hide — a key that is not here is dropped by the arrangement, which is exactly what happened
    // to the phone's Record row while the television's happened to survive.
    MenuActionRef("record", R.string.recording_record),
    // Keep this channel for the Multiview grid. Same reason as `record` above, and the same symptom:
    // absent from this list, the phone's row was silently dropped by the arrangement while the
    // television's survived — and it is the only way to *start* a grid from the channel list.
    MenuActionRef("add_to_multiview", R.string.multiview_add_to),
    MenuActionRef("play_external", R.string.content_play_external),
    MenuActionRef("move", R.string.content_move),
    MenuActionRef("move_to_category", R.string.content_move_to_category),
    // Only offered inside a category the user created; it takes the channel out of that one alone.
    MenuActionRef("remove_from_category", R.string.content_remove_from_category),
    MenuActionRef("hide", R.string.content_hide_channel),
    MenuActionRef("remove_history", R.string.content_remove_history),
)

private val MOVIE_ACTIONS = listOf(
    MenuActionRef("favourite", R.string.content_add_favourite),
    MenuActionRef("mark_watched", R.string.content_mark_watched),
    MenuActionRef("move", R.string.content_move),
    MenuActionRef("move_to_category", R.string.content_move_to_category),
    MenuActionRef("remove_history", R.string.content_remove_history),
    MenuActionRef("hide", R.string.common_hide),
    MenuActionRef("download", R.string.content_download),
    MenuActionRef("delete_subtitles", R.string.content_delete_subtitles),
    MenuActionRef("play_external", R.string.content_play_external),
    MenuActionRef("tmdb_details", R.string.content_tmdb_details),
    MenuActionRef("play_trailer", R.string.content_play_trailer),
    MenuActionRef("refetch_tmdb", R.string.content_refetch_tmdb),
    MenuActionRef("set_tmdb_name", R.string.content_set_tmdb_name),
)

private val SERIES_ACTIONS = listOf(
    MenuActionRef("favourite", R.string.content_add_favourite),
    MenuActionRef("move", R.string.content_move),
    MenuActionRef("move_to_category", R.string.content_move_to_category),
    MenuActionRef("remove_history", R.string.content_remove_history),
    MenuActionRef("hide", R.string.common_hide),
    MenuActionRef("download", R.string.content_download_all_episodes),
    MenuActionRef("tmdb_details", R.string.content_tmdb_details),
    MenuActionRef("play_trailer", R.string.content_play_trailer),
    MenuActionRef("refetch_tmdb", R.string.content_refetch_tmdb),
    MenuActionRef("set_tmdb_name", R.string.content_set_tmdb_name),
)

private val EPISODE_ACTIONS = listOf(
    MenuActionRef("play_start", R.string.content_play_from_start),
    MenuActionRef("download", R.string.content_download),
    MenuActionRef("play_external", R.string.content_play_external),
    MenuActionRef("mark_watched", R.string.content_mark_watched),
    MenuActionRef("tmdb_details", R.string.content_tmdb_details),
    MenuActionRef("refetch_tmdb", R.string.content_refetch_tmdb),
    MenuActionRef("delete_subtitles", R.string.content_delete_subtitles),
)

/**
 * Put [items] into the user's saved [order].
 *
 * Two rules, and both matter more than they look:
 * - a saved key that no longer exists is ignored, so an order saved by an older release never drops
 *   an action or crashes;
 * - an item the saved order has never heard of keeps its shipped position relative to the other
 *   unknown ones and is appended, so an action added by a *newer* release still shows up for someone
 *   who arranged their menu months ago.
 *
 * An empty [order] returns [items] unchanged, which is the shipped order.
 */
fun <T> applyMenuOrder(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
    if (order.isEmpty()) return items
    val byKey = items.associateBy(key)
    return order.mapNotNull { byKey[it] } + items.filterNot { key(it) in order }
}
