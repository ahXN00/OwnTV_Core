package tv.own.owntv.core.sync.local

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed requests between two paired devices, for automatic sync. The pairing secret never goes on
 * the wire: each request carries the caller's id, a time, a one-off nonce and
 * `HMAC-SHA256(secret, method|path|time|nonce|sha256(body))`. The host checks the signature with its
 * copy of the secret, refuses a time more than [WINDOW_MS] off and a nonce it has already seen.
 */
object LocalAutoSyncAuth {
    const val HEADER_DEVICE = "x-owntv-device"
    const val HEADER_TIME = "x-owntv-time"
    const val HEADER_NONCE = "x-owntv-nonce"
    const val HEADER_SIGNATURE = "x-owntv-signature"

    const val WINDOW_MS = 5 * 60_000L

    fun sign(secret: String, method: String, path: String, time: Long, nonce: String, body: ByteArray): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC))
        val message = listOf(method.uppercase(Locale.ROOT), path, time.toString(), nonce, sha256(body)).joinToString("|")
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun newNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let(::hex)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    private const val HMAC = "HmacSHA256"

    enum class Verdict { OK, UNKNOWN_DEVICE, BAD_SIGNATURE, STALE, REPLAY }

    /**
     * The host's side. [clock] is the wall clock both devices compare times on; the secret is the one
     * this device holds for the paired caller, null when it is not paired. Keeps the nonces it has
     * seen for [WINDOW_MS] — a request older than that is refused for its time anyway.
     */
    class Verifier(private val clock: () -> Long) {
        private val seen = LinkedHashMap<String, Long>()

        @Synchronized
        fun verify(
            secret: String?,
            method: String,
            path: String,
            time: Long,
            nonce: String,
            body: ByteArray,
            signature: String,
        ): Verdict {
            if (secret == null) return Verdict.UNKNOWN_DEVICE
            val expected = sign(secret, method, path, time, nonce, body)
            if (!MessageDigest.isEqual(expected.toByteArray(), signature.lowercase(Locale.ROOT).toByteArray())) return Verdict.BAD_SIGNATURE
            val now = clock()
            if (kotlin.math.abs(now - time) > WINDOW_MS) return Verdict.STALE
            seen.entries.removeAll { now - it.value > WINDOW_MS }
            if (nonce.isBlank() || seen.containsKey(nonce)) return Verdict.REPLAY
            seen[nonce] = now
            return Verdict.OK
        }
    }

    /**
     * Slows down a caller that keeps failing the checks, per address: each failure in a row adds a
     * second before the answer, up to [MAX_DELAY_MS]. A right request clears it. Nothing is ever shut
     * down — the paired device on the same address must keep working.
     */
    class Slowdown {
        private val failures = HashMap<String, Int>()

        @Synchronized
        fun delayFor(address: String): Long = ((failures[address] ?: 0) * 1_000L).coerceAtMost(MAX_DELAY_MS)

        @Synchronized
        fun failed(address: String) {
            failures[address] = (failures[address] ?: 0) + 1
        }

        @Synchronized
        fun succeeded(address: String) {
            failures.remove(address)
        }

        private companion object {
            const val MAX_DELAY_MS = 10_000L
        }
    }
}

/** Why the host refused an automatic-sync request, as it says so in the answer. */
enum class AutoSyncRefusal(val code: Int, val reason: String) {
    /** Paired, but this device has no agreement with the caller (never made, or turned off here). */
    NO_AGREEMENT(403, "no-agreement"),

    /** Not (or no longer) paired: the caller's id is unknown, or its signature does not match the secret. */
    UNPAIRED(401, "unpaired"),

    /** The address is not on the local network. */
    NOT_LAN(403, "not-lan"),

    /** A pass with this device is already running. */
    BUSY(409, "busy"),

    /** A stale time or a reused nonce. */
    BAD_REQUEST(400, "bad-request"),
    ;

    companion object {
        /** What the caller makes of an answer: its HTTP [code] and the JSON reason it carried. */
        fun of(code: Int, reason: String?): AutoSyncRefusal? = when (code) {
            409 -> BUSY
            401 -> UNPAIRED
            403 -> if (reason == NOT_LAN.reason) NOT_LAN else NO_AGREEMENT
            400 -> BAD_REQUEST
            else -> null
        }
    }
}
