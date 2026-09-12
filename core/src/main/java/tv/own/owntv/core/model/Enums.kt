package tv.own.owntv.core.model

/** What kind of media an item is. Used to scope favorites/history/progress generically. */
enum class MediaType { LIVE, MOVIE, SERIES, EPISODE }

/** How a source delivers its content. */
enum class SourceType { M3U, XTREAM, LOCAL_BACKUP, STALKER }

/**
 * What we know about an Xtream provider's HLS support, from `user_info.allowed_output_formats`.
 *
 * Three states rather than a boolean because "no" and "we haven't asked yet" are different answers and
 * the UI needs to tell them apart: a playlist that has never synced knows nothing, while one that has
 * synced and saw no `m3u8` in the list has a real negative answer worth showing.
 *
 * [code] is stored in `sources.hlsSupported` and is deliberately pinned: `0`/`1` keep the meaning the
 * old boolean column had, so every existing row reads back correctly with no migration — an old `1`
 * really was "supported", and an old `0` really was "either unsupported or never checked", which is
 * exactly [UNKNOWN]. The next sync resolves those rows to a definite answer.
 */
enum class HlsSupport(val code: Int) {
    UNKNOWN(0),
    SUPPORTED(1),
    UNSUPPORTED(2),
    ;

    companion object {
        fun fromCode(code: Int): HlsSupport = entries.firstOrNull { it.code == code } ?: UNKNOWN

        /** The panel answered: `m3u8` was either in `allowed_output_formats` or it wasn't. */
        fun of(supported: Boolean): HlsSupport = if (supported) SUPPORTED else UNSUPPORTED
    }
}

/** Lifecycle of a download (movies & series only). */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

/**
 * Lifecycle of a live recording.
 *
 * [MISSED] is the one that does not exist for downloads, and it is the whole reason the Recordings
 * screen has a fourth group: a live programme that could not be recorded when it aired is gone, so
 * "it did not happen, and here is why" is a result the user has to be told, not an error to retry.
 * [FAILED] is a recording that started and then stopped; [MISSED] never started at all.
 */
enum class RecordingStatus { SCHEDULED, RECORDING, COMPLETED, FAILED, MISSED, CANCELLED }

/**
 * Why a recording failed or was missed — the sentence the Recordings screen shows instead of a
 * shrug. [NONE] is every row that has nothing to explain, including the ones still to come.
 *
 * Stored by name, so adding a reason later is additive and an unknown one read back by an older
 * build is the only thing that would break — which is why nothing outside core reads the raw value.
 */
enum class RecordingFailure {
    NONE,

    /** Free space reached the reserve floor (D2/D8). Whatever was captured is kept and playable. */
    NO_SPACE,

    /**
     * The playlist had no connection left — either the provider refused, or the app's own budget
     * said so before asking (D10/D11). On a one-connection account this is the ordinary outcome of
     * recording while watching, not a fault.
     */
    NO_CONNECTION,

    /** Another recording already held the budget when this one was due (D10). */
    CLASH,

    /** The network went away and did not come back inside the programme's window. */
    NETWORK,

    /** The provider stopped serving the stream, or never did. */
    STREAM_UNAVAILABLE,

    /** The playlist or channel the row points at is no longer in the catalogue. */
    CHANNEL_GONE,

    /**
     * The channel is delivered as encrypted HLS (`#EXT-X-KEY` with a method other than `NONE`).
     *
     * Refused rather than attempted, on purpose: writing the encrypted segments out unchanged would
     * produce a file of exactly the right size that will not play, which is worse than saying no.
     */
    ENCRYPTED,

    UNKNOWN,
}
