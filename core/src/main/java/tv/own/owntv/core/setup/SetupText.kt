package tv.own.owntv.core.setup

import android.content.res.Resources
import androidx.annotation.StringRes
import tv.own.owntv.core.R
import tv.own.owntv.core.parser.HlsInconclusiveReason
import tv.own.owntv.core.parser.HlsNotServedReason
import tv.own.owntv.core.parser.HlsProbe
import tv.own.owntv.core.parser.HlsTest
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.util.FriendlySyncFailure
import java.text.DateFormat
import java.util.Date

/**
 * The words that go with adding a source. Both apps ask the same questions of a provider and get the
 * same semantic answers back, so the sentences belong next to the answers rather than being written
 * twice in two UI toolkits. Rendering — colour, layout, which line goes where — stays with each app.
 */

/** Words a sync failure. Not a plain string resource: [FriendlySyncFailure.Unknown] carries the
 *  provider's own message, which is the only thing there is to show. */
fun FriendlySyncFailure.displayText(res: Resources): String = when (this) {
    FriendlySyncFailure.Offline -> res.getString(R.string.sync_error_offline)
    FriendlySyncFailure.Generic -> res.getString(R.string.sync_error_generic)
    FriendlySyncFailure.Timeout -> res.getString(R.string.sync_error_timeout)
    FriendlySyncFailure.Unreachable -> res.getString(R.string.sync_error_unreachable)
    FriendlySyncFailure.ConnectionFailed -> res.getString(R.string.sync_error_connection)
    FriendlySyncFailure.StreamInterrupted -> res.getString(R.string.sync_error_interrupted)
    FriendlySyncFailure.MacNotAuthorised -> res.getString(R.string.sync_error_mac)
    FriendlySyncFailure.InvalidMac -> res.getString(R.string.sync_error_invalid_mac)
    FriendlySyncFailure.PortalHandshakeFailed -> res.getString(R.string.sync_error_portal)
    FriendlySyncFailure.PortalSessionExpired -> res.getString(R.string.sync_error_session)
    FriendlySyncFailure.AuthenticationRejected -> res.getString(R.string.sync_error_auth)
    FriendlySyncFailure.NotFound -> res.getString(R.string.sync_error_not_found)
    FriendlySyncFailure.ServerError -> res.getString(R.string.sync_error_server)
    FriendlySyncFailure.SecureConnectionFailed -> res.getString(R.string.sync_error_secure)
    FriendlySyncFailure.MalformedGuide -> res.getString(R.string.sync_error_malformed_guide)
    FriendlySyncFailure.GuideEmpty -> res.getString(R.string.sync_error_guide_empty)
    FriendlySyncFailure.PlaylistFileUnavailable -> res.getString(R.string.setup_playlist_file_unavailable)
    FriendlySyncFailure.PlaylistPathUnsupported -> res.getString(R.string.setup_playlist_path_unsupported)
    is FriendlySyncFailure.Unknown -> rawMessage
}

/** Words a semantic onboarding failure. */
fun SourceImporter.SetupFailure.displayText(res: Resources): String = when (this) {
    SourceImporter.SetupFailure.InvalidMac -> res.getString(R.string.setup_invalid_mac)
    SourceImporter.SetupFailure.BackupRead -> res.getString(R.string.setup_backup_read_failed)
    SourceImporter.SetupFailure.Restore -> res.getString(R.string.setup_restore_failed)
    is SourceImporter.SetupFailure.Sync -> failure.displayText(res)
}

/** Words the "Test HLS support" probe: what was asked for, what came back, and what the panel claims. */
fun HlsTest.displayText(res: Resources): String {
    val advertised = declared ?: return res.getString(R.string.setup_hls_test_provider_unreachable)

    return when (val probe = probe) {
        HlsProbe.Served -> res.getString(
            if (advertised) R.string.setup_hls_test_works else R.string.setup_hls_test_works_unadvertised,
        )
        is HlsProbe.Busy -> res.getString(R.string.setup_hls_test_busy, probe.code)
        is HlsProbe.NotServed -> {
            val reason = when (val reason = probe.reason) {
                HlsNotServedReason.NotPlaylist -> res.getString(R.string.setup_hls_test_reason_not_playlist)
                is HlsNotServedReason.NoEndpoint -> res.getString(R.string.setup_hls_test_reason_no_endpoint, reason.httpCode)
            }
            res.getString(R.string.setup_hls_test_not_served, reason)
        }
        is HlsProbe.Inconclusive -> {
            val reason = when (val reason = probe.reason) {
                is HlsInconclusiveReason.HttpError -> res.getString(R.string.setup_hls_test_reason_http, reason.httpCode)
                is HlsInconclusiveReason.Unexpected -> reason.rawMessage
                HlsInconclusiveReason.NoAnswer -> res.getString(R.string.setup_hls_test_reason_no_answer)
                HlsInconclusiveReason.NoLiveChannels -> res.getString(R.string.setup_hls_test_reason_no_live_channels)
                HlsInconclusiveReason.DeadTestChannel -> res.getString(R.string.setup_hls_test_reason_dead_channel)
            }
            res.getString(
                R.string.setup_hls_test_inconclusive,
                reason,
                res.getString(
                    if (advertised) R.string.setup_hls_provider_advertises else R.string.setup_hls_provider_does_not_advertise,
                ),
            )
        }
    }
}

/** The one-line verdict of "Test connection". Each app colours it: a failure is not the accent colour. */
fun SourceTestResult.headline(res: Resources): String = when (this) {
    is SourceTestResult.Ok -> res.getString(R.string.settings_sources_test_ok)
    SourceTestResult.AuthFailed -> res.getString(R.string.settings_sources_test_auth)
    is SourceTestResult.Expired -> res.getString(R.string.settings_sources_test_expired)
    is SourceTestResult.Unreachable -> res.getString(R.string.settings_sources_test_unreachable)
}

/**
 * The detail lines under [headline]: the provider's own status word, the trial marker, the expiry
 * date and the connection count — each omitted when the provider said nothing about it. A playlist
 * that reports no numbers shows no connection line rather than an invented "0 of 0".
 *
 * The status word is deliberately **not** translated: panels invent their own vocabulary there, and a
 * wrong translation of "Banned" would be worse than the English original.
 */
fun SourceTestResult.detailLines(
    res: Resources,
    /**
     * What the playlist has stored about its stream limit. Shown when the provider itself did not
     * report one in this test — which is the usual case for a Stalker portal or an M3U, and exactly
     * why the number was measured and saved in the first place.
     */
    limit: tv.own.owntv.core.live.ConnectionLimit? = null,
): List<String> {
    val ok = this as? SourceTestResult.Ok
    val expiryMs = ok?.expiryMs ?: (this as? SourceTestResult.Expired)?.expiryMs
    return buildList {
        ok?.status?.takeIf { it.isNotBlank() }?.let { add(res.getString(R.string.settings_sources_test_status, it)) }
        if (ok?.trial == true) add(res.getString(R.string.settings_sources_test_trial))
        if (this@detailLines !is SourceTestResult.Unreachable && this@detailLines !== SourceTestResult.AuthFailed) {
            // A portal's own wording first, then a timestamp, then "none reported".
            val expiry = ok?.expiryText?.takeIf { it.isNotBlank() }
                ?: expiryMs?.let { formatTestDate(it) }
            add(
                expiry?.let { res.getString(R.string.settings_sources_expiry, it) }
                    ?: res.getString(R.string.settings_sources_test_expiry_none),
            )
        }
        if (ok != null && ok.maxConnections > 0) {
            add(
                if (ok.activeConnections >= 0) {
                    res.getString(R.string.settings_sources_test_connections, ok.activeConnections, ok.maxConnections)
                } else {
                    res.getString(R.string.settings_sources_test_connections_max, ok.maxConnections)
                },
            )
        } else if (limit != null) {
            // The provider said nothing this time, so fall back to what was measured. Silence about a
            // measurement that ran and could not tell is worse than saying so: the user would press
            // Re-test forever waiting for a line that is never going to appear.
            // Nothing measured yet says nothing worth printing — Re-test is the answer to that, and
            // it is on the same panel.
            when {
                limit.isKnown -> add(res.getString(R.string.settings_sources_test_connections_max, limit.streams))
                limit.measuredWithoutAnswer -> add(res.getString(R.string.settings_sources_probe_unknown))
            }
        }
    }
}

private fun formatTestDate(ms: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** A MAG set-top model whose User-Agent some Stalker portals insist on. Empty = the portal's default. */
data class MagUserAgent(@param:StringRes val labelRes: Int, val userAgent: String)

/** The presets offered under "Device model", in the order both apps show them. */
val MAG_USER_AGENTS: List<MagUserAgent> = listOf(
    MagUserAgent(R.string.setup_default_mag, ""),
    MagUserAgent(R.string.setup_mag250, "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"),
    MagUserAgent(R.string.setup_mag254, "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 2 rev: 250 Safari/533.3"),
    MagUserAgent(R.string.setup_mag270, "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG270 stbapp ver: 2 rev: 250 Safari/533.3"),
    MagUserAgent(R.string.setup_mag420, "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/601.1 (KHTML, like Gecko) MAG420 stbapp ver: 4 rev: 2721 Safari/601.1"),
)
