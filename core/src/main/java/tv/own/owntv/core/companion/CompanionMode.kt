package tv.own.owntv.core.companion

/**
 * What the companion HTTP server is currently serving:
 *  - [ADD_SOURCE] — the mobile add-source form (Xtream / M3U / Stalker), the original Remote flow;
 *  - [EDIT_SOURCE] — the same form for one existing playlist, its type fixed and secrets left out
 *    (see [CompanionSourceEdit]);
 *  - [BACKUP_RESTORE] — an upload page the remote device uses to send an OwnTV backup JSON to the TV;
 *  - [BACKUP_DOWNLOAD] — a download page the remote device uses to fetch a backup the TV just exported;
 *  - [IMAGE_UPLOAD] — an upload page the remote device uses to send a background image to the TV;
 *  - [TMDB_CONFIG] / [OPEN_SUBTITLES_CONFIG] — a page the remote device uses to send a service's key
 *    and server (and, for OpenSubtitles, the account) to the TV;
 *  - [LOCAL_SYNC] — the only mode with no web page behind it: the other OwnTV app on the same Wi-Fi
 *    talks to it directly. It accepts a backup upload AND serves this device's own export, because a
 *    merge sends and receives in one session, and it answers `/sync/hello` and `/sync/pair` so the
 *    two devices can identify each other and remember the pairing.
 *
 * "Remote device" is any browser on the same Wi-Fi — a phone, a tablet or a desktop with the URL
 * typed in — which is why nothing here is named after a phone.
 *
 * One server, one PIN gate; the mode only changes which page is served and which endpoint is accepted.
 */
enum class CompanionMode { ADD_SOURCE, EDIT_SOURCE, BACKUP_RESTORE, BACKUP_DOWNLOAD, IMAGE_UPLOAD, TMDB_CONFIG, OPEN_SUBTITLES_CONFIG, LOCAL_SYNC }

/**
 * A service setup handed over from the remote browser.
 *
 * [username]/[password] are only ever populated in [CompanionMode.OPEN_SUBTITLES_CONFIG] — TMDB has
 * no account to sign in to, and the server drops both fields in any other mode.
 */
data class CompanionServiceConfig(
    val apiKey: String,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
)
