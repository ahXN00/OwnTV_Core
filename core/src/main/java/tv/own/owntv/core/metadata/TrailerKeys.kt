package tv.own.owntv.core.metadata

/**
 * A title's YouTube trailer keys, best first, as stored in the single `trailerKey` column: joined by
 * commas (a YouTube key never contains one). Older rows hold one key, which reads as a list of one, so
 * no migration is needed. Several are kept because an owner can block a video from embedded players.
 */
object TrailerKeys {
    /** At most this many: enough backups, without a long row of retries before the "open in YouTube" offer. */
    const val MAX = 4

    fun join(keys: List<String>): String? =
        keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX).takeIf { it.isNotEmpty() }?.joinToString(",")

    fun split(stored: String?): List<String> =
        stored?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
