package tv.own.owntv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import tv.own.owntv.core.network.StreamHeaders
import java.io.File
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore

// Hands a stream URL (or a downloaded file path) to an external video player (VLC, MX Player, etc.)
// via ACTION_VIEW. When it fires, the in-app player is bypassed entirely (the fullscreen player
// never opens, because OwnTVPlayer is never told to play).
//
// Network URLs (http/https/rtsp/rtmp/udp/mms) are handed over verbatim. Local download paths are
// shared through the app FileProvider with a read permission grant.
//
// A source's User-Agent and any per-channel request headers are passed as intent extras. ACTION_VIEW
// has no standard slot for these, but VLC and MX Player both read the de-facto keys used below, so a
// stream behind a UA check or per-channel auth headers now has a chance of playing instead of a
// guaranteed 403. Players that ignore the extras are unaffected — they see the same intent as before.
//
// Limitations: extras are best-effort (an unknown player may still ignore them); no resume position
// or prev/next queue.
class ExternalPlayerLauncher(private val context: Context) {

    // Open url externally. Returns true if an external app was actually launched.
    // [userAgent] is the source's UA; [httpHeaders] is the stored `Key: Value` per-line block from
    // ChannelEntity.httpHeaders (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`). Both are optional.
    fun launch(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        userAgent: String? = null,
        httpHeaders: String? = null,
    ): Boolean {
        val localized = localizedContext()
        val uri = uriFor(url)
        if (uri == null) {
            toast(localized.getString(tv.own.owntv.core.R.string.player_external_could_not_open))
            return false
        }
        // Try the precise MIME first, then widen. VLC and MX Player advertise `video/*` but NOT every
        // specific type: a live channel ending `.ts` (video/mp2t) or `.m3u8` (application/x-mpegURL)
        // matched no activity at all and reported "no external player installed", even though the same
        // players happily took a movie's video/mp4. Falling back to `video/*`, and finally to the URI
        // with no type at all, gets live streams to the same players without touching the movie path.
        for (mime in mimeCandidates(url)) {
            val intent = Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (mime != null) intent.setDataAndType(uri, mime) else intent.data = uri
            applyHeaders(intent, url, userAgent, httpHeaders)

            val targets = context.packageManager.queryIntentActivities(intent, 0)
            if (targets.isEmpty()) continue
            return if (targets.size == 1) {
                startActivity(intent)
            } else {
                val chooserTitle = if (title != null && subtitle != null) {
                    localized.getString(tv.own.owntv.core.R.string.player_external_play_with_item, title, subtitle)
                } else {
                    title ?: localized.getString(tv.own.owntv.core.R.string.player_external_play_with)
                }
                startActivity(
                    Intent.createChooser(intent, chooserTitle)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        toast(localized.getString(tv.own.owntv.core.R.string.player_external_not_found))
        return false
    }

    // Whether any installed app can handle a video URL.
    fun isAvailable(): Boolean {
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType("https://example.com/video.mp4".toUri(), "video/mp4")
        return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
    }

    private fun startActivity(intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }
            .onFailure { toast(localizedContext().getString(tv.own.owntv.core.R.string.player_external_not_found)) }
            .isSuccess

    // Attach the source User-Agent and per-channel headers to the intent, using the keys the common
    // Android players read. Local files need none of this, and an empty header set adds no extras.
    //
    // Keys, all de-facto rather than platform API:
    //  - "headers": String[] of alternating name/value — MX Player, and VLC for Android since 3.x.
    //  - "User-Agent": read by several players (incl. older VLC builds) as a standalone override.
    //  - "android.media.intent.extra.HTTP_HEADERS": the Bundle form some ExoPlayer-based players use.
    // A player that knows none of them ignores the extras entirely, which is the behaviour we had.
    private fun applyHeaders(intent: Intent, url: String, userAgent: String?, httpHeaders: String?) {
        if (url.toUri().scheme?.lowercase() !in NETWORK_SCHEMES) return
        val headers = LinkedHashMap<String, String>(4)
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        // Per-channel headers win over the source UA: a playlist that sets its own User-Agent set it
        // for this specific stream, which is the more specific instruction (same order the in-app
        // engines apply them in).
        headers.putAll(StreamHeaders.decode(httpHeaders))
        if (headers.isEmpty()) return

        intent.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        headers["User-Agent"]?.let { intent.putExtra("User-Agent", it) }
        intent.putExtra(
            "android.media.intent.extra.HTTP_HEADERS",
            Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } },
        )
    }

    // Network scheme: hand the URL over verbatim; otherwise treat as a local file path.
    private fun uriFor(url: String): Uri? {
        val scheme = url.toUri().scheme?.lowercase()
        if (scheme in NETWORK_SCHEMES) return url.toUri()
        // A download or recording saved into a folder the user picked is already a shareable URI —
        // it needs no FileProvider, and wrapping it in one is impossible anyway. It is passed
        // through with the read grant the intent already carries. Whether the other app is allowed
        // to open it is then between it and the provider; before this, a document simply fell
        // through to File(), did not exist, and the external player silently refused to start.
        if (scheme == CONTENT_SCHEME) return url.toUri()
        val file = File(url)
        if (!file.exists()) return null
        val authority = context.packageName + ".fileprovider"
        return runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull()
    }

    // MIME types to offer for a URL, most specific first, ending with an untyped attempt. Duplicates
    // are dropped so a plain video/* URL doesn't query the same intent twice.
    private fun mimeCandidates(url: String): List<String?> {
        val path = url.substringBefore('?').substringAfterLast('/', "")
        val ext = path.substringAfterLast('.', "").lowercase()
        val specific = when (ext) {
            "m3u8", "m3u" -> "application/x-mpegURL"
            "ts", "m2t", "mts" -> "video/mp2t"
            "mp4", "m4v", "mov", "3gp" -> "video/mp4"
            else -> "video/*"
        }
        return listOf(specific, "video/*", null).distinct()
    }

    private fun localizedContext(): Context =
        AppLocale.wrap(context, LocaleStore.from(context).readBlocking())

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** URL schemes handed to the external player as-is (everything else is a local file path). */
        val NETWORK_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "udp", "mms")

        /** A file saved into a folder the user picked through the system picker. */
        const val CONTENT_SCHEME = "content"
    }
}
