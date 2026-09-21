package tv.own.owntv.core.util

/** Semantic failure categories. The presentation layer supplies the translated wording. */
sealed interface FriendlySyncFailure {
    data object Offline : FriendlySyncFailure
    data object Generic : FriendlySyncFailure
    data object Timeout : FriendlySyncFailure
    data object Unreachable : FriendlySyncFailure
    data object ConnectionFailed : FriendlySyncFailure
    data object StreamInterrupted : FriendlySyncFailure
    data object MacNotAuthorised : FriendlySyncFailure
    data object InvalidMac : FriendlySyncFailure
    data object PortalHandshakeFailed : FriendlySyncFailure
    data object PortalSessionExpired : FriendlySyncFailure
    data object AuthenticationRejected : FriendlySyncFailure
    data object NotFound : FriendlySyncFailure
    data object ServerError : FriendlySyncFailure
    data object SecureConnectionFailed : FriendlySyncFailure
    data object MalformedGuide : FriendlySyncFailure

    /**
     * The guide source was reached and answered in full, and carried nothing at all. Usually the
     * provider has not published this week's guide yet, so it is worth trying again another day.
     */
    data object GuideEmpty : FriendlySyncFailure
    data object PlaylistFileUnavailable : FriendlySyncFailure
    data object PlaylistPathUnsupported : FriendlySyncFailure
    data class Unknown(val rawMessage: String) : FriendlySyncFailure
}

/**
 * Classifies a raw exception / sync message without producing user-facing language. [online] lets
 * callers turn the common "everything failed" case into a semantic offline state instead of a
 * stack-trace-ish string.
 *
 * The English needles below are stable comparison keys emitted by OwnTV's own networking code;
 * translating either the throw sites or these needles would silently break classification.
 */
fun classifySyncFailure(raw: String?, online: Boolean): FriendlySyncFailure = when {
    !online -> FriendlySyncFailure.Offline
    raw.isNullOrBlank() -> FriendlySyncFailure.Generic
    raw.containsAny("timeout", "timed out") -> FriendlySyncFailure.Timeout
    raw.containsAny("Unable to resolve host", "UnknownHost", "No address associated") -> FriendlySyncFailure.Unreachable
    raw.containsAny("Failed to connect", "ECONNREFUSED", "Connection refused", "Connection reset") -> FriendlySyncFailure.ConnectionFailed
    raw.containsAny("stream was reset", "PROTOCOL_ERROR", "StreamReset") -> FriendlySyncFailure.StreamInterrupted
    raw.containsAny("MAC may not be authorized", "MAC not authorized", "Portal rejected", "no valid MAC") -> FriendlySyncFailure.MacNotAuthorised
    raw.containsAny("Portal handshake", "portal API endpoint") -> FriendlySyncFailure.PortalHandshakeFailed
    raw.containsAny("token may have expired", "returned no cmd") -> FriendlySyncFailure.PortalSessionExpired
    raw.containsAny("HTTP 401", "HTTP 403") -> FriendlySyncFailure.AuthenticationRejected
    raw.contains("HTTP 404", ignoreCase = true) -> FriendlySyncFailure.NotFound
    raw.containsAny("HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504") -> FriendlySyncFailure.ServerError
    raw.containsAny("CertPath", "SSLHandshake", "trust anchor", "CertificateException") -> FriendlySyncFailure.SecureConnectionFailed
    raw.containsAny("END_TAG", "START_TAG", "XmlPull", "PullParser", "ParserException") -> FriendlySyncFailure.MalformedGuide
    raw.containsAny("Portal returned no guide", "no programmes in the guide window") -> FriendlySyncFailure.GuideEmpty
    raw.contains("playlist_file_unavailable", ignoreCase = true) -> FriendlySyncFailure.PlaylistFileUnavailable
    raw.contains("playlist_path_unsupported", ignoreCase = true) -> FriendlySyncFailure.PlaylistPathUnsupported
    else -> FriendlySyncFailure.Unknown(raw)
}

/**
 * True when a sync failure looks transient (offline, network blip, server 5xx) — i.e. worth a
 * WorkManager [androidx.work.ListenableWorker.Result.retry] with backoff instead of a terminal failure.
 * Auth/URL problems (401/403/404, malformed data) are permanent: retrying them just hammers the panel.
 */
fun isTransientSyncError(raw: String?, online: Boolean): Boolean = when {
    !online -> true
    raw.isNullOrBlank() -> false
    else -> raw.containsAny(
        "timeout", "timed out",
        "Unable to resolve host", "UnknownHost", "No address associated",
        "Failed to connect", "ECONNREFUSED", "Connection refused", "Connection reset",
        "Software caused connection abort", "unexpected end of stream",
        "stream was reset", "PROTOCOL_ERROR", "StreamReset",
        "HTTP 429", "HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504",
    )
}

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it, ignoreCase = true) }
